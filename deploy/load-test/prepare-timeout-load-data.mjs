import crypto from 'node:crypto'
import { once } from 'node:events'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import zlib from 'node:zlib'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const projectRoot = path.resolve(scriptDirectory, '..', '..')
const envFile = process.env.ENV_FILE || path.join(projectRoot, '.env.microservices')
const composeProject = process.env.COMPOSE_PROJECT_NAME || 'exam-platform-cloud-timeout'
const composeOverrideFile = process.env.COMPOSE_OVERRIDE_FILE
  ? path.resolve(process.env.COMPOSE_OVERRIDE_FILE)
  : path.join(scriptDirectory, 'compose.timeout-test.yaml')
const composeArguments = [
  'compose', '-p', composeProject,
  '-f', path.join(projectRoot, 'docker-compose.yml'),
  '-f', composeOverrideFile,
  '--env-file', envFile
]
const users = positiveInteger(process.env.USERS || '10000', 'USERS')
const examId = positiveInteger(process.env.EXAM_ID || '99000001', 'EXAM_ID')
const dueInSeconds = positiveInteger(process.env.DUE_IN_SECONDS || '120', 'DUE_IN_SECONDS')
const payloadBytes = positiveInteger(process.env.PAYLOAD_BYTES || '100000', 'PAYLOAD_BYTES')
const studentBase = BigInt(process.env.STUDENT_BASE || '880000000000')
const sessionBase = BigInt(process.env.SESSION_BASE || '890000000000')
const submissionBase = BigInt(process.env.SUBMISSION_BASE || '900000000000')
const tokenFile = process.env.TOKENS_FILE
  ? path.resolve(process.env.TOKENS_FILE)
  : path.join(scriptDirectory, `tokens-${examId}.json`)
const sessionMapFile = process.env.SESSION_MAP_FILE
  ? path.resolve(process.env.SESSION_MAP_FILE)
  : path.join(scriptDirectory, `session-map-${examId}.json`)
const privateKeyFile = process.env.JWT_PRIVATE_KEY_FILE
  ? path.resolve(process.env.JWT_PRIVATE_KEY_FILE)
  : null

if (privateKeyFile && !fs.existsSync(privateKeyFile)) {
  throw new Error(`JWT private key does not exist: ${privateKeyFile}`)
}

const answerTemplate = buildAnswers(payloadBytes)
const samplePayload = snapshotPayload(studentBase + 1n, answerTemplate)
const actualPayloadBytes = Buffer.byteLength(samplePayload)
const canonicalDraft = Buffer.from(JSON.stringify({
  answers: Object.fromEntries(answerTemplate.map(answer => [answer.questionId, answer.answerText]))
}))
const compressedDraft = zlib.gzipSync(canonicalDraft)
const draftSha256 = crypto.createHash('sha256').update(canonicalDraft).digest('hex')
const privateKey = privateKeyFile
  ? fs.readFileSync(privateKeyFile, 'utf8')
  : await readComposePrivateKey()

process.stdout.write(
  `Preparing ${users} timeout sessions; snapshot bytes=${actualPayloadBytes}; `
    + `draft gzip bytes=${compressedDraft.length}\n`
)
await seedRedis(answerTemplate)
await writeTokens(privateKey)
const deadlineEpochMs = await seedMySql()
const sessionMap = await writeVerifiedSessionMap()

process.stdout.write(`${JSON.stringify({
  examId,
  users,
  requestedPayloadBytes: payloadBytes,
  actualPayloadBytes,
  deadlineEpochMs,
  tokenFile,
  sessionMapFile,
  targetTaskId: sessionMap.entries[0]?.taskId ?? null
})}\n`)

function positiveInteger(value, name) {
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive safe integer`)
  }
  return parsed
}

async function readComposePrivateKey() {
  const child = spawn('docker', [
    ...composeArguments, 'exec', '-T', 'iam-service',
    'cat', '/run/secrets/jwt-private.pem'
  ], { stdio: ['ignore', 'pipe', 'inherit'] })
  let stdout = ''
  child.stdout.setEncoding('utf8')
  child.stdout.on('data', chunk => { stdout += chunk })
  const [exitCode] = await once(child, 'close')
  if (exitCode !== 0 || !stdout.includes('BEGIN PRIVATE KEY')) {
    throw new Error('Unable to read the Compose-managed JWT private key')
  }
  return stdout
}

function buildAnswers(targetBytes) {
  const answerCount = 100
  const overhead = Buffer.byteLength(JSON.stringify({
    examId,
    studentId: Number(studentBase + 1n),
    answers: Array.from({ length: answerCount }, (_, index) => ({
      questionId: index + 1,
      answerText: ''
    })),
    clientTimestamp: 1,
    snapshotVersion: 1,
    serverReceivedAt: '2026-01-01T00:00:00'
  }))
  const answerBytes = Math.max(1, Math.floor((targetBytes - overhead) / answerCount))
  return Array.from({ length: answerCount }, (_, index) => ({
    questionId: index + 1,
    answerText: deterministicText(answerBytes, index + 1)
  }))
}

function deterministicText(length, seed) {
  let result = ''
  let counter = 0
  while (result.length < length) {
    result += crypto.createHash('sha256').update(`timeout-load-${seed}-${counter}`).digest('hex')
    counter += 1
  }
  return result.slice(0, length)
}

function snapshotPayload(studentId, answers) {
  return JSON.stringify({
    examId,
    studentId: Number(studentId),
    answers,
    clientTimestamp: Date.now(),
    snapshotVersion: 1,
    serverReceivedAt: new Date().toISOString().replace('Z', '')
  })
}

async function seedRedis(answers) {
  const socket = net.createConnection({ host: '127.0.0.1', port: 26379 })
  const replies = createRedisReplyReader(socket)
  try {
    await once(socket, 'connect')
    await writeRedis(socket, replies, ['SELECT', '1'])
    for (let index = 1; index <= users; index += 1) {
      const studentId = studentBase + BigInt(index)
      const key = `exam:snapshot:${examId}:${studentId}`
      await writeRedis(socket, replies, ['SET', key, snapshotPayload(studentId, answers), 'EX', '172800'])
      if (index % 1000 === 0) {
        process.stdout.write(`Redis snapshots: ${index}/${users}\n`)
      }
    }
    const closed = once(socket, 'close')
    socket.end()
    await closed
  } catch (error) {
    socket.destroy()
    throw error
  }
}

function createRedisReplyReader(socket) {
  let buffered = Buffer.alloc(0)
  let pending = null
  let terminalError = null

  const consume = () => {
    if (!pending) {
      return
    }
    const lineEnd = buffered.indexOf('\r\n')
    if (lineEnd < 0) {
      return
    }
    const line = buffered.subarray(0, lineEnd).toString('utf8')
    buffered = buffered.subarray(lineEnd + 2)
    const current = pending
    pending = null
    if (line.startsWith('+')) {
      current.resolve(line.slice(1))
      return
    }
    if (line.startsWith('-')) {
      current.reject(new Error(`Redis ${current.command} failed: ${line.slice(1)}`))
      return
    }
    current.reject(new Error(`Redis ${current.command} returned an unexpected reply: ${line}`))
  }

  const abort = (error) => {
    if (!terminalError) {
      terminalError = error instanceof Error ? error : new Error(String(error))
    }
    if (pending) {
      const current = pending
      pending = null
      current.reject(terminalError)
    }
  }

  socket.on('data', chunk => {
    buffered = buffered.length === 0 ? chunk : Buffer.concat([buffered, chunk])
    consume()
  })
  socket.on('error', abort)
  socket.on('close', () => {
    if (pending) {
      abort(new Error(`Redis connection closed before ${pending.command} replied`))
    }
  })

  return {
    abort,
    next(command) {
      if (terminalError) {
        return Promise.reject(terminalError)
      }
      if (pending) {
        return Promise.reject(new Error('Redis command overlap is not supported'))
      }
      return new Promise((resolve, reject) => {
        pending = { command, resolve, reject }
        consume()
      })
    }
  }
}

async function writeRedis(socket, replies, values) {
  const chunks = [`*${values.length}\r\n`]
  for (const value of values) {
    const body = Buffer.from(String(value))
    chunks.push(`$${body.length}\r\n`, body, '\r\n')
  }
  const command = Buffer.concat(chunks.map(value => Buffer.isBuffer(value) ? value : Buffer.from(value)))
  const response = replies.next(String(values[0] || 'COMMAND').toUpperCase())
  try {
    socket.write(command, error => {
      if (error) {
        replies.abort(error)
      }
    })
  } catch (error) {
    replies.abort(error)
  }
  const reply = await response
  if (reply !== 'OK') {
    throw new Error(`Redis ${values[0]} returned an unexpected success reply: ${reply}`)
  }
}

async function writeTokens(key) {
  const now = Math.floor(Date.now() / 1000)
  const tokens = []
  for (let index = 1; index <= users; index += 1) {
    const studentId = studentBase + BigInt(index)
    const header = base64Url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))
    const payload = base64Url(JSON.stringify({
      iss: 'exam-system',
      sub: `timeout-load-${studentId}`,
      uid: Number(studentId),
      roles: ['STUDENT'],
      typ: 'access',
      tokenVersion: 0,
      jti: crypto.randomUUID(),
      iat: now,
      exp: now + 3600
    }))
    const signingInput = `${header}.${payload}`
    const signature = crypto.sign('RSA-SHA256', Buffer.from(signingInput), key)
    tokens.push(`${signingInput}.${signature.toString('base64url')}`)
  }
  fs.writeFileSync(tokenFile, JSON.stringify(tokens), { mode: 0o644 })
  process.stdout.write(`JWT tokens: ${tokens.length}; output is intentionally not printed\n`)
}

function base64Url(value) {
  return Buffer.from(value).toString('base64url')
}

async function seedMySql() {
  const sql = `
SET SESSION cte_max_recursion_depth=${Math.max(20000, users + 10)};
SET @exam_id=${examId};
SET @student_base=${studentBase};
SET @session_base=${sessionBase};
SET @submission_base=${submissionBase};
SET @staging_due_at=TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP(3));

SET @fixture_span=1000000;
DELETE fp FROM submission_final_payload fp
JOIN submission s ON s.id=fp.submission_id
WHERE s.exam_id=@exam_id OR (s.id>@submission_base AND s.id<=@submission_base+@fixture_span);
DELETE dp FROM submission_draft_payload dp
JOIN submission s ON s.id=dp.submission_id
WHERE s.exam_id=@exam_id OR (s.id>@submission_base AND s.id<=@submission_base+@fixture_span);
DELETE oe FROM outbox_event oe
JOIN submission s ON oe.aggregate_id=CAST(s.id AS CHAR)
WHERE s.exam_id=@exam_id OR (s.id>@submission_base AND s.id<=@submission_base+@fixture_span);
DELETE sa FROM submission_answer sa
JOIN submission s ON s.id=sa.submission_id
WHERE s.exam_id=@exam_id OR (s.id>@submission_base AND s.id<=@submission_base+@fixture_span);
DELETE FROM submission_timeout_task
WHERE exam_id=@exam_id OR (session_id>@session_base AND session_id<=@session_base+@fixture_span);
DELETE FROM submission
WHERE exam_id=@exam_id OR (id>@submission_base AND id<=@submission_base+@fixture_span);
DELETE FROM exam_session
WHERE exam_id=@exam_id OR (id>@session_base AND id<=@session_base+@fixture_span);

INSERT INTO exam_session(
  id,exam_id,student_id,status,start_time,deadline_time,last_snapshot_time,
  create_time,update_time
)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<${users}
)
SELECT @session_base+n,@exam_id,@student_base+n,'ANSWERING',CURRENT_TIMESTAMP(3),
       @staging_due_at,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)
FROM seq;

INSERT INTO submission(
  id,exam_id,student_id,status,timeout_submit,draft_version,create_time,update_time
)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<${users}
)
SELECT @submission_base+n,@exam_id,@student_base+n,'IN_PROGRESS',0,1,
       CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)
FROM seq;

INSERT INTO submission_draft_payload(
  submission_id,client_id,client_sequence,server_revision,codec,payload,payload_sha256,
  accepted_at,created_at,updated_at
)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<${users}
)
SELECT @submission_base+n,'timeout-load',1,1,'GZIP_JSON_V1',
       UNHEX('${compressedDraft.toString('hex')}'),'${draftSha256}',
       CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)
FROM seq;

INSERT IGNORE INTO submission_timeout_task(
  id,session_id,exam_id,student_id,submission_id,due_at,status,attempt_count,created_at,updated_at
)
WITH RECURSIVE seq(n) AS (
  SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<${users}
)
SELECT @session_base+n,@session_base+n,@exam_id,@student_base+n,@submission_base+n,@staging_due_at,'PENDING',0,
       CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)
FROM seq;

SET @due_at=TIMESTAMPADD(SECOND,${dueInSeconds},CURRENT_TIMESTAMP(3));
UPDATE exam_session SET deadline_time=@due_at WHERE exam_id=@exam_id;
UPDATE submission_timeout_task SET due_at=@due_at WHERE exam_id=@exam_id;
SELECT ROUND(UNIX_TIMESTAMP(@due_at)*1000);
`
  const child = spawn('docker', [
    ...composeArguments, 'exec', '-T', 'mysql', 'sh', '-c',
    'mysql -uexam_runtime -p"$EXAM_DB_PASSWORD" -N exam_runtime'
  ], { stdio: ['pipe', 'pipe', 'inherit'] })
  child.stdin.end(sql)
  let stdout = ''
  child.stdout.setEncoding('utf8')
  child.stdout.on('data', chunk => { stdout += chunk })
  const exitCode = await new Promise((resolve, reject) => {
    child.once('error', reject)
    child.once('close', resolve)
  })
  if (exitCode !== 0) {
    throw new Error(`MySQL seed failed with exit code ${exitCode}`)
  }
  const deadline = Number(stdout.trim().split(/\s+/).at(-1))
  if (!Number.isFinite(deadline) || deadline <= Date.now()) {
    throw new Error(`Invalid deadline returned by MySQL: ${stdout.trim()}`)
  }
  return deadline
}

async function writeVerifiedSessionMap() {
  const child = spawn('docker', [
    ...composeArguments, 'exec', '-T', 'mysql', 'sh', '-c',
    `mysql -uexam_runtime -p"$EXAM_DB_PASSWORD" -N exam_runtime -e `
      + `"select id,session_id,student_id from submission_timeout_task `
      + `where exam_id=${examId} order by student_id,id"`
  ], { stdio: ['ignore', 'pipe', 'inherit'] })
  let stdout = ''
  child.stdout.setEncoding('utf8')
  child.stdout.on('data', chunk => { stdout += chunk })
  const exitCode = await new Promise((resolve, reject) => {
    child.once('error', reject)
    child.once('close', resolve)
  })
  if (exitCode !== 0) {
    throw new Error(`Verified session map query failed with exit code ${exitCode}`)
  }
  const entries = stdout.trim().split(/\r?\n/).filter(Boolean).map((line, iteration) => {
    const [taskId, sessionId, studentId] = line.trim().split(/\s+/)
    if (!/^\d+$/.test(taskId || '') || !/^\d+$/.test(sessionId || '')
      || !/^\d+$/.test(studentId || '')) {
      throw new Error(`Invalid verified session map row: ${line}`)
    }
    return { iteration, taskId, sessionId, studentId }
  })
  if (entries.length !== users || entries.some((entry, index) => entry.iteration !== index)) {
    throw new Error(`Verified session map contains ${entries.length}/${users} rows`)
  }
  const parent = path.dirname(sessionMapFile)
  fs.mkdirSync(parent, { recursive: true, mode: 0o700 })
  fs.writeFileSync(sessionMapFile, `${JSON.stringify({ examId, entries }, null, 2)}\n`, { mode: 0o600 })
  fs.chmodSync(sessionMapFile, 0o600)
  return { examId, entries }
}
