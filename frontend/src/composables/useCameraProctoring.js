import { reactive } from 'vue'
import { checkScreenDetails, consumeRetainedScreenShareStream } from '../utils/preExamCheck'

const DARK_FRAME_INTERVAL_MS = 5000
const DARK_FRAME_LIMIT = 3
const DARK_LUMINANCE_THRESHOLD = 18
const EVIDENCE_WIDTH = 1280
const EVIDENCE_MIME_TYPE = 'image/jpeg'
const EVIDENCE_QUALITY = 0.78

const CAMERA_EVENT_TYPES = new Set([
  'CAMERA_START_FAILED',
  'CAMERA_STREAM_ENDED',
  'CAMERA_TRACK_MUTED',
  'CAMERA_FRAME_DARK'
])

const createVideoForStream = async (mediaStream) => {
  if (!mediaStream) {
    return null
  }
  const element = document.createElement('video')
  element.muted = true
  element.playsInline = true
  element.srcObject = mediaStream
  await element.play().catch(() => {})
  return element
}

const captureVideoFrame = (videoElement) => new Promise((resolve, reject) => {
  if (!videoElement || videoElement.readyState < 2 || !videoElement.videoWidth || !videoElement.videoHeight) {
    reject(new Error('video frame unavailable'))
    return
  }
  const ratio = videoElement.videoWidth / videoElement.videoHeight
  const width = Math.min(EVIDENCE_WIDTH, videoElement.videoWidth)
  const height = Math.round(width / ratio)
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const context = canvas.getContext('2d')
  if (!context) {
    reject(new Error('canvas unavailable'))
    return
  }
  context.drawImage(videoElement, 0, 0, width, height)
  canvas.toBlob((blob) => {
    if (!blob) {
      reject(new Error('frame encoding failed'))
      return
    }
    resolve(blob)
  }, EVIDENCE_MIME_TYPE, EVIDENCE_QUALITY)
})

export const useCameraProctoring = ({ reportEvent, uploadEvidence }) => {
  const state = reactive({
    status: 'idle',
    title: '摄像头待启动',
    detail: '进入考试后开启监控。',
    screenCount: null,
    screenCheckSupported: false,
    screenShareActive: false,
    darkFrameCount: 0,
    lastEventAt: null
  })

  let cameraStream = null
  let screenStream = null
  let cameraVideo = null
  let screenVideo = null
  let sampleCanvas = null
  let darkFrameTimer = null
  let screenDetails = null
  let screenChangeHandler = null
  let stopped = false
  let screenIssueActive = false
  let activePolicy = {}

  const getVideoTrack = () => cameraStream?.getVideoTracks?.()[0] || null
  const getScreenTrack = () => screenStream?.getVideoTracks?.()[0] || null

  const updateState = (status, title, detail) => {
    state.status = status
    state.title = title
    state.detail = detail
  }

  const buildPayload = (extra = {}) => {
    const cameraTrack = getVideoTrack()
    const screenTrack = getScreenTrack()
    return {
      screenCount: state.screenCount,
      screenCheckSupported: state.screenCheckSupported,
      screenShareActive: state.screenShareActive,
      screenTrackState: screenTrack?.readyState || null,
      cameraTrackState: cameraTrack?.readyState || null,
      cameraMuted: Boolean(cameraTrack?.muted),
      darkFrameCount: state.darkFrameCount,
      ...extra
    }
  }

  const captureEvidence = async (eventType) => {
    const evidence = []
    const errors = []

    if (screenVideo) {
      try {
        const blob = await captureVideoFrame(screenVideo)
        evidence.push(await uploadEvidence(blob, 'SCREEN', eventType))
      } catch (error) {
        errors.push(`screen:${error?.message || 'capture failed'}`)
      }
    } else {
      errors.push('screen:stream unavailable')
    }

    if (CAMERA_EVENT_TYPES.has(eventType) && cameraVideo) {
      try {
        const blob = await captureVideoFrame(cameraVideo)
        evidence.push(await uploadEvidence(blob, 'CAMERA', eventType))
      } catch (error) {
        errors.push(`camera:${error?.message || 'capture failed'}`)
      }
    }

    return { evidence, errors }
  }

  const report = async (eventType, durationMs = 0, extra = {}, options = {}) => {
    if (!shouldReportEvent(eventType)) {
      return
    }
    state.lastEventAt = Date.now()
    updateState('danger', '监控异常已记录', eventType)
    const evidenceResult = options.skipEvidence || activePolicy.captureEvidence === false
      ? { evidence: [], errors: [] }
      : await captureEvidence(eventType)
    await reportEvent(
      eventType,
      durationMs,
      buildPayload({
        ...extra,
        evidenceUploadError: evidenceResult.errors.length ? evidenceResult.errors.join('; ') : undefined
      }),
      evidenceResult.evidence
    )
  }

  const shouldReportEvent = (eventType) => {
    if (!activePolicy) {
      return true
    }
    if (eventType?.startsWith('CAMERA_')) {
      return activePolicy.requireCamera !== false
    }
    if (eventType?.startsWith('SCREEN_SHARE_')) {
      return activePolicy.requireScreenShare !== false
    }
    if (eventType === 'MULTI_MONITOR_DETECTED' || eventType === 'SCREEN_CHECK_UNAVAILABLE') {
      return activePolicy.blockMultiMonitor !== false
    }
    return true
  }

  const inspectScreens = async () => {
    if (activePolicy.blockMultiMonitor === false) {
      return
    }
    const result = await checkScreenDetails()
    state.screenCount = result.screenCount ?? null
    state.screenCheckSupported = result.screenCount != null

    if (result.status !== 'failed') {
      screenIssueActive = false
      if (cameraStream) {
        updateState('success', '摄像头监控中', '摄像头与屏幕共享持续检测。')
      }
      return
    }
    screenIssueActive = true
    if (Number(result.screenCount || 0) > 1) {
      await report('MULTI_MONITOR_DETECTED', 0, {
        screenCount: result.screenCount,
        screenDetail: result.detail
      })
      return
    }
    await report('SCREEN_CHECK_UNAVAILABLE', 0, {
      screenDetail: result.detail
    })
  }

  const bindScreenChange = async () => {
    if (activePolicy.blockMultiMonitor === false) {
      return
    }
    if (!window.isSecureContext || typeof window.getScreenDetails !== 'function') {
      return
    }
    try {
      screenDetails = await window.getScreenDetails()
      if (typeof screenDetails?.addEventListener !== 'function') {
        return
      }
      screenChangeHandler = () => {
        void inspectScreens()
      }
      screenDetails.addEventListener('screenschange', screenChangeHandler)
    } catch {
      // The initial strict check already records the unavailable state.
    }
  }

  const startScreenShare = async () => {
    if (activePolicy.requireScreenShare === false) {
      state.screenShareActive = false
      return
    }
    const retainedStream = consumeRetainedScreenShareStream()
    if (retainedStream?.getVideoTracks?.()[0]?.readyState === 'live') {
      screenStream = retainedStream
    } else if (!navigator.mediaDevices?.getDisplayMedia) {
      await report('SCREEN_SHARE_START_FAILED', 0, {
        reason: 'mediaDevices.getDisplayMedia unavailable'
      }, { skipEvidence: true })
      return
    } else {
      try {
        screenStream = await navigator.mediaDevices.getDisplayMedia({
          video: {
            displaySurface: 'monitor'
          },
          audio: false
        })
      } catch (error) {
        await report('SCREEN_SHARE_START_FAILED', 0, {
          reason: error?.name || error?.message || 'screen share start failed'
        }, { skipEvidence: true })
        return
      }
    }

    try {
      const track = getScreenTrack()
      const displaySurface = track?.getSettings?.().displaySurface
      if (displaySurface && displaySurface !== 'monitor') {
        await report('SCREEN_SHARE_START_FAILED', 0, {
          reason: 'screen share is not entire monitor',
          displaySurface
        }, { skipEvidence: true })
        screenStream?.getTracks?.().forEach((item) => item.stop())
        screenStream = null
        state.screenShareActive = false
        return
      }
      state.screenShareActive = true
      screenVideo = await createVideoForStream(screenStream)
      if (track) {
        track.onended = () => {
          state.screenShareActive = false
          if (!stopped) {
            void report('SCREEN_SHARE_ENDED', 0, {}, { skipEvidence: true })
          }
        }
      }
    } catch (error) {
      await report('SCREEN_SHARE_START_FAILED', 0, {
        reason: error?.name || error?.message || 'screen share start failed'
      }, { skipEvidence: true })
    }
  }

  const sampleFrame = async () => {
    if (!cameraVideo || !sampleCanvas || cameraVideo.readyState < 2) {
      return
    }
    const width = 32
    const height = 18
    sampleCanvas.width = width
    sampleCanvas.height = height
    const context = sampleCanvas.getContext('2d', { willReadFrequently: true })
    if (!context) {
      return
    }
    try {
      context.drawImage(cameraVideo, 0, 0, width, height)
      const data = context.getImageData(0, 0, width, height).data
      let luminance = 0
      for (let i = 0; i < data.length; i += 4) {
        luminance += (data[i] * 0.2126) + (data[i + 1] * 0.7152) + (data[i + 2] * 0.0722)
      }
      luminance /= data.length / 4
      if (luminance < DARK_LUMINANCE_THRESHOLD) {
        state.darkFrameCount += 1
      } else {
        state.darkFrameCount = 0
      }
      if (state.darkFrameCount >= DARK_FRAME_LIMIT) {
        await report('CAMERA_FRAME_DARK', DARK_FRAME_INTERVAL_MS * state.darkFrameCount, {
          averageLuminance: Math.round(luminance)
        })
        state.darkFrameCount = 0
      }
    } catch {
      // Canvas sampling can fail transiently while the track is switching state.
    }
  }

  const startFrameMonitor = async () => {
    cameraVideo = await createVideoForStream(cameraStream)
    sampleCanvas = document.createElement('canvas')
    darkFrameTimer = window.setInterval(() => {
      void sampleFrame()
    }, DARK_FRAME_INTERVAL_MS)
  }

  const bindTrackEvents = () => {
    const track = getVideoTrack()
    if (!track) {
      return
    }
    track.onended = () => {
      if (!stopped) {
        void report('CAMERA_STREAM_ENDED', 0)
      }
    }
    track.onmute = () => {
      if (!stopped) {
        void report('CAMERA_TRACK_MUTED', 0)
      }
    }
    track.onunmute = () => {
      if (!stopped) {
        updateState('success', '摄像头监控中', '摄像头画面已恢复。')
      }
    }
  }

  const start = async (policy = {}) => {
    activePolicy = policy || {}
    stopped = false
    await startScreenShare()
    await inspectScreens()
    await bindScreenChange()

    if (activePolicy.requireCamera === false) {
      updateState('success', '监控策略已生效', '本场考试未要求摄像头监控。')
      return
    }

    if (!navigator.mediaDevices?.getUserMedia) {
      await report('CAMERA_START_FAILED', 0, {
        reason: 'mediaDevices.getUserMedia unavailable'
      })
      return
    }

    try {
      cameraStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false })
      bindTrackEvents()
      await startFrameMonitor()
      if (!screenIssueActive) {
        updateState('success', '摄像头监控中', '摄像头与屏幕共享持续检测。')
      }
    } catch (error) {
      await report('CAMERA_START_FAILED', 0, {
        reason: error?.name || error?.message || 'camera start failed'
      })
    }
  }

  const stop = () => {
    stopped = true
    if (darkFrameTimer) {
      window.clearInterval(darkFrameTimer)
      darkFrameTimer = null
    }
    if (screenDetails && screenChangeHandler && typeof screenDetails.removeEventListener === 'function') {
      screenDetails.removeEventListener('screenschange', screenChangeHandler)
    }
    screenChangeHandler = null
    screenDetails = null
    cameraStream?.getTracks?.().forEach((track) => track.stop())
    screenStream?.getTracks?.().forEach((track) => track.stop())
    cameraStream = null
    screenStream = null
    state.screenShareActive = false
    if (cameraVideo) {
      cameraVideo.srcObject = null
    }
    if (screenVideo) {
      screenVideo.srcObject = null
    }
    cameraVideo = null
    screenVideo = null
    sampleCanvas = null
    updateState('idle', '摄像头已关闭', '考试结束后停止监控。')
  }

  return {
    state,
    start,
    stop,
    captureEvidence
  }
}
