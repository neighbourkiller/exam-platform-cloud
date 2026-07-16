<template>
  <div class="student-exams-page">
    <section class="student-hero">
      <div class="student-hero__copy">
        <p class="student-hero__eyebrow">学生考试中心</p>
        <h1>{{ greetingText }}，{{ auth.username || 'student' }}</h1>
        <p class="student-hero__description">从这里查看考试安排、完成考前检测，并在开考前从容进入答题。</p>
      </div>

      <div class="student-hero__date" aria-label="当前日期">
        <span>今日</span>
        <strong>{{ calendarLabel }}</strong>
        <small>请提前完成环境检测</small>
      </div>

      <div class="exam-command-panel">
        <el-input
          v-model="keyword"
          size="large"
          clearable
          placeholder="查找考试或输入课程名称"
          class="exam-search"
        />
      </div>

      <div class="exam-filters" aria-label="考试筛选">
        <button
          v-for="item in filters"
          :key="item.key"
          type="button"
          :class="['filter-pill', { 'is-active': filterKey === item.key }]"
          @click="filterKey = item.key"
        >
          <el-icon v-if="item.icon" class="filter-pill-icon"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </button>
      </div>
    </section>

    <section class="exam-overview" aria-label="考试概况">
      <article
        v-for="metric in examMetrics"
        :key="metric.key"
        :class="['overview-card', `overview-card--${metric.tone}`]"
      >
        <span class="overview-card__icon" aria-hidden="true"><el-icon><component :is="metric.icon" /></el-icon></span>
        <span class="overview-card__copy">
          <span>{{ metric.label }}</span>
          <strong>{{ metric.value }}</strong>
          <small>{{ metric.description }}</small>
        </span>
      </article>
    </section>

    <section class="exam-panel">
      <div class="exam-panel__header">
        <div>
          <p>我的考试</p>
          <h2>考试列表</h2>
        </div>
        <button type="button" class="refresh-action" @click="load">刷新列表</button>
      </div>

      <el-skeleton :loading="loading" animated>
        <template #template>
          <div style="padding: 10px 0;">
            <el-skeleton-item variant="rect" style="width: 100%; height: 50px; margin-bottom: 12px; border-radius: 8px" />
            <el-skeleton-item variant="rect" style="width: 100%; height: 50px; margin-bottom: 12px; border-radius: 8px" />
            <el-skeleton-item variant="rect" style="width: 100%; height: 50px; border-radius: 8px" />
          </div>
        </template>
        <template #default>
          <el-table :data="filteredExams" class="student-table" style="width: 100%;">
            <el-table-column label="课程" width="170">
              <template #default="{ row }"><span class="course-name">{{ row.subjectName || '--' }}</span></template>
            </el-table-column>
            <el-table-column prop="name" label="名称" />
            <el-table-column label="开始时间" width="190">
              <template #default="{ row }"><span class="time-text">{{ formatDateTime(row.startTime) }}</span></template>
            </el-table-column>
            <el-table-column label="结束时间" width="190">
              <template #default="{ row }"><span class="time-text">{{ formatDateTime(row.endTime) }}</span></template>
            </el-table-column>
            <el-table-column label="状态" width="120" align="center" header-align="center">
              <template #default="{ row }">
                <span :class="['status-chip', statusTone(row.status)]">{{ row.status || '--' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="监考策略" width="110" align="center" header-align="center">
              <template #default="{ row }">
                <span class="policy-chip">{{ policyLevelLabel(row.proctoringLevel || row.proctoringPolicy?.level) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="已提交" width="90" align="center" header-align="center">
              <template #default="{ row }">
                <span :class="['submit-chip', row.submitted ? 'is-submitted' : 'is-pending']">
                  {{ row.submitted ? '是' : '否' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" align="center" header-align="center">
              <template #default="scope">
                <button
                  :disabled="!canEnter(scope.row)"
                  type="button"
                  class="enter-action"
                  @click="start(scope.row)"
                >
                  进入考试
                </button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="exam-empty">
                <el-empty description="没有匹配的考试" :image-size="100" />
              </div>
            </template>
          </el-table>
        </template>
      </el-skeleton>
    </section>

    <PreExamCheckDialog
      v-model="checkVisible"
      :exam="pendingExam"
      :policy="pendingExam?.proctoringPolicy || {}"
      @passed="enterPendingExam"
    />
  </div>
</template>

<script setup>
import { computed, ref, onMounted, markRaw } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Calendar, Menu, VideoPlay, Timer, CircleCheck, DocumentDelete } from '@element-plus/icons-vue'
import { studentExamsApi } from '../../api'
import { formatDateTime, parseDateTime } from '../../utils/datetime'
import PreExamCheckDialog from './PreExamCheckDialog.vue'
import { useAuthStore } from '../../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const exams = ref([])
const loading = ref(true)
const keyword = ref('')
const filterKey = ref('all')
const checkVisible = ref(false)
const pendingExam = ref(null)
const disallowedStatuses = new Set(['FINISHED', 'TERMINATED'])
const filters = [
  { key: 'all', label: '全部', icon: markRaw(Menu) },
  { key: 'available', label: '可进入', icon: markRaw(VideoPlay) },
  { key: 'ongoing', label: '进行中', icon: markRaw(Timer) },
  { key: 'finished', label: '已结束', icon: markRaw(CircleCheck) },
  { key: 'unsubmitted', label: '未提交', icon: markRaw(DocumentDelete) }
]
const metricIcons = {
  available: markRaw(Calendar),
  ongoing: markRaw(Timer),
  completed: markRaw(CircleCheck)
}
const greetingText = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '上午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})
const calendarLabel = computed(() => new Intl.DateTimeFormat('zh-CN', {
  month: 'long',
  day: 'numeric',
  weekday: 'long'
}).format(new Date()))
const examMetrics = computed(() => [
  {
    key: 'available',
    label: '待参加',
    value: exams.value.filter(canEnter).length,
    description: '可进入考试',
    tone: 'accent',
    icon: metricIcons.available
  },
  {
    key: 'ongoing',
    label: '进行中',
    value: exams.value.filter((exam) => exam.status === 'ONGOING').length,
    description: '请及时完成作答',
    tone: 'warning',
    icon: metricIcons.ongoing
  },
  {
    key: 'completed',
    label: '已完成',
    value: exams.value.filter((exam) => exam.submitted || exam.status === 'FINISHED').length,
    description: '可前往查看结果',
    tone: 'success',
    icon: metricIcons.completed
  }
])

const load = async () => {
  loading.value = true
  try {
    exams.value = await studentExamsApi()
  } finally {
    loading.value = false
  }
}

const canEnter = (exam) => {
  if (!exam) return false
  if (exam.submitted) return false
  if (disallowedStatuses.has(exam.status)) return false
  const endTime = parseDateTime(exam.endTime)
  if (endTime && endTime.getTime() <= Date.now()) return false
  return true
}

const filteredExams = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return exams.value.filter((exam) => {
    const haystack = `${exam.subjectName || ''} ${exam.name || ''} ${exam.status || ''}`.toLowerCase()
    if (query && !haystack.includes(query)) return false
    if (filterKey.value === 'available') return canEnter(exam)
    if (filterKey.value === 'ongoing') return exam.status === 'ONGOING' || exam.status === 'PUBLISHED'
    if (filterKey.value === 'finished') return disallowedStatuses.has(exam.status)
    if (filterKey.value === 'unsubmitted') return !exam.submitted
    return true
  })
})

const start = (row) => {
  if (!canEnter(row)) return
  pendingExam.value = row
  checkVisible.value = true
}

const enterPendingExam = async (handoff) => {
  if (!canEnter(pendingExam.value)) {
    handoff?.reject?.()
    ElMessage.warning('当前考试已不可进入，请刷新考试列表后重试。')
    return
  }
  const target = `/student/exam/${pendingExam.value.examId}`
  handoff?.accept?.()
  try {
    const failure = await router.push(target)
    if (failure) {
      handoff?.reject?.()
    }
  } catch {
    handoff?.reject?.()
  }
}

const statusTone = (status) => {
  switch (status) {
    case 'ONGOING': return 'is-live'
    case 'PUBLISHED': return 'is-ready'
    case 'TERMINATED': return 'is-ended-danger'
    case 'FINISHED': return 'is-ended'
    default: return 'is-ended'
  }
}

const policyLevelLabel = (level) => {
  switch (level) {
    case 'LOW': return '宽松'
    case 'STRICT': return '严格'
    case 'CUSTOM': return '自定义'
    default: return '标准'
  }
}

onMounted(load)
</script>

<style scoped>
.student-exams-page {
  display: grid;
  gap: 22px;
}

.student-hero {
  display: flex;
  align-items: center;
  flex-direction: column;
  text-align: center;
}

.student-hero h1 {
  margin: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 15px;
  color: var(--text-main);
  font-family: Georgia, 'Times New Roman', 'Songti SC', serif;
  font-size: clamp(32px, 3vw, 46px);
  font-weight: 500;
  line-height: 1.08;
}

.student-hero h1 span:last-child {
  word-spacing: 0.04em;
}

.hero-burst {
  flex: 0 0 auto;
  width: 27px;
  height: 27px;
  border-radius: 50%;
  background:
    repeating-conic-gradient(
      from 0deg,
      var(--student-accent, #d97757) 0deg 9deg,
      transparent 9deg 18deg
    );
  mask: radial-gradient(circle, transparent 0 33%, #000 34% 100%);
}

.exam-command-panel {
  width: min(560px, 100%);
  margin-top: 28px;
  padding: 14px 22px;
  border: none;
  border-radius: 20px;
  background: #ffffff;
  box-shadow:
    rgba(0, 0, 0, 0.06) 0px 4px 20px 0px,
    rgba(30, 28, 25, 0.22) 0px 0px 0px 0.5px;
}

.exam-command-panel:focus-within {
  box-shadow:
    rgba(0, 0, 0, 0.08) 0px 4px 24px 0px,
    rgba(207, 107, 78, 0.40) 0px 0px 0px 1px;
  transition: box-shadow 0.2s ease;
}

.exam-search {
  width: 100%;
}

.exam-search :deep(.el-input__wrapper) {
  min-height: 44px;
  padding: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.exam-search :deep(.el-input__inner) {
  color: var(--text-main);
  font-size: 16px;
}

.exam-search :deep(.el-input__inner::placeholder) {
  color: #a7a19a;
}

.exam-search :deep(.el-input__suffix) {
  display: none;
}

.exam-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 14px;
}

.filter-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 0 14px;
  border: 1px solid var(--student-line, #e7dfd3);
  border-radius: 10px;
  background: #fffdfa;
  color: var(--text-muted);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
  transition: all var(--transition-fast, 0.2s);
}

.filter-pill-icon {
  font-size: 16px;
}

.filter-pill:hover {
  color: var(--text-main);
  background: var(--student-soft, #eee8df);
}

.filter-pill.is-active {
  color: var(--text-main);
  background: var(--student-soft, #eee8df);
  border-color: rgba(207, 107, 78, 0.35);
  font-weight: 550;
}

.exam-panel {
  padding: 26px 28px 24px;
  border: none;
  border-radius: 18px;
  background: #ffffff;
  box-shadow:
    rgba(0, 0, 0, 0.04) 0px 2px 12px 0px,
    rgba(30, 28, 25, 0.10) 0px 0px 0px 0.5px;
}

.exam-panel__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 22px;
}

.exam-panel__header p {
  margin: 0 0 5px;
  color: var(--student-accent, #d97757);
  font-size: 13px;
  font-weight: 700;
}

.exam-panel__header h2 {
  margin: 0;
  color: var(--text-main);
  font-size: 24px;
  font-weight: 700;
}

.refresh-action {
  height: 36px;
  padding: 0 16px;
  border: 1px solid #ded3c4;
  border-radius: 999px;
  background: #fffdfa;
  color: #615950;
  font: inherit;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
}

.refresh-action:hover {
  background: #f5efe7;
  border-color: #d2c5b5;
}

.course-name {
  font-weight: 600;
  color: var(--text-main);
}

.time-text {
  color: var(--text-muted);
  font-size: 13px;
}

.student-table {
  border-radius: 8px;
  overflow: hidden;
  --el-table-header-bg-color: #ebe3d7;
  --el-table-header-text-color: var(--text-main);
  --el-table-row-hover-bg-color: #f0e8dc;
  --el-table-border-color: #ddd2c2;
  --el-table-text-color: var(--text-main);
  --el-table-tr-bg-color: #f8f3eb;
}

.student-table :deep(.el-table__cell) {
  padding: 13px 0;
}

.student-table :deep(.el-table),
.student-table :deep(.el-table__expanded-cell) {
  background-color: transparent !important;
}

.student-table :deep(.el-table th.el-table__cell) {
  font-weight: 700;
}

.student-table :deep(.el-table tr),
.student-table :deep(.el-table td.el-table__cell) {
  background-color: #f8f3eb !important;
  border-bottom-color: #ddd2c2;
}

.student-table :deep(.el-table__empty-block),
.student-table :deep(.el-table__empty-text) {
  background-color: #f8f3eb !important;
}

.student-table :deep(.el-table__empty-block) {
  min-height: 160px;
}

.student-table :deep(.el-table tr:hover > td.el-table__cell) {
  background-color: #f0e8dc !important;
}

.student-table :deep(.el-table::before) {
  display: none;
}

.status-chip,
.policy-chip,
.submit-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 44px;
  height: 25px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 13px;
  line-height: 1;
}

.status-chip {
  min-width: 86px;
  border: 1px solid #dedbd6;
  background: #f4f2ee;
  color: #78726c;
  text-transform: uppercase;
  white-space: nowrap;
}

.status-chip.is-live {
  border-color: #d7e4cc;
  background: #f3f8ef;
  color: #617c49;
}

.status-chip.is-ready {
  border-color: #ead9bc;
  background: #fff8eb;
  color: #8a6a35;
}

.status-chip.is-ended-danger {
  border-color: #edc8c1;
  background: #fff4f1;
  color: #b65a48;
}

.policy-chip {
  border: 1px solid #dacfc0;
  background: #fbf7f0;
  color: #6c6258;
}

.submit-chip.is-submitted {
  border: 1px solid #d7e3c8;
  background: #f4f7ee;
  color: #2f5e2f;
  font-weight: 650;
}

.submit-chip.is-pending {
  border: 1px solid #dedbd6;
  background: #f6f4f0;
  color: #655e57;
  font-weight: 650;
}

.enter-action {
  min-width: 86px;
  height: 28px;
  padding: 0 15px;
  border: 0.67px solid rgba(47, 45, 42, 0.18);
  border-radius: 8px;
  background: transparent;
  color: #5a4f45;
  font: inherit;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast), color var(--transition-fast);
}

.enter-action:hover:not(:disabled) {
  border-color: #d79a82;
  background: #e6d2c5;
  color: var(--student-accent-dark, #a84930);
}

.enter-action:disabled {
  cursor: not-allowed;
  border-color: #d8cfc4;
  background: transparent;
  color: #6b5f54;
  opacity: 1;
}

.exam-empty {
  padding: 28px 0;
  color: var(--text-muted);
}

/* 暖色考试中心：以信息层级而非大面积留白承载首页重点。 */
.student-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 18px 32px;
  padding: 8px 0 0;
  text-align: left;
}

.student-hero__eyebrow {
  margin: 0 0 8px;
  color: var(--student-accent);
  font-size: 13px;
  font-weight: 750;
  letter-spacing: 0.08em;
}

.student-hero h1 {
  display: block;
  color: var(--student-text);
  font-family: 'Noto Serif SC', 'Songti SC', Georgia, serif;
  font-size: clamp(30px, 3vw, 42px);
  font-weight: 650;
  letter-spacing: 0.01em;
}

.student-hero__description {
  max-width: 620px;
  margin: 12px 0 0;
  color: var(--student-muted);
  font-size: 15px;
  line-height: 1.7;
}

.student-hero__date {
  min-width: 208px;
  display: grid;
  gap: 4px;
  padding: 14px 18px;
  border: 1px solid #ecd9c8;
  border-radius: 14px;
  background: #fff5ea;
  color: var(--student-text);
}

.student-hero__date span,
.student-hero__date small {
  color: var(--student-muted);
  font-size: 12px;
}

.student-hero__date strong {
  font-size: 15px;
  font-weight: 700;
}

.exam-command-panel {
  grid-column: 1 / -1;
  width: 100%;
  margin-top: 4px;
  padding: 8px 14px;
  border: 1px solid var(--student-line);
  border-radius: 14px;
  background: var(--student-panel-strong);
  box-shadow: 0 8px 22px rgba(83, 57, 40, 0.05);
}

.exam-command-panel:focus-within {
  box-shadow: 0 0 0 3px rgba(201, 106, 61, 0.16), 0 8px 22px rgba(83, 57, 40, 0.06);
}

.exam-filters {
  grid-column: 1 / -1;
  justify-content: flex-start;
  margin-top: -6px;
}

.filter-pill {
  min-height: 40px;
  border-radius: 10px;
  background: var(--student-panel-strong);
}

.filter-pill:hover,
.filter-pill.is-active {
  color: var(--student-accent-dark);
  background: #fff0e4;
  border-color: #eac7b4;
}

.exam-overview {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.overview-card {
  min-height: 112px;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
  border: 1px solid var(--student-line);
  border-radius: 16px;
  background: var(--student-panel-strong);
  box-shadow: 0 8px 20px rgba(83, 57, 40, 0.04);
}

.overview-card__icon {
  width: 48px;
  height: 48px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  font-size: 23px;
}

.overview-card__copy {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.overview-card__copy > span,
.overview-card__copy small {
  color: var(--student-muted);
  font-size: 13px;
}

.overview-card__copy strong {
  color: var(--student-text);
  font-size: 30px;
  font-variant-numeric: tabular-nums;
  line-height: 1.05;
}

.overview-card--accent .overview-card__icon {
  color: var(--student-accent);
  background: #fbe8dd;
}

.overview-card--warning .overview-card__icon {
  color: var(--student-warning);
  background: #fff4dc;
}

.overview-card--success .overview-card__icon {
  color: var(--student-success);
  background: #edf5eb;
}

.exam-panel {
  padding: 26px 28px 24px;
  border: 1px solid var(--student-line);
  background: var(--student-panel-strong);
  box-shadow: var(--shadow-soft);
}

.refresh-action {
  min-height: 40px;
  border-color: #e3cab9;
  background: #fff9f3;
  color: var(--student-accent-dark);
}

.refresh-action:hover {
  background: #fff0e4;
  border-color: #dba98e;
}

.student-table {
  --el-table-header-bg-color: #fbf1e7;
  --el-table-row-hover-bg-color: #fff6ee;
  --el-table-border-color: var(--student-line);
  --el-table-tr-bg-color: var(--student-panel-strong);
}

.student-table :deep(.el-table tr),
.student-table :deep(.el-table td.el-table__cell),
.student-table :deep(.el-table__empty-block),
.student-table :deep(.el-table__empty-text) {
  background-color: var(--student-panel-strong) !important;
  border-bottom-color: var(--student-line);
}

.student-table :deep(.el-table tr:hover > td.el-table__cell) {
  background-color: #fff6ee !important;
}

.status-chip.is-live,
.submit-chip.is-submitted {
  border-color: #c8ddc7;
  background: #eff7ed;
  color: var(--student-success);
}

.status-chip.is-ready {
  border-color: #ead39f;
  background: #fff6e1;
  color: #90641d;
}

.status-chip.is-ended-danger {
  border-color: #edc7c0;
  background: #fdf1ee;
  color: var(--student-danger);
}

.enter-action {
  min-height: 34px;
  border-color: #e1c7b5;
  color: var(--student-accent-dark);
}

.enter-action:hover:not(:disabled) {
  border-color: var(--student-accent);
  background: var(--student-accent);
  color: #fffefa;
}

.filter-pill:focus-visible,
.refresh-action:focus-visible,
.enter-action:focus-visible {
  outline: 3px solid rgba(201, 106, 61, 0.28);
  outline-offset: 2px;
}

@media (max-width: 760px) {
  .student-hero {
    grid-template-columns: 1fr;
    gap: 16px;
  }

  .student-hero__date,
  .exam-command-panel,
  .exam-filters {
    grid-column: auto;
  }

  .student-hero h1 {
    font-size: 34px;
  }

  .exam-overview {
    grid-template-columns: 1fr;
  }

  .exam-panel {
    padding: 16px;
  }

  .exam-panel__header {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
