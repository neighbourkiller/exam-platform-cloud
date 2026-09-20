export const questionTypeLabelMap = {
  SINGLE: '单选',
  MULTI: '多选',
  JUDGE: '判断',
  BLANK: '填空',
  SHORT: '简答'
}

export const questionTypeLabel = (type) => questionTypeLabelMap[type] || type || '-'

export const difficultyLabelMap = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难'
}

export const difficultyLabel = (difficulty) => difficultyLabelMap[difficulty] || difficulty || '-'

export const formatClassNames = (value) => (Array.isArray(value) && value.length ? value.join('、') : '--')

export const formatDuration = (durationMs) => {
  if (!durationMs || durationMs <= 0) {
    return '0秒'
  }
  const totalSeconds = Math.ceil(durationMs / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  if (!minutes) {
    return `${seconds}秒`
  }
  return `${minutes}分${seconds}秒`
}
