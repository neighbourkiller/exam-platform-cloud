<template>
  <el-card class="page-card">
    <template #header>
      <h1 class="header">班级管理</h1>
    </template>

    <el-row :gutter="16">
      <el-col :span="10">
        <el-card class="section-card">
          <template #header>
            <div class="section-header">
              <span>我的教学班</span>
              <el-button size="small" @click="loadClasses">刷新</el-button>
            </div>
          </template>
          <el-table
            v-if="classes.length || loadingClasses"
            v-loading="loadingClasses"
            :data="classes"
            highlight-current-row
            row-key="id"
            :current-row-key="selectedClassId"
            @current-change="onClassSelect"
          >
            <el-table-column prop="id" label="班级ID" width="160" />
            <el-table-column prop="name" label="班级名称" min-width="180" />
            <el-table-column label="课程" min-width="150">
              <template #default="{ row }">{{ row.subjectName || row.subjectId }}</template>
            </el-table-column>
            <el-table-column prop="studentCount" label="人数" width="80" />
          </el-table>
          <el-empty v-else description="暂无教学班" />
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card class="section-card">
          <template #header>
            <div class="section-header">
              <span>{{ selectedClass ? `${selectedClass.name} - 学生列表` : '班级学生' }}</span>
              <div class="section-actions">
                <el-button type="primary" :disabled="!selectedClassId" @click="openAddDialog">添加学生</el-button>
                <el-button :disabled="!selectedClassId" @click="loadStudents">刷新</el-button>
              </div>
            </div>
          </template>

          <el-empty v-if="!selectedClassId" description="请先选择左侧教学班" />
          <el-table v-else v-loading="loadingStudents" :data="students">
            <el-table-column prop="id" label="学生ID" width="160" />
            <el-table-column prop="studentNo" label="学号" width="160" />
            <el-table-column prop="username" label="用户名" width="150" />
            <el-table-column prop="realName" label="姓名" min-width="120" />
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button type="danger" text @click="removeStudent(row)">移除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="candidateDialogVisible" title="添加学生" width="860px">
      <el-form :inline="true" :model="candidateQuery" class="mb-12">
        <el-form-item label="关键字">
          <el-input v-model="candidateQuery.keyword" placeholder="学号/用户名/姓名" clearable style="width: 220px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="searchCandidates">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="candidateRows" @selection-change="onCandidateSelectionChange">
        <el-table-column type="selection" width="54" />
        <el-table-column prop="id" label="学生ID" width="160" />
        <el-table-column prop="studentNo" label="学号" width="160" />
        <el-table-column prop="username" label="用户名" width="150" />
        <el-table-column prop="realName" label="姓名" min-width="120" />
      </el-table>

      <div class="pager-row">
        <el-pagination
          v-model:current-page="candidateQuery.pageNum"
          v-model:page-size="candidateQuery.pageSize"
          :page-sizes="[10, 20, 50]"
          :total="candidateTotal"
          layout="total, sizes, prev, pager, next"
          @size-change="loadCandidates"
          @current-change="loadCandidates"
        />
      </div>

      <template #footer>
        <el-button @click="candidateDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmAddStudents">添加选中学生</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  teacherClassAddStudentsApi,
  teacherClassRemoveStudentApi,
  teacherClassStudentCandidatesApi,
  teacherClassStudentsApi,
  teacherClassesApi
} from '../../api'

const classes = ref([])
const selectedClassId = ref('')
const students = ref([])
const selectedCandidateIds = ref([])

const loadingClasses = ref(false)
const loadingStudents = ref(false)

const candidateDialogVisible = ref(false)
const candidateRows = ref([])
const candidateTotal = ref(0)
const candidateQuery = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: ''
})

const selectedClass = computed(() =>
  classes.value.find(item => String(item.id) === String(selectedClassId.value)) || null
)

const loadClasses = async () => {
  loadingClasses.value = true
  try {
    classes.value = await teacherClassesApi()
    if (!classes.value.length) {
      selectedClassId.value = ''
      students.value = []
      return
    }
    if (!selectedClass.value) {
      selectedClassId.value = classes.value[0].id
    }
    await loadStudents()
  } finally {
    loadingClasses.value = false
  }
}

const onClassSelect = async (row) => {
  selectedClassId.value = row ? row.id : ''
  await loadStudents()
}

const loadStudents = async () => {
  if (!selectedClassId.value) {
    students.value = []
    return
  }
  loadingStudents.value = true
  try {
    students.value = await teacherClassStudentsApi(selectedClassId.value)
  } finally {
    loadingStudents.value = false
  }
}

const loadCandidates = async () => {
  if (!selectedClassId.value) {
    return
  }
  const page = await teacherClassStudentCandidatesApi(selectedClassId.value, candidateQuery)
  candidateRows.value = page.records || []
  candidateTotal.value = page.total || 0
}

const searchCandidates = async () => {
  candidateQuery.pageNum = 1
  await loadCandidates()
}

const openAddDialog = async () => {
  if (!selectedClassId.value) {
    ElMessage.warning('请先选择教学班')
    return
  }
  selectedCandidateIds.value = []
  candidateQuery.pageNum = 1
  candidateQuery.pageSize = 10
  candidateQuery.keyword = ''
  candidateDialogVisible.value = true
  await loadCandidates()
}

const onCandidateSelectionChange = (rows) => {
  selectedCandidateIds.value = (rows || []).map(item => item.id)
}

const confirmAddStudents = async () => {
  if (!selectedClassId.value) {
    return
  }
  if (!selectedCandidateIds.value.length) {
    ElMessage.warning('请先选择学生')
    return
  }
  await teacherClassAddStudentsApi(selectedClassId.value, { studentIds: selectedCandidateIds.value })
  ElMessage.success('添加成功')
  candidateDialogVisible.value = false
  await loadStudents()
  await loadClasses()
}

const removeStudent = async (row) => {
  if (!selectedClassId.value) {
    return
  }
  await ElMessageBox.confirm(`确认将 ${row.realName || row.username} 移出当前教学班吗？`, '提示')
  await teacherClassRemoveStudentApi(selectedClassId.value, row.id)
  ElMessage.success('移除成功')
  await loadStudents()
  await loadClasses()
}

onMounted(loadClasses)
</script>

<style scoped>


.section-card {
  min-height: 520px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-actions {
  display: flex;
  gap: 8px;
}

.mb-12 {
  margin-bottom: 12px;
}

.pager-row {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>

