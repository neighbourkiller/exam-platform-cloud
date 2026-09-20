import fs from 'node:fs'
import path from 'node:path'
 
const mapFile = process.env.SESSION_MAP_FILE
  ? path.resolve(process.env.SESSION_MAP_FILE)
  : process.argv[2]
const outputFile = process.env.DIAGNOSTIC_SESSION_IDS_FILE
  ? path.resolve(process.env.DIAGNOSTIC_SESSION_IDS_FILE)
  : process.argv[3]
const stride = Number(process.env.DIAGNOSTIC_SAMPLE_STRIDE || process.argv[4] || 10)

if (!mapFile || !outputFile || !Number.isSafeInteger(stride) || stride < 1) {
  throw new Error('用法: select-diagnostic-sessions.mjs MAP_FILE OUTPUT_FILE [STRIDE]')
}

const mapping = JSON.parse(fs.readFileSync(mapFile, 'utf8'))
if (!mapping || !Array.isArray(mapping.entries) || mapping.entries.length === 0) {
  throw new Error('SESSION_MAP_FILE 缺少已核对的 entries')
}

const selected = mapping.entries.filter(entry => entry.iteration % stride === 0)
if (selected.length === 0) {
  throw new Error('诊断抽样没有选中会话')
}
for (const entry of selected) {
  if (!/^\d+$/.test(String(entry.sessionId))) {
    throw new Error(`诊断映射中的 sessionId 非法: ${entry.sessionId}`)
  }
}

const parent = path.dirname(outputFile)
fs.mkdirSync(parent, { recursive: true, mode: 0o700 })
fs.writeFileSync(
  outputFile,
  `${selected.map(entry => `${entry.studentId}\t${entry.sessionId}\t${entry.taskId}\t${entry.iteration}`).join('\n')}\n`,
  { mode: 0o600 }
)
fs.chmodSync(outputFile, 0o600)
process.stdout.write(`${JSON.stringify({
  source: path.basename(mapFile),
  output: path.basename(outputFile),
  stride,
  selectedCount: selected.length,
  firstIteration: selected[0].iteration,
  lastIteration: selected.at(-1).iteration
})}\n`)
