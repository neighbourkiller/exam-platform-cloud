<template>
  <div class="course-manage-page">
    <el-card class="page-card">
      <template #header><div class="header">课程管理</div></template>

      <el-card class="section-card">
        <template #header>新增课程</template>
        <el-form :model="courseForm" label-width="90px" class="course-form">
          <el-form-item label="课程ID">
            <el-input
              v-model="courseForm.id"
              placeholder="可选，不填则自动生成"
              clearable
            />
          </el-form-item>
          <el-form-item label="课程名"><el-input v-model="courseForm.name" /></el-form-item>
          <el-form-item label="描述"><el-input v-model="courseForm.description" type="textarea" :rows="3" /></el-form-item>
          <el-form-item>
            <el-button type="primary" @click="createCourse">新增课程</el-button>
            <el-button @click="resetCreateForm">重置</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card class="section-card">
        <template #header>
          <div class="table-header">
            <span>课程列表</span>
            <el-button size="small" @click="loadCourses">刷新</el-button>
          </div>
        </template>

        <el-table :data="courses">
          <el-table-column prop="id" label="课程ID" width="220" />
          <el-table-column prop="name" label="课程名" width="220" />
          <el-table-column prop="description" label="课程描述" />
          <el-table-column label="操作" width="120" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEditDialog(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </el-card>

    <el-dialog v-model="editDialogVisible" title="编辑课程" width="520px">
      <el-form :model="editForm" label-width="90px">
        <el-form-item label="课程ID">
          <el-input :model-value="String(editForm.id ?? '')" disabled />
        </el-form-item>
        <el-form-item label="课程名">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEditCourse">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { createCourseApi, listCoursesApi, updateCourseApi } from '../../api'

const courses = ref([])
const courseForm = reactive({ id: '', name: '', description: '' })
const editDialogVisible = ref(false)
const editForm = reactive({ id: null, name: '', description: '' })

const loadCourses = async () => {
  courses.value = await listCoursesApi()
}

const parseCourseId = (rawId) => {
  const text = String(rawId ?? '').trim()
  if (!text) {
    return null
  }
  if (!/^\d+$/.test(text)) {
    throw new Error('课程ID必须为正整数')
  }
  return text
}

const resetCreateForm = () => {
  courseForm.id = ''
  courseForm.name = ''
  courseForm.description = ''
}

const createCourse = async () => {
  const name = (courseForm.name || '').trim()
  if (!name) {
    ElMessage.warning('请输入课程名')
    return
  }

  let id = null
  try {
    id = parseCourseId(courseForm.id)
  } catch (error) {
    ElMessage.warning(error.message)
    return
  }

  const payload = {
    name,
    description: (courseForm.description || '').trim() || null
  }
  if (id !== null) {
    payload.id = id
  }

  await createCourseApi(payload)
  ElMessage.success('课程创建成功')
  resetCreateForm()
  await loadCourses()
}

const openEditDialog = (row) => {
  editForm.id = row.id
  editForm.name = row.name || ''
  editForm.description = row.description || ''
  editDialogVisible.value = true
}

const saveEditCourse = async () => {
  const name = (editForm.name || '').trim()
  if (!name) {
    ElMessage.warning('请输入课程名')
    return
  }

  await updateCourseApi(editForm.id, {
    name,
    description: (editForm.description || '').trim() || null
  })
  ElMessage.success('课程更新成功')
  editDialogVisible.value = false
  await loadCourses()
}

onMounted(loadCourses)
</script>

<style scoped>

.course-manage-page {
  width: 100%;
}

.section-card + .section-card { margin-top: 16px; }
.table-header { display: flex; justify-content: space-between; align-items: center; }
.course-form { max-width: 680px; }

.page-card {
  border: none;
  border-radius: 16px;
  box-shadow: 0 4px 20px -2px rgba(0, 0, 0, 0.02);
  background-color: #ffffff;
}

:deep(.el-card__header) {
  border-bottom: none;
  padding-bottom: 0;
}

.header { 
  font-size: 20px; 
  font-weight: 700; 
  color: #1e293b;
}

:deep(.el-table) {
  border-radius: 12px;
  overflow: hidden;
}

:deep(.el-table th.el-table__cell) {
  background-color: #f8fafc;
  color: #475569;
  font-weight: 600;
  border-bottom: 1px solid #f1f5f9;
}

:deep(.el-table td.el-table__cell) {
  border-bottom: 1px solid #f1f5f9;
  padding: 12px 0;
}

:deep(.el-table--enable-row-hover .el-table__body tr:hover > td.el-table__cell) {
  background-color: #f8fafc;
}

:deep(.el-input__wrapper), :deep(.el-select__wrapper) {
  border-radius: 8px;
  box-shadow: 0 0 0 1px #e2e8f0 inset;
  background-color: #f8fafc;
  transition: all 0.2s ease;
}

:deep(.el-input__wrapper.is-focus), :deep(.el-select__wrapper.is-focus) {
  box-shadow: 0 0 0 2px #bfdbfe inset, 0 0 0 1px #3b82f6 inset;
  background-color: #ffffff;
}

:deep(.el-button) {
  border-radius: 8px;
  font-weight: 500;
}

:deep(.el-dialog) {
  border-radius: 16px;
}

</style>
