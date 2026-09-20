<template>
  <div class="analytics-dashboard" v-loading="loading">
    <header class="dashboard-header">
      <div class="header-left">
        <div class="title-wrapper">
          <div class="title-icon">
            <el-icon><DataLine /></el-icon>
          </div>
          <div>
            <h1 class="dashboard-title">考试数据分析看板</h1>
            <p class="dashboard-subtitle">
              <span v-if="selectedExam" class="exam-name">
                {{ selectedExam.name }} 
                <span class="exam-id">#{{ selectedExam.examId }}</span>
              </span>
              <span v-else>请选择一场考试以查看多维数据分析报告</span>
            </p>
          </div>
        </div>
      </div>
      <div class="header-actions">
        <el-select v-model="selectedExamId" filterable placeholder="检索或选择分析考试" class="exam-selector" size="large">
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
          <el-option v-for="exam in examOptions" :key="exam.examId" :label="exam.name" :value="String(exam.examId)">
            <span class="id-tag">ID:{{ exam.examId }}</span> {{ exam.name }}
          </el-option>
        </el-select>
        <el-button-group class="action-group">
          <el-button @click="loadAll" :icon="Refresh" size="large" class="refresh-btn">刷新</el-button>
          <el-dropdown @command="handleExport">
            <el-button type="primary" size="large" class="export-btn">
              数据导出<el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu class="modern-dropdown">
                <el-dropdown-item command="csv">CSV 原始数据</el-dropdown-item>
                <el-dropdown-item command="scores">学生成绩列表</el-dropdown-item>
                <el-dropdown-item divided command="dist">成绩分布报告</el-dropdown-item>
                <el-dropdown-item command="trend">班级趋势报告</el-dropdown-item>
                <el-dropdown-item command="wrong">错题统计报告</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </el-button-group>
      </div>
    </header>

    <div v-if="!examOptions.length" class="empty-state">
      <el-empty description="暂无已结束或进行中的考试数据" image-size="200" />
    </div>

    <main v-else class="dashboard-content">
      <section class="metrics-grid">
        <div v-for="item in overviewCards" :key="item.key" class="metric-card">
          <div class="metric-header">
            <div class="metric-icon" :style="{ color: item.color, backgroundColor: item.bgColor }">
              <el-icon><component :is="item.icon" /></el-icon>
            </div>
            <span class="metric-label">{{ item.label }}</span>
          </div>
          <div class="metric-body">
            <div class="metric-value">
              {{ item.value }}<span class="metric-suffix">{{ item.suffix }}</span>
            </div>
          </div>
        </div>
      </section>

      <section class="score-list-panel">
        <div class="section-heading">
          <div>
            <h2>学生成绩列表</h2>
            <p>按目标班级汇总学生提交状态、客观分、主观分与总分。</p>
          </div>
          <div class="score-tools">
            <el-input
              v-model="scoreKeyword"
              :prefix-icon="Search"
              clearable
              placeholder="搜索姓名、学号或账号"
              class="score-search"
            />
            <el-select v-model="scoreStatusFilter" placeholder="提交状态" class="score-status-filter">
              <el-option
                v-for="item in scoreStatusOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </div>
        </div>

        <div class="score-summary-row">
          <div class="score-summary-item">
            <span>应考学生</span>
            <strong>{{ scoreSummary.total }}</strong>
          </div>
          <div class="score-summary-item">
            <span>已交卷</span>
            <strong>{{ scoreSummary.submitted }}</strong>
          </div>
          <div class="score-summary-item">
            <span>已出分</span>
            <strong>{{ scoreSummary.graded }}</strong>
          </div>
          <div class="score-summary-item is-muted">
            <span>未交卷</span>
            <strong>{{ scoreSummary.notSubmitted }}</strong>
          </div>
        </div>

        <el-table
          :data="filteredScoreRows"
          row-key="studentId"
          class="score-table"
          empty-text="暂无学生成绩数据"
        >
          <el-table-column type="index" label="#" width="56" />
          <el-table-column label="学生" min-width="180">
            <template #default="{ row }">
              <div class="student-cell">
                <span class="student-avatar">{{ studentInitial(row) }}</span>
                <div>
                  <strong>{{ row.studentName || row.username || '未命名学生' }}</strong>
                  <span>{{ row.username || '--' }}</span>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="studentNo" label="学号" min-width="130">
            <template #default="{ row }">{{ row.studentNo || '--' }}</template>
          </el-table-column>
          <el-table-column label="班级" min-width="180">
            <template #default="{ row }">{{ formatClassNames(row.classNames) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="108">
            <template #default="{ row }">
              <el-tag :type="submissionStatusTagType(row)" effect="light" round>
                {{ submissionStatusText(row) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="客观 / 主观" width="128">
            <template #default="{ row }">
              <span class="score-pair">{{ formatScore(row.objectiveScore) }} / {{ formatScore(row.subjectiveScore) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="总分" width="170">
            <template #default="{ row }">
              <div class="score-meter">
                <strong :class="scoreTextClass(row)">{{ formatScore(row.totalScore) }}</strong>
                <el-progress
                  :percentage="scorePercent(row.totalScore)"
                  :show-text="false"
                  :stroke-width="6"
                  :color="scoreProgressColor(row)"
                />
              </div>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="92">
            <template #default="{ row }">
              <el-tag :type="passTagType(row)" effect="plain" round>{{ passText(row) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="提交时间" min-width="168">
            <template #default="{ row }">{{ formatDateTime(row.submittedAt, '--') }}</template>
          </el-table-column>
        </el-table>
      </section>

      <section class="charts-layout">
        <div class="chart-container distribution-chart">
          <div class="chart-header">
            <div class="chart-title-group">
              <h3>成绩段分布</h3>
              <span class="chart-tip">展示不同分值区间的学生人数比例</span>
            </div>
          </div>
          <div ref="distRef" class="echart-view" role="img" aria-label="成绩段分布柱状图"></div>
        </div>

        <div class="chart-container trend-chart">
          <div class="chart-header">
            <div class="chart-title-group">
              <h3>班级均分对比</h3>
              <span class="chart-tip">不同教学班级的横向对比分析</span>
            </div>
          </div>
          <div ref="trendRef" class="echart-view" role="img" aria-label="班级均分对比图"></div>
        </div>

        <div class="chart-container wrong-rate-chart full-width">
          <div class="chart-header">
            <div class="chart-title-group">
              <h3>高频错题榜单</h3>
              <span class="chart-tip">按错题率从高到低排序，辅助靶向教学</span>
            </div>
            <div class="chart-header-actions">
              <span class="filter-label">显示数量:</span>
              <el-radio-group v-model="topN" size="small" class="modern-radio">
                <el-radio-button :label="5">Top 5</el-radio-button>
                <el-radio-button :label="10">Top 10</el-radio-button>
                <el-radio-button :label="20">Top 20</el-radio-button>
              </el-radio-group>
            </div>
          </div>
          <div ref="wrongRef" class="echart-view wide" role="img" aria-label="高频错题榜单横向柱状图"></div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, markRaw } from 'vue'
import * as echarts from 'echarts'
import { ArrowDown, Refresh, User, Trophy, DataLine, Histogram, CircleCheck, Warning, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { analyticsOverviewApi, classTrendApi, scoreDistributionApi, studentScoresApi, teacherExamsApi, wrongTopicsApi } from '../../api'
import { formatDateTime } from '../../utils/datetime'

const selectedExamId = ref('')
const examOptions = ref([])
const topN = ref(10)
const loading = ref(false)
const scoreKeyword = ref('')
const scoreStatusFilter = ref('ALL')

const overview = ref({ totalStudents: 0, passCount: 0, passRate: 0, avgScore: 0, maxScore: null, minScore: null })
const distData = ref([])
const trendData = ref([])
const wrongData = ref([])
const studentScoreRows = ref([])

const distRef = ref(null)
const trendRef = ref(null)
const wrongRef = ref(null)
let distChart, trendChart, wrongChart

const overviewCards = computed(() => [
  { key: 'total', label: '参考总人数', value: overview.value.totalStudents ?? 0, suffix: '人', icon: markRaw(User), color: '#3b82f6', bgColor: 'rgba(59, 130, 246, 0.1)' },
  { key: 'avg', label: '全级平均分', value: formatNum(overview.value.avgScore), suffix: '分', icon: markRaw(DataLine), color: '#8b5cf6', bgColor: 'rgba(139, 92, 246, 0.1)' },
  { key: 'passRate', label: '整体及格率', value: formatNum(overview.value.passRate), suffix: '%', icon: markRaw(CircleCheck), color: '#10b981', bgColor: 'rgba(16, 185, 129, 0.1)' },
  { key: 'passCount', label: '达标及格数', value: overview.value.passCount ?? 0, suffix: '人', icon: markRaw(Trophy), color: '#f59e0b', bgColor: 'rgba(245, 158, 11, 0.1)' },
  { key: 'max', label: '最高得分', value: overview.value.maxScore ?? '-', suffix: '分', icon: markRaw(Histogram), color: '#ef4444', bgColor: 'rgba(239, 68, 68, 0.1)' },
  { key: 'min', label: '最低得分', value: overview.value.minScore ?? '-', suffix: '分', icon: markRaw(Warning), color: '#64748b', bgColor: 'rgba(100, 116, 139, 0.1)' }
])

const selectedExam = computed(() => examOptions.value.find(e => String(e.examId) === String(selectedExamId.value)))

const formatNum = (v) => v != null ? (Number.isInteger(v) ? v : v.toFixed(1)) : '0'

const scoreStatusOptions = [
  { label: '全部状态', value: 'ALL' },
  { label: '未交卷', value: 'NOT_SUBMITTED' },
  { label: '作答中', value: 'IN_PROGRESS' },
  { label: '判分中', value: 'PROCESSING' },
  { label: '待阅卷', value: 'SUBMITTED' },
  { label: '已出分', value: 'GRADED' }
]

const scoreSummary = computed(() => {
  const rows = studentScoreRows.value
  return {
    total: rows.length,
    submitted: rows.filter(item => item.submitted).length,
    graded: rows.filter(item => item.submissionStatus === 'GRADED').length,
    notSubmitted: rows.filter(item => !item.submitted).length
  }
})

const filteredScoreRows = computed(() => {
  const keyword = scoreKeyword.value.trim().toLowerCase()
  return studentScoreRows.value.filter((row) => {
    const status = row.submissionStatus || 'NOT_SUBMITTED'
    if (scoreStatusFilter.value !== 'ALL' && status !== scoreStatusFilter.value) {
      return false
    }
    if (!keyword) {
      return true
    }
    const text = [
      row.studentName,
      row.studentNo,
      row.username,
      ...(row.classNames || [])
    ].filter(Boolean).join(' ').toLowerCase()
    return text.includes(keyword)
  })
})

const exportLabels = {
  csv: 'CSV 原始数据',
  scores: '学生成绩列表',
  dist: '成绩分布报告',
  trend: '班级趋势报告',
  wrong: '错题统计报告'
}

const escapeCsvValue = (value) => {
  if (value == null) return ''
  const text = String(value).replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

const toCsvContent = (rows) => rows.map(row => row.map(escapeCsvValue).join(',')).join('\r\n')

const safeFilePart = (value) => String(value || '未命名考试')
  .replace(/[\\/:*?"<>|]/g, '_')
  .replace(/\s+/g, '_')
  .slice(0, 60)

const buildFileName = (command) => {
  const exam = selectedExam.value
  const examId = exam?.examId ?? selectedExamId.value
  const examName = safeFilePart(exam?.name)
  const label = safeFilePart(exportLabels[command])
  return `考试数据_${examId}_${examName}_${label}.csv`
}

const buildExamInfoRows = () => {
  const exam = selectedExam.value
  return [
    ['考试信息'],
    ['考试ID', exam?.examId ?? selectedExamId.value],
    ['考试名称', exam?.name ?? ''],
    ['导出时间', new Date().toLocaleString()]
  ]
}

const buildOverviewRows = () => [
  ['概览指标'],
  ['指标', '数值', '单位'],
  ['参考总人数', overview.value.totalStudents ?? 0, '人'],
  ['全级平均分', formatNum(overview.value.avgScore), '分'],
  ['整体及格率', formatNum(overview.value.passRate), '%'],
  ['达标及格数', overview.value.passCount ?? 0, '人'],
  ['最高得分', overview.value.maxScore ?? '', '分'],
  ['最低得分', overview.value.minScore ?? '', '分']
]

const buildDistributionRows = () => [
  ['成绩段分布'],
  ['成绩区间', '人数'],
  ...distData.value.map(item => [item.range, item.count ?? 0])
]

const buildTrendRows = () => [
  ['班级均分对比'],
  ['班级ID', '班级名称', '平均分'],
  ...trendData.value.map(item => [item.classId ?? '', item.className ?? '', formatNum(item.avgScore)])
]

const buildStudentScoreRows = () => [
  ['学生成绩列表'],
  ['学生ID', '学号', '姓名', '账号', '班级', '提交状态', '客观分', '主观分', '总分', '结果', '提交时间'],
  ...studentScoreRows.value.map(item => [
    item.studentId ?? '',
    item.studentNo ?? '',
    item.studentName ?? '',
    item.username ?? '',
    formatClassNames(item.classNames),
    submissionStatusText(item),
    formatScore(item.objectiveScore),
    formatScore(item.subjectiveScore),
    formatScore(item.totalScore),
    passText(item),
    formatDateTime(item.submittedAt, '')
  ])
]

const buildWrongTopicRows = () => [
  ['高频错题榜单'],
  ['题目ID', '题目内容', '错题率(%)', '错误人次', '总作答数'],
  ...wrongData.value.map(item => [
    item.questionId ?? '',
    item.questionContent ?? '',
    formatNum(item.wrongRate),
    item.wrongCount ?? 0,
    item.totalCount ?? 0
  ])
]

const buildExportRows = (command) => {
  if (command === 'scores') return buildStudentScoreRows()
  if (command === 'dist') return buildDistributionRows()
  if (command === 'trend') return buildTrendRows()
  if (command === 'wrong') return buildWrongTopicRows()
  return [
    ...buildExamInfoRows(),
    [],
    ...buildOverviewRows(),
    [],
    ...buildStudentScoreRows(),
    [],
    ...buildDistributionRows(),
    [],
    ...buildTrendRows(),
    [],
    ...buildWrongTopicRows()
  ]
}

const downloadCsv = (filename, rows) => {
  const blob = new Blob([`\ufeff${toCsvContent(rows)}`], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

const formatScore = (score) => score == null ? '--' : score

const formatClassNames = (classNames) => {
  if (!Array.isArray(classNames) || !classNames.length) return '--'
  return classNames.join(' / ')
}

const submissionStatusText = (row) => {
  if (!row?.submissionId) return '未交卷'
  if (row.submissionStatus === 'IN_PROGRESS') return '作答中'
  if (row.submissionStatus === 'PROCESSING') return '判分中'
  if (row.submissionStatus === 'SUBMITTED') return '待阅卷'
  if (row.submissionStatus === 'GRADED') return '已出分'
  return row.submissionStatus || '--'
}

const submissionStatusTagType = (row) => {
  if (!row?.submissionId) return 'info'
  if (row.submissionStatus === 'GRADED') return 'success'
  if (row.submissionStatus === 'SUBMITTED') return 'warning'
  if (row.submissionStatus === 'PROCESSING') return 'primary'
  return 'info'
}

const passText = (row) => {
  if (!row?.submitted) return '未交'
  if (row.passFlag === true) return '通过'
  if (row.passFlag === false) return '未过'
  return '待定'
}

const passTagType = (row) => {
  if (!row?.submitted || row.passFlag == null) return 'info'
  return row.passFlag ? 'success' : 'danger'
}

const scorePercent = (score) => {
  const value = Number(score)
  if (!Number.isFinite(value)) return 0
  return Math.max(0, Math.min(100, value))
}

const scoreProgressColor = (row) => {
  if (row.passFlag === true) return '#16a34a'
  if (row.passFlag === false) return '#dc2626'
  return '#64748b'
}

const scoreTextClass = (row) => ({
  'is-pass': row.passFlag === true,
  'is-fail': row.passFlag === false
})

const studentInitial = (row) => {
  const text = row?.studentName || row?.username || '?'
  return text.charAt(0).toUpperCase()
}

const escapeHtml = (value) => String(value ?? '')
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;')

// 现代化的通用 ECharts 提示框配置
const modernTooltip = {
  backgroundColor: 'rgba(255, 255, 255, 0.95)',
  borderColor: 'rgba(226, 232, 240, 1)',
  borderWidth: 1,
  padding: [12, 16],
  textStyle: { color: '#1e293b', fontSize: 13 },
  extraCssText: 'box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05); border-radius: 8px; backdrop-filter: blur(8px);'
}

const renderCharts = () => {
  if (!distChart || !trendChart || !wrongChart) return

  distChart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'none' }, ...modernTooltip },
    grid: { left: '2%', right: '2%', bottom: '2%', top: '15%', containLabel: true },
    xAxis: { 
      type: 'category', 
      data: distData.value.map(d => d.range), 
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#e2e8f0' } },
      axisLabel: { color: '#64748b', margin: 12 }
    },
    yAxis: { 
      type: 'value', 
      splitLine: { lineStyle: { type: 'dashed', color: '#f1f5f9' } },
      axisLabel: { color: '#94a3b8' }
    },
    series: [{
      name: '人数',
      type: 'bar',
      data: distData.value.map(d => d.count),
      itemStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: '#60a5fa' },
          { offset: 1, color: '#2563eb' }
        ]),
        borderRadius: [6, 6, 0, 0]
      },
      barWidth: '32%',
      showBackground: true,
      backgroundStyle: { color: '#f8fafc', borderRadius: [6, 6, 0, 0] }
    }]
  })

  trendChart.setOption({
    tooltip: { trigger: 'axis', ...modernTooltip },
    grid: { left: '2%', right: '4%', bottom: '2%', top: '15%', containLabel: true },
    xAxis: { 
      type: 'category', 
      data: trendData.value.map(d => d.className), 
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#e2e8f0' } },
      axisLabel: { color: '#64748b', margin: 12 }
    },
    yAxis: { 
      type: 'value', 
      max: 100,
      splitLine: { lineStyle: { type: 'dashed', color: '#f1f5f9' } },
      axisLabel: { color: '#94a3b8' }
    },
    series: [{
      name: '平均分',
      type: 'bar',
      data: trendData.value.map(d => d.avgScore),
      itemStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: '#22c55e' },
          { offset: 1, color: '#0f766e' }
        ]),
        borderRadius: [6, 6, 0, 0]
      },
      barWidth: '34%',
      label: {
        show: true,
        position: 'top',
        formatter: '{c}',
        color: '#0f766e',
        fontWeight: 700
      }
    }]
  })

  wrongChart.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'none' },
      ...modernTooltip,
      formatter: (p) => {
        const d = wrongData.value[p[0].dataIndex];
        return `
          <div style="max-width:320px; white-space:normal; line-height: 1.6;">
            <div style="color:#64748b; font-size:12px; margin-bottom:8px;">题目 ID: ${escapeHtml(d.questionId)}</div>
            <div style="font-weight:600; color:#1e293b; margin-bottom:12px; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden;">${escapeHtml(d.questionContent)}</div>
            <div style="display:flex; justify-content:space-between; border-top:1px solid #f1f5f9; padding-top:8px;">
              <span style="color:#64748b;">错题率</span>
              <b style="color:#ef4444; font-size:16px;">${escapeHtml(d.wrongRate)}%</b>
            </div>
            <div style="display:flex; justify-content:space-between; margin-top:4px;">
              <span style="color:#64748b;">错误人次 / 总作答</span>
              <span style="color:#334155; font-weight:500;">${escapeHtml(d.wrongCount)} / ${escapeHtml(d.totalCount)}</span>
            </div>
          </div>
        `
      }
    },
    grid: { left: '2%', right: '6%', bottom: '2%', top: '5%', containLabel: true },
    xAxis: { 
      type: 'value', 
      max: 100, 
      splitLine: { lineStyle: { type: 'dashed', color: '#f1f5f9' } },
      axisLabel: { color: '#94a3b8', formatter: '{value}%' }
    },
    yAxis: { 
      type: 'category', 
      data: wrongData.value.map(d => `Q-${d.questionId}`), 
      inverse: true,
      axisTick: { show: false },
      axisLine: { show: false },
      axisLabel: { color: '#64748b', fontWeight: 500, margin: 16 }
    },
    series: [{
      name: '错题率',
      type: 'bar',
      data: wrongData.value.map(d => d.wrongRate),
      itemStyle: {
        color: new echarts.graphic.LinearGradient(1, 0, 0, 0, [
          { offset: 0, color: '#f43f5e' },
          { offset: 1, color: '#fb923c' }
        ]),
        borderRadius: [0, 4, 4, 0]
      },
      barWidth: '45%',
      showBackground: true,
      backgroundStyle: { color: '#f8fafc', borderRadius: [0, 4, 4, 0] },
      label: { 
        show: true, 
        position: 'right', 
        formatter: '{c}%', 
        color: '#ef4444',
        fontWeight: 600,
        distance: 10
      }
    }]
  })
}

const loadAll = async () => {
  if (!selectedExamId.value) return
  loading.value = true
  try {
    const id = selectedExamId.value
    const [ov, dist, tr, wr, scores] = await Promise.all([
      analyticsOverviewApi(id),
      scoreDistributionApi(id),
      classTrendApi(id),
      wrongTopicsApi(id, topN.value),
      studentScoresApi(id)
    ])
    if (String(id) !== String(selectedExamId.value)) return
    overview.value = ov || { totalStudents: 0, passCount: 0, passRate: 0, avgScore: 0, maxScore: null, minScore: null }
    distData.value = dist || []
    trendData.value = tr || []
    wrongData.value = wr || []
    studentScoreRows.value = scores || []
    nextTick(() => {
      renderCharts()
      resizeCharts()
    })
  } catch (e) {
    ElMessage.error('加载分析数据失败')
  } finally {
    loading.value = false
  }
}

const handleExport = (command) => {
  const label = exportLabels[command]
  if (!label) {
    ElMessage.error('不支持的导出类型')
    return
  }
  if (!selectedExamId.value) {
    ElMessage.warning('请选择考试后再导出')
    return
  }
  if (loading.value) {
    ElMessage.warning('数据加载中，请稍后再导出')
    return
  }

  try {
    downloadCsv(buildFileName(command), buildExportRows(command))
    ElMessage.success(`${label}导出成功`)
  } catch (e) {
    ElMessage.error('导出失败，请稍后重试')
  }
}

const resizeCharts = () => {
  distChart?.resize()
  trendChart?.resize()
  wrongChart?.resize()
}

const initCharts = () => {
  if (!distRef.value || !trendRef.value || !wrongRef.value) return
  distChart = distChart || echarts.init(distRef.value)
  trendChart = trendChart || echarts.init(trendRef.value)
  wrongChart = wrongChart || echarts.init(wrongRef.value)
}

watch(selectedExamId, loadAll)
watch(topN, loadAll)

onMounted(async () => {
  try {
    examOptions.value = await teacherExamsApi()
    if (examOptions.value.length) {
      await nextTick()
      initCharts()
      window.addEventListener('resize', resizeCharts)
      if (!selectedExamId.value) {
        selectedExamId.value = String(examOptions.value[0].examId)
      } else {
        await loadAll()
      }
    }
  } catch (e) {
    ElMessage.error('加载考试列表失败')
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  distChart?.dispose()
  trendChart?.dispose()
  wrongChart?.dispose()
})
</script>

<style scoped>
.analytics-dashboard {
  padding: 24px;
  background-color: var(--bg-main);
  min-height: 100%;
  font-family: "Microsoft YaHei", "PingFang SC", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding: 20px 24px;
  background: #ffffff;
  border-radius: 8px;
  border: 1px solid #dbe3ec;
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.05);
}

.title-wrapper {
  display: flex;
  align-items: center;
  gap: 20px;
}

.title-icon {
  width: 48px;
  height: 48px;
  background: #e8f1ff;
  color: #1d4ed8;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  box-shadow: inset 0 2px 4px rgba(255, 255, 255, 0.8), 0 4px 8px rgba(37, 99, 235, 0.1);
}

.dashboard-title {
  margin: 0 0 6px;
  font-size: 22px;
  color: #0f172a;
  font-weight: 750;
  letter-spacing: 0;
}

.dashboard-subtitle {
  margin: 0;
  color: #64748b;
  font-size: 14px;
}

.exam-name {
  font-weight: 600;
  color: #334155;
}

.exam-id {
  background: #f1f5f9;
  padding: 2px 6px;
  border-radius: 6px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  color: #64748b;
  margin-left: 6px;
  border: 1px solid #e2e8f0;
}

.header-actions {
  display: flex;
  gap: 16px;
  align-items: center;
}

.exam-selector {
  width: 360px;
}

:deep(.exam-selector .el-input__wrapper) {
  border-radius: 8px;
  box-shadow: 0 0 0 1px #e2e8f0 inset;
  background-color: #f8fafc;
}

:deep(.exam-selector .el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px #bfdbfe inset, 0 0 0 1px #3b82f6 inset;
  background-color: #ffffff;
}

.refresh-btn, .export-btn {
  border-radius: 8px;
  font-weight: 500;
}

.action-group .el-button {
  height: 40px;
}

.empty-state {
  background: #ffffff;
  border-radius: 8px;
  padding: 80px 0;
  border: 1px dashed #e2e8f0;
}

.dashboard-content {
  display: grid;
  gap: 20px;
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 14px;
}

.metric-card {
  padding: 16px;
  background: #ffffff;
  border-radius: 8px;
  border: 1px solid #dbe3ec;
  box-shadow: 0 8px 22px rgba(15, 23, 42, 0.04);
  transition: transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.metric-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 14px 28px rgba(15, 23, 42, 0.07);
  border-color: #b8c6d8;
}

.metric-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.metric-icon {
  width: 38px;
  height: 38px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
}

.metric-label {
  font-size: 14px;
  color: #64748b;
  font-weight: 500;
}

.metric-value {
  font-size: 28px;
  font-weight: 760;
  color: #0f172a;
  line-height: 1;
  font-feature-settings: "tnum";
  font-variant-numeric: tabular-nums;
}

.metric-suffix {
  font-size: 15px;
  margin-left: 6px;
  color: #94a3b8;
  font-weight: 500;
}

.score-list-panel,
.chart-container {
  background: #ffffff;
  border-radius: 8px;
  border: 1px solid #dbe3ec;
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.05);
}

.score-list-panel {
  padding: 20px;
}

.section-heading {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 16px;
}

.section-heading h2 {
  margin: 0 0 4px;
  color: #0f172a;
  font-size: 18px;
  font-weight: 750;
}

.section-heading p {
  margin: 0;
  color: #64748b;
  font-size: 13px;
}

.score-tools {
  display: flex;
  align-items: center;
  gap: 10px;
}

.score-search {
  width: 260px;
}

.score-status-filter {
  width: 136px;
}

:deep(.score-search .el-input__wrapper),
:deep(.score-status-filter .el-input__wrapper) {
  border-radius: 8px;
}

.score-summary-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 16px;
}

.score-summary-item {
  min-height: 64px;
  padding: 12px 14px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.score-summary-item span {
  color: #64748b;
  font-size: 13px;
}

.score-summary-item strong {
  color: #0f172a;
  font-size: 24px;
  font-weight: 760;
}

.score-summary-item.is-muted strong {
  color: #64748b;
}

.score-table {
  width: 100%;
  border-radius: 8px;
  overflow: hidden;
}

:deep(.score-table .el-table__header th) {
  background: #f8fafc;
  color: #334155;
  font-weight: 700;
}

.student-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.student-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #0f172a;
  color: #ffffff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  flex: 0 0 34px;
}

.student-cell strong,
.student-cell span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.student-cell strong {
  color: #0f172a;
  font-size: 14px;
}

.student-cell span {
  color: #64748b;
  font-size: 12px;
  margin-top: 2px;
}

.score-pair {
  color: #334155;
  font-variant-numeric: tabular-nums;
}

.score-meter {
  display: grid;
  gap: 6px;
}

.score-meter strong {
  color: #334155;
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}

.score-meter strong.is-pass {
  color: #15803d;
}

.score-meter strong.is-fail {
  color: #b91c1c;
}

.charts-layout {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}

.chart-container {
  padding: 22px 24px;
}

.full-width {
  grid-column: span 2;
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
}

.chart-title-group h3 {
  margin: 0 0 4px;
  font-size: 17px;
  color: #1e293b;
  font-weight: 700;
}

.chart-tip {
  font-size: 13px;
  color: #64748b;
}

.chart-header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.filter-label {
  font-size: 13px;
  color: #64748b;
  font-weight: 500;
}

.echart-view {
  height: 340px;
  width: 100%;
}

.id-tag {
  color: #94a3b8;
  font-size: 11px;
  margin-right: 8px;
  font-family: monospace;
}

@media (max-width: 1280px) {
  .metrics-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .charts-layout {
    grid-template-columns: 1fr;
  }
  .full-width {
    grid-column: span 1;
  }
}

@media (max-width: 768px) {
  .analytics-dashboard {
    padding: 16px;
  }
  .dashboard-header {
    flex-direction: column;
    align-items: flex-start;
    padding: 20px;
    gap: 24px;
  }
  .header-actions {
    width: 100%;
    flex-wrap: wrap;
  }
  .action-group {
    width: 100%;
  }
  .exam-selector {
    width: 100%;
  }
  .metrics-grid,
  .score-summary-row {
    grid-template-columns: 1fr;
  }
  .section-heading,
  .score-tools {
    flex-direction: column;
    align-items: stretch;
  }
  .score-search,
  .score-status-filter {
    width: 100%;
  }
  .chart-container {
    padding: 20px;
  }
}
</style>
