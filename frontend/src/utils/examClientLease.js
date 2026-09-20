const STORAGE_PREFIX = 'exam-client-lease'
const BROADCAST_ELECTION_MS = 150

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
    heartbeatIntervalSeconds: 30,
    leaseTimeoutSeconds: 90
  })
}

export const updateExamClientLease = (userId, examId, lease = {}) => {
  const current = getOrCreateExamClientLease(userId, examId)
  const next = {
    ...current,
    leaseToken: lease.leaseToken || current.leaseToken || null,
    leaseExpiresAt: lease.leaseExpiresAt || current.leaseExpiresAt || null,
    heartbeatIntervalSeconds: Number(lease.heartbeatIntervalSeconds || current.heartbeatIntervalSeconds || 30),
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
  const instanceStartedAt = Date.now()

  if (typeof navigator !== 'undefined' && navigator.locks?.request) {
    let releaseLock = null
    let settled = false
    let requestFailed = false
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
      }).catch(() => {
        requestFailed = true
        settle(false)
      })
    })
    if (!requestFailed) {
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
  }

  if (typeof BroadcastChannel !== 'undefined') {
    const channel = new BroadcastChannel(name)
    let closed = false
    let acquired = false
    let activeInstanceId = null
    const contenders = new Map([[instanceId, instanceStartedAt]])
    const precedes = (candidateId, candidateStartedAt) => {
      const candidateTime = Number(candidateStartedAt) || 0
      return candidateTime < instanceStartedAt
        || (candidateTime === instanceStartedAt && String(candidateId) < instanceId)
    }
    channel.onmessage = (event) => {
      const message = event?.data || {}
      if (!message.type || message.instanceId === instanceId) {
        return
      }
      if (message.type === 'WHO_IS_ACTIVE') {
        if (acquired) {
          channel.postMessage({
            type: 'ACTIVE_EXAM_WINDOW',
            instanceId,
            startedAt: instanceStartedAt
          })
        } else {
          contenders.set(message.instanceId, Number(message.startedAt) || 0)
        }
      } else if (message.type === 'ACTIVE_EXAM_WINDOW') {
        if (!acquired) {
          activeInstanceId = message.instanceId
        } else if (precedes(message.instanceId, message.startedAt) && !closed) {
          acquired = false
          onDuplicate?.()
        }
      }
    }
    channel.postMessage({
      type: 'WHO_IS_ACTIVE',
      instanceId,
      startedAt: instanceStartedAt
    })
    await new Promise((resolve) => setTimeout(resolve, BROADCAST_ELECTION_MS))
    if (activeInstanceId == null) {
      const elected = [...contenders.entries()].sort((left, right) => {
        const timeOrder = left[1] - right[1]
        return timeOrder !== 0 ? timeOrder : String(left[0]).localeCompare(String(right[0]))
      })[0]?.[0]
      acquired = elected === instanceId
      if (acquired && !closed) {
        channel.postMessage({
          type: 'ACTIVE_EXAM_WINDOW',
          instanceId,
          startedAt: instanceStartedAt
        })
      }
    }
    return {
      acquired,
      mode: 'broadcast-channel',
      close() {
        closed = true
        acquired = false
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
