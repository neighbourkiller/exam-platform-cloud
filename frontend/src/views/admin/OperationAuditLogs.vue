<template>
  <el-card class="page-card">
    <template #header>
      <div class="header-row">
        <span>操作日志</span>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </template>

    <el-form :inline="true" :model="query" class="filter-form">
      <el-form-item label="操作者"><el-input v-model="query.operatorKeyword" clearable /></el-form-item>
      <el-form-item label="动作"><el-input v-model="query.action" clearable placeholder="如 QUESTION_UPDATE" /></el-form-item>
      <el-form-item label="对象"><el-input v-model="query.targetType" clearable placeholder="EXAM/QUESTION" /></el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" clearable style="width: 130px">
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAILED" />
        </el-select>
      </el-form-item>
      <el-form-item><el-button type="primary" @click="search">查询</el-button></el-form-item>
    </el-form>

    <el-table :data="logs" v-loading="loading">
      <el-table-column prop="operateTime" label="时间" min-width="170" />
      <el-table-column prop="operatorUsername" label="操作者" width="120" />
      <el-table-column label="动作" min-width="180">
        <template #default="{ row }">{{ actionLabel(row.action) }}</template>
      </el-table-column>
      <el-table-column prop="targetType" label="对象" width="110" />
      <el-table-column prop="targetId" label="对象ID" width="120" />
      <el-table-column prop="requestIp" label="IP" width="130" />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.pageNum"
      v-model:page-size="query.pageSize"
      :total="total"
      layout="total, sizes, prev, pager, next"
      class="pager"
      @current-change="load"
      @size-change="load"
    />

    <el-drawer v-model="drawerVisible" title="日志详情" size="520px">
      <el-descriptions v-if="currentLog" :column="1" border>
        <el-descriptions-item label="动作">{{ currentLog.action }}</el-descriptions-item>
        <el-descriptions-item label="操作者">{{ currentLog.operatorUsername }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ currentLog.operatorRoles }}</el-descriptions-item>
        <el-descriptions-item label="请求">{{ currentLog.requestMethod }} {{ currentLog.requestPath }}</el-descriptions-item>
        <el-descriptions-item label="IP">{{ currentLog.requestIp }}</el-descriptions-item>
        <el-descriptions-item label="对象">{{ currentLog.targetType }} / {{ currentLog.targetId }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ currentLog.status }}</el-descriptions-item>
        <el-descriptions-item label="错误">{{ currentLog.errorMessage || '-' }}</el-descriptions-item>
      </el-descriptions>
      <pre v-if="currentLog?.detail" class="detail-text">{{ currentLog.detail }}</pre>
    </el-drawer>
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { auditLogsApi } from '../../api'

const query = reactive({ pageNum: 1, pageSize: 20, operatorKeyword: '', action: '', targetType: '', status: '' })
const logs = ref([])
const total = ref(0)
const loading = ref(false)
const drawerVisible = ref(false)
const currentLog = ref(null)

const labels = {
  QUESTION_UPDATE: '题目修改',
  EXAM_PUBLISH: '考试发布',
  SUBMISSION_SCORE_SUBJECTIVE: '成绩修改',
  QUESTION_BATCH_SCORE_SUBJECTIVE: '批量评分',
  BULK_USER_IMPORT: '批量用户导入',
  BULK_EXAM_SCHEDULE_IMPORT: '批量考试安排导入',
  BULK_USER_OPERATION: '批量用户操作'
}

const actionLabel = (action) => labels[action] ? `${labels[action]} (${action})` : action

const load = async () => {
  loading.value = true
  try {
    const page = await auditLogsApi({ ...query })
    logs.value = page.records || []
    total.value = page.total || 0
  } finally {
    loading.value = false
  }
}

const search = () => {
  query.pageNum = 1
  load()
}

const openDetail = (row) => {
  currentLog.value = row
  drawerVisible.value = true
}

onMounted(load)
</script>

<style scoped>
.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.filter-form {
  margin-bottom: 12px;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
.detail-text {
  margin-top: 16px;
  white-space: pre-wrap;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 12px;
}
</style>
