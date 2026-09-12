import { computed, onScopeDispose, ref, watch } from 'vue'
import { createRegradeApi, regradeDetailApi, regradeHistoryApi, regradeKeyApi,
  restoreRegradeApi, retryRegradeApi, regradeBankContextApi, retryRegradeBankApi } from '../api'

export function useRegrade(examId) {
  const key = ref(null)
  const history = ref([])
  const detail = ref(null)
  const selected = ref(null)
  const page = ref(1)
  const historyPage = ref(1)
  const busy = ref(false)
  const error = ref('')
  let stopped = false
  let timer
  let generation = 0
  // Keep the key after a transport failure so a retry cannot create another job.
  let pendingRequest = null
  const unfinished = computed(() => Boolean(key.value?.activeJob))
  async function refresh() {
    const current = ++generation
    try {
      const [answerKey, jobs] = await Promise.all([regradeKeyApi(examId.value), regradeHistoryApi(examId.value, historyPage.value)])
      if (stopped || current !== generation) return
      key.value = answerKey
      history.value = jobs
      if (!selected.value && jobs.length) selected.value = jobs[0].id
      if (selected.value) {
        const result = await regradeDetailApi(examId.value, selected.value, page.value)
        if (stopped || current !== generation) return
        detail.value = result
      }
      error.value = ''
    } catch (e) { if (!stopped && current === generation) error.value = e.message || '读取重判状态失败' }
  }
  async function run(action) {
    if (busy.value) return
    busy.value = true
    clearTimeout(timer)
    try { await action(); await refresh() }
    catch (e) { error.value = e.message || '操作失败' }
    finally { busy.value = false; schedule() }
  }
  async function submit(answers, reason, restoreVersion = null) {
    const body = { expectedVersion: Number(key.value.version), reason, answers }
    const signature = JSON.stringify([body, restoreVersion])
    if (!pendingRequest || pendingRequest.signature !== signature) {
      pendingRequest = { signature, requestKey: crypto.randomUUID() }
    }
    await run(async () => {
      const request = { ...body, requestKey: pendingRequest.requestKey }
      const result = restoreVersion === null
        ? await createRegradeApi(examId.value, request)
        : await restoreRegradeApi(examId.value, restoreVersion, request)
      pendingRequest = null
      selected.value = result.jobId
      page.value = 1
      historyPage.value = 1
    })
  }
  const retry = () => run(() => retryRegradeApi(examId.value, selected.value))
  const bankContext = (syncId) => regradeBankContextApi(examId.value, selected.value, syncId)
  const retryBank = (syncId, fingerprint) => run(() => retryRegradeBankApi(examId.value, selected.value, syncId, fingerprint))
  function select(jobId) { selected.value = jobId; page.value = 1; void refresh() }
  function schedule() {
    clearTimeout(timer)
    if (!stopped) timer = setTimeout(async () => { if (!busy.value) await refresh(); schedule() }, 5000)
  }
  watch([page, historyPage], () => { void refresh() })
  void refresh().then(schedule)
  onScopeDispose(() => { stopped = true; generation++; clearTimeout(timer) })
  return { key, history, detail, page, historyPage, busy, error, unfinished, refresh, submit, retry, bankContext, retryBank, select }
}
