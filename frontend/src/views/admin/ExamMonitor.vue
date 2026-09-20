<template>
  <el-card class="page-card">
    <template #header>
      <div class="header-row">
        <span>考试状态监控</span>
        <el-button size="small" @click="loadSummary">刷新</el-button>
      </div>
    </template>

    <div v-if="summary" class="summary-grid">
      <div class="summary-item"><span>考试数</span><strong>{{ summary.totalExams }}</strong></div>
      <div class="summary-item"><span>未开始</span><strong>{{ summary.notStartedCount }}</strong></div>
      <div class="summary-item"><span>考试中</span><strong>{{ summary.answeringCount }}</strong></div>
      <div class="summary-item"><span>已提交</span><strong>{{ summary.submittedCount }}</strong></div>
      <div class="summary-item warn"><span>异常</span><strong>{{ summary.abnormalCount }}</strong></div>
      <div class="summary-item danger"><span>缺考</span><strong>{{ summary.absentCount }}</strong></div>
    </div>

    <el-table :data="summary?.exams || []" v-loading="loading" style="margin-top: 16px">
      <el-table-column prop="examId" label="ID" width="90" />
      <el-table-column prop="name" label="考试" min-width="180" />
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column label="时间" min-width="260">
        <template #default="{ row }">{{ row.startTime }} ~ {{ row.endTime }}</template>
      </el-table-column>
      <el-table-column prop="totalStudents" label="目标人数" width="100" />
      <el-table-column prop="notStartedCount" label="未开始" width="90" />
      <el-table-column prop="answeringCount" label="考试中" width="90" />
      <el-table-column prop="submittedCount" label="已提交" width="90" />
      <el-table-column prop="abnormalCount" label="异常" width="90" />
      <el-table-column prop="absentCount" label="缺考" width="90" />
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button link type="primary" @click="openStudents(row)">查看学生</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="drawerVisible" :title="drawerTitle" size="70%">
      <el-table :data="students" v-loading="studentLoading">
        <el-table-column prop="studentId" label="学生ID" width="100" />
        <el-table-column prop="username" label="账号" width="130" />
        <el-table-column prop="realName" label="姓名" width="120" />
        <el-table-column prop="className" label="班级" min-width="150" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column label="异常" width="90">
          <template #default="{ row }">
            <el-tag :type="row.abnormal ? 'danger' : 'success'" size="small">{{ row.abnormal ? '有' : '无' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="submittedAt" label="提交时间" min-width="180" />
        <el-table-column prop="latestEventType" label="最近异常" min-width="170" />
      </el-table>
    </el-drawer>
  </el-card>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { adminExamMonitorStudentsApi, adminExamMonitorSummaryApi } from '../../api'

const summary = ref(null)
const students = ref([])
const loading = ref(false)
const studentLoading = ref(false)
const drawerVisible = ref(false)
const drawerTitle = ref('学生状态')

const loadSummary = async () => {
  loading.value = true
  try {
    summary.value = await adminExamMonitorSummaryApi()
  } finally {
    loading.value = false
  }
}

const openStudents = async (row) => {
  drawerTitle.value = `${row.name} - 学生状态`
  drawerVisible.value = true
  studentLoading.value = true
  try {
    students.value = await adminExamMonitorStudentsApi(row.examId)
  } finally {
    studentLoading.value = false
  }
}

onMounted(loadSummary)
</script>

<style scoped>
.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
}
.summary-item {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 12px;
  background: #fff;
}
.summary-item span {
  display: block;
  color: #64748b;
  font-size: 13px;
}
.summary-item strong {
  display: block;
  margin-top: 6px;
  font-size: 24px;
}
.summary-item.warn strong { color: #d97706; }
.summary-item.danger strong { color: #dc2626; }
</style>
