import { reactive } from 'vue'

export const useExamSnapshotSync = () => {
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
    snapshotVersion: 0,
    clientSequence: 0,
    serverRevision: 0,
    editVersion: 0,
    confirmedEditVersion: 0
  })

  let snapshotSender = null
  let pendingSnapshot = null
  let snapshotInFlight = null
  let debounceTimer = null
  let maxWaitTimer = null
  let scheduleStartedAt = 0

  const markEdited = () => {
    syncState.editVersion = Math.max(
      Number(syncState.editVersion || 0) + 1,
      Number(syncState.confirmedEditVersion || 0) + 1
    )
    syncState.dirty = true
    return syncState.editVersion
  }

  const observeAck = (ack, fallbackClientSequence = 0, acknowledgedEditVersion = null) => {
    const acknowledgedRevision = Number(ack?.serverRevision || 0)
    syncState.serverRevision = Math.max(syncState.serverRevision || 0, acknowledgedRevision)
    if (ack?.accepted === false) {
      return false
    }
    const storedSequence = Number(
      ack?.storedClientSequence || ack?.snapshotVersion || fallbackClientSequence || 0
    )
    syncState.snapshotVersion = Math.max(syncState.snapshotVersion || 0, storedSequence)
    syncState.clientSequence = Math.max(syncState.clientSequence || 0, storedSequence)
    if (acknowledgedEditVersion != null) {
      syncState.confirmedEditVersion = Math.max(
        Number(syncState.confirmedEditVersion || 0),
        Number(acknowledgedEditVersion || 0)
      )
      syncState.dirty = Number(syncState.editVersion || 0)
        > Number(syncState.confirmedEditVersion || 0)
    }
    syncState.lastServerAckAt = ack?.serverReceivedAt || syncState.lastServerAckAt
    return true
  }

  const restoreRevision = ({
    snapshotVersion = 0,
    clientSequence = 0,
    serverRevision = 0,
    editVersion = 0,
    confirmedEditVersion = 0,
    dirty = false
  } = {}) => {
    syncState.snapshotVersion = Math.max(syncState.snapshotVersion || 0, Number(snapshotVersion || 0))
    syncState.clientSequence = Math.max(
      syncState.clientSequence || 0,
      Number(clientSequence || snapshotVersion || 0)
    )
    syncState.serverRevision = Math.max(syncState.serverRevision || 0, Number(serverRevision || 0))
    const restoredEditVersion = Number(editVersion || 0)
    const restoredConfirmedVersion = Number(confirmedEditVersion || 0)
    // 旧草稿没有 editVersion 时，dirty 仍然必须恢复为待发送状态。
    syncState.editVersion = Math.max(
      Number(syncState.editVersion || 0),
      restoredEditVersion,
      dirty && restoredEditVersion <= restoredConfirmedVersion
        ? Math.max(Number(clientSequence || snapshotVersion || 0), 1)
        : 0
    )
    syncState.confirmedEditVersion = Math.max(
      Number(syncState.confirmedEditVersion || 0),
      restoredConfirmedVersion
    )
    syncState.dirty = Boolean(dirty)
      || Number(syncState.editVersion || 0) > Number(syncState.confirmedEditVersion || 0)
  }

  const nextClientSequence = () => {
    syncState.clientSequence = Math.max(
      Number(syncState.clientSequence || 0),
      Number(syncState.snapshotVersion || 0)
    ) + 1
    return syncState.clientSequence
  }

  const setSnapshotSender = (sender) => {
    snapshotSender = typeof sender === 'function' ? sender : null
  }

  const pumpSnapshots = () => {
    if (snapshotInFlight || !pendingSnapshot || !snapshotSender) {
      return snapshotInFlight || Promise.resolve(null)
    }
    const current = pendingSnapshot
    pendingSnapshot = null
    snapshotInFlight = Promise.resolve()
      .then(() => snapshotSender(current))
      .finally(() => {
        snapshotInFlight = null
        if (pendingSnapshot) {
          void pumpSnapshots()
        }
      })
    return snapshotInFlight
  }

  const enqueueSnapshot = (item) => {
    // Complete snapshots are replaceable while one request is in flight. The
    // newest edit is the only one worth sending after the current request.
    pendingSnapshot = item
    if (snapshotInFlight) {
      return snapshotInFlight.then(() => pumpSnapshots())
    }
    return pumpSnapshots()
  }

  const replacePendingSnapshot = (item) => {
    pendingSnapshot = item
  }

  const flushScheduledSnapshot = (factory) => {
    const next = typeof factory === 'function' ? factory() : null
    if (next && typeof next.then === 'function') {
      return next
    }
    return next == null ? Promise.resolve(null) : enqueueSnapshot(next)
  }

  const clearSchedule = () => {
    if (debounceTimer) clearTimeout(debounceTimer)
    if (maxWaitTimer) clearTimeout(maxWaitTimer)
    debounceTimer = null
    maxWaitTimer = null
    scheduleStartedAt = 0
  }

  const scheduleSnapshot = (factory, {
    debounceMs = 500,
    maxWaitMs = 2_000,
    nowProvider = () => Date.now()
  } = {}) => {
    if (!scheduleStartedAt) {
      scheduleStartedAt = nowProvider()
      maxWaitTimer = setTimeout(() => {
        clearSchedule()
        void flushScheduledSnapshot(factory)
      }, maxWaitMs)
    }
    if (debounceTimer) clearTimeout(debounceTimer)
    debounceTimer = setTimeout(() => {
      clearSchedule()
      void flushScheduledSnapshot(factory)
    }, Math.max(0, debounceMs))
  }

  const dispose = () => clearSchedule()

  return {
    syncState,
    observeAck,
    restoreRevision,
    nextClientSequence,
    markEdited,
    setSnapshotSender,
    enqueueSnapshot,
    replacePendingSnapshot,
    scheduleSnapshot,
    dispose
  }
}
