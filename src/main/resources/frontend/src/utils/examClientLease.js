const STORAGE_PREFIX = 'exam-client-lease'

const storageKey = (userId, examId) => `${STORAGE_PREFIX}:${String(userId)}:${String(examId)}`

const randomId = () => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

const canUseSessionStorage = () =>
  typeof window !== 'undefined' && typeof window.sessionStorage !== 'undefined'

const readRecord = (userId, examId) => {
  if (!userId || !examId || !canUseSessionStorage()) {
    return null
  }
  try {
    const raw = window.sessionStorage.getItem(storageKey(userId, examId))
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

const writeRecord = (userId, examId, record) => {
  if (!userId || !examId || !canUseSessionStorage()) {
    return record
  }
  try {
    window.sessionStorage.setItem(storageKey(userId, examId), JSON.stringify(record))
  } catch {
    // The in-memory copy still lets the current page continue.
  }
  return record
}

export const getOrCreateExamClientLease = (userId, examId) => {
  const current = readRecord(userId, examId)
  if (current?.clientId) {
    return current
  }
  return writeRecord(userId, examId, {
    clientId: randomId(),
    leaseToken: null,
    leaseExpiresAt: null,
    heartbeatIntervalSeconds: 15,
    leaseTimeoutSeconds: 90
  })
}

export const updateExamClientLease = (userId, examId, lease = {}) => {
  const current = getOrCreateExamClientLease(userId, examId)
  const next = {
    ...current,
    leaseToken: lease.leaseToken || current.leaseToken || null,
    leaseExpiresAt: lease.leaseExpiresAt || current.leaseExpiresAt || null,
    heartbeatIntervalSeconds: Number(lease.heartbeatIntervalSeconds || current.heartbeatIntervalSeconds || 15),
    leaseTimeoutSeconds: Number(lease.leaseTimeoutSeconds || current.leaseTimeoutSeconds || 90)
  }
  return writeRecord(userId, examId, next)
}

export const clearExamClientLease = (userId, examId) => {
  if (!userId || !examId || !canUseSessionStorage()) {
    return
  }
  try {
    window.sessionStorage.removeItem(storageKey(userId, examId))
  } catch {
    // Ignore storage cleanup failures.
  }
}

export const createExamWindowGuard = async ({ userId, examId, onDuplicate } = {}) => {
  const name = `exam-window:${String(userId)}:${String(examId)}`
  const instanceId = randomId()

  if (typeof navigator !== 'undefined' && navigator.locks?.request) {
    let releaseLock = null
    let settled = false
    const acquired = await new Promise((resolve) => {
      const settle = (value) => {
        if (!settled) {
          settled = true
          resolve(value)
        }
      }
      navigator.locks.request(name, { ifAvailable: true }, async (lock) => {
        if (!lock) {
          settle(false)
          return
        }
        settle(true)
        await new Promise((release) => {
          releaseLock = release
        })
      }).catch(() => settle(true))
    })
    return {
      acquired,
      mode: 'web-locks',
      close() {
        if (releaseLock) {
          releaseLock()
          releaseLock = null
        }
      }
    }
  }

  if (typeof BroadcastChannel !== 'undefined') {
    const channel = new BroadcastChannel(name)
    let closed = false
    channel.onmessage = (event) => {
      const message = event?.data || {}
      if (!message.type || message.instanceId === instanceId) {
        return
      }
      if (message.type === 'WHO_IS_ACTIVE') {
        channel.postMessage({ type: 'ACTIVE_EXAM_WINDOW', instanceId })
      } else if (message.type === 'ACTIVE_EXAM_WINDOW' && !closed) {
        onDuplicate?.()
      }
    }
    setTimeout(() => {
      if (!closed) {
        channel.postMessage({ type: 'WHO_IS_ACTIVE', instanceId })
      }
    }, 0)
    return {
      acquired: true,
      mode: 'broadcast-channel',
      close() {
        closed = true
        channel.close()
      }
    }
  }

  return {
    acquired: true,
    mode: 'none',
    close() {}
  }
}
