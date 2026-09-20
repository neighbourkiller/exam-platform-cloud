const pad = (value) => String(value).padStart(2, '0')

const parseDateTimeValue = (value) => {
  if (value == null || value === '') {
    return null
  }
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : new Date(value.getTime())
  }
  if (Array.isArray(value) && value.length >= 3) {
    const [year, month, day, hour = 0, minute = 0, second = 0] = value
    const date = new Date(
      Number(year),
      Number(month) - 1,
      Number(day),
      Number(hour),
      Number(minute),
      Number(second)
    )
    return Number.isNaN(date.getTime()) ? null : date
  }
  if (typeof value === 'number') {
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? null : date
  }
  const text = String(value).trim()
  if (!text) {
    return null
  }
  if (text.endsWith('Z')) {
    const utcDate = new Date(text)
    if (!Number.isNaN(utcDate.getTime())) {
      return utcDate
    }
  }
  const normalized = text
    .replaceAll('：', ':')
    .replace('T', ' ')
    .replace(/\//g, '-')
  const matched = normalized.match(
    /^(\d{4})-(\d{1,2})-(\d{1,2})(?:\s+(\d{1,2}))?(?::(\d{1,2}))?(?::(\d{1,2}))?/
  )
  if (!matched) {
    return null
  }
  const [, year, month, day, hour = '0', minute = '0', second = '0'] = matched
  const date = new Date(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour),
    Number(minute),
    Number(second)
  )
  return Number.isNaN(date.getTime()) ? null : date
}

const dateToText = (date, separator = ' ') => {
  const year = date.getFullYear()
  const month = pad(date.getMonth() + 1)
  const day = pad(date.getDate())
  const hour = pad(date.getHours())
  const minute = pad(date.getMinutes())
  const second = pad(date.getSeconds())
  return `${year}-${month}-${day}${separator}${hour}:${minute}:${second}`
}

export const parseDateTime = (value) => {
  const parsed = parseDateTimeValue(value)
  return parsed ? new Date(parsed.getTime()) : null
}

export const formatDateTime = (value, fallback = '-') => {
  const date = parseDateTimeValue(value)
  return date ? dateToText(date, ' ') : fallback
}

export const normalizeDateTimeToMinute = (value) => {
  const date = parseDateTimeValue(value)
  if (!date) {
    return ''
  }
  date.setSeconds(0, 0)
  return dateToText(date, 'T')
}

export const addMinutesToDateTime = (value, minutes) => {
  const date = parseDateTimeValue(value)
  if (!date) {
    return ''
  }
  const safeMinutes = Number(minutes)
  if (!Number.isFinite(safeMinutes)) {
    return ''
  }
  date.setSeconds(0, 0)
  date.setMinutes(date.getMinutes() + safeMinutes)
  date.setSeconds(0, 0)
  return dateToText(date, 'T')
}
