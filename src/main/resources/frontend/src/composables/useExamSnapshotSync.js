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
    serverRevision: 0
  })

  const observeAck = (ack, fallbackClientSequence = 0) => {
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
    syncState.lastServerAckAt = ack?.serverReceivedAt || syncState.lastServerAckAt
    return true
  }

  const restoreRevision = ({ snapshotVersion = 0, clientSequence = 0, serverRevision = 0 } = {}) => {
    syncState.snapshotVersion = Math.max(syncState.snapshotVersion || 0, Number(snapshotVersion || 0))
    syncState.clientSequence = Math.max(
      syncState.clientSequence || 0,
      Number(clientSequence || snapshotVersion || 0)
    )
    syncState.serverRevision = Math.max(syncState.serverRevision || 0, Number(serverRevision || 0))
  }

  const nextClientSequence = () => {
    syncState.clientSequence = Math.max(
      Number(syncState.clientSequence || 0),
      Number(syncState.snapshotVersion || 0)
    ) + 1
    return syncState.clientSequence
  }

  return { syncState, observeAck, restoreRevision, nextClientSequence }
}
