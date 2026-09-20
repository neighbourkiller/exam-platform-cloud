import axios from 'axios'
import { ElMessage } from 'element-plus'
import { clearLegacyAuthStorage, useAuthStore } from '../stores/auth'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:16730/api/v1'

const http = axios.create({
  baseURL: API_BASE_URL,
  timeout: 15000,
  withCredentials: true
})

const authHttp = axios.create({
  baseURL: API_BASE_URL,
  timeout: 15000,
  withCredentials: true
})

let refreshPromise = null

const isAuthRoute = (url = '') => url.includes('/auth/login') || url.includes('/auth/refresh') || url.includes('/auth/logout')

const getAuthStore = () => {
  try {
    return useAuthStore()
  } catch {
    return null
  }
}

const buildBusinessError = (message, code = null, responseData = null) => {
  const error = new Error(message || '请求失败')
  error.code = code
  error.responseData = responseData
  error.isBusinessError = true
  return error
}

const clearAuthState = () => {
  const auth = getAuthStore()
  if (auth) {
    auth.clear()
    return
  }
  clearLegacyAuthStorage()
}

const applyAuthPayload = (payload) => {
  const auth = getAuthStore()
  if (!auth) {
    return
  }
  auth.setAuth({
    ...payload,
    userId: auth.userId,
    username: auth.username
  })
}

const redirectToLogin = () => {
  if (window.location.pathname !== '/login') {
    window.location.href = '/login'
  }
}

export const loadCurrentUserProfile = async () => {
  const profile = await http.get('/auth/me')
  const auth = getAuthStore()
  if (auth) {
    auth.setProfile(profile)
  }
  return profile
}

export const refreshAccessToken = async () => {
  if (refreshPromise) {
    return refreshPromise
  }
  refreshPromise = authHttp.post('/auth/refresh')
    .then((response) => {
      const payload = response?.data?.data
      if (!response?.data?.success || !payload?.accessToken) {
        throw buildBusinessError(response?.data?.message || '刷新登录态失败', response?.data?.code || null, response?.data)
      }
      applyAuthPayload(payload)
      return payload.accessToken
    })
    .finally(() => {
      refreshPromise = null
    })
  return refreshPromise
}

export const restoreSession = async () => {
  const auth = getAuthStore()
  if (auth?.isLogin) {
    if (!auth.username || !auth.userId) {
      try {
        await loadCurrentUserProfile()
      } catch {
        // Ignore profile hydration failures during session restore.
      }
    }
    return true
  }

  try {
    await refreshAccessToken()
    if (auth && (!auth.username || !auth.userId)) {
      try {
        await loadCurrentUserProfile()
      } catch {
        // Ignore profile hydration failures during session restore.
      }
    }
    return true
  } catch {
    clearAuthState()
    return false
  }
}

http.interceptors.request.use((config) => {
  const token = getAuthStore()?.token
  if (token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const resp = response.data
    if (resp && resp.success) {
      return resp.data
    }
    if (!response.config?.silent) {
      ElMessage.error(resp?.message || '请求失败')
    }
    return Promise.reject(buildBusinessError(resp?.message || '请求失败', resp?.code || null, resp))
  },
  async (error) => {
    const status = error?.response?.status
    const originalRequest = error?.config || {}
    if (status === 401 && !originalRequest._retry && !isAuthRoute(originalRequest.url)) {
      originalRequest._retry = true
      try {
        const accessToken = await refreshAccessToken()
        originalRequest.headers = originalRequest.headers || {}
        originalRequest.headers.Authorization = `Bearer ${accessToken}`
        return http(originalRequest)
      } catch (refreshError) {
        clearAuthState()
        redirectToLogin()
        ElMessage.error('登录已失效，请重新登录')
        return Promise.reject(refreshError)
      }
    }

    if (status === 401) {
      clearAuthState()
      redirectToLogin()
      ElMessage.error('登录已失效，请重新登录')
      return Promise.reject(error)
    }

    if (!originalRequest.silent) {
      ElMessage.error(error?.response?.data?.message || error.message || '网络异常')
    }
    if (error?.response?.data?.code && !error.code) {
      error.code = error.response.data.code
    }
    if (error?.response?.data) {
      error.responseData = error.response.data
      error.retryAfterMs = Number(error.response.data?.data?.retryAfterMs || 0) || null
    }
    return Promise.reject(error)
  }
)

export default http
