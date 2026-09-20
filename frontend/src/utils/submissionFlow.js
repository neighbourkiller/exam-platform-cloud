export const createSubmissionPlan = ({ online, deadlineReached }) => {
  if (!online) {
    return deadlineReached
      ? { mode: 'OFFLINE_DEADLINE_WAIT', validate: false, sync: false, stripAnswers: false }
      : { mode: 'OFFLINE_WAIT', validate: false, sync: false, stripAnswers: false }
  }
  return deadlineReached
    ? {
        mode: 'SERVER_DEADLINE_HANDOFF',
        validate: false,
        sync: false,
        stripAnswers: true,
        retryWithAnswersWhenActive: true
      }
    : { mode: 'ACTIVE_SUBMIT', validate: true, sync: true, stripAnswers: false }
}

export const isSubmissionHandoffAccepted = (result) => {
  const phase = String(result?.phase || '').toUpperCase()
  const status = String(result?.status || '').toUpperCase()
  const sessionStatus = String(result?.sessionStatus || '').toUpperCase()
  const submissionStatus = String(result?.submissionStatus || '').toUpperCase()
  if (phase === 'HANDOFF_ACCEPTED' || phase === 'RUNTIME_FINALIZED') {
    return true
  }
  if (status === 'SUBMITTING' || status === 'PROCESSING' || status === 'SUBMITTED') {
    return true
  }
  if (sessionStatus === 'AUTO_SUBMITTING' || sessionStatus === 'SUBMITTED') {
    return true
  }
  return submissionStatus === 'PROCESSING' || submissionStatus === 'SUBMITTED'
}

export const isRuntimeFinalized = (result) => {
  if (result?.runtimeFinalized === true) return true
  if (result?.runtimeFinalized === false) return false

  const phase = String(result?.phase || '').toUpperCase()
  const status = String(result?.status || '').toUpperCase()
  const sessionStatus = String(result?.sessionStatus || '').toUpperCase()
  const submissionStatus = String(result?.submissionStatus || '').toUpperCase()
  if (phase === 'RUNTIME_FINALIZED') return true
  if (status === 'PROCESSING' || status === 'SUBMITTED') return true
  return sessionStatus === 'SUBMITTED'
    && (submissionStatus === 'PROCESSING' || submissionStatus === 'SUBMITTED')
}

// Keep the previous export for callers that only need to know whether the server took ownership.
export const isSubmissionAccepted = isSubmissionHandoffAccepted

export const shouldRequestDeadlineHandoff = (result) => (
  !isRuntimeFinalized(result) && !isSubmissionHandoffAccepted(result)
)

export const shouldRetryDeadlineWithAnswers = (plan, error) => {
  const code = error?.responseData?.code || error?.response?.data?.code || error?.code
  return plan?.retryWithAnswersWhenActive === true && code === 'INVALID_EXAM_ANSWERS'
}

export const submissionPollDelay = (attempt, randomValue = Math.random()) => {
  const safeAttempt = Math.max(1, Number(attempt) || 1)
  const safeRandom = Math.min(1, Math.max(0, Number(randomValue) || 0))
  const baseDelay = 10_000
  return Math.round(baseDelay * (0.8 + safeRandom * 0.4))
}

export const SUBMISSION_CONFIRMATION_TIMEOUT_MS = 65_000
