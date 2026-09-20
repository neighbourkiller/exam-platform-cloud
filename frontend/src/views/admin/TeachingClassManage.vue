<template>
  <el-card class="page-card">
    <template #header>
      <div class="header-row">
        <span>教学班管理</span>
        <el-button size="small" @click="loadAll">刷新</el-button>
      </div>
    </template>

    <el-form :inline="true" class="toolbar">
      <el-form-item>
        <el-button type="primary" @click="openCreate">新增教学班</el-button>
      </el-form-item>
      <el-form-item label="批量状态">
        <el-select v-model="batchStatus" placeholder="选择状态" style="width: 140px">
          <el-option label="进行中" value="ONGOING" />
          <el-option label="已结课" value="CLOSED" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button :disabled="!selectedRows.length || !batchStatus" @click="batchUpdateStatus">应用</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="classes" @selection-change="selectedRows = $event">
      <el-table-column type="selection" width="48" />
      <el-table-column prop="id" label="ID" width="110" />
      <el-table-column prop="name" label="班级名称" min-width="150" />
      <el-table-column prop="subjectName" label="课程" min-width="140" />
      <el-table-column prop="teacherName" label="教师" min-width="120" />
      <el-table-column prop="term" label="学期" width="140" />
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column prop="capacity" label="容量" width="100" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.id && editing ? '编辑教学班' : '新增教学班'" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="班级ID">
          <el-input v-model="form.id" :disabled="editing" placeholder="可选，不填自动生成" />
        </el-form-item>
        <el-form-item label="班级名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="课程">
          <el-select v-model="form.subjectId" filterable>
            <el-option v-for="course in courses" :key="course.id" :label="course.name" :value="course.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="教师">
          <el-select v-model="form.teacherId" filterable>
            <el-option v-for="teacher in teachers" :key="teacher.id" :label="teacher.realName || teacher.username" :value="teacher.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="学期"><el-input v-model="form.term" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option label="进行中" value="ONGOING" />
            <el-option label="已结课" value="CLOSED" />
          </el-select>
        </el-form-item>
        <el-form-item label="容量"><el-input-number v-model="form.capacity" :min="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  batchTeachingClassesApi,
  createTeachingClassApi,
  listCoursesApi,
  listTeachingClassesApi,
  queryUsersApi,
  updateTeachingClassApi
} from '../../api'

const classes = ref([])
const courses = ref([])
const teachers = ref([])
const selectedRows = ref([])
const batchStatus = ref('')
const dialogVisible = ref(false)
const editing = ref(false)
const form = reactive({ id: '', name: '', subjectId: null, teacherId: null, term: '', status: 'ONGOING', capacity: null })

const resetForm = () => {
  form.id = ''
  form.name = ''
  form.subjectId = null
  form.teacherId = null
  form.term = ''
  form.status = 'ONGOING'
  form.capacity = null
}

const loadAll = async () => {
  const [classData, courseData, userPage] = await Promise.all([
    listTeachingClassesApi(),
    listCoursesApi(),
    queryUsersApi({ pageNum: 1, pageSize: 500, keyword: '' })
  ])
  classes.value = classData || []
  courses.value = courseData || []
  teachers.value = (userPage.records || []).filter((user) => (user.roles || []).some((role) => role.code === 'TEACHER'))
}

const openCreate = () => {
  editing.value = false
  resetForm()
  dialogVisible.value = true
}

const openEdit = (row) => {
  editing.value = true
  form.id = row.id
  form.name = row.name || ''
  form.subjectId = row.subjectId
  form.teacherId = row.teacherId
  form.term = row.term || ''
  form.status = row.status || 'ONGOING'
  form.capacity = row.capacity ?? null
  dialogVisible.value = true
}

const save = async () => {
  const payload = {
    name: (form.name || '').trim(),
    subjectId: form.subjectId,
    teacherId: form.teacherId,
    term: (form.term || '').trim(),
    status: form.status,
    capacity: form.capacity
  }
  if (!payload.name || !payload.subjectId || !payload.teacherId || !payload.term) {
    ElMessage.warning('请完整填写教学班信息')
    return
  }
  if (editing.value) {
    await updateTeachingClassApi(form.id, payload)
  } else {
    const idText = String(form.id || '').trim()
    if (idText) payload.id = Number(idText)
    await createTeachingClassApi(payload)
  }
  ElMessage.success('保存成功')
  dialogVisible.value = false
  await loadAll()
}

const batchUpdateStatus = async () => {
  await batchTeachingClassesApi({
    classIds: selectedRows.value.map((item) => item.id),
    status: batchStatus.value
  })
  ElMessage.success('批量更新成功')
  selectedRows.value = []
  await loadAll()
}

onMounted(loadAll)
</script>

<style scoped>
.header-row,
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.toolbar {
  justify-content: flex-start;
  margin-bottom: 12px;
}
</style>
