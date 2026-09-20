<template>
  <div class="result-container">
    <el-card class="result-card">
      <template #header>
        <div class="header">
          <el-icon class="header-icon" aria-hidden="true"><CircleCheck /></el-icon>
          <span>交卷结果</span>
        </div>
      </template>

      <div class="result-summary">
        <div class="score-circle" :class="{ 'is-passed': result.passFlag, 'is-failed': result.passFlag === false }">
          <span class="score-label">总分</span>
          <span class="score-value">{{ result.totalScore != null ? result.totalScore : '--' }}</span>
        </div>
      </div>

      <el-descriptions :column="1" border class="result-desc" :label-style="{ background: 'rgba(201, 106, 61, 0.08)', color: 'var(--brand)', fontWeight: '600', width: '120px' }">
        <el-descriptions-item label="提交ID">{{ result.submissionId || '--' }}</el-descriptions-item>
        <el-descriptions-item label="客观题得分">{{ result.objectiveScore != null ? result.objectiveScore : '--' }}</el-descriptions-item>
        <el-descriptions-item label="主观题得分">{{ result.subjectiveScore != null ? result.subjectiveScore : '--' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="result.status === 'GRADED' ? 'success' : 'warning'" round effect="light">{{ result.status || '--' }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <div class="action-footer">
        <el-button type="primary" round size="large" @click="goList">返回我的考试</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CircleCheck } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()

const result = computed(() => {
  try {
    return JSON.parse(route.query.result || '{}')
  } catch {
    return {}
  }
})

const goList = () => router.push('/student/exams')
</script>

<style scoped>
.result-container {
  padding: 8px 0;
  max-width: 600px;
  margin: 0 auto;
}

.result-card {
  border: 1px solid var(--student-line, #eadfce);
  border-radius: 18px;
  background: var(--student-panel-strong, #fffefa);
  box-shadow: var(--shadow-soft);
  overflow: hidden;
}

.result-card :deep(.el-card__header) {
  padding: 20px 24px;
  border-bottom: 1px solid var(--student-line, #eadfce);
  background: #fff7ef;
}

.result-card :deep(.el-card__body) {
  padding: 28px 24px 24px;
}

.header {
  font-size: 20px;
  font-weight: 700;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--student-accent-dark);
}

.header-icon {
  color: var(--student-accent);
  font-size: 22px;
}

.result-summary {
  display: flex;
  justify-content: center;
  margin-bottom: 30px;
  margin-top: 10px;
}

.score-circle {
  width: 140px;
  height: 140px;
  border-radius: 50%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: #fffaf4;
  border: 6px solid #eddccc;
  box-shadow: 0 10px 24px rgba(83, 57, 40, 0.08);
}

.score-circle.is-passed {
  border-color: var(--student-success, #4f7a5a);
  color: var(--student-success, #4f7a5a);
  box-shadow: 0 10px 24px rgba(79, 122, 90, 0.16);
}

.score-circle.is-failed {
  border-color: var(--student-danger, #b75a4e);
  color: var(--student-danger, #b75a4e);
  box-shadow: 0 10px 24px rgba(183, 90, 78, 0.15);
}

.score-label {
  font-size: 14px;
  color: var(--text-muted);
}

.score-value {
  font-size: 42px;
  font-weight: 800;
  line-height: 1.1;
  color: inherit;
}

.action-footer {
  margin-top: 30px;
  display: flex;
  justify-content: center;
}

:deep(.el-descriptions__body) {
  background: transparent !important;
}
:deep(.el-descriptions__cell) {
  background: #fffefa !important;
  border-color: var(--student-line, #eadfce) !important;
}

.result-container :deep(.el-button--primary) {
  min-height: 44px;
  padding-inline: 24px;
  border-radius: 10px;
  box-shadow: 0 8px 18px rgba(201, 106, 61, 0.18);
}
</style>
