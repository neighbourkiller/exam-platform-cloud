import { formatDateTime } from './datetime'

export const riskLevelLabel = (level) => {
  if (level === 'HIGH') return '高风险'
  if (level === 'MEDIUM') return '中风险'
  if (level === 'LOW') return '低风险'
  return '无异常'
}

export const dispositionLabel = (status) => {
  if (status === 'RESOLVED') return '已处理（违纪）'
  if (status === 'DISMISSED') return '已处理（误报）'
  if (status === 'ESCALATED') return '已上报'
  return '待核查'
}

export const formatClassNames = (value) => (Array.isArray(value) && value.length ? value.join('、') : '--')

export const formatDuration = (durationMs) => {
  if (!durationMs || durationMs <= 0) return '0秒'
  const totalSeconds = Math.ceil(durationMs / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  if (!minutes) return `${seconds}秒`
  return `${minutes}分${seconds}秒`
}

export const formatEventType = (type) => {
  const map = {
    TAB_SWITCH: '切换标签页',
    WINDOW_MINIMIZE: '窗口最小化',
    NETWORK_OFFLINE: '网络断开',
    FULLSCREEN_EXIT: '退出全屏',
    MULTIPLE_FACES: '多张人脸',
    NO_FACE: '未检测到人脸',
    VOICE_DETECTED: '检测到声音',
    DEVICE_CHANGED: '设备变更',
    SYSTEM_SUSPEND: '系统休眠'
  }
  return map[type] || type || '未知异常'
}

export const parsePayloadObject = (payload) => {
  if (!payload) return null
  try {
    const parsed = JSON.parse(payload)
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch {
    return null
  }
}

export const formatPayload = (payload) => {
  if (!payload) return ''
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload
  }
}

export const formatPayloadTime = (value) => {
  if (!value) return ''
  const numberValue = Number(value)
  if (Number.isFinite(numberValue) && numberValue > 0) {
    return formatDateTime(new Date(numberValue).toISOString())
  }
  return formatDateTime(value)
}

export const formatEventContext = (event) => {
  const payload = parsePayloadObject(event.payload)
  if (!payload) return ''
  const parts = []
  if (payload.replayed) parts.push('恢复联网后重放')
  if (payload.occurredAt) parts.push(`原始发生：${formatPayloadTime(payload.occurredAt)}`)
  const offlineDurationMs = Number(payload.offlineDurationMs || 0)
  if (Number.isFinite(offlineDurationMs) && offlineDurationMs > 0) {
    parts.push(`离线约 ${formatDuration(offlineDurationMs)}`)
  }
  if (event.eventType === 'NETWORK_OFFLINE' && event.durationMs) {
    parts.push(`断线 ${formatDuration(event.durationMs)}`)
  }
  return parts.join('，')
}

export const parseEvidence = (evidenceJson) => {
  if (!evidenceJson) return []
  try {
    const parsed = JSON.parse(evidenceJson)
    return Array.isArray(parsed) ? parsed.filter((item) => item?.url) : []
  } catch {
    return []
  }
}

export const evidenceSourceLabel = (source) => {
  if (source === 'SCREEN') return '屏幕截图'
  if (source === 'CAMERA') return '摄像头画面'
  return source || '证据图片'
}
