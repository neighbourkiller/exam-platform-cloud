<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { gradingClaimApi, gradingRenewApi, gradingReleaseApi } from '../../api'

const props = defineProps({ answerId: { type: [String, Number], required: true } })
const emit = defineEmits(['change'])
const token = ref('')
const remaining = ref(0)
const busy = ref(false)
const error = ref('')
const label = computed(() => `${Math.floor(remaining.value / 60)}:${String(remaining.value % 60).padStart(2, '0')}`)
let deadline = 0
let disposed = false
const timer = setInterval(() => {
  remaining.value = Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
  if (token.value && remaining.value === 0) {
    token.value = ''
    emit('change', null)
    error.value = '租约已过期，请重新认领'
  }
}, 500)

async function claimOrRenew() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  // Count from request start so network latency cannot extend the local lease.
  const started = Date.now()
  try {
    const lease = token.value
      ? await gradingRenewApi(props.answerId, token.value)
      : await gradingClaimApi(props.answerId)
    if (disposed) {
      await gradingReleaseApi(props.answerId, lease.token)
      return
    }
    deadline = started + lease.expiresInSeconds * 1000
    remaining.value = Math.max(0, Math.ceil((deadline - Date.now()) / 1000))
    token.value = remaining.value > 0 ? lease.token : ''
    emit('change', token.value || null)
  } catch (cause) {
    if (cause.code === 'GRADING_LEASE_CONFLICT') {
      token.value = ''
      deadline = 0
      remaining.value = 0
      emit('change', null)
    }
    error.value = '认领或续期失败，请稍后重试或刷新列表'
  } finally {
    busy.value = false
  }
}

async function release() {
  if (busy.value) return
  busy.value = true
  try {
    await gradingReleaseApi(props.answerId, token.value)
    token.value = ''
    deadline = 0
    remaining.value = 0
    emit('change', null)
  } catch {
    error.value = '释放失败，可重试；租约将在倒计时结束后失效'
  } finally {
    busy.value = false
  }
}

onBeforeUnmount(() => {
  disposed = true
  clearInterval(timer)
  emit('change', null)
  if (token.value) gradingReleaseApi(props.answerId, token.value).catch(() => {})
})
</script>

<template>
  <div>
    <el-button size="small" :loading="busy" @click="claimOrRenew">
      {{ token ? `续期 5 分钟（剩余 ${label}）` : '认领批阅（5 分钟）' }}
    </el-button>
    <el-button v-if="token" size="small" :disabled="busy" @click="release">释放</el-button>
    <div v-if="error" role="status">{{ error }}</div>
  </div>
</template>
