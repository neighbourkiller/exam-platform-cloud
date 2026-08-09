<template>
  <div :class="['exam-shell', { 'exam-shell--ending': endingExam }]" :aria-busy="endingExam">
    <div v-if="endingExam" class="submission-overlay" role="status" aria-live="polite">
      <div class="submission-overlay__card">
        <el-icon class="submission-overlay__spinner" :size="34"><Loading /></el-icon>
        <h2>正在完成交卷</h2>
        <p>{{ submissionProgressText }}</p>
      </div>
    </div>
    <header class="exam-header">
      <div class="exam-header__meta">
        <p class="exam-header__eyebrow">在线考试</p>
        <h1>{{ state.examName || '考试进行中' }}</h1>
        <p class="exam-header__hint">答题过程中系统会自动保存快照，离开页面或失焦会记录防作弊事件。</p>
      </div>
      <div class="exam-header__status">
        <div class="status-pill">
          <span>剩余时间</span>
          <strong>{{ formattedTimeLeft }}</strong>
        </div>
        <div class="status-pill">
          <span>已答题数</span>
          <strong>{{ answeredCount }}/{{ state.questions.length }}</strong>
        </div>
        <div :class="['status-pill', 'status-pill--sync', `status-pill--${saveStatus.type}`]">
          <span>保存状态</span>
          <strong>{{ saveStatus.title }}</strong>
          <small>{{ saveStatus.detail }}</small>
        </div>
        <div :class="['status-pill', 'status-pill--sync', `status-pill--${cameraStatus.type}`]">
          <span>摄像头监控</span>
          <strong>{{ cameraStatus.title }}</strong>
          <small>{{ cameraStatus.detail }}</small>
        </div>
      </div>
    </header>

    <div class="exam-banner">
      <el-alert
        :type="bannerState.type"
        show-icon
        :closable="false"
        :title="bannerState.title"
        :description="bannerState.description"
      />
    </div>

    <div class="exam-body">
      <aside class="exam-outline">
        <div class="outline-card">
          <div class="outline-toolbar">
            <el-switch
              v-model="showMarkedOnly"
              size="small"
              active-text="只看标记"
              :disabled="endingExam"
            />
          </div>
          <div class="outline-grid">
            <button
              v-for="q in outlineQuestions"
              :key="q.questionId"
              :class="[
                  'outline-item',
                {
                  'outline-item--done': isQuestionAnswered(q),
                  'outline-item--marked': isQuestionMarked(q),
                  'outline-item--active': isCurrentQuestion(q)
                }
              ]"
              type="button"
              :disabled="endingExam"
              @click="goToQuestion(q.questionId)"
            >
              <span class="outline-item__marker" aria-hidden="true"></span>
              <span class="outline-item__number">{{ q.sortOrder }}</span>
            </button>
          </div>
          <el-empty
            v-if="showMarkedOnly && !outlineQuestions.length"
            description="暂无标记题"
            :image-size="72"
          />
        </div>
      </aside>

      <section class="question-list">
        <article
          v-if="currentQuestion"
          :id="questionAnchorId(currentQuestion.questionId)"
          :key="currentQuestion.questionId"
          class="q-item"
        >
          <div class="q-item__head">
            <div>
              <div class="q-item__index">第 {{ currentQuestion.sortOrder }} 题</div>
              <div class="q-item__meta">{{ typeLabelMap[currentQuestion.type] || currentQuestion.type || '题目' }} · {{ currentQuestion.score }} 分</div>
            </div>
            <div class="q-item__actions">
              <el-button
                :type="isQuestionMarked(currentQuestion) ? 'warning' : 'info'"
                :plain="!isQuestionMarked(currentQuestion)"
                size="small"
                :disabled="endingExam"
                @click="toggleQuestionMark(currentQuestion)"
              >
                {{ isQuestionMarked(currentQuestion) ? '取消标记' : '标记' }}
              </el-button>
              <div :class="['q-item__status', { 'q-item__status--done': isQuestionAnswered(currentQuestion) }]">
                {{ isQuestionAnswered(currentQuestion) ? '已作答' : '待作答' }}
              </div>
            </div>
          </div>

          <div class="q-content">{{ currentQuestion.content }}</div>

          <div v-if="questionImages(currentQuestion).length" class="q-image-list">
            <figure
              v-for="asset in questionImages(currentQuestion)"
              :key="asset.assetId || asset.url"
              class="q-image-frame"
            >
              <el-image
                :src="asset.url"
                :preview-src-list="questionImages(currentQuestion).map(item => item.url)"
                fit="contain"
                class="q-image"
              />
            </figure>
          </div>

          <div class="q-answer">
            <template v-if="currentQuestion.type === 'MULTI'">
              <el-checkbox-group v-model="answers[currentQuestion.questionId]" class="option-list" :disabled="endingExam">
                <el-checkbox
                  v-for="opt in parseOptions(currentQuestion)"
                  :key="`${currentQuestion.questionId}-${opt.label}`"
                  :label="opt.label"
                  class="option-item"
                >
                  <span class="option-marker">{{ opt.label }}</span>
                  <span>{{ displayOptionText(currentQuestion, opt) }}</span>
                </el-checkbox>
              </el-checkbox-group>
            </template>

            <template v-else-if="currentQuestion.type === 'SINGLE' || currentQuestion.type === 'JUDGE'">
              <el-radio-group v-model="answers[currentQuestion.questionId]" class="option-list" :disabled="endingExam">
                <el-radio
                  v-for="opt in parseOptions(currentQuestion)"
                  :key="`${currentQuestion.questionId}-${opt.label}`"
                  :label="opt.label"
                  class="option-item"
                >
                  <span class="option-marker">{{ opt.label }}</span>
                  <span>{{ displayOptionText(currentQuestion, opt) }}</span>
                </el-radio>
              </el-radio-group>
            </template>

            <template v-else>
              <el-input
                v-model="answers[currentQuestion.questionId]"
                type="textarea"
                :rows="3"
                :maxlength="MAX_ANSWER_TEXT_LENGTH"
                show-word-limit
                resize="none"
                placeholder="请输入答案"
                :disabled="endingExam"
              />
            </template>
          </div>

          <div class="q-navigation">
            <el-button size="large" :disabled="endingExam || isFirstVisibleQuestion" @click="goToPreviousQuestion">
              上一题
            </el-button>
            <span class="q-navigation__progress">{{ currentQuestionPosition }}/{{ visibleQuestions.length }}</span>
            <el-button size="large" type="primary" :disabled="endingExam || isLastVisibleQuestion" @click="goToNextQuestion">
              下一题
            </el-button>
          </div>
        </article>
        <el-empty
          v-else-if="showMarkedOnly && !visibleQuestions.length"
          description="暂无标记题"
        />
      </section>
    </div>

    <footer class="exam-footer">
      <div class="exam-footer__summary">
        <span>共 {{ state.questions.length }} 题</span>
        <span>已完成 {{ answeredCount }} 题</span>
        <span>已标记 {{ markedCount }} 题</span>
      </div>
      <el-button
        type="primary"
        size="large"
        :loading="endingExam"
        :disabled="endingExam || leaseState.conflict"
        @click="submit"
      >{{ endingExam ? '交卷处理中' : '交卷' }}</el-button>
    </footer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { antiCheatApi, clientHeartbeatApi, healthPingApi, snapshotApi, startExamApi, submissionStatusApi, submitExamApi, uploadAntiCheatEvidenceApi } from '../../api'
import { useAuthStore } from '../../stores/auth'
import { useCameraProctoring } from '../../composables/useCameraProctoring'
import {
  clearDraft,
  clearSyncItems,
  deleteSyncItem,
  enqueueSyncItem,
  listSyncItems,
  loadDraft,
  saveDraft,
  updateSyncItem
} from '../../utils/examDraftStore'
import {
  clearExamClientLease,
  createExamWindowGuard,
  getOrCreateExamClientLease,
  updateExamClientLease
} from '../../utils/examClientLease'
import { parseDateTime } from '../../utils/datetime'
import {
  createSubmissionPlan,
  isSubmissionAccepted,
  shouldRetryDeadlineWithAnswers,
  submissionPollDelay
} from '../../utils/submissionFlow'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const examId = String(route.params.id || '')
const MAX_ANSWER_COUNT = 500
const MAX_ANSWER_TEXT_LENGTH = 16000
const MAX_TOTAL_ANSWER_LENGTH = 1000000

const state = reactive({
  examId,
  examName: '',
  questions: [],
  proctoringPolicy: {}
})
const answers = reactive({})
const markedQuestionIds = ref([])
const showMarkedOnly = ref(false)
const currentQuestionId = ref('')
const secondsLeft = ref(null)
const examEndAt = ref(null)
const blurStartedAt = ref(null)
const hiddenStartedAt = ref(null)
const offlineStartedAt = ref(null)
const lastActivityAt = ref(Date.now())
const endingExam = ref(false)
const submissionProgressText = ref('正在将交卷请求交给服务器，请勿重复操作。')
const allowLeaveExam = ref(false)
const isOnline = ref(navigator.onLine)
const networkState = reactive({
  status: navigator.onLine ? 'ONLINE' : 'OFFLINE',
  latencyMs: null,
  lastCheckedAt: null,
  queueSize: 0,
  flushing: false
})
const leaseState = reactive({
  clientId: '',
  leaseToken: null,
  leaseExpiresAt: null,
  heartbeatIntervalSeconds: 30,
  leaseTimeoutSeconds: 90,
  conflict: false
})
const offlineRecoveredMode = ref(false)
const pendingSubmitIntent = ref(null)
const bannerState = reactive({
  type: 'warning',
  title: '考试页面处于监考模式',
  description: '切屏、退出全屏、离线、复制粘贴等异常行为会被记录，但不会阻断你当前答题。'
})
const typeLabelMap = {
  SINGLE: '单选题',
  MULTI: '多选题',
  JUDGE: '判断题',
  BLANK: '填空题',
  SHORT: '简答题'
}
const DEFAULT_PROCTORING_POLICY = {
  level: 'STANDARD',
  trackWindowBlur: true,
  trackPageHidden: true,
  trackNavigationLeave: true,
  trackFullscreenExit: true,
  trackCopyPaste: true,
  trackContextMenu: true,
  trackNetworkOffline: true,
  trackLongInactivity: true,
  requireFullscreen: true,
  requireCamera: true,
  requireMicrophone: true,
  requireScreenShare: true,
  blockMultiMonitor: true,
  captureEvidence: true,
  inactivityThresholdSeconds: 180,
  offscreenLongThresholdSeconds: 30,
  repeatEventWindowMinutes: 10,
  repeatEventThreshold: 3
}
const SNAPSHOT_SYNC_INTERVAL_MS = 30_000
let timer = null
let snapshotTimer = null
let inactivityTimer = null
let bannerResetTimer = null
let draftSaveTimer = null
let queueFlushTimer = null
let healthCheckTimer = null
let heartbeatTimer = null
let windowGuard = null
let pendingDraftDirty = false
let pendingDraftAnswerTimestampUpdate = false
let navigationLeaveReporting = false
let lastNavigationLeaveAttemptAt = 0
let inactivityEventOpen = false
let leaseRequestTail = Promise.resolve()
let submissionPromise = null
let lastLeaseRenewedAt = 0
const recentEventTimes = new Map()
const cameraProctoring = useCameraProctoring({
  reportEvent: (eventType, durationMs, payload, evidence = []) =>
    reportAntiCheatEvent(eventType, durationMs, payload, evidence),
  uploadEvidence: (blob, source, eventType) => uploadEvidence(blob, source, eventType)
})
const syncState = reactive({
  userId: null,
  initialized: false,
  dirty: false,
  syncing: false,
  updatedAt: 0,
  lastSyncedAt: null,
  localSaving: false,
  localSavedAt: null,
  localSaveFailed: false,
  syncErrorAt: null,
  lastSyncErrorMessage: '',
  lastServerAckAt: null,
  snapshotVersion: 0
})

const answeredCount = computed(() =>
  state.questions.filter((q) => isQuestionAnswered(q)).length
)

const markedQuestionIdSet = computed(() => new Set(markedQuestionIds.value))
const markedCount = computed(() => markedQuestionIds.value.length)
const visibleQuestions = computed(() =>
  showMarkedOnly.value
    ? state.questions.filter((q) => isQuestionMarked(q))
    : state.questions
)
const outlineQuestions = computed(() => visibleQuestions.value)
const currentQuestionIndex = computed(() =>
  visibleQuestions.value.findIndex((question) => toStoredQuestionId(question.questionId) === currentQuestionId.value)
)
const normalizedCurrentQuestionIndex = computed(() => {
  if (!visibleQuestions.value.length) {
    return -1
  }
  return currentQuestionIndex.value >= 0 ? currentQuestionIndex.value : 0
})
const currentQuestion = computed(() =>
  normalizedCurrentQuestionIndex.value < 0
    ? null
    : visibleQuestions.value[normalizedCurrentQuestionIndex.value]
)
const currentQuestionPosition = computed(() =>
  normalizedCurrentQuestionIndex.value < 0 ? 0 : normalizedCurrentQuestionIndex.value + 1
)
const isFirstVisibleQuestion = computed(() => normalizedCurrentQuestionIndex.value <= 0)
const isLastVisibleQuestion = computed(() =>
  normalizedCurrentQuestionIndex.value < 0
    || normalizedCurrentQuestionIndex.value >= visibleQuestions.value.length - 1
)

const formattedTimeLeft = computed(() => {
  if (!Number.isFinite(secondsLeft.value)) {
    return '--:--:--'
  }
  const total = Math.max(0, secondsLeft.value)
  const hours = String(Math.floor(total / 3600)).padStart(2, '0')
  const minutes = String(Math.floor((total % 3600) / 60)).padStart(2, '0')
  const seconds = String(total % 60).padStart(2, '0')
  return `${hours}:${minutes}:${seconds}`
})

const formatClockTime = (timestamp) => {
  if (!timestamp) {
    return '--:--:--'
  }
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) {
    return '--:--:--'
  }
  return date.toLocaleTimeString('zh-CN', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

const saveStatus = computed(() => {
  if (endingExam.value) {
    return {
      type: 'warning',
      title: '交卷处理中',
      detail: submissionProgressText.value
    }
  }
  if (syncState.localSaveFailed) {
    return {
      type: 'danger',
      title: '本机保存失败',
      detail: syncState.lastSyncErrorMessage || '请勿关闭页面，系统会继续尝试。'
    }
  }
  if (syncState.localSaving) {
    return {
      type: 'info',
      title: '本机保存中',
      detail: '正在写入本地草稿。'
    }
  }
  if (networkState.flushing) {
    return {
      type: 'warning',
      title: '正在恢复同步',
      detail: `待同步 ${networkState.queueSize} 项，请保持网络连接。`
    }
  }
  if (!isOnline.value) {
    return {
      type: 'warning',
      title: '离线，仅本机保存',
      detail: syncState.localSavedAt ? `本机保存于 ${formatClockTime(syncState.localSavedAt)}` : '恢复网络后会自动同步。'
    }
  }
  if (networkState.status === 'DEGRADED') {
    return {
      type: 'warning',
      title: '弱网，本机优先保存',
      detail: networkState.latencyMs ? `延迟约 ${networkState.latencyMs}ms，后台继续同步。` : '请求较慢，后台继续重试。'
    }
  }
  if (offlineRecoveredMode.value) {
    return {
      type: 'warning',
      title: '离线恢复模式',
      detail: '已从本机恢复，联网后需同步到服务器。'
    }
  }
  if (syncState.syncing) {
    return {
      type: 'warning',
      title: '正在同步到服务器',
      detail: '请保持网络连接。'
    }
  }
  if (syncState.syncErrorAt && syncState.dirty) {
    return {
      type: 'danger',
      title: '同步失败，将自动重试',
      detail: syncState.lastSyncErrorMessage || `失败于 ${formatClockTime(syncState.syncErrorAt)}`
    }
  }
  if (syncState.dirty) {
    return {
      type: 'warning',
      title: '已保存到本机',
      detail: '等待同步到服务器。'
    }
  }
  if (syncState.lastSyncedAt) {
    return {
      type: 'success',
      title: '已同步到服务器',
      detail: `同步于 ${formatClockTime(syncState.lastSyncedAt)}`
    }
  }
  if (syncState.localSavedAt) {
    return {
      type: 'success',
      title: '已保存到本机',
      detail: `保存于 ${formatClockTime(syncState.localSavedAt)}`
    }
  }
  return {
    type: 'info',
    title: '准备保存',
    detail: '开始答题后自动保存。'
  }
})

const cameraStatus = computed(() => {
  const type = cameraProctoring.state.status === 'danger'
    ? 'danger'
    : cameraProctoring.state.status === 'success'
      ? 'success'
      : 'info'
  return {
    type,
    title: cameraProctoring.state.title,
    detail: cameraProctoring.state.detail
  }
})

const normalizePolicy = (policy = {}) => ({
  ...DEFAULT_PROCTORING_POLICY,
  ...(policy || {}),
  inactivityThresholdSeconds: Number(policy?.inactivityThresholdSeconds || DEFAULT_PROCTORING_POLICY.inactivityThresholdSeconds),
  offscreenLongThresholdSeconds: Number(policy?.offscreenLongThresholdSeconds || DEFAULT_PROCTORING_POLICY.offscreenLongThresholdSeconds),
  repeatEventWindowMinutes: Number(policy?.repeatEventWindowMinutes || DEFAULT_PROCTORING_POLICY.repeatEventWindowMinutes),
  repeatEventThreshold: Number(policy?.repeatEventThreshold || DEFAULT_PROCTORING_POLICY.repeatEventThreshold)
})

const shouldRecordClientEvent = (eventType) => {
  const policy = state.proctoringPolicy || DEFAULT_PROCTORING_POLICY
  switch (eventType) {
    case 'WINDOW_BLUR':
      return policy.trackWindowBlur !== false
    case 'TAB_HIDDEN':
      return policy.trackPageHidden !== false
    case 'NAVIGATION_LEAVE_ATTEMPT':
      return policy.trackNavigationLeave !== false
    case 'FULLSCREEN_EXIT':
      return policy.trackFullscreenExit !== false
    case 'COPY_ATTEMPT':
    case 'PASTE_ATTEMPT':
    case 'CUT_ATTEMPT':
      return policy.trackCopyPaste !== false
    case 'CONTEXT_MENU':
      return policy.trackContextMenu !== false
    case 'NETWORK_OFFLINE':
      return policy.trackNetworkOffline !== false
    case 'LONG_INACTIVITY':
      return policy.trackLongInactivity !== false
    default:
      return true
  }
}

const policyNumber = (key, fallback) => {
  const value = Number(state.proctoringPolicy?.[key])
  return Number.isFinite(value) && value > 0 ? value : fallback
}

const questionAnchorId = (questionId) => `question-${questionId}`

const judgeFallbackOptions = [
  { label: 'A', value: 'true' },
  { label: 'B', value: 'false' }
]

const parseOptions = (question) => {
  if (!question?.optionsJson) {
    return question?.type === 'JUDGE' ? judgeFallbackOptions : []
  }
  try {
    const parsed = JSON.parse(question.optionsJson)
    if (!Array.isArray(parsed)) {
      return []
    }
    const normalized = parsed.map((item, index) => ({
      label: item?.label || String.fromCharCode(65 + index),
      value: item?.value == null ? '' : String(item.value)
    }))
    return normalized.filter((item) => item.value)
  } catch {
    return question?.type === 'JUDGE' ? judgeFallbackOptions : []
  }
}

const displayOptionText = (question, option) => {
  if (question?.type !== 'JUDGE') {
    return option.value
  }
  if (option.value === 'true') {
    return '正确'
  }
  if (option.value === 'false') {
    return '错误'
  }
  return option.value
}

const isImageAsset = (asset) => {
  if (!asset?.url) {
    return false
  }
  const fileType = String(asset.fileType || '').toUpperCase()
  if (fileType === 'IMAGE') {
    return true
  }
  return /\.(png|jpe?g|gif|webp|bmp|svg)(?:[?#].*)?$/i.test(String(asset.url))
}

const questionImages = (question) =>
  Array.isArray(question?.assets)
    ? question.assets.filter(isImageAsset)
    : []

const toStoredQuestionId = (questionId) => String(questionId)

const buildValidQuestionIdSet = () =>
  new Set(state.questions.map((question) => toStoredQuestionId(question.questionId)))

const normalizeMarkedQuestionIds = (ids = []) => {
  const validQuestionIds = buildValidQuestionIdSet()
  if (!Array.isArray(ids) || !validQuestionIds.size) {
    return []
  }
  return [...new Set(ids.map((item) => String(item)).filter((item) => validQuestionIds.has(item)))]
}

const normalizeAnswerInputValue = (question, value) => {
  if (question?.type === 'MULTI') {
    if (Array.isArray(value)) {
      return value.map((item) => String(item)).filter((item) => item)
    }
    if (typeof value === 'string' && value) {
      return value.split(',').map((item) => item.trim()).filter((item) => item)
    }
    return []
  }
  if (value == null) {
    return ''
  }
  return String(value)
}

const isQuestionAnswered = (question) => {
  const value = answers[question.questionId]
  if (Array.isArray(value)) {
    return value.length > 0
  }
  return String(value || '').trim().length > 0
}

const isQuestionMarked = (question) =>
  markedQuestionIdSet.value.has(toStoredQuestionId(question?.questionId))

const isCurrentQuestion = (question) =>
  toStoredQuestionId(question?.questionId) === toStoredQuestionId(currentQuestion.value?.questionId)

const applyMarkedQuestionIds = (ids = []) => {
  markedQuestionIds.value = normalizeMarkedQuestionIds(ids)
  if (showMarkedOnly.value && !markedQuestionIds.value.length) {
    showMarkedOnly.value = false
  }
}

const buildMarkedQuestionIdsFromState = () => normalizeMarkedQuestionIds(markedQuestionIds.value)

const toggleQuestionMark = (question) => {
  const questionId = toStoredQuestionId(question?.questionId)
  if (!questionId) {
    return
  }
  if (markedQuestionIdSet.value.has(questionId)) {
    markedQuestionIds.value = markedQuestionIds.value.filter((item) => item !== questionId)
  } else {
    markedQuestionIds.value = [...markedQuestionIds.value, questionId]
  }
  if (showMarkedOnly.value && !markedQuestionIds.value.length) {
    showMarkedOnly.value = false
  }
  scheduleDraftSave({ dirty: false, updateAnswerTimestamp: false })
}

const goToQuestion = (questionId) => {
  const normalizedQuestionId = toStoredQuestionId(questionId)
  if (!visibleQuestions.value.some((question) => toStoredQuestionId(question.questionId) === normalizedQuestionId)) {
    return
  }
  currentQuestionId.value = normalizedQuestionId
}

const goToPreviousQuestion = () => {
  if (isFirstVisibleQuestion.value) {
    return
  }
  const previousQuestion = visibleQuestions.value[normalizedCurrentQuestionIndex.value - 1]
  if (previousQuestion) {
    goToQuestion(previousQuestion.questionId)
  }
}

const goToNextQuestion = () => {
  if (isLastVisibleQuestion.value) {
    return
  }
  const nextQuestion = visibleQuestions.value[normalizedCurrentQuestionIndex.value + 1]
  if (nextQuestion) {
    goToQuestion(nextQuestion.questionId)
  }
}

const normalizeAnswer = (q) => {
  const val = answers[q.questionId]
  if (Array.isArray(val)) {
    return val.join(',')
  }
  return val || ''
}

const buildAnswerMapFromState = () => {
  const map = {}
  state.questions.forEach((question) => {
    map[toStoredQuestionId(question.questionId)] = normalizeAnswerInputValue(question, answers[question.questionId])
  })
  return map
}

const buildAnswerMapFromQuestions = (questions = []) => {
  const map = {}
  questions.forEach((question) => {
    map[toStoredQuestionId(question.questionId)] = normalizeAnswerInputValue(question, question.currentAnswer)
  })
  return map
}

const applyAnswerMap = (answerMap = {}) => {
  state.questions.forEach((question) => {
    const rawValue = answerMap?.[question.questionId] ?? answerMap?.[toStoredQuestionId(question.questionId)]
    answers[question.questionId] = normalizeAnswerInputValue(question, rawValue)
  })
}

const buildSubmitPayload = () => ({
  answers: state.questions.map((q) => ({
    questionId: q.questionId,
    answerText: normalizeAnswer(q)
  }))
})

const answerPayloadValidationError = (payload = {}) => {
  const payloadAnswers = Array.isArray(payload.answers) ? payload.answers : []
  if (!payloadAnswers.length || payloadAnswers.length > MAX_ANSWER_COUNT) {
    return `答案数量不能超过 ${MAX_ANSWER_COUNT} 题`
  }
  let totalLength = 0
  for (const answer of payloadAnswers) {
    const answerText = String(answer?.answerText ?? '')
    if (answerText.length > MAX_ANSWER_TEXT_LENGTH) {
      return `单题答案不能超过 ${MAX_ANSWER_TEXT_LENGTH} 个字符`
    }
    totalLength += answerText.length
    if (totalLength > MAX_TOTAL_ANSWER_LENGTH) {
      return `整份答案不能超过 ${MAX_TOTAL_ANSWER_LENGTH} 个字符`
    }
  }
  return ''
}

const hasDraftContent = (answerMap = {}) =>
  state.questions.some((question) => {
    const value = answerMap?.[question.questionId] ?? answerMap?.[toStoredQuestionId(question.questionId)]
    if (Array.isArray(value)) {
      return value.length > 0
    }
    return String(value || '').trim().length > 0
  })

const resetBanner = () => {
  bannerState.type = 'warning'
  bannerState.title = '考试页面处于监考模式'
  bannerState.description = state.proctoringPolicy?.trackLongInactivity === false
    ? '本场考试将按老师设置记录异常行为，但不会阻断你当前答题。'
    : '切屏、离开页面、复制粘贴、长时间无操作等异常行为会被记录，但不会阻断你当前答题。'
}

const showBanner = (type, title, description, sticky = false) => {
  bannerState.type = type
  bannerState.title = title
  bannerState.description = description
  if (bannerResetTimer) {
    clearTimeout(bannerResetTimer)
    bannerResetTimer = null
  }
  if (!sticky) {
    bannerResetTimer = setTimeout(() => {
      resetBanner()
      bannerResetTimer = null
    }, 8000)
  }
}

const showRecoveryBanner = (mode) => {
  if (mode === 'offline-local') {
    showBanner('warning', '已从本机离线恢复', '当前无法连接服务器，可继续作答并保存到本机；恢复网络且未超过截止时间后才能交卷。', true)
    return
  }
  if (mode === 'local') {
    showBanner('success', '已恢复本地草稿', '检测到本机保存的较新答题进度，系统已优先恢复，并会在联网后自动同步。')
    return
  }
  if (mode === 'server') {
    showBanner('success', '已恢复服务端草稿', '检测到服务器上的答题进度，系统已自动恢复到你上次保存的位置。')
    return
  }
  if (mode === 'resumed') {
    showBanner('info', '已恢复考试会话', '你之前已经进入过本场考试，当前会话已继续，可直接接着作答。')
    return
  }
  showBanner('info', '已开始答题', '本地草稿保存已开启。网络中断时会继续在本机保存，恢复网络后自动同步。')
}

const formatDuration = (durationMs) => {
  if (!durationMs || durationMs <= 0) {
    return '不足1秒'
  }
  const totalSeconds = Math.ceil(durationMs / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  if (!minutes) {
    return `${seconds}秒`
  }
  return `${minutes}分${seconds}秒`
}

const recordRecentEvent = (eventType) => {
  const now = Date.now()
  const recent = recentEventTimes.get(eventType) || []
  const filtered = recent.filter((time) => now - time < 10 * 60 * 1000)
  filtered.push(now)
  recentEventTimes.set(eventType, filtered)
  return filtered.length
}

const buildAntiCheatPayload = (extra = {}) => JSON.stringify({
  path: window.location.pathname,
  visibilityState: document.visibilityState,
  online: navigator.onLine,
  fullscreen: Boolean(document.fullscreenElement),
  timestamp: Date.now(),
  ...extra
})

const warningMessageForEvent = (eventType, durationMs) => {
  switch (eventType) {
    case 'WINDOW_BLUR':
      return `检测到窗口离开 ${formatDuration(durationMs)}，系统已记录。`
    case 'TAB_HIDDEN':
      return `检测到页面隐藏 ${formatDuration(durationMs)}，系统已记录。`
    case 'FULLSCREEN_EXIT':
      return '检测到你已退出全屏模式，请尽快恢复。'
    case 'COPY_ATTEMPT':
      return '检测到复制操作，系统已记录。'
    case 'PASTE_ATTEMPT':
      return '检测到粘贴操作，系统已记录。'
    case 'CUT_ATTEMPT':
      return '检测到剪切操作，系统已记录。'
    case 'CONTEXT_MENU':
      return '检测到右键菜单操作，系统已记录。'
    case 'NETWORK_OFFLINE':
      return `网络中断 ${formatDuration(durationMs)}，系统已记录。`
    case 'LONG_INACTIVITY':
      return `检测到 ${formatDuration(durationMs)} 未操作考试页面，系统已记录。`
    case 'CAMERA_START_FAILED':
      return '考试摄像头启动失败，系统已记录。'
    case 'CAMERA_STREAM_ENDED':
      return '摄像头画面中断，系统已记录。'
    case 'CAMERA_TRACK_MUTED':
      return '摄像头画面暂停，系统已记录。'
    case 'CAMERA_FRAME_DARK':
      return `摄像头画面连续异常 ${formatDuration(durationMs)}，系统已记录。`
    case 'MULTI_MONITOR_DETECTED':
      return '检测到多个显示器，系统已记录。'
    case 'SCREEN_CHECK_UNAVAILABLE':
      return '无法确认显示器数量，系统已记录。'
    case 'SCREEN_SHARE_START_FAILED':
      return '屏幕共享启动失败，系统已记录。'
    case 'SCREEN_SHARE_ENDED':
      return '屏幕共享已中断，系统已记录。'
    case 'NAVIGATION_LEAVE_ATTEMPT':
      return '考试中不能返回上一页，请继续答题或点击交卷。'
    default:
      return '检测到异常考试行为，系统已记录。'
  }
}

const uploadEvidence = async (blob, source, eventType) => {
  const formData = new FormData()
  const filename = `${source.toLowerCase()}-${Date.now()}.jpg`
  formData.append('file', blob, filename)
  formData.append('source', source)
  formData.append('eventType', eventType)
  return uploadAntiCheatEvidenceApi(examId, formData)
}

const reportAntiCheatEvent = async (eventType, durationMs = 0, extraPayload = {}, evidence = null) => {
  if (!shouldRecordClientEvent(eventType)) {
    return
  }
  const occurredAt = Date.now()
  const eventCount = recordRecentEvent(eventType)
  const repeatThreshold = policyNumber('repeatEventThreshold', DEFAULT_PROCTORING_POLICY.repeatEventThreshold)
  const offscreenLongMs = policyNumber('offscreenLongThresholdSeconds', DEFAULT_PROCTORING_POLICY.offscreenLongThresholdSeconds) * 1000
  const inactivityMs = policyNumber('inactivityThresholdSeconds', DEFAULT_PROCTORING_POLICY.inactivityThresholdSeconds) * 1000
  const escalated = eventCount >= repeatThreshold
    || durationMs > offscreenLongMs
    || eventType === 'FULLSCREEN_EXIT'
    || eventType === 'CAMERA_START_FAILED'
    || eventType === 'CAMERA_STREAM_ENDED'
    || eventType === 'MULTI_MONITOR_DETECTED'
    || eventType === 'SCREEN_CHECK_UNAVAILABLE'
    || eventType === 'SCREEN_SHARE_START_FAILED'
    || eventType === 'SCREEN_SHARE_ENDED'
    || eventType === 'NAVIGATION_LEAVE_ATTEMPT'
    || eventType === 'LONG_INACTIVITY'
    || (eventType === 'CAMERA_TRACK_MUTED' && eventCount >= 2)
    || eventType === 'CAMERA_FRAME_DARK'
    || (eventType === 'NETWORK_OFFLINE' && durationMs > 10_000)
    || (eventType === 'LONG_INACTIVITY' && durationMs >= inactivityMs)
  const message = warningMessageForEvent(eventType, durationMs)
  showBanner(
    escalated ? 'error' : 'warning',
    escalated ? '监考风险已升级' : '监考事件已记录',
    `${message}${escalated ? ' 当前异常频率较高，请立即恢复正常答题状态。' : ''}`
  )
  if (escalated) {
    ElMessage.error(message)
  } else {
    ElMessage.warning(message)
  }

  let evidenceList = Array.isArray(evidence) ? evidence : []
  let evidenceUploadError = extraPayload.evidenceUploadError
  if (escalated && evidence == null && state.proctoringPolicy?.captureEvidence !== false) {
    try {
      const evidenceResult = await cameraProctoring.captureEvidence(eventType)
      evidenceList = evidenceResult.evidence
      evidenceUploadError = evidenceResult.errors.length
        ? [evidenceUploadError, evidenceResult.errors.join('; ')].filter(Boolean).join('; ')
        : evidenceUploadError
    } catch (error) {
      evidenceUploadError = [evidenceUploadError, error?.message || 'evidence capture failed'].filter(Boolean).join('; ')
    }
  }

  try {
    await antiCheatApi(examId, {
      eventType,
      durationMs,
      payload: buildAntiCheatPayload({
        eventCount,
        occurredAt,
        ...extraPayload,
        evidenceUploadError
      }),
      evidenceJson: evidenceList.length ? JSON.stringify(evidenceList) : null
    }, { silent: true, timeout: 10000 })
  } catch (error) {
    if (!isRecoverableNetworkError(error)) {
      syncState.syncErrorAt = Date.now()
      syncState.lastSyncErrorMessage = error?.message || '监考事件同步失败'
      return
    }
    await queueAntiCheatSync({
      eventType,
      durationMs,
      payload: buildAntiCheatPayload({
        eventCount,
        occurredAt,
        ...extraPayload,
        evidenceUploadError,
        enqueueError: error?.message || 'anti-cheat event queued'
      }),
      evidenceJson: evidenceList.length ? JSON.stringify(evidenceList) : null,
      occurredAt
    })
    scheduleQueueFlush(retryDelayForAttempt(0))
  }
}

const resolveExamEndTime = (data) => {
  const deadline = parseDateTime(data?.deadlineTime)
  if (deadline) {
    return deadline
  }

  const explicitEnd = parseDateTime(data?.endTime)
  if (explicitEnd) {
    return explicitEnd
  }

  const start = parseDateTime(data?.startTime)
  const durationMinutes = Number(data?.durationMinutes)
  if (start && Number.isFinite(durationMinutes) && durationMinutes > 0) {
    return new Date(start.getTime() + durationMinutes * 60 * 1000)
  }

  return null
}

const syncCountdown = () => {
  if (!(examEndAt.value instanceof Date) || Number.isNaN(examEndAt.value.getTime())) {
    secondsLeft.value = null
    return
  }
  secondsLeft.value = Math.max(0, Math.floor((examEndAt.value.getTime() - Date.now()) / 1000))
}

const isExamExpiredLocally = () =>
  examEndAt.value instanceof Date
  && !Number.isNaN(examEndAt.value.getTime())
  && Date.now() >= examEndAt.value.getTime()

const buildExamRuntime = () => ({
  examId: state.examId || examId,
  examName: state.examName,
  questions: state.questions,
  proctoringPolicy: state.proctoringPolicy,
  examEndAt: examEndAt.value instanceof Date ? examEndAt.value.toISOString() : null,
  savedAt: Date.now()
})

const updateNetworkStatus = (status, latencyMs = null) => {
  networkState.status = status
  networkState.latencyMs = latencyMs
  networkState.lastCheckedAt = Date.now()
  isOnline.value = status !== 'OFFLINE'
}

const syncLeaseState = (record) => {
  leaseState.clientId = record?.clientId || ''
  leaseState.leaseToken = record?.leaseToken || null
  leaseState.leaseExpiresAt = record?.leaseExpiresAt || null
  leaseState.heartbeatIntervalSeconds = Number(record?.heartbeatIntervalSeconds || 30)
  leaseState.leaseTimeoutSeconds = Number(record?.leaseTimeoutSeconds || 90)
}

const ensureExamClientLease = () => {
  if (!syncState.userId) {
    return null
  }
  const record = getOrCreateExamClientLease(syncState.userId, examId)
  syncLeaseState(record)
  return record
}

const applyLeaseResponse = (payload = {}) => {
  if (!syncState.userId || !payload?.leaseToken) {
    return
  }
  const record = updateExamClientLease(syncState.userId, examId, payload)
  syncLeaseState(record)
  lastLeaseRenewedAt = Date.now()
}

const runLeaseRequest = (request) => {
  const current = leaseRequestTail.catch(() => {}).then(request)
  leaseRequestTail = current.catch(() => {})
  return current
}

const buildClientLeasePayload = () => {
  const record = ensureExamClientLease()
  return {
    clientId: leaseState.clientId || record?.clientId || null,
    leaseToken: leaseState.leaseToken || record?.leaseToken || null
  }
}

const withClientLeasePayload = (payload = {}) => ({
  ...payload,
  ...buildClientLeasePayload()
})

const withoutClientLeasePayload = (payload = {}) => {
  const { clientId, leaseToken, ...rest } = payload
  return rest
}

const isExamClientLeaseError = (error) => {
  const code = error?.code || error?.responseData?.code || error?.response?.data?.code
  return code === 'EXAM_CLIENT_CONFLICT' || code === 'EXAM_CLIENT_REQUIRED'
}

const isRecoverableNetworkError = (error) => {
  if (error?.isBusinessError || error?.responseData) {
    return false
  }
  return !navigator.onLine
    || error?.code === 'ECONNABORTED'
    || error?.code === 'ERR_NETWORK'
    || error?.code === 'ERR_CANCELED'
    || error?.message === 'Network Error'
}

const markNetworkFailure = (error) => {
  const message = error?.code === 'ECONNABORTED' ? '网络请求超时' : (error?.message || '网络异常')
  updateNetworkStatus(navigator.onLine ? 'DEGRADED' : 'OFFLINE')
  syncState.lastSyncErrorMessage = message
}

const stopExamRuntimeTimers = () => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  if (snapshotTimer) {
    clearInterval(snapshotTimer)
    snapshotTimer = null
  }
  if (heartbeatTimer) {
    clearTimeout(heartbeatTimer)
    heartbeatTimer = null
  }
  if (healthCheckTimer) {
    clearInterval(healthCheckTimer)
    healthCheckTimer = null
  }
  if (inactivityTimer) {
    clearInterval(inactivityTimer)
    inactivityTimer = null
  }
  if (queueFlushTimer) {
    clearTimeout(queueFlushTimer)
    queueFlushTimer = null
  }
}

const handleExamClientLeaseError = async (error) => {
  if (!isExamClientLeaseError(error) || leaseState.conflict) {
    return isExamClientLeaseError(error)
  }
  leaseState.conflict = true
  allowLeaveExam.value = true
  stopExamRuntimeTimers()
  cameraProctoring.stop()
  if (syncState.userId) {
    clearExamClientLease(syncState.userId, examId)
  }
  if (windowGuard) {
    windowGuard.close()
    windowGuard = null
  }
  const message = error?.message || '本场考试已在其他窗口答题，请回到原窗口继续作答或稍后重试。'
  try {
    await ElMessageBox.alert(message, '考试窗口已被占用', {
      confirmButtonText: '返回我的考试',
      type: 'warning',
      closeOnClickModal: false,
      closeOnPressEscape: false
    })
  } catch {
    // Keep routing even if the dialog is dismissed by the browser.
  }
  router.replace('/student/exams')
  return true
}

const runHealthCheck = async () => {
  if (!navigator.onLine) {
    updateNetworkStatus('OFFLINE')
    return false
  }
  const startedAt = Date.now()
  try {
    await healthPingApi()
    const latency = Date.now() - startedAt
    updateNetworkStatus(latency > 2500 ? 'DEGRADED' : 'ONLINE', latency)
    return true
  } catch (error) {
    markNetworkFailure(error)
    return false
  }
}

const runClientHeartbeat = async () => {
  if (!syncState.initialized || leaseState.conflict || !navigator.onLine) {
    return false
  }
  const heartbeatIntervalMs = Math.max(5, Number(leaseState.heartbeatIntervalSeconds || 30)) * 1000
  if (Date.now() - lastLeaseRenewedAt < heartbeatIntervalMs) {
    return true
  }
  try {
    const startedAt = Date.now()
    const lease = await runLeaseRequest(() => {
      if (Date.now() - lastLeaseRenewedAt < heartbeatIntervalMs) {
        return null
      }
      return clientHeartbeatApi(examId, buildClientLeasePayload(), { silent: true, timeout: 10000 })
    })
    if (!lease) {
      return true
    }
    applyLeaseResponse(lease)
    const latency = Date.now() - startedAt
    updateNetworkStatus(latency > 2500 ? 'DEGRADED' : 'ONLINE', latency)
    return true
  } catch (error) {
    if (await handleExamClientLeaseError(error)) {
      return false
    }
    if (isRecoverableNetworkError(error)) {
      markNetworkFailure(error)
      return false
    }
    syncState.syncErrorAt = Date.now()
    syncState.lastSyncErrorMessage = error?.message || '考试窗口心跳失败'
    return false
  }
}

const scheduleNextHeartbeat = ({ initial = false } = {}) => {
  if (endingExam.value || leaseState.conflict || !syncState.initialized) {
    return
  }
  if (heartbeatTimer) {
    clearTimeout(heartbeatTimer)
  }
  const baseMs = Math.max(5, Number(leaseState.heartbeatIntervalSeconds || 30)) * 1000
  const delayMs = initial
    ? Math.floor(Math.random() * 10000)
    : Math.round(baseMs * (0.8 + Math.random() * 0.4))
  heartbeatTimer = setTimeout(async () => {
    heartbeatTimer = null
    await runClientHeartbeat()
    if (!endingExam.value && !leaseState.conflict && syncState.initialized) {
      scheduleNextHeartbeat()
    }
  }, delayMs)
}

const refreshQueueSize = async () => {
  if (!syncState.userId) {
    networkState.queueSize = 0
    return 0
  }
  const items = await listSyncItems(syncState.userId, examId)
  networkState.queueSize = items.length
  return items.length
}

const persistDraftState = async ({
  updatedAt = Date.now(),
  lastSyncedAt = syncState.lastSyncedAt,
  lastServerAckAt = syncState.lastServerAckAt,
  snapshotVersion = syncState.snapshotVersion,
  dirty = true,
  nextPendingSubmitIntent = pendingSubmitIntent.value,
  answersMap = buildAnswerMapFromState(),
  markedIds = buildMarkedQuestionIdsFromState(),
  examRuntime = buildExamRuntime()
} = {}) => {
  if (!syncState.userId) {
    return null
  }
  syncState.localSaving = true
  try {
    const nextLastSyncedAt = lastSyncedAt ?? null
    const record = await saveDraft({
      userId: syncState.userId,
      examId,
      answers: answersMap,
      markedQuestionIds: markedIds,
      updatedAt,
      lastSyncedAt: nextLastSyncedAt,
      lastServerAckAt,
      snapshotVersion,
      pendingSubmitIntent: nextPendingSubmitIntent,
      examRuntime,
      dirty
    })
    syncState.updatedAt = updatedAt
    syncState.lastSyncedAt = nextLastSyncedAt
    syncState.lastServerAckAt = lastServerAckAt
    syncState.snapshotVersion = Number(snapshotVersion || 0)
    pendingSubmitIntent.value = nextPendingSubmitIntent
    syncState.dirty = Boolean(dirty)
    syncState.localSavedAt = Date.now()
    syncState.localSaveFailed = false
    syncState.lastSyncErrorMessage = ''
    return record
  } catch (error) {
    syncState.localSaveFailed = true
    syncState.lastSyncErrorMessage = error?.message || '本地草稿保存失败'
    return null
  } finally {
    syncState.localSaving = false
  }
}

const resolveDraftUpdatedAt = (updateAnswerTimestamp) =>
  updateAnswerTimestamp ? Date.now() : (syncState.updatedAt || Date.now())

const scheduleDraftSave = ({ dirty = true, updateAnswerTimestamp = true } = {}) => {
  if (!syncState.userId) {
    return
  }
  syncState.localSaving = true
  pendingDraftDirty = pendingDraftDirty || dirty
  pendingDraftAnswerTimestampUpdate = pendingDraftAnswerTimestampUpdate || updateAnswerTimestamp
  if (draftSaveTimer) {
    clearTimeout(draftSaveTimer)
  }
  draftSaveTimer = setTimeout(() => {
    draftSaveTimer = null
    void persistDraftState({
      updatedAt: resolveDraftUpdatedAt(pendingDraftAnswerTimestampUpdate),
      lastSyncedAt: syncState.lastSyncedAt,
      dirty: pendingDraftDirty ? true : syncState.dirty
    })
    pendingDraftDirty = false
    pendingDraftAnswerTimestampUpdate = false
  }, 400)
}

const flushDraftSave = () => {
  if (!draftSaveTimer) {
    return
  }
  clearTimeout(draftSaveTimer)
  draftSaveTimer = null
  void persistDraftState({
    updatedAt: resolveDraftUpdatedAt(pendingDraftAnswerTimestampUpdate),
    lastSyncedAt: syncState.lastSyncedAt,
    dirty: pendingDraftDirty ? true : syncState.dirty
  })
  pendingDraftDirty = false
  pendingDraftAnswerTimestampUpdate = false
}

const snapshotHistoryState = (state = window.history.state) => {
  try {
    return state == null ? null : JSON.parse(JSON.stringify(state))
  } catch {
    return { unavailable: true }
  }
}

const currentBrowserPath = () => `${window.location.pathname}${window.location.search}${window.location.hash}`

const isCurrentExamHistoryLocked = () =>
  Boolean(
    window.history.state?.examLocked
    && window.history.state?.examId === examId
    && window.history.state?.examPath === route.fullPath
    && currentBrowserPath() === route.fullPath
  )

const setExamHistoryLock = ({ replace = false } = {}) => {
  if (allowLeaveExam.value) {
    return
  }
  try {
    if (isCurrentExamHistoryLocked()) {
      return
    }
    const nextState = {
      ...(window.history.state || {}),
      examLocked: true,
      examId,
      examPath: route.fullPath
    }
    if (replace) {
      window.history.replaceState(nextState, '', route.fullPath)
    } else {
      window.history.pushState(nextState, '', route.fullPath)
    }
  } catch {
    // Route guard still protects in-app navigation if manual history writes are unavailable.
  }
}

const saveAndSyncBeforeBlockingLeave = async () => {
  if (draftSaveTimer) {
    clearTimeout(draftSaveTimer)
    draftSaveTimer = null
    pendingDraftDirty = false
    pendingDraftAnswerTimestampUpdate = false
  }
  await persistDraftState({
    updatedAt: Date.now(),
    dirty: true
  })
  if (navigator.onLine) {
    await syncDirtyDraft({ force: true })
  }
}

const handleBlockedExamLeave = async ({ source, fromPath, toPath, historyState = null } = {}) => {
  if (allowLeaveExam.value) {
    return
  }
  const now = Date.now()
  if (navigationLeaveReporting || now - lastNavigationLeaveAttemptAt < 1200) {
    showBanner('error', '已阻止离开考试页', '考试中不能返回上一页，请继续答题或点击交卷。', true)
    return
  }

  navigationLeaveReporting = true
  lastNavigationLeaveAttemptAt = now
  try {
    await saveAndSyncBeforeBlockingLeave()
    await reportAntiCheatEvent('NAVIGATION_LEAVE_ATTEMPT', 0, {
      source,
      fromPath: fromPath || route.fullPath,
      toPath: toPath || null,
      historyState: snapshotHistoryState(historyState),
      mode: 'exam-navigation-lock'
    })
  } finally {
    navigationLeaveReporting = false
  }
}

const retryDelayForAttempt = (attemptCount = 0) => {
  const delays = [2000, 5000, 10000, 30000]
  return delays[Math.min(attemptCount, delays.length - 1)]
}

const buildSnapshotPayload = (snapshotVersion = syncState.updatedAt || Date.now()) => ({
  ...withClientLeasePayload(buildSubmitPayload()),
  clientTimestamp: snapshotVersion,
  snapshotVersion
})

const queueSnapshotSync = async (payload) => {
  if (!syncState.userId) {
    return
  }
  await enqueueSyncItem({
    userId: syncState.userId,
    examId,
    type: 'SNAPSHOT',
    payload: withoutClientLeasePayload(payload),
    occurredAt: Date.now()
  })
  await refreshQueueSize()
}

const queueAntiCheatSync = async ({ eventType, durationMs, payload, evidenceJson, occurredAt = Date.now() }) => {
  if (!syncState.userId) {
    return
  }
  await enqueueSyncItem({
    userId: syncState.userId,
    examId,
    type: 'ANTI_CHEAT',
    payload: {
      eventType,
      durationMs,
      payload,
      evidenceJson,
      occurredAt
    },
    occurredAt
  })
  await refreshQueueSize()
}

const flushSyncQueue = async ({ force = false } = {}) => {
  if (!syncState.userId || networkState.flushing) {
    return false
  }
  if (!navigator.onLine) {
    updateNetworkStatus('OFFLINE')
    return false
  }
  const items = await listSyncItems(syncState.userId, examId)
  networkState.queueSize = items.length
  const now = Date.now()
  const dueItems = items.filter((item) => force || !item.nextAttemptAt || item.nextAttemptAt <= now)
  if (!dueItems.length) {
    return true
  }
  networkState.flushing = true
  updateNetworkStatus('RECONNECTING')
  try {
    for (const item of dueItems) {
      try {
        if (item.type === 'SNAPSHOT') {
          const validationError = answerPayloadValidationError(item.payload)
          if (validationError) {
            const error = new Error(validationError)
            error.isBusinessError = true
            throw error
          }
          const ack = await runLeaseRequest(() => snapshotApi(
            examId,
            withClientLeasePayload(item.payload),
            { silent: true, timeout: 10000 }
          ))
          applyLeaseResponse(ack)
          syncState.lastSyncedAt = Math.max(Number(syncState.lastSyncedAt || 0), Number(item.payload?.snapshotVersion || item.occurredAt || 0))
          syncState.lastServerAckAt = ack?.serverReceivedAt || syncState.lastServerAckAt
          syncState.snapshotVersion = Math.max(syncState.snapshotVersion || 0, Number(ack?.snapshotVersion || item.payload?.snapshotVersion || 0))
        } else if (item.type === 'ANTI_CHEAT') {
          const originalPayload = item.payload?.payload
          let parsedPayload = {}
          try {
            parsedPayload = originalPayload ? JSON.parse(originalPayload) : {}
          } catch {
            parsedPayload = { rawPayload: originalPayload }
          }
          await antiCheatApi(examId, {
            eventType: item.payload?.eventType,
            durationMs: item.payload?.durationMs || 0,
            payload: JSON.stringify({
              ...parsedPayload,
              occurredAt: item.payload?.occurredAt || item.occurredAt,
              replayed: true,
              replayedAt: Date.now(),
              offlineDurationMs: Math.max(0, Date.now() - Number(item.payload?.occurredAt || item.occurredAt || Date.now()))
            }),
            evidenceJson: item.payload?.evidenceJson || null
          }, { silent: true, timeout: 10000 })
        }
        await deleteSyncItem(item.id)
      } catch (error) {
        if (await handleExamClientLeaseError(error)) {
          break
        }
        if (!isRecoverableNetworkError(error)) {
          item.lastError = error?.message || '同步失败'
          await deleteSyncItem(item.id)
          syncState.syncErrorAt = Date.now()
          syncState.lastSyncErrorMessage = item.lastError
          break
        }
        item.attemptCount = Number(item.attemptCount || 0) + 1
        item.nextAttemptAt = Date.now() + retryDelayForAttempt(item.attemptCount)
        item.lastError = error?.message || '同步失败'
        await updateSyncItem(item)
        markNetworkFailure(error)
        break
      }
    }
  } finally {
    networkState.flushing = false
    await refreshQueueSize()
    if (navigator.onLine && networkState.status === 'RECONNECTING') {
      updateNetworkStatus('ONLINE')
    }
  }
  return networkState.queueSize === 0
}

const scheduleQueueFlush = (delayMs = 0) => {
  if (queueFlushTimer) {
    clearTimeout(queueFlushTimer)
  }
  queueFlushTimer = setTimeout(() => {
    queueFlushTimer = null
    void flushSyncQueue()
  }, delayMs)
}

const syncDirtyDraft = async ({ force = false, notify = false } = {}) => {
  if (!syncState.userId || !syncState.initialized || syncState.syncing) {
    return false
  }
  if (!navigator.onLine) {
    updateNetworkStatus('OFFLINE')
    return false
  }
  if (!force && !syncState.dirty) {
    await flushSyncQueue()
    return false
  }

  syncState.syncErrorAt = null
  syncState.lastSyncErrorMessage = ''
  const syncVersion = Math.max(syncState.updatedAt || 0, (syncState.snapshotVersion || 0) + 1, Date.now())
  const payload = withoutClientLeasePayload(buildSnapshotPayload(syncVersion))
  const validationError = answerPayloadValidationError(payload)
  if (validationError) {
    syncState.syncErrorAt = Date.now()
    syncState.lastSyncErrorMessage = validationError
    if (notify) {
      ElMessage.error(validationError)
    }
    return false
  }
  syncState.syncing = true
  try {
    const startedAt = Date.now()
    const ack = await runLeaseRequest(() => snapshotApi(
      examId,
      withClientLeasePayload(payload),
      { silent: true, timeout: 10000 }
    ))
    applyLeaseResponse(ack)
    const latency = Date.now() - startedAt
    updateNetworkStatus(latency > 2500 ? 'DEGRADED' : 'ONLINE', latency)

    const latestUpdatedAt = syncState.updatedAt || syncVersion
    await persistDraftState({
      updatedAt: latestUpdatedAt,
      lastSyncedAt: syncVersion,
      lastServerAckAt: ack?.serverReceivedAt || null,
      snapshotVersion: Number(ack?.snapshotVersion || syncVersion),
      dirty: latestUpdatedAt > syncVersion
    })
    await flushSyncQueue({ force: true })

    if (notify) {
      ElMessage.success('答题进度已同步到服务器')
    }
    return true
  } catch (error) {
    syncState.syncErrorAt = Date.now()
    syncState.lastSyncErrorMessage = error?.message || '服务器同步失败'
    if (await handleExamClientLeaseError(error)) {
      return false
    }
    if (!isRecoverableNetworkError(error)) {
      return false
    }
    await queueSnapshotSync(payload)
    markNetworkFailure(error)
    scheduleQueueFlush(retryDelayForAttempt(0))
    return false
  } finally {
    syncState.syncing = false
  }
}

const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds))

const pollSubmissionStatus = async () => {
  let lastError = null
  const stopAt = Date.now() + 45_000
  let attempt = 0
  while (Date.now() < stopAt) {
    attempt += 1
    try {
      const remaining = Math.max(500, stopAt - Date.now())
      const status = await submissionStatusApi(examId, {
        silent: true,
        timeout: Math.min(5000, remaining)
      })
      if (status?.timeoutTaskStatus === 'FAILED') {
        throw new Error('自动交卷任务暂时处理失败，系统已记录并会由管理员处理。')
      }
      if (isSubmissionAccepted(status)) {
        return status
      }
    } catch (error) {
      lastError = error
      if (!isRecoverableNetworkError(error)) {
        throw error
      }
    }
    submissionProgressText.value = `正在确认服务器交卷状态（第 ${attempt} 次）…`
    const jitteredDelay = submissionPollDelay(attempt)
    await wait(Math.min(jitteredDelay, Math.max(0, stopAt - Date.now())))
  }
  throw lastError || new Error('暂时无法确认交卷状态')
}

const completeSubmissionUi = async (result = {}) => {
  allowLeaveExam.value = true
  stopExamRuntimeTimers()
  if (draftSaveTimer) {
    clearTimeout(draftSaveTimer)
    draftSaveTimer = null
  }
  if (syncState.userId) {
    await clearDraft(syncState.userId, examId)
    await clearSyncItems(syncState.userId, examId)
    clearExamClientLease(syncState.userId, examId)
  }
  syncState.dirty = false
  cameraProctoring.stop()
  await exitFullscreenForExamEnd()
  const status = String(result?.status || result?.sessionStatus || '').toUpperCase()
  ElMessage.success(status === 'SUBMITTING' || status === 'AUTO_SUBMITTING'
    ? '交卷请求已由服务器接管，完成后会自动进入判分。'
    : '试卷已提交，系统正在处理成绩。')
  await router.replace('/student/results')
}

const waitForOfflineDeadlineConfirmation = async () => {
  endingExam.value = true
  submissionProgressText.value = '本机倒计时已结束，等待网络恢复并由服务器确认交卷状态。'
  if (offlineStartedAt.value == null) {
    offlineStartedAt.value = Date.now()
  }
  pendingSubmitIntent.value = { attemptedAt: Date.now() }
  await persistDraftState({
    updatedAt: Date.now(),
    lastSyncedAt: syncState.lastSyncedAt,
    dirty: true,
    nextPendingSubmitIntent: pendingSubmitIntent.value
  })
  ElMessage.warning('当前网络不可用，本地答案已保留；恢复网络后将由服务器确认交卷。')
}

const submit = async (needConfirm = true) => {
  if (submissionPromise) {
    return submissionPromise
  }
  const deadlineReached = isExamExpiredLocally()
  const submissionPlan = createSubmissionPlan({
    online: navigator.onLine,
    deadlineReached
  })
  if (submissionPlan.mode === 'OFFLINE_DEADLINE_WAIT') {
    return waitForOfflineDeadlineConfirmation()
  }
  if (submissionPlan.mode === 'OFFLINE_WAIT') {
    pendingSubmitIntent.value = { attemptedAt: Date.now() }
    await persistDraftState({
      updatedAt: Date.now(),
      lastSyncedAt: syncState.lastSyncedAt,
      dirty: true,
      nextPendingSubmitIntent: pendingSubmitIntent.value
    })
    ElMessage.warning('当前网络已断开，答案已保存在本地草稿。请恢复网络后再交卷。')
    return
  }
  if (submissionPlan.validate) {
    const validationError = answerPayloadValidationError(buildSubmitPayload())
    if (validationError) {
      ElMessage.error(validationError)
      return
    }
  }
  if (needConfirm && submissionPlan.validate) {
    await ElMessageBox.confirm('确认提交试卷？提交后不可修改。', '提示')
  }
  await persistDraftState({
    updatedAt: Date.now(),
    lastSyncedAt: syncState.lastSyncedAt,
    dirty: true,
    nextPendingSubmitIntent: null
  })
  if (submissionPlan.sync) {
    const synced = await syncDirtyDraft({ force: true })
    if (!synced && syncState.dirty) {
      ElMessage.warning(syncState.lastSyncErrorMessage || '当前网络不稳定，答案已保存在本机。请等待同步成功后再交卷。')
      return
    }
  }

  endingExam.value = true
  submissionProgressText.value = deadlineReached
    ? '考试已截止，正在通知服务器接管自动交卷。'
    : '正在提交最终答案，请勿重复操作。'
  submissionPromise = (async () => {
    try {
      let result
      try {
        const submitPayload = submissionPlan.stripAnswers
          ? withClientLeasePayload({ answers: [] })
          : withClientLeasePayload(buildSubmitPayload())
        const request = () => submitExamApi(
          examId,
          submitPayload,
          { silent: true, timeout: 10000 }
        )
        try {
          result = submissionPlan.mode === 'ACTIVE_SUBMIT'
            ? await runLeaseRequest(request)
            : await request()
        } catch (error) {
          if (!shouldRetryDeadlineWithAnswers(submissionPlan, error)) {
            throw error
          }
          submissionProgressText.value = '服务端尚未截止，正在提交当前完整答案。'
          result = await runLeaseRequest(() => submitExamApi(
            examId,
            withClientLeasePayload(buildSubmitPayload()),
            { silent: true, timeout: 10000 }
          ))
        }
      } catch (error) {
        if (await handleExamClientLeaseError(error)) {
          return
        }
        if (!isRecoverableNetworkError(error)) {
          throw error
        }
        submissionProgressText.value = '交卷请求结果暂时不确定，正在查询服务器权威状态。'
        result = await pollSubmissionStatus()
      }
      if (!isSubmissionAccepted(result)) {
        submissionProgressText.value = '服务器正在接管交卷，正在确认最终状态。'
        result = await pollSubmissionStatus()
      }
      await completeSubmissionUi(result)
    } catch (error) {
      if (deadlineReached) {
        endingExam.value = false
        pendingSubmitIntent.value = { attemptedAt: Date.now() }
        await persistDraftState({
          updatedAt: Date.now(),
          lastSyncedAt: syncState.lastSyncedAt,
          dirty: true,
          nextPendingSubmitIntent: pendingSubmitIntent.value
        })
        submissionProgressText.value = '暂时无法确认服务端交卷状态，请保持页面并重试。'
        const reason = error?.message ? `${error.message}；` : ''
        ElMessage.warning(`${reason}本地答案已保留，请保持页面并重试。`)
        return
      }
      endingExam.value = false
      submissionProgressText.value = '正在将交卷请求交给服务器，请勿重复操作。'
      ElMessage.error(error?.message || '交卷失败，请重试。')
    } finally {
      submissionPromise = null
    }
  })()
  return submissionPromise
}

const exitFullscreenForExamEnd = async () => {
  if (!document.fullscreenElement || typeof document.exitFullscreen !== 'function') {
    return
  }
  endingExam.value = true
  try {
    await document.exitFullscreen()
  } catch {
    // Browser may reject exitFullscreen when the document is no longer active.
  }
}

const tryEnterFullscreen = async () => {
  if (state.proctoringPolicy?.requireFullscreen === false) {
    return
  }
  if (!document.fullscreenEnabled || document.fullscreenElement) {
    return
  }
  try {
    await document.documentElement.requestFullscreen()
    showBanner('success', '已进入考试全屏模式', '建议保持全屏完成答题，退出全屏会被记录为监考异常。')
  } catch {
    showBanner('info', '浏览器未进入全屏', '已继续考试，建议你手动进入全屏以减少误触和切屏风险。')
  }
}

const onBlur = () => {
  if (blurStartedAt.value == null) {
    blurStartedAt.value = Date.now()
  }
}

const onFocus = () => {
  if (blurStartedAt.value == null) {
    return
  }
  const durationMs = Date.now() - blurStartedAt.value
  blurStartedAt.value = null
  reportAntiCheatEvent('WINDOW_BLUR', durationMs, { recovered: true })
}

const onVisibility = () => {
  if (document.hidden) {
    if (hiddenStartedAt.value == null) {
      hiddenStartedAt.value = Date.now()
    }
    return
  }
  if (hiddenStartedAt.value == null) {
    return
  }
  const durationMs = Date.now() - hiddenStartedAt.value
  hiddenStartedAt.value = null
  reportAntiCheatEvent('TAB_HIDDEN', durationMs, { recovered: true })
}

const onFullscreenChange = () => {
  if (endingExam.value) {
    return
  }
  if (state.proctoringPolicy?.trackFullscreenExit === false) {
    return
  }
  if (!document.fullscreenElement) {
    reportAntiCheatEvent('FULLSCREEN_EXIT', 0, { mode: 'browser-fullscreen' })
  }
}

const onCopy = (event) => {
  reportAntiCheatEvent('COPY_ATTEMPT', 0, { targetTag: event.target?.tagName || null })
}

const onPaste = (event) => {
  reportAntiCheatEvent('PASTE_ATTEMPT', 0, { targetTag: event.target?.tagName || null })
}

const onCut = (event) => {
  reportAntiCheatEvent('CUT_ATTEMPT', 0, { targetTag: event.target?.tagName || null })
}

const onContextMenu = (event) => {
  reportAntiCheatEvent('CONTEXT_MENU', 0, { targetTag: event.target?.tagName || null })
}

const onOffline = () => {
  updateNetworkStatus('OFFLINE')
  if (offlineStartedAt.value == null) {
    offlineStartedAt.value = Date.now()
  }
  showBanner('warning', '网络连接已断开', '请尽快恢复网络。页面会尽量保留当前内容，恢复后会记录本次离线时长。', true)
}

const onOnline = async () => {
  updateNetworkStatus('RECONNECTING')
  await runHealthCheck()
  const shouldNotify = syncState.dirty
  if (offlineStartedAt.value == null) {
    resetBanner()
    await flushSyncQueue({ force: true })
    if (pendingSubmitIntent.value && isExamExpiredLocally()) {
      showBanner('warning', '网络已恢复，正在确认交卷', '服务器将判断实际截止状态并接管交卷。', true)
      void submit(false)
      return
    }
    void syncDirtyDraft({ force: shouldNotify, notify: shouldNotify })
    return
  }
  const durationMs = Date.now() - offlineStartedAt.value
  offlineStartedAt.value = null
  await reportAntiCheatEvent('NETWORK_OFFLINE', durationMs, { recovered: true })
  await flushSyncQueue({ force: true })
  if (isExamExpiredLocally()) {
    showBanner('warning', '网络已恢复，正在确认交卷', '服务器将判断实际截止状态并接管交卷。', true)
    void submit(false)
    return
  }
  offlineRecoveredMode.value = false
  void syncDirtyDraft({ force: shouldNotify, notify: shouldNotify })
  if (pendingSubmitIntent.value) {
    showBanner('warning', '网络已恢复', '之前离线时曾尝试交卷，请确认同步完成后重新点击交卷。', true)
  }
}

const onActivity = () => {
  lastActivityAt.value = Date.now()
  inactivityEventOpen = false
}

const checkLongInactivity = () => {
  if (!syncState.initialized || !shouldRecordClientEvent('LONG_INACTIVITY')) {
    return
  }
  const thresholdMs = policyNumber('inactivityThresholdSeconds', DEFAULT_PROCTORING_POLICY.inactivityThresholdSeconds) * 1000
  const durationMs = Date.now() - lastActivityAt.value
  if (durationMs < thresholdMs || inactivityEventOpen) {
    return
  }
  inactivityEventOpen = true
  reportAntiCheatEvent('LONG_INACTIVITY', durationMs, {
    thresholdSeconds: Math.round(thresholdMs / 1000),
    lastActivityAt: lastActivityAt.value
  })
}

const onBeforeUnload = (event) => {
  flushDraftSave()
  if (!allowLeaveExam.value) {
    void reportAntiCheatEvent('NAVIGATION_LEAVE_ATTEMPT', 0, {
      source: 'beforeunload',
      fromPath: route.fullPath,
      mode: 'browser-unload'
    }, [])
    event.preventDefault()
    event.returnValue = ''
    return ''
  }
}

const onPopState = (event) => {
  if (allowLeaveExam.value) {
    return
  }
  const attemptedPath = `${window.location.pathname}${window.location.search}${window.location.hash}`
  setExamHistoryLock({ replace: attemptedPath === route.fullPath })
  void handleBlockedExamLeave({
    source: 'browser-popstate',
    fromPath: route.fullPath,
    toPath: attemptedPath,
    historyState: event?.state || null
  })
}

const bootstrapExam = async () => {
  syncState.userId = auth.userId == null ? null : String(auth.userId)
  syncState.initialized = false
  if (navigator.onLine) {
    try {
      const existingStatus = await submissionStatusApi(examId, { silent: true, timeout: 5000 })
      if (isSubmissionAccepted(existingStatus)) {
        endingExam.value = true
        submissionProgressText.value = '服务器已接管本场考试，正在前往结果中心。'
        await completeSubmissionUi(existingStatus)
        return
      }
    } catch {
      // 尚未开考时不存在 Runtime 会话；网络异常时继续使用原有离线恢复流程。
    }
  }
  const clientLease = ensureExamClientLease()
  windowGuard = await createExamWindowGuard({
    userId: syncState.userId || 'anonymous',
    examId,
    onDuplicate: () => {
      void handleExamClientLeaseError({
        code: 'EXAM_CLIENT_CONFLICT',
        message: '本场考试已在另一个本机窗口打开，请回到原窗口继续作答。'
      })
    }
  })
  if (!windowGuard.acquired) {
    await handleExamClientLeaseError({
      code: 'EXAM_CLIENT_CONFLICT',
      message: '本场考试已在另一个窗口答题，请回到原窗口继续作答。'
    })
    return
  }
  const localDraft = syncState.userId ? await loadDraft(syncState.userId, examId) : null
  let data = null
  try {
    data = await startExamApi(examId, buildClientLeasePayload())
    applyLeaseResponse(data)
  } catch (error) {
    if (await handleExamClientLeaseError(error)) {
      return
    }
    if (!isRecoverableNetworkError(error)) {
      throw error
    }
    if (!clientLease?.leaseToken) {
      throw error
    }
    const runtime = localDraft?.examRuntime
    const localExamEndAt = parseDateTime(runtime?.examEndAt || runtime?.deadlineTime)
    if (!runtime?.questions?.length || !localExamEndAt || Date.now() >= localExamEndAt.getTime()) {
      throw error
    }
    data = {
      examId,
      examName: runtime.examName,
      questions: runtime.questions,
      proctoringPolicy: runtime.proctoringPolicy,
      deadlineTime: localExamEndAt,
      resumed: true,
      offlineRecovered: true
    }
    offlineRecoveredMode.value = true
    updateNetworkStatus('OFFLINE')
  }
  state.examId = String(data?.examId || examId)
  state.examName = data?.examName || ''
  state.questions = Array.isArray(data?.questions) ? data.questions : []
  state.proctoringPolicy = normalizePolicy(data?.proctoringPolicy)
  examEndAt.value = resolveExamEndTime(data)
  resetBanner()

  const serverAnswerMap = buildAnswerMapFromQuestions(state.questions)
  const serverDraftTime = parseDateTime(data?.draftUpdatedAt)?.getTime() || 0
  const hasServerDraft = serverDraftTime > 0 || hasDraftContent(serverAnswerMap)

  let selectedAnswers = serverAnswerMap
  let selectedMode = data?.offlineRecovered ? 'offline-local' : 'new'
  let draftUpdatedAt = serverDraftTime || Date.now()
  let lastSyncedAt = serverDraftTime || Date.now()
  let dirty = false
  let selectedMarkedQuestionIds = []

  pendingSubmitIntent.value = localDraft?.pendingSubmitIntent || null
  syncState.snapshotVersion = Number(localDraft?.snapshotVersion || 0)
  syncState.lastServerAckAt = localDraft?.lastServerAckAt || null

  if (data?.offlineRecovered && localDraft) {
    selectedAnswers = localDraft.answers || {}
    selectedMarkedQuestionIds = localDraft.markedQuestionIds || []
    draftUpdatedAt = Number(localDraft.updatedAt) || Date.now()
    lastSyncedAt = Number(localDraft.lastSyncedAt || 0) || null
    dirty = true
  } else if (localDraft && Number(localDraft.updatedAt || 0) > serverDraftTime) {
    selectedAnswers = localDraft.answers || {}
    selectedMarkedQuestionIds = localDraft.markedQuestionIds || []
    selectedMode = 'local'
    draftUpdatedAt = Number(localDraft.updatedAt) || Date.now()
    lastSyncedAt = Number(localDraft.lastSyncedAt || 0) || null
    dirty = Boolean(localDraft.dirty) || !lastSyncedAt || draftUpdatedAt > lastSyncedAt
  } else if (hasServerDraft) {
    selectedMode = 'server'
    draftUpdatedAt = serverDraftTime || Date.now()
    lastSyncedAt = serverDraftTime || Date.now()
  } else if (data?.resumed) {
    selectedMode = 'resumed'
    draftUpdatedAt = Date.now()
    lastSyncedAt = Date.now()
  }

  applyAnswerMap(selectedAnswers)
  applyMarkedQuestionIds(selectedMarkedQuestionIds)
  await persistDraftState({
    updatedAt: draftUpdatedAt,
    lastSyncedAt,
    dirty
  })
  syncState.initialized = true

  syncCountdown()
  timer = setInterval(() => {
    syncCountdown()
    if (Number.isFinite(secondsLeft.value) && secondsLeft.value <= 0) {
      clearInterval(timer)
      timer = null
      void submit(false)
    }
  }, 1000)

  snapshotTimer = setInterval(() => {
    void syncDirtyDraft()
    void flushSyncQueue()
  }, SNAPSHOT_SYNC_INTERVAL_MS)
  scheduleNextHeartbeat({ initial: true })
  healthCheckTimer = setInterval(() => {
    void runHealthCheck()
  }, 30000)
  lastActivityAt.value = Date.now()
  inactivityTimer = setInterval(checkLongInactivity, 5000)
  window.addEventListener('blur', onBlur)
  window.addEventListener('focus', onFocus)
  window.addEventListener('offline', onOffline)
  window.addEventListener('online', onOnline)
  window.addEventListener('popstate', onPopState)
  window.addEventListener('beforeunload', onBeforeUnload)
  window.addEventListener('mousemove', onActivity, { passive: true })
  window.addEventListener('mousedown', onActivity, { passive: true })
  window.addEventListener('keydown', onActivity)
  window.addEventListener('scroll', onActivity, { passive: true })
  window.addEventListener('touchstart', onActivity, { passive: true })
  document.addEventListener('visibilitychange', onVisibility)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  document.addEventListener('copy', onCopy)
  document.addEventListener('paste', onPaste)
  document.addEventListener('cut', onCut)
  document.addEventListener('contextmenu', onContextMenu)
  setExamHistoryLock({ replace: true })

  showRecoveryBanner(selectedMode)
  await refreshQueueSize()
  if ((selectedMode === 'local' || selectedMode === 'offline-local') && navigator.onLine) {
    void syncDirtyDraft({ force: true })
  }
  void flushSyncQueue({ force: true })
  await tryEnterFullscreen()
  void cameraProctoring.start(state.proctoringPolicy)
}

watch(answers, () => {
  if (endingExam.value || !syncState.initialized || !state.questions.length) {
    return
  }
  scheduleDraftSave({ dirty: true, updateAnswerTimestamp: true })
}, { deep: true })

watch(visibleQuestions, (questions) => {
  if (!questions.length) {
    currentQuestionId.value = ''
    return
  }
  const hasCurrentQuestion = questions.some((question) =>
    toStoredQuestionId(question.questionId) === currentQuestionId.value
  )
  if (!hasCurrentQuestion) {
    currentQuestionId.value = toStoredQuestionId(questions[0].questionId)
  }
}, { immediate: true })

onBeforeRouteLeave(async (to, from) => {
  if (allowLeaveExam.value) {
    return true
  }
  await handleBlockedExamLeave({
    source: 'router-leave',
    fromPath: from.fullPath,
    toPath: to.fullPath,
    historyState: window.history.state || null
  })
  setExamHistoryLock({ replace: true })
  return false
})

onMounted(async () => {
  try {
    await bootstrapExam()
  } catch (error) {
    if (await handleExamClientLeaseError(error)) {
      return
    }
    allowLeaveExam.value = true
    ElMessage.error(error?.message || '进入考试失败，请返回考试列表后重试。')
    router.replace('/student/exams')
  }
})

onBeforeUnmount(() => {
  endingExam.value = true
  void exitFullscreenForExamEnd()
  if (timer) clearInterval(timer)
  if (snapshotTimer) clearInterval(snapshotTimer)
  if (heartbeatTimer) clearTimeout(heartbeatTimer)
  if (inactivityTimer) clearInterval(inactivityTimer)
  if (healthCheckTimer) clearInterval(healthCheckTimer)
  if (queueFlushTimer) clearTimeout(queueFlushTimer)
  if (bannerResetTimer) clearTimeout(bannerResetTimer)
  cameraProctoring.stop()
  if (windowGuard) {
    windowGuard.close()
    windowGuard = null
  }
  flushDraftSave()
  window.removeEventListener('blur', onBlur)
  window.removeEventListener('focus', onFocus)
  window.removeEventListener('offline', onOffline)
  window.removeEventListener('online', onOnline)
  window.removeEventListener('popstate', onPopState)
  window.removeEventListener('beforeunload', onBeforeUnload)
  window.removeEventListener('mousemove', onActivity)
  window.removeEventListener('mousedown', onActivity)
  window.removeEventListener('keydown', onActivity)
  window.removeEventListener('scroll', onActivity)
  window.removeEventListener('touchstart', onActivity)
  document.removeEventListener('visibilitychange', onVisibility)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  document.removeEventListener('copy', onCopy)
  document.removeEventListener('paste', onPaste)
  document.removeEventListener('cut', onCut)
  document.removeEventListener('contextmenu', onContextMenu)
})
</script>

<style scoped>
.exam-shell {
  --student-bg: #fff9f2;
  --student-panel: #fffdf9;
  --student-panel-strong: #fffefa;
  --student-line: #eadfce;
  --student-text: #3d3028;
  --student-muted: #786b60;
  --student-accent: #c96a3d;
  --student-accent-dark: #a94f2b;
  --student-success: #4f7a5a;
  --student-warning: #c98a32;
  --student-danger: #b75a4e;
  --student-soft: #f7ecdf;
  --brand: var(--student-accent);
  --brand-light: #f9e7dc;
  --brand-hover: var(--student-accent-dark);
  --bg-main: var(--student-bg);
  --bg-card: var(--student-panel-strong);
  --text-main: var(--student-text);
  --text-muted: var(--student-muted);
  --shadow-soft: 0 14px 32px rgba(83, 57, 40, 0.08);
  --shadow-hover: 0 18px 36px rgba(83, 57, 40, 0.12);
  --el-color-primary: var(--student-accent);
  --el-color-primary-light-9: #fdf2ec;
  --el-color-primary-dark-2: var(--student-accent-dark);
  --el-color-success: var(--student-success);
  --el-color-success-light-9: #f2f7f0;
  --el-color-warning: var(--student-warning);
  --el-color-warning-light-9: #fff8e8;
  --el-color-danger: var(--student-danger);
  --el-color-danger-light-9: #fdf1ef;
  --el-color-info: #8c7e70;
  --el-color-info-light-9: #f7f3ef;
  height: 100%;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--student-bg, #fff9f2);
}

.submission-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(61, 48, 40, 0.38);
  backdrop-filter: blur(4px);
}

.submission-overlay__card {
  width: min(440px, 100%);
  padding: 34px 30px;
  border: 1px solid rgba(201, 106, 61, 0.22);
  border-radius: 22px;
  background: #fffdf9;
  color: var(--student-text, #3d3028);
  text-align: center;
  box-shadow: 0 24px 70px rgba(61, 48, 40, 0.2);
}

.submission-overlay__card h2 {
  margin: 18px 0 8px;
}

.submission-overlay__card p {
  margin: 0;
  color: var(--student-muted, #786b60);
  line-height: 1.7;
}

.submission-overlay__spinner {
  color: var(--brand, #c96a3d);
  animation: submission-spin 1s linear infinite;
}

@keyframes submission-spin {
  to { transform: rotate(360deg); }
}

.exam-header {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: flex-end;
  padding: 22px 32px 18px;
  color: white;
  background: var(--student-accent, #c96a3d);
  box-shadow: 0 8px 22px rgba(169, 79, 43, 0.18);
  border-bottom: 1px solid rgba(255, 254, 250, 0.24);
}

.exam-header__eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  letter-spacing: 0.24em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.8);
}

.exam-header h1 {
  margin: 0;
  font-size: clamp(24px, 3vw, 36px);
  line-height: 1.1;
  font-weight: 800;
}

.exam-header__hint {
  max-width: 760px;
  margin: 10px 0 0;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.8);
}

.exam-header__status {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.status-pill {
  min-width: 140px;
  padding: 12px 20px;
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 14px;
  background: rgba(255, 254, 250, 0.18);
  display: flex;
  flex-direction: column;
  align-items: center;
}

.status-pill span {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.9);
}

.status-pill strong {
  margin-top: 4px;
  font-size: 22px;
  font-weight: 800;
  letter-spacing: 1px;
}

.status-pill small {
  margin-top: 4px;
  max-width: 180px;
  color: rgba(255, 255, 255, 0.78);
  font-size: 11px;
  line-height: 1.35;
  text-align: center;
}

.status-pill--sync {
  min-width: 180px;
}

.status-pill--sync strong {
  font-size: 16px;
  letter-spacing: 0;
  white-space: nowrap;
}

.status-pill--success {
  background: rgba(79, 122, 90, 0.32);
}

.status-pill--warning,
.status-pill--info {
  background: rgba(255, 248, 232, 0.2);
}

.status-pill--danger {
  background: rgba(183, 90, 78, 0.38);
}

.exam-banner {
  padding: 20px 32px 0;
}

.exam-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 112px minmax(0, 1fr);
  gap: 24px;
  padding: 20px 32px 32px;
}

.exam-outline {
  position: sticky;
  top: 130px;
  align-self: start;
}

.outline-card {
  padding: 12px 10px;
  border: 1px solid var(--student-line, #eadfce);
  border-radius: var(--radius-md);
  background: var(--student-panel-strong, #fffefa);
  box-shadow: var(--shadow-soft);
}

.outline-toolbar {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  font-size: 13px;
}

.outline-grid {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 7px;
  max-height: min(54vh, 520px);
  overflow-y: auto;
  padding: 2px 4px 2px 0;
}

.outline-item {
  position: relative;
  width: 100%;
  min-height: 44px;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--student-muted, #786b60);
  font-weight: 700;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all var(--transition-fast);
}

.outline-item:hover {
  color: var(--brand);
  background: #fff0e4;
}

.outline-item:focus-visible {
  outline: 3px solid rgba(201, 106, 61, 0.3);
  outline-offset: 2px;
}

.outline-item__marker {
  position: absolute;
  top: 0;
  left: calc(50% - 16px);
  z-index: 1;
  width: 9px;
  height: 13px;
  border-radius: 2px 2px 1px 1px;
  background: var(--student-warning, #c98a32);
  opacity: 0;
}

.outline-item__marker::after {
  content: '';
  position: absolute;
  left: 0;
  bottom: -4px;
  border-top: 4px solid var(--student-warning, #c98a32);
  border-right: 4px solid transparent;
}

.outline-item__number {
  width: 32px;
  height: 32px;
  border: 1px solid #ead6c5;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: #fffdfa;
  font-variant-numeric: tabular-nums;
  color: inherit;
  transition: all var(--transition-fast);
}

.outline-item--done {
  background: transparent;
  color: white;
}

.outline-item--done .outline-item__number {
  border-color: transparent;
  background: var(--brand);
  box-shadow: 0 4px 12px rgba(201, 106, 61, 0.2);
}

.outline-item--marked {
  background: transparent;
}

.outline-item--marked .outline-item__marker {
  opacity: 1;
}

.outline-item--done.outline-item--marked {
  box-shadow: none;
}

.outline-item--marked .outline-item__number {
  border-color: var(--student-warning, #c98a32);
}

.outline-item--active .outline-item__number {
  outline: 3px solid rgba(201, 106, 61, 0.22);
}

.question-list {
  min-width: 0;
  display: grid;
  gap: 24px;
  align-content: start;
  padding-right: 4px;
}

.q-item {
  padding: 32px;
  border: 1px solid var(--student-line, #eadfce);
  border-radius: var(--radius-lg);
  background: var(--student-panel-strong, #fffefa);
  box-shadow: var(--shadow-soft);
  transition: transform var(--transition-smooth), box-shadow var(--transition-smooth);
}

.q-item:hover {
  box-shadow: var(--shadow-hover);
  transform: translateY(-2px);
}

.q-item__head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #f0dfd1;
}

.q-item__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.q-item__index {
  font-size: 22px;
  font-weight: 800;
  color: var(--brand);
}

.q-item__meta {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-muted);
  background: #f9e7dc;
  padding: 4px 10px;
  border-radius: 100px;
  display: inline-block;
}

.q-item__status {
  padding: 6px 14px;
  border-radius: 100px;
  background: #fff7ed;
  color: #c2410c;
  font-size: 12px;
  font-weight: 700;
  border: 1px solid rgba(194, 65, 12, 0.2);
}

.q-item__status--done {
  background: #eff7ed;
  color: var(--student-success, #4f7a5a);
  border-color: rgba(79, 122, 90, 0.24);
}

.q-content {
  font-size: 16px;
  line-height: 1.8;
  color: var(--text-main);
}

.q-image-list {
  margin-top: 20px;
  display: grid;
  gap: 16px;
}

.q-image-frame {
  margin: 0;
  padding: 12px;
  border: 1px solid #f0dfd1;
  border-radius: var(--radius-md);
  background: #fffdfa;
}

.q-image {
  width: 100%;
  height: clamp(220px, 34vw, 420px);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.q-answer {
  margin-top: 28px;
}

.q-navigation {
  margin-top: 34px;
  padding-top: 22px;
  border-top: 1px solid #f0dfd1;
  display: grid;
  grid-template-columns: minmax(120px, auto) 1fr minmax(120px, auto);
  align-items: center;
  gap: 16px;
}

.q-navigation__progress {
  justify-self: center;
  color: var(--text-muted);
  font-size: 14px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.option-list {
  width: 100%;
  display: grid;
  gap: 14px;
}

.option-item {
  margin: 0 !important;
  padding: 16px 20px;
  border: 2px solid transparent;
  border-radius: var(--radius-md);
  background: #fffdfa;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  display: flex;
  align-items: center;
  box-shadow: 0 2px 8px rgba(83, 57, 40, 0.03);
}

.option-item:hover {
  transform: translateX(4px);
  background: #fff6ee;
  border-color: rgba(201, 106, 61, 0.36);
  box-shadow: 0 4px 12px rgba(201, 106, 61, 0.09);
}

:deep(.el-radio.is-checked),
:deep(.el-checkbox.is-checked) {
  background: var(--brand-light) !important;
  border-color: var(--brand) !important;
  transform: translateX(4px);
  box-shadow: 0 4px 12px rgba(201, 106, 61, 0.15);
}

.option-marker {
  display: inline-flex;
  width: 32px;
  height: 32px;
  margin-right: 16px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #f9e7dc;
  color: var(--brand);
  font-weight: 800;
  transition: all 0.2s;
}

:deep(.is-checked) .option-marker {
  background: var(--brand);
  color: white;
}

.exam-footer {
  position: sticky;
  bottom: 0;
  z-index: 15;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 20px 32px;
  border-top: 1px solid var(--student-line, #eadfce);
  background: rgba(255, 254, 250, 0.96);
  box-shadow: 0 -10px 30px rgba(83, 57, 40, 0.06);
}

.exam-footer__summary {
  display: flex;
  gap: 20px;
  color: var(--text-muted);
  font-size: 15px;
}

@media (max-width: 1100px) {
  .exam-header {
    flex-direction: column;
    align-items: flex-start;
  }
  .exam-body {
    grid-template-columns: 1fr;
  }
  .exam-outline {
    position: static;
  }
}

@media (max-width: 768px) {
  .exam-header,
  .exam-banner,
  .exam-body,
  .exam-footer {
    padding-left: 16px;
    padding-right: 16px;
  }
  .status-pill {
    min-width: 0;
    flex: 1;
  }
  .q-item {
    padding: 20px;
  }
  .q-item__head,
  .q-item__actions {
    align-items: flex-start;
    flex-direction: column;
  }
  .q-navigation {
    grid-template-columns: 1fr;
  }
  .q-navigation__progress {
    order: -1;
  }
}
</style>
