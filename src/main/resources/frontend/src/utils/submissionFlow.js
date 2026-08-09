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

export const isSubmissionAccepted = (result) => {
  const status = String(result?.status || '').toUpperCase()
  const sessionStatus = String(result?.sessionStatus || '').toUpperCase()
  const submissionStatus = String(result?.submissionStatus || '').toUpperCase()
  if (status === 'SUBMITTING' || status === 'PROCESSING' || status === 'SUBMITTED') {
    return true
  }
  if (sessionStatus === 'AUTO_SUBMITTING' || sessionStatus === 'SUBMITTED') {
    return true
  }
  return submissionStatus === 'PROCESSING' || submissionStatus === 'SUBMITTED'
}

export const shouldRetryDeadlineWithAnswers = (plan, error) => {
  const code = error?.responseData?.code || error?.response?.data?.code || error?.code
  return plan?.retryWithAnswersWhenActive === true && code === 'INVALID_EXAM_ANSWERS'
}

export const submissionPollDelay = (attempt, randomValue = Math.random()) => {
  const safeAttempt = Math.max(1, Number(attempt) || 1)
  const safeRandom = Math.min(1, Math.max(0, Number(randomValue) || 0))
  const baseDelay = Math.min(5000, 1500 * (1.35 ** Math.min(safeAttempt - 1, 5)))
  return Math.round(baseDelay * (0.8 + safeRandom * 0.4))
}
