const DB_NAME = 'exam-local-drafts'
const DB_VERSION = 3
const DRAFT_STORE_NAME = 'drafts'
const QUEUE_STORE_NAME = 'syncQueue'
const EVIDENCE_STORE_NAME = 'submissionEvidence'

let openRequestPromise = null

const canUseIndexedDb = () =>
  typeof window !== 'undefined'
  && typeof window.indexedDB !== 'undefined'

const cloneAnswers = (answers = {}) => {
  const result = {}
  Object.entries(answers || {}).forEach(([questionId, value]) => {
    if (Array.isArray(value)) {
      result[questionId] = value.map((item) => String(item))
      return
    }
    result[questionId] = value == null ? '' : String(value)
  })
  return result
}

const cloneMarkedQuestionIds = (markedQuestionIds = []) => {
  if (!Array.isArray(markedQuestionIds)) {
    return []
  }
  return [...new Set(markedQuestionIds.map((item) => String(item)).filter((item) => item))]
}

const cloneExamRuntime = (runtime = {}) => ({
  examId: runtime?.examId == null ? null : String(runtime.examId),
  examName: runtime?.examName || '',
  questions: Array.isArray(runtime?.questions) ? JSON.parse(JSON.stringify(runtime.questions)) : [],
  proctoringPolicy: runtime?.proctoringPolicy ? JSON.parse(JSON.stringify(runtime.proctoringPolicy)) : {},
  examEndAt: runtime?.examEndAt || null,
  deadlineTime: runtime?.deadlineTime || null,
  savedAt: runtime?.savedAt || Date.now()
})

const buildStorageKey = (userId, examId) => `${String(userId)}:${String(examId)}`

const ensureStores = (db) => {
  if (!db.objectStoreNames.contains(DRAFT_STORE_NAME)) {
    db.createObjectStore(DRAFT_STORE_NAME, { keyPath: 'storageKey' })
  }
  if (!db.objectStoreNames.contains(QUEUE_STORE_NAME)) {
    const queueStore = db.createObjectStore(QUEUE_STORE_NAME, { keyPath: 'id', autoIncrement: true })
    queueStore.createIndex('byExam', 'examStorageKey', { unique: false })
    queueStore.createIndex('byNextAttemptAt', 'nextAttemptAt', { unique: false })
  }
  if (!db.objectStoreNames.contains(EVIDENCE_STORE_NAME)) {
    const evidenceStore = db.createObjectStore(EVIDENCE_STORE_NAME, { keyPath: 'storageKey' })
    evidenceStore.createIndex('byExpiresAt', 'expiresAt', { unique: false })
  }
}

const openDatabase = () => {
  if (!canUseIndexedDb()) {
    return Promise.resolve(null)
  }
  if (!openRequestPromise) {
    openRequestPromise = new Promise((resolve, reject) => {
      const request = window.indexedDB.open(DB_NAME, DB_VERSION)
      request.onerror = () => reject(request.error || new Error('打开本地草稿库失败'))
      request.onupgradeneeded = () => ensureStores(request.result)
      request.onsuccess = () => {
        const db = request.result
        db.onversionchange = () => {
          db.close()
          openRequestPromise = null
        }
        resolve(db)
      }
    })
  }
  return openRequestPromise
}

const withStore = async (storeName, mode, handler) => {
  const db = await openDatabase()
  if (!db) {
    return null
  }
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(storeName, mode)
    const store = transaction.objectStore(storeName)
    let settled = false

    const finish = (value) => {
      if (settled) {
        return
      }
      settled = true
      resolve(value)
    }

    transaction.onerror = () => {
      if (settled) {
        return
      }
      settled = true
      reject(transaction.error || new Error('本地草稿操作失败'))
    }

    handler(store, finish)
  })
}

export const loadDraft = async (userId, examId) => {
  if (!userId || !examId) {
    return null
  }
  const result = await withStore(DRAFT_STORE_NAME, 'readonly', (store, done) => {
    const request = store.get(buildStorageKey(userId, examId))
    request.onerror = () => done(null)
    request.onsuccess = () => done(request.result || null)
  })
  if (!result) {
    return null
  }
  return {
    ...result,
    answers: cloneAnswers(result.answers || {}),
    markedQuestionIds: cloneMarkedQuestionIds(result.markedQuestionIds || []),
    examRuntime: cloneExamRuntime(result.examRuntime || {})
  }
}

export const saveDraft = async ({
  userId,
  examId,
  answers,
  markedQuestionIds,
  updatedAt,
  lastSyncedAt,
  lastServerAckAt,
  snapshotVersion,
  clientSequence,
  serverRevision,
  dirty,
  pendingSubmitIntent,
  examRuntime
}) => {
  if (!userId || !examId) {
    return null
  }
  const record = {
    storageKey: buildStorageKey(userId, examId),
    userId: String(userId),
    examId: String(examId),
    answers: cloneAnswers(answers),
    markedQuestionIds: cloneMarkedQuestionIds(markedQuestionIds),
    examRuntime: cloneExamRuntime(examRuntime),
    updatedAt: Number.isFinite(Number(updatedAt)) ? Number(updatedAt) : Date.now(),
    lastSyncedAt: Number.isFinite(Number(lastSyncedAt)) ? Number(lastSyncedAt) : null,
    lastServerAckAt: lastServerAckAt || null,
    snapshotVersion: Number.isFinite(Number(snapshotVersion)) ? Number(snapshotVersion) : 0,
    clientSequence: Number.isFinite(Number(clientSequence)) ? Number(clientSequence) : 0,
    serverRevision: Number.isFinite(Number(serverRevision)) ? Number(serverRevision) : 0,
    pendingSubmitIntent: pendingSubmitIntent || null,
    dirty: Boolean(dirty)
  }
  await withStore(DRAFT_STORE_NAME, 'readwrite', (store, done) => {
    const request = store.put(record)
    request.onerror = () => done(null)
    request.onsuccess = () => done(record)
  })
  return record
}

export const clearDraft = async (userId, examId) => {
  if (!userId || !examId) {
    return
  }
  await withStore(DRAFT_STORE_NAME, 'readwrite', (store, done) => {
    const request = store.delete(buildStorageKey(userId, examId))
    request.onerror = () => done(null)
    request.onsuccess = () => done(null)
  })
}

export const enqueueSyncItem = async ({ userId, examId, type, payload, occurredAt, nextAttemptAt }) => {
  if (!userId || !examId || !type) {
    return null
  }
  const record = {
    examStorageKey: buildStorageKey(userId, examId),
    userId: String(userId),
    examId: String(examId),
    type,
    payload: payload || {},
    occurredAt: occurredAt || Date.now(),
    attemptCount: 0,
    nextAttemptAt: nextAttemptAt || Date.now(),
    lastError: ''
  }
  const saved = await withStore(QUEUE_STORE_NAME, 'readwrite', (store, done) => {
    const request = store.add(record)
    request.onerror = () => done(null)
    request.onsuccess = () => done({ ...record, id: request.result })
  })
  return saved || record
}

export const listSyncItems = async (userId, examId) => {
  if (!userId || !examId) {
    return []
  }
  const storageKey = buildStorageKey(userId, examId)
  const result = await withStore(QUEUE_STORE_NAME, 'readonly', (store, done) => {
    const items = []
    const index = store.index('byExam')
    const request = index.openCursor(IDBKeyRange.only(storageKey))
    request.onerror = () => done([])
    request.onsuccess = () => {
      const cursor = request.result
      if (!cursor) {
        done(items.sort((a, b) => (a.occurredAt || 0) - (b.occurredAt || 0)))
        return
      }
      items.push(cursor.value)
      cursor.continue()
    }
  })
  return result || []
}

export const updateSyncItem = async (item) => {
  if (!item?.id) {
    return null
  }
  await withStore(QUEUE_STORE_NAME, 'readwrite', (store, done) => {
    const request = store.put(item)
    request.onerror = () => done(null)
    request.onsuccess = () => done(item)
  })
  return item
}

export const deleteSyncItem = async (id) => {
  if (!id) {
    return
  }
  await withStore(QUEUE_STORE_NAME, 'readwrite', (store, done) => {
    const request = store.delete(id)
    request.onerror = () => done(null)
    request.onsuccess = () => done(null)
  })
}

export const clearSyncItems = async (userId, examId) => {
  const items = await listSyncItems(userId, examId)
  await Promise.all(items.map((item) => deleteSyncItem(item.id)))
}

export const saveSubmissionEvidence = async ({
  userId,
  examId,
  answers,
  snapshotVersion,
  serverRevision,
  failureCode,
  incidentId,
  attemptedAt = Date.now(),
  expiresAt = Date.now() + 7 * 24 * 60 * 60 * 1000
}) => {
  if (!userId || !examId) return null
  const record = {
    storageKey: buildStorageKey(userId, examId),
    userId: String(userId),
    examId: String(examId),
    answers: cloneAnswers(answers),
    snapshotVersion: Number(snapshotVersion || 0),
    serverRevision: Number(serverRevision || 0),
    failureCode: failureCode || null,
    incidentId: incidentId || null,
    attemptedAt: Number(attemptedAt || Date.now()),
    expiresAt: Number(expiresAt)
  }
  await withStore(EVIDENCE_STORE_NAME, 'readwrite', (store, done) => {
    const request = store.put(record)
    request.onerror = () => done(null)
    request.onsuccess = () => done(record)
  })
  return record
}

export const loadSubmissionEvidence = async (userId, examId) => {
  if (!userId || !examId) return null
  return withStore(EVIDENCE_STORE_NAME, 'readonly', (store, done) => {
    const request = store.get(buildStorageKey(userId, examId))
    request.onerror = () => done(null)
    request.onsuccess = () => done(request.result || null)
  })
}

export const purgeExpiredSubmissionEvidence = async (now = Date.now()) => {
  let removed = 0
  await withStore(EVIDENCE_STORE_NAME, 'readwrite', (store, done) => {
    const request = store.openCursor()
    request.onerror = () => done(removed)
    request.onsuccess = () => {
      const cursor = request.result
      if (!cursor) {
        done(removed)
        return
      }
      if (Number(cursor.value?.expiresAt || 0) <= now) {
        cursor.delete()
        removed += 1
      }
      cursor.continue()
    }
  })
  return removed
}
