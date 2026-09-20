export const entryRetryCodes = new Set([
  'EXAM_ENTRY_NOT_READY',
  'EXAM_ENTRY_BUSY',
  'EXAM_PREPARING',
  'EXAM_PAPER_UNAVAILABLE',
  'EXAM_ENTRY_UNAVAILABLE',
  'EXAM_CLIENT_LEASE_UNAVAILABLE'
])

export const entryErrorCode = (error) =>
  error?.code || error?.responseData?.code || error?.response?.data?.code || null

export const isRetryableEntryError = (error) => {
  const code = entryErrorCode(error)
  if (entryRetryCodes.has(code)) return true
  const status = Number(error?.response?.status || 0)
  return status === 425 || status === 429 || (status === 503 && code !== 'EXAM_ENTRY_V2_DISABLED')
}

export const entryRetryAfterMs = (error) => Number(
  error?.retryAfterMs
  || error?.responseData?.data?.retryAfterMs
  || error?.response?.data?.data?.retryAfterMs
  || 0
)

export const fullJitterDelay = (attempt, serverDelayMs = 0, random = Math.random) => {
  const cap = Math.min(2000, 200 * (2 ** Math.min(Math.max(0, attempt), 5)))
  const jitter = Math.floor(random() * Math.max(1, cap))
  return Math.max(50, Math.max(0, serverDelayMs) + jitter)
}

export const scheduledEntryDelayMs = (prepared = {}) => {
  const serverTime = Date.parse(prepared.serverTime)
  const scheduledTime = Date.parse(prepared.scheduledActivationTime)
  if (!Number.isFinite(serverTime) || !Number.isFinite(scheduledTime)) return 0
  return Math.max(0, scheduledTime - serverTime)
}

export const mergePaperDelivery = (delivered = {}) => {
  const draftAnswers = Object.fromEntries(
    (delivered.draftAnswers || []).map((answer) => [String(answer.questionId), answer.answerText || ''])
  )
  return (delivered.questions || []).map((question) => ({
    ...question,
    currentAnswer: draftAnswers[String(question.questionId)] ?? null
  }))
}
