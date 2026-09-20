<template>
  <el-card class="page-card">
    <template #header>
      <div class="header-row">
        <span>批量导入</span>
        <el-button size="small" @click="downloadTemplate">下载当前模板</el-button>
      </div>
    </template>

    <el-tabs v-model="activeType">
      <el-tab-pane label="学生" name="STUDENT" />
      <el-tab-pane label="教师" name="TEACHER" />
      <el-tab-pane label="课程" name="COURSE" />
      <el-tab-pane label="教学班" name="CLASS" />
      <el-tab-pane label="考试安排" name="EXAM" />
    </el-tabs>

    <el-upload
      drag
      :auto-upload="false"
      :limit="1"
      :on-change="onFileChange"
      :on-remove="onFileRemove"
      accept=".csv,.tsv,text/csv,text/tab-separated-values"
    >
      <div class="upload-text">拖入 CSV/TSV 文件，或点击选择</div>
    </el-upload>

    <div class="actions">
      <el-button type="primary" :disabled="!selectedFile" :loading="loading" @click="runImport(true)">预校验</el-button>
      <el-button type="success" :disabled="!selectedFile || !canConfirm" :loading="loading" @click="runImport(false)">确认导入</el-button>
    </div>

    <el-alert
      v-if="result"
      :title="`总行数 ${result.total || 0}，成功 ${result.successCount || 0}，失败 ${result.failureCount || 0}`"
      :type="(result.failureCount || 0) > 0 ? 'warning' : 'success'"
      show-icon
      class="result-alert"
    />

    <el-table v-if="errors.length" :data="errors" class="error-table">
      <el-table-column prop="rowNumber" label="行号" width="90" />
      <el-table-column prop="field" label="字段" width="160" />
      <el-table-column prop="message" label="错误" min-width="220" />
      <el-table-column prop="rawValue" label="原始值" min-width="160" />
    </el-table>
  </el-card>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { importCoursesApi, importExamSchedulesApi, importTeachingClassesApi, importUsersApi } from '../../api'

const activeType = ref('STUDENT')
const selectedFile = ref(null)
const result = ref(null)
const loading = ref(false)

const errors = computed(() => result.value?.errors || [])
const canConfirm = computed(() => result.value && (result.value.failureCount || 0) === 0)

const templates = {
  STUDENT: 'studentNo,realName,password,enrollmentYear,teachingClassIds\nS001,张三,123456,2026,"1001,1002"\n',
  TEACHER: 'username,realName,password,teacherNo,title\nteacher01,李老师,123456,T001,讲师\n',
  COURSE: 'id,name,description\n1,Java程序设计,Java基础课程\n',
  CLASS: 'id,name,subjectId,teacherId,teacherUsername,term,status,capacity\n1001,高一数学1班,1,2,,2026春,ONGOING,60\n',
  EXAM: 'name,paperId,startTime,endTime,durationMinutes,passScore,targetClassIds,autoPublish,proctoringLevel\n期中考试,1,2026-05-01 09:00:00,2026-05-01 11:00:00,120,60,"1001,1002",true,STRICT\n'
}

const onFileChange = (uploadFile) => {
  selectedFile.value = uploadFile.raw
  result.value = null
}

const onFileRemove = () => {
  selectedFile.value = null
  result.value = null
}

const buildFormData = () => {
  const formData = new FormData()
  formData.append('file', selectedFile.value)
  return formData
}

const runImport = async (dryRun) => {
  if (!selectedFile.value) return
  loading.value = true
  try {
    const formData = buildFormData()
    if (activeType.value === 'STUDENT' || activeType.value === 'TEACHER') {
      result.value = await importUsersApi(activeType.value, dryRun, formData)
    } else if (activeType.value === 'COURSE') {
      result.value = await importCoursesApi(dryRun, formData)
    } else if (activeType.value === 'CLASS') {
      result.value = await importTeachingClassesApi(dryRun, formData)
    } else {
      result.value = await importExamSchedulesApi(dryRun, formData)
    }
    ElMessage.success(dryRun ? '预校验完成' : '导入完成')
  } finally {
    loading.value = false
  }
}

const downloadTemplate = () => {
  const blob = new Blob([templates[activeType.value]], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${activeType.value.toLowerCase()}-import-template.csv`
  link.click()
  URL.revokeObjectURL(url)
}
</script>

<style scoped>
.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.upload-text {
  padding: 24px;
  color: #64748b;
}
.actions {
  margin-top: 16px;
  display: flex;
  gap: 10px;
}
.result-alert,
.error-table {
  margin-top: 16px;
}
</style>
