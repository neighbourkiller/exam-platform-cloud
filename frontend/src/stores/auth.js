import { defineStore } from 'pinia'

const LEGACY_STORAGE_KEYS = ['token', 'refreshToken', 'roles', 'username']

export function clearLegacyAuthStorage() {
  if (typeof window === 'undefined') {
    return
  }
  LEGACY_STORAGE_KEYS.forEach((key) => {
    window.localStorage.removeItem(key)
  })
}

function parseJwtPayload(token) {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4)
    const json = atob(padded)
    return JSON.parse(json)
  } catch {
    return null
  }
}

function isExpired(token) {
  const payload = parseJwtPayload(token)
  if (!payload || !payload.exp) return true
  return payload.exp * 1000 <= Date.now()
}

clearLegacyAuthStorage()

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: '',
    userId: null,
    username: '',
    roles: []
  }),
  getters: {
    isLogin: (state) => !!state.token && !isExpired(state.token),
    isAdmin: (state) => state.roles.includes('ADMIN'),
    isTeacher: (state) => state.roles.includes('TEACHER'),
    isStudent: (state) => state.roles.includes('STUDENT')
  },
  actions: {
    setAuth(payload = {}) {
      this.token = payload.accessToken || ''
      this.roles = payload.roles || []
      if (payload.userId !== undefined) {
        this.userId = payload.userId || null
      }
      if (payload.username !== undefined) {
        this.username = payload.username || ''
      }
      clearLegacyAuthStorage()
    },
    setProfile(profile = {}) {
      if (profile.userId !== undefined) {
        this.userId = profile.userId || null
      }
      this.username = profile.username || this.username || ''
      if (Array.isArray(profile.roles) && profile.roles.length) {
        this.roles = profile.roles
      }
    },
    clear() {
      this.token = ''
      this.userId = null
      this.roles = []
      this.username = ''
      clearLegacyAuthStorage()
    }
  }
})
