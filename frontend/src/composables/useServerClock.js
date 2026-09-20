import { readonly, shallowRef } from 'vue'

export const calculateServerOffset = ({ serverEpochMs, sentAt, sentAtMs, receivedAt, receivedAtMs }) => {
  const server = Number(serverEpochMs)
  const sent = Number(sentAt ?? sentAtMs)
  const received = Number(receivedAt ?? receivedAtMs)
  if (![server, sent, received].every(Number.isFinite) || received < sent) {
    return null
  }
  return {
    offsetMs: server - (sent + received) / 2,
    uncertaintyMs: Math.max(0, (received - sent) / 2)
  }
}

export const responseDeadlineEpochMs = (payload, fallback = null) => {
  const explicit = Number(payload?.deadlineEpochMs)
  if (Number.isFinite(explicit) && explicit > 0) return explicit
  if (fallback instanceof Date && !Number.isNaN(fallback.getTime())) return fallback.getTime()
  const parsed = Date.parse(payload?.deadlineTime || payload?.endTime || '')
  return Number.isFinite(parsed) ? parsed : null
}

export const useServerClock = (nowProvider = () => Date.now()) => {
  const offsetMs = shallowRef(0)
  const uncertaintyMs = shallowRef(Number.POSITIVE_INFINITY)
  const synchronized = shallowRef(false)

  const observe = (payload, sentAt, receivedAt = nowProvider()) => {
    const sample = calculateServerOffset({
      serverEpochMs: payload?.serverEpochMs,
      sentAt,
      receivedAt
    })
    if (!sample) return false
    if (!synchronized.value || sample.uncertaintyMs <= uncertaintyMs.value) {
      offsetMs.value = sample.offsetMs
      uncertaintyMs.value = sample.uncertaintyMs
    }
    synchronized.value = true
    return true
  }

  const nowMs = () => nowProvider() + offsetMs.value
  const secondsUntil = (deadlineEpochMs) => {
    const deadline = Number(deadlineEpochMs)
    if (!Number.isFinite(deadline)) return null
    return Math.max(0, Math.floor((deadline - nowMs()) / 1000))
  }
  const isExpired = (deadlineEpochMs) => {
    const deadline = Number(deadlineEpochMs)
    return Number.isFinite(deadline) && nowMs() >= deadline
  }

  return {
    offsetMs: readonly(offsetMs),
    uncertaintyMs: readonly(uncertaintyMs),
    synchronized: readonly(synchronized),
    observe,
    nowMs,
    secondsUntil,
    isExpired
  }
}
