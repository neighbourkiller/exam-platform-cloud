const HEALTH_PATH = '/actuator/health'
const NETWORK_SAMPLE_COUNT = 3
const NETWORK_PASS_THRESHOLD_MS = 800
const NETWORK_WARNING_THRESHOLD_MS = 2000
const NETWORK_REQUEST_TIMEOUT_MS = 3000

const activeStreams = []
let retainedScreenShareStream = null

export const CHECK_DEFINITIONS = [
  { key: 'browser', label: '浏览器兼容性', pendingDetail: '等待检测浏览器能力。' },
  { key: 'network', label: '网络连接', pendingDetail: '等待检测考试服务器连接。' },
  { key: 'screen', label: '显示器数量', pendingDetail: '等待检测是否连接多个显示器。' },
  { key: 'screenShare', label: '屏幕共享授权', pendingDetail: '等待授权共享整个屏幕。' },
  { key: 'camera', label: '摄像头', pendingDetail: '等待检测摄像头权限和设备。' },
  { key: 'microphone', label: '麦克风', pendingDetail: '等待检测麦克风权限和设备。' }
]

const createResult = ({ key, label, status, detail, blocking = false, latencyMs = null, ...extra }) => ({
  key,
  label,
  status,
  detail,
  blocking,
  latencyMs,
  ...extra
})

const disabledResult = (key, label) => createResult({
  key,
  label,
  status: 'passed',
  detail: '当前考试策略未启用该项强制检测。'
})

const getApiBaseUrl = () =>
  import.meta.env.VITE_API_BASE_URL || 'http://localhost:16730/api/v1'

export const deriveBackendBaseUrl = () => {
  const apiBaseUrl = getApiBaseUrl().replace(/\/+$/, '')
  if (apiBaseUrl.endsWith('/api/v1')) {
    return apiBaseUrl.slice(0, -'/api/v1'.length) || window.location.origin
  }
  if (apiBaseUrl.endsWith('/api')) {
    return apiBaseUrl.slice(0, -'/api'.length) || window.location.origin
  }
  return apiBaseUrl || window.location.origin
}

const stopStream = (stream) => {
  stream?.getTracks?.().forEach((track) => track.stop())
}

export const releaseMediaStreams = ({ includeRetainedScreenShare = true } = {}) => {
  while (activeStreams.length) {
    const stream = activeStreams.pop()
    stopStream(stream)
  }
  if (includeRetainedScreenShare) {
    stopStream(retainedScreenShareStream)
    retainedScreenShareStream = null
  }
}

export const consumeRetainedScreenShareStream = () => {
  const stream = retainedScreenShareStream
  retainedScreenShareStream = null
  return stream
}

const describeMediaError = (error, deviceLabel) => {
  if (error?.name === 'NotAllowedError' || error?.name === 'PermissionDeniedError') {
    return `${deviceLabel}权限未授权，请在浏览器地址栏允许访问后重新检测。`
  }
  if (error?.name === 'NotFoundError' || error?.name === 'DevicesNotFoundError') {
    return `未检测到可用${deviceLabel}，请连接设备后重新检测。`
  }
  if (error?.name === 'NotReadableError' || error?.name === 'TrackStartError') {
    return `${deviceLabel}可能被其他软件占用，请关闭占用设备的程序后重新检测。`
  }
  return `${deviceLabel}检测失败，请检查浏览器权限和设备连接。`
}

const checkMediaDevice = async ({ key, label, constraints }) => {
  if (!navigator.mediaDevices?.getUserMedia) {
    return createResult({
      key,
      label,
      status: 'failed',
      detail: '当前浏览器不支持媒体设备检测，请更换新版 Chrome、Edge 或 Safari。',
      blocking: true
    })
  }

  try {
    const stream = await navigator.mediaDevices.getUserMedia(constraints)
    activeStreams.push(stream)
    releaseMediaStreams({ includeRetainedScreenShare: false })
    return createResult({
      key,
      label,
      status: 'passed',
      detail: `${label}可用，权限已确认。`
    })
  } catch (error) {
    return createResult({
      key,
      label,
      status: 'failed',
      detail: describeMediaError(error, label),
      blocking: true
    })
  }
}

const checkScreenShare = async ({ retain = false } = {}) => {
  if (!navigator.mediaDevices?.getDisplayMedia) {
    return createResult({
      key: 'screenShare',
      label: '屏幕共享授权',
      status: 'failed',
      detail: '当前浏览器不支持屏幕共享授权，请更换新版 Chrome 或 Edge。',
      blocking: true
    })
  }

  try {
    const stream = await navigator.mediaDevices.getDisplayMedia({
      video: {
        displaySurface: 'monitor'
      },
      audio: false
    })
    const track = stream.getVideoTracks?.()[0]
    const displaySurface = track?.getSettings?.().displaySurface
    if (displaySurface && displaySurface !== 'monitor') {
      stopStream(stream)
      return createResult({
        key: 'screenShare',
        label: '屏幕共享授权',
        status: 'failed',
        detail: '请共享整个屏幕，而不是单个窗口或浏览器标签页。',
        blocking: true
      })
    }
    if (retain) {
      stopStream(retainedScreenShareStream)
      retainedScreenShareStream = stream
    } else {
      activeStreams.push(stream)
      releaseMediaStreams({ includeRetainedScreenShare: false })
    }
    return createResult({
      key: 'screenShare',
      label: '屏幕共享授权',
      status: 'passed',
      detail: '屏幕共享授权已确认，正式考试中会持续保持共享。'
    })
  } catch (error) {
    return createResult({
      key: 'screenShare',
      label: '屏幕共享授权',
      status: 'failed',
      detail: error?.name === 'NotAllowedError'
        ? '屏幕共享授权被拒绝，请选择共享整个屏幕后重新检测。'
        : '屏幕共享检测失败，请确认浏览器权限后重新检测。',
      blocking: true
    })
  }
}

const checkBrowserCompatibility = () => {
  const missing = []
  const warnings = []
  if (!window.indexedDB) missing.push('本地草稿存储')
  if (!window.fetch) missing.push('网络请求能力')
  if (!window.Promise) missing.push('异步能力')
  if (!navigator.mediaDevices?.getUserMedia) missing.push('摄像头/麦克风访问能力')
  if (!document.fullscreenEnabled) warnings.push('全屏模式')
  if (typeof navigator.onLine !== 'boolean') warnings.push('联网状态识别')

  if (missing.length) {
    return createResult({
      key: 'browser',
      label: '浏览器兼容性',
      status: 'failed',
      detail: `缺少${missing.join('、')}，请更换新版 Chrome、Edge 或 Safari。`,
      blocking: true
    })
  }

  if (warnings.length) {
    return createResult({
      key: 'browser',
      label: '浏览器兼容性',
      status: 'warning',
      detail: `${warnings.join('、')}可能不可用，考试可继续，但建议更换或升级浏览器。`
    })
  }

  return createResult({
    key: 'browser',
    label: '浏览器兼容性',
    status: 'passed',
    detail: '当前浏览器支持考试所需能力。'
  })
}

const requestHealth = async (url) => {
  const startedAt = performance.now()
  const controller = new AbortController()
  const timeoutId = window.setTimeout(() => controller.abort(), NETWORK_REQUEST_TIMEOUT_MS)
  try {
    const response = await fetch(url, {
      method: 'GET',
      cache: 'no-store',
      credentials: 'include',
      signal: controller.signal
    })
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    return Math.round(performance.now() - startedAt)
  } finally {
    window.clearTimeout(timeoutId)
  }
}

const checkNetwork = async () => {
  if (!navigator.onLine) {
    return createResult({
      key: 'network',
      label: '网络连接',
      status: 'failed',
      detail: '浏览器显示当前处于离线状态，请恢复网络后重新检测。',
      blocking: true
    })
  }

  const baseUrl = deriveBackendBaseUrl()
  const latencies = []
  const errors = []
  for (let i = 0; i < NETWORK_SAMPLE_COUNT; i += 1) {
    try {
      const url = `${baseUrl}${HEALTH_PATH}?t=${Date.now()}-${i}`
      latencies.push(await requestHealth(url))
    } catch (error) {
      errors.push(error)
    }
  }

  if (!latencies.length) {
    return createResult({
      key: 'network',
      label: '网络连接',
      status: 'failed',
      detail: `无法连接考试服务器健康检查接口，请确认网络或稍后重试。${errors[0]?.message || ''}`,
      blocking: true
    })
  }

  const averageLatency = Math.round(latencies.reduce((sum, item) => sum + item, 0) / latencies.length)
  if (averageLatency <= NETWORK_PASS_THRESHOLD_MS) {
    return createResult({
      key: 'network',
      label: '网络连接',
      status: 'passed',
      detail: `考试服务器连接正常，平均延迟 ${averageLatency}ms。`,
      latencyMs: averageLatency
    })
  }

  if (averageLatency <= NETWORK_WARNING_THRESHOLD_MS) {
    return createResult({
      key: 'network',
      label: '网络连接',
      status: 'warning',
      detail: `网络延迟偏高，平均 ${averageLatency}ms。考试可继续，建议保持网络稳定。`,
      latencyMs: averageLatency
    })
  }

  return createResult({
    key: 'network',
    label: '网络连接',
    status: 'failed',
    detail: `网络延迟过高，平均 ${averageLatency}ms。请切换稳定网络后重新检测。`,
    blocking: true,
    latencyMs: averageLatency
  })
}

export const checkScreenDetails = async () => {
  if (!window.isSecureContext) {
    return createResult({
      key: 'screen',
      label: '显示器数量',
      status: 'failed',
      detail: '当前页面不是安全上下文，无法可靠检测显示器数量。请使用 HTTPS 或 localhost 后重新检测。',
      blocking: true
    })
  }
  if (typeof window.getScreenDetails !== 'function') {
    return createResult({
      key: 'screen',
      label: '显示器数量',
      status: 'failed',
      detail: '当前浏览器不支持可靠的多显示器检测，请更换支持 Window Management API 的浏览器。',
      blocking: true
    })
  }

  try {
    const details = await window.getScreenDetails()
    const screenCount = Array.isArray(details?.screens) ? details.screens.length : 0
    if (screenCount === 1) {
      return createResult({
        key: 'screen',
        label: '显示器数量',
        status: 'passed',
        detail: '仅检测到 1 个显示器，符合考试要求。',
        screenCount
      })
    }
    if (screenCount > 1) {
      return createResult({
        key: 'screen',
        label: '显示器数量',
        status: 'failed',
        detail: `检测到 ${screenCount} 个显示器，请关闭副屏或断开外接显示器后重新检测。`,
        blocking: true,
        screenCount
      })
    }
    return createResult({
      key: 'screen',
      label: '显示器数量',
      status: 'failed',
      detail: '浏览器未返回有效的显示器数量，请检查浏览器权限后重新检测。',
      blocking: true
    })
  } catch (error) {
    const denied = error?.name === 'NotAllowedError' || error?.name === 'SecurityError'
    return createResult({
      key: 'screen',
      label: '显示器数量',
      status: 'failed',
      detail: denied
        ? '显示器信息权限未授权，无法确认是否有多个显示器，请授权后重新检测。'
        : '显示器数量检测失败，请关闭副屏并重新检测。',
      blocking: true
    })
  }
}

export const runPreExamCheck = async ({ retainScreenShare = false, policy = {} } = {}) => {
  releaseMediaStreams()
  const browser = checkBrowserCompatibility()
  const network = await checkNetwork()
  const screen = policy.blockMultiMonitor === false
    ? disabledResult('screen', '显示器数量')
    : await checkScreenDetails()
  const screenShare = policy.requireScreenShare === false
    ? disabledResult('screenShare', '屏幕共享授权')
    : await checkScreenShare({ retain: retainScreenShare })
  const camera = policy.requireCamera === false
    ? disabledResult('camera', '摄像头')
    : await checkMediaDevice({
        key: 'camera',
        label: '摄像头',
        constraints: { video: true }
      })
  const microphone = policy.requireMicrophone === false
    ? disabledResult('microphone', '麦克风')
    : await checkMediaDevice({
        key: 'microphone',
        label: '麦克风',
        constraints: { audio: true }
      })
  releaseMediaStreams({ includeRetainedScreenShare: !retainScreenShare })

  return [browser, network, screen, screenShare, camera, microphone]
}
