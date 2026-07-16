<template>
  <div class="results-page">
    <section class="results-hero">
      <div class="hero-content">
        <p>成绩档案</p>
        <h1 class="results-title">考试结果中心</h1>
        <p class="results-subtitle">查看全部考试与成绩状态，包含待阅卷与未交卷记录。</p>
      </div>
      <el-button plain round @click="loadResults" class="refresh-btn">刷新数据</el-button>
    </section>

    <section class="metrics-row">
      <article class="metric-item" style="animation-delay: 0.1s">
        <div class="metric-label">总考试数</div>
        <div class="metric-value">{{ metrics.totalCount }}</div>
      </article>
      <article class="metric-item" style="animation-delay: 0.2s">
        <div class="metric-label">已交卷</div>
        <div class="metric-value">{{ metrics.submittedCount }}</div>
      </article>
      <article class="metric-item" style="animation-delay: 0.3s">
        <div class="metric-label">已出分</div>
        <div class="metric-value">{{ metrics.gradedCount }}</div>
      </article>
      <article class="metric-item" style="animation-delay: 0.4s">
        <div class="metric-label">已通过</div>
        <div class="metric-value">{{ metrics.passCount }}</div>
      </article>
    </section>

    <section class="table-section">
      <el-table :data="rows" class="results-table" style="width: 100%">
        <el-table-column label="课程" width="170">
          <template #default="{ row }"><span class="fw-semibold">{{ row.subjectName || '--' }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="考试名称" min-width="180" />
        <el-table-column label="开始时间" width="160">
          <template #default="{ row }"><span class="text-muted">{{ formatDateTime(row.startTime) }}</span></template>
        </el-table-column>
        <el-table-column label="结束时间" width="160">
          <template #default="{ row }"><span class="text-muted">{{ formatDateTime(row.endTime) }}</span></template>
        </el-table-column>
        <el-table-column label="考试状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="examStatusTagType(row.examStatus)" round effect="light">{{ examStatusText(row.examStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提交状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="submissionStatusTagType(row)" round effect="light">{{ submissionStatusText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="主客观分" width="140">
          <template #default="{ row }">
            <div class="score-split">
              <span>客: {{ formatScore(row.objectiveScore) }}</span>
              <span class="divider">|</span>
              <span>主: {{ formatScore(row.subjectiveScore) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="总分" width="90">
          <template #default="{ row }">
            <strong class="total-score" :class="{ 'text-green': row.passFlag, 'text-red': row.passFlag === false }">
              {{ formatScore(row.totalScore) }}
            </strong>
          </template>
        </el-table-column>
        <el-table-column label="是否通过" width="90">
          <template #default="{ row }">
            <span :class="{ 'pass-text': row.passFlag, 'fail-text': row.passFlag === false }">
              {{ passText(row.passFlag) }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { studentExamResultsApi } from '../../api'
import { formatDateTime } from '../../utils/datetime'

const rows = ref([])

const metrics = computed(() => {
  const list = rows.value || []
  return {
    totalCount: list.length,
    submittedCount: list.filter(item => item.submitted).length,
    gradedCount: list.filter(item => item.submissionStatus === 'GRADED').length,
    passCount: list.filter(item => item.passFlag === true).length
  }
})

const examStatusTextMap = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  ONGOING: '进行中',
  FINISHED: '已结束',
  TERMINATED: '已终止'
}

const examStatusText = (status) => examStatusTextMap[status] || status || '--'

const examStatusTagType = (status) => {
  if (status === 'ONGOING') return 'success'
  if (status === 'PUBLISHED') return 'warning'
  if (status === 'TERMINATED') return 'danger'
  return 'info'
}

const submissionStatusText = (row) => {
  if (!row || !row.submissionId) return '未交卷'
  if (row.submissionStatus === 'IN_PROGRESS') return '作答中'
  if (row.submissionStatus === 'PROCESSING') return '系统判分中'
  if (row.submissionStatus === 'SUBMITTED') return '待阅卷'
  if (row.submissionStatus === 'GRADED') return '已出分'
  return row.submissionStatus || '--'
}

const submissionStatusTagType = (row) => {
  const text = submissionStatusText(row)
  if (text === '已出分') return 'success'
  if (text === '系统判分中') return 'warning'
  if (text === '待阅卷') return 'warning'
  if (text === '作答中') return 'danger'
  return 'info'
}

const formatScore = (score) => (score == null ? '--' : score)

const passText = (passFlag) => {
  if (passFlag == null) return '--'
  return passFlag ? '通过' : '未通过'
}

const loadResults = async () => {
  rows.value = await studentExamResultsApi()
}

onMounted(loadResults)
</script>

<style scoped>
.results-page {
  display: grid;
  gap: 22px;
}

.results-hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 20px;
  padding: 30px 32px;
  border: 1px solid var(--student-line, #eadfce);
  border-radius: 16px;
  background: var(--student-panel-strong, #fffefa);
  box-shadow: var(--shadow-soft);
}

.hero-content {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.hero-content > p:first-child {
  margin: 0;
  color: var(--student-accent, #c96a3d);
  font-size: 13px;
  font-weight: 700;
}

.results-title {
  margin: 0;
  color: var(--text-main);
  font-family: 'Noto Serif SC', 'Songti SC', Georgia, serif;
  font-size: clamp(30px, 4vw, 42px);
  font-weight: 650;
  line-height: 1.08;
}

.results-subtitle {
  margin: 0;
  color: var(--text-muted);
  font-size: 15px;
}

.refresh-btn {
  min-height: 40px;
  padding-inline: 16px;
  border-color: #e3cab9;
  background: #fff9f3;
  color: var(--student-accent-dark);
}

.metrics-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 20px;
}

.metric-item {
  padding: 20px 22px;
  border: 1px solid var(--student-line, #eadfce);
  border-top: 3px solid var(--student-accent);
  border-radius: 14px;
  background: var(--student-panel-strong, #fffefa);
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  animation: fadeUp 0.6s ease-out backwards;
  box-shadow: 0 10px 26px rgba(83, 57, 40, 0.05);
}

.metric-item:nth-child(2) { border-top-color: var(--student-warning); }
.metric-item:nth-child(3),
.metric-item:nth-child(4) { border-top-color: var(--student-success); }

.metric-label {
  color: var(--text-muted);
  font-size: 14px;
  font-weight: 500;
}

.metric-value {
  color: var(--student-text);
  font-size: 38px;
  font-weight: 700;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.text-brand { color: var(--brand); }
.text-blue { color: var(--student-accent); }
.text-green { color: var(--student-success); }
.text-orange { color: var(--student-warning); }
.text-red { color: var(--student-danger); }

.table-section {
  padding: 24px;
  border: 1px solid var(--student-line, #eadfce);
  border-radius: 16px;
  background: var(--student-panel-strong, #fffefa);
  box-shadow: var(--shadow-soft);
}

.score-split {
  display: flex;
  gap: 6px;
  font-size: 13px;
  color: var(--text-muted);
}
.divider { color: #d9cabb; }

.total-score {
  font-size: 16px;
}

.pass-text {
  color: var(--student-success);
  font-weight: 600;
}
.fail-text {
  color: var(--student-danger);
  font-weight: 600;
}
.fw-semibold { font-weight: 600; }
.text-muted { color: var(--text-muted); font-size: 13px; }

.results-table {
  --el-table-header-bg-color: #fbf1e7;
  --el-table-header-text-color: var(--text-main);
  --el-table-row-hover-bg-color: #fff6ee;
  --el-table-border-color: var(--student-line, #eadfce);
  --el-table-text-color: var(--text-main);
  --el-table-tr-bg-color: transparent;
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.results-table :deep(.el-table),
.results-table :deep(.el-table__expanded-cell) {
  background-color: transparent !important;
}

.results-table :deep(.el-table th.el-table__cell) {
  font-weight: 700;
}

.results-table :deep(.el-table tr),
.results-table :deep(.el-table td.el-table__cell) {
  background-color: transparent !important;
  border-bottom-color: var(--student-line, #eadfce);
}

.refresh-btn:focus-visible {
  outline: 3px solid rgba(201, 106, 61, 0.28);
  outline-offset: 2px;
}

.results-table :deep(.el-table::before) {
  display: none;
}

@keyframes fadeUp {
  from { opacity: 0; transform: translateY(20px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 1100px) {
  .metrics-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 720px) {
  .results-hero {
    flex-direction: column;
    align-items: flex-start;
    padding: 24px;
  }
  .metrics-row { grid-template-columns: 1fr; }
  .table-section { padding: 16px; }
}
</style>
