<template>
  <el-card class="page-card">
    <template #header>
      <h1 class="header">题库管理</h1>
    </template>

    <el-form :inline="true" :model="query" class="mb-12">
      <el-form-item label="课程">
        <el-select
          v-model="query.subjectId"
          clearable
          filterable
          placeholder="请选择课程"
          style="width: 260px"
        >
          <el-option
            v-for="course in subjectOptions"
            :key="course.id"
            :label="courseLabel(course)"
            :value="String(course.id)"
          >
            <div class="course-option-row">
              <span class="course-option-id">{{ course.id }}</span>
              <span>{{ course.name }}</span>
            </div>
          </el-option>
        </el-select>
      </el-form-item>
      <el-form-item label="关键字">
        <el-input v-model="query.keyword" placeholder="题干关键字" clearable />
      </el-form-item>
      <el-form-item label="题型">
        <el-select v-model="query.type" clearable style="width: 120px">
          <el-option label="单选" value="SINGLE" />
          <el-option label="多选" value="MULTI" />
          <el-option label="判断" value="JUDGE" />
          <el-option label="填空" value="BLANK" />
          <el-option label="简答" value="SHORT" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadQuestions">查询</el-button>
        <el-button class="ml-8" type="success" @click="openCreateDialog">新增题目</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="tableData">
      <el-table-column prop="subjectName" label="课程名" width="180" />
      <el-table-column label="题型" width="90">
        <template #default="scope">
          {{ questionTypeLabel(scope.row.type) }}
        </template>
      </el-table-column>
      <el-table-column label="难度" width="90">
        <template #default="scope">
          {{ difficultyLabel(scope.row.difficulty) }}
        </template>
      </el-table-column>
      <el-table-column prop="content" label="题目" />
      <el-table-column prop="defaultScore" label="默认分值" width="100" />
      <el-table-column label="操作" width="280">
        <template #default="scope">
          <el-button
            size="small"
            @click="openPreviewDialog(scope.row)"
          >
            查看
          </el-button>
          <el-button
            type="primary"
            size="small"
            :disabled="scope.row.canManage === false"
            @click="openEditDialog(scope.row)"
          >
            修改
          </el-button>
          <el-button
            type="danger"
            size="small"
            :disabled="scope.row.canManage === false"
            @click="del(scope.row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="createDialogVisible"
      :title="dialogTitle"
      width="900px"
      destroy-on-close
    >
      <el-form :model="form" label-width="90px">
        <el-row :gutter="10">
          <el-col :span="10">
            <el-form-item label="课程">
              <el-select
                v-model="form.subjectId"
                clearable
                filterable
                placeholder="请选择课程"
                style="width: 100%"
              >
                <el-option
                  v-for="course in subjectOptions"
                  :key="course.id"
                  :label="courseLabel(course)"
                  :value="String(course.id)"
                >
                  <div class="course-option-row">
                    <span class="course-option-id">{{ course.id }}</span>
                    <span>{{ course.name }}</span>
                  </div>
                </el-option>
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="5">
            <el-form-item label="题型">
              <el-select v-model="form.type">
                <el-option label="单选" value="SINGLE" />
                <el-option label="多选" value="MULTI" />
                <el-option label="判断" value="JUDGE" />
                <el-option label="填空" value="BLANK" />
                <el-option label="简答" value="SHORT" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="5">
            <el-form-item label="难度">
              <el-select v-model="form.difficulty">
                <el-option label="简单" value="EASY" />
                <el-option label="中等" value="MEDIUM" />
                <el-option label="困难" value="HARD" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="4">
            <el-form-item label="分值">
              <el-input v-model.number="form.defaultScore" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="题目">
          <el-input v-model="form.content" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="插图">
          <el-upload
            :show-file-list="false"
            :http-request="uploadImage"
            accept="image/*"
          >
            <el-button type="primary, plain">上传插图</el-button>
          </el-upload>
          <span class="upload-tip">支持 jpg/png/webp，上传后将在下方预览</span>
        </el-form-item>
        <el-form-item v-if="uploadedAssets.length" label="插图预览">
          <div class="asset-preview-list">
            <div
              v-for="asset in uploadedAssets"
              :key="asset.assetId"
              class="asset-preview-item"
            >
              <el-image
                :src="asset.url"
                :preview-src-list="uploadedAssets.map(item => item.url)"
                fit="cover"
                class="asset-thumb"
              />
              <el-button type="danger" text size="small" @click="removeAsset(asset.assetId)">移除</el-button>
            </div>
          </div>
        </el-form-item>
        <el-form-item v-if="showOptionEditor" label="选项">
          <div class="option-editor">
            <div
              v-for="(item, index) in optionItems"
              :key="item.label"
              class="option-row"
            >
              <span class="option-tag">{{ item.label }}:</span>
              <el-input
                v-model="item.value"
                :disabled="isJudgeType"
                placeholder="请输入选项内容"
              />
              <el-button
                v-if="isChoiceType"
                type="danger"
                plain
                @click="removeOption(index)"
              >
                -
              </el-button>
            </div>
            <el-button v-if="isChoiceType" type="primary" plain @click="addOption">+</el-button>
          </div>
        </el-form-item>
        <el-form-item label="答案">
          <el-select
            v-if="isSingleAnswerType"
            v-model="singleAnswerValue"
            clearable
            placeholder="请选择答案选项"
            style="width: 100%"
          >
            <el-option
              v-for="item in answerOptionCandidates"
              :key="item.label"
              :label="`${item.label}: ${item.value}`"
              :value="item.label"
            />
          </el-select>
          <el-select
            v-else-if="isMultiAnswerType"
            v-model="multiAnswerValues"
            multiple
            clearable
            collapse-tags
            collapse-tags-tooltip
            placeholder="请选择多个答案选项"
            style="width: 100%"
          >
            <el-option
              v-for="item in answerOptionCandidates"
              :key="item.label"
              :label="`${item.label}: ${item.value}`"
              :value="item.label"
            />
          </el-select>
          <el-input v-else v-model="form.answer" />
        </el-form-item>
        <el-form-item label="解析">
          <el-input v-model="form.analysis" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="success" @click="submitQuestion">{{ dialogSubmitText }}</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="previewDialogVisible"
      title="题目预览"
      width="900px"
      destroy-on-close
      @closed="resetPreviewDialog"
    >
      <div v-loading="previewLoading" class="question-preview-body">
        <div v-if="previewQuestion.id" class="question-preview">
          <div class="question-preview-header">
            <div class="question-preview-tags">
              <el-tag>{{ questionTypeLabel(previewQuestion.type) }}</el-tag>
              <el-tag type="info">{{ difficultyLabel(previewQuestion.difficulty) }}</el-tag>
              <span class="question-preview-score">{{ displayValue(previewQuestion.defaultScore) }} 分</span>
            </div>
            <div class="question-preview-meta">
              <span>题目ID：{{ displayValue(previewQuestion.id) }}</span>
              <span>课程：{{ displayValue(previewQuestion.subjectName || previewQuestion.subjectId) }}</span>
              <span>创建者ID：{{ displayValue(previewQuestion.creatorId) }}</span>
            </div>
          </div>

          <section class="preview-section">
            <div class="preview-section-title">题干</div>
            <div class="preview-text">{{ displayValue(previewQuestion.content) }}</div>
          </section>

          <section v-if="previewOptions.length" class="preview-section">
            <div class="preview-section-title">选项</div>
            <div class="preview-option-list">
              <div
                v-for="option in previewOptions"
                :key="option.label"
                class="preview-option-item"
              >
                <span class="preview-option-label">{{ option.label }}</span>
                <span class="preview-option-value">{{ option.value }}</span>
              </div>
            </div>
          </section>

          <section v-if="previewImageAssets.length" class="preview-section">
            <div class="preview-section-title">附件插图</div>
            <div class="preview-image-list">
              <el-image
                v-for="asset in previewImageAssets"
                :key="asset.assetId"
                :src="asset.url"
                :preview-src-list="previewImageUrls"
                fit="contain"
                class="preview-image-item"
              />
            </div>
          </section>

          <section v-if="previewOtherAssets.length" class="preview-section">
            <div class="preview-section-title">其他附件</div>
            <div class="preview-attachment-list">
              <a
                v-for="asset in previewOtherAssets"
                :key="asset.assetId"
                :href="asset.url"
                target="_blank"
                rel="noopener noreferrer"
                class="preview-attachment-item"
              >
                {{ asset.originalName || asset.url || asset.assetId }}
              </a>
            </div>
          </section>

          <section class="preview-section">
            <div class="preview-section-title">参考答案</div>
            <div class="preview-text">{{ displayValue(previewQuestion.answer) }}</div>
          </section>

          <section class="preview-section">
            <div class="preview-section-title">解析</div>
            <div class="preview-text">{{ displayValue(previewQuestion.analysis) }}</div>
          </section>
        </div>
        <el-empty v-else-if="!previewLoading" description="暂无可预览内容" />
      </div>
      <template #footer>
        <el-button @click="previewDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, reactive, ref, onMounted, watch, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createQuestionApi,
  getQuestionDetailApi,
  deleteQuestionApi,
  listQuestionSubjectsApi,
  queryQuestionsApi,
  updateQuestionApi,
  uploadQuestionImageApi
} from '../../api'
import { questionTypeLabel, difficultyLabel } from '../../utils/formatters'

const tableData = ref([])
const subjectOptions = ref([])
const uploadedAssets = ref([])
const createDialogVisible = ref(false)
const previewDialogVisible = ref(false)
const previewLoading = ref(false)
const previewLoadSeq = ref(0)
const dialogMode = ref('create')
const editingQuestionId = ref(null)
const formHydrating = ref(false)

const query = reactive({
  pageNum: 1,
  pageSize: 20,
  subjectId: null,
  keyword: '',
  type: null,
  difficulty: null
})

const createFormDefaults = () => ({
  subjectId: null,
  type: 'SINGLE',
  difficulty: 'EASY',
  content: '',
  answer: '',
  analysis: '略',
  defaultScore: 5
})
const form = reactive(createFormDefaults())
const optionItems = ref([])
const singleAnswerValue = ref('')
const multiAnswerValues = ref([])

const createPreviewQuestionDefaults = () => ({
  id: null,
  subjectId: null,
  subjectName: '',
  type: '',
  difficulty: '',
  content: '',
  optionsJson: '',
  answer: '',
  analysis: '',
  defaultScore: null,
  creatorId: null,
  canManage: false,
  assets: []
})
const previewQuestion = reactive(createPreviewQuestionDefaults())

const courseLabel = (course) => `${course.id} - ${course.name}`

const normalizeId = (value) => (value === null || value === undefined ? null : String(value))
const displayValue = (value) => (value === null || value === undefined || value === '' ? '-' : value)
const normalizeQuestionRecords = (records) =>
  (records || []).map(item => ({
    ...item,
    id: normalizeId(item?.id)
  }))

const loadSubjectOptions = async () => {
  subjectOptions.value = await listQuestionSubjectsApi()
}

const loadQuestions = async () => {
  const payload = {
    ...query,
    subjectId: query.subjectId || null,
    keyword: (query.keyword || '').trim() || null,
    type: query.type || null,
    difficulty: query.difficulty || null
  }
  const data = await queryQuestionsApi(payload)
  tableData.value = normalizeQuestionRecords(data.records)
}

const optionLabel = (index) => String.fromCharCode(65 + index)

const relabelOptions = () => {
  optionItems.value = optionItems.value.map((item, index) => ({
    ...item,
    label: optionLabel(index)
  }))
}

const resetChoiceOptions = () => {
  optionItems.value = [
    { label: 'A', value: '' },
    { label: 'B', value: '' }
  ]
}

const resetJudgeOptions = () => {
  optionItems.value = [
    { label: 'A', value: 'true' },
    { label: 'B', value: 'false' }
  ]
}

const isChoiceType = computed(() => form.type === 'SINGLE' || form.type === 'MULTI')
const isJudgeType = computed(() => form.type === 'JUDGE')
const showOptionEditor = computed(() => isChoiceType.value || isJudgeType.value)
const isSingleAnswerType = computed(() => form.type === 'SINGLE' || form.type === 'JUDGE')
const isMultiAnswerType = computed(() => form.type === 'MULTI')
const dialogTitle = computed(() => (dialogMode.value === 'edit' ? '修改题目' : '新增题目'))
const dialogSubmitText = computed(() => (dialogMode.value === 'edit' ? '保存修改' : '新增题目'))
const answerOptionCandidates = computed(() =>
  optionItems.value.filter(item => (item.value || '').trim())
)

const resetCreateForm = () => {
  dialogMode.value = 'create'
  editingQuestionId.value = null
  Object.assign(form, createFormDefaults())
  uploadedAssets.value = []
  singleAnswerValue.value = ''
  multiAnswerValues.value = []
  resetChoiceOptions()
}

const openCreateDialog = () => {
  resetCreateForm()
  createDialogVisible.value = true
}

const normalizeUploadedAssets = (assets) => {
  if (!Array.isArray(assets)) {
    return []
  }
  return assets
    .filter(asset => asset && asset.assetId !== null && asset.assetId !== undefined)
    .map(asset => ({
      assetId: normalizeId(asset.assetId),
      url: asset.url,
      objectKey: asset.objectKey,
      originalName: asset.originalName,
      size: asset.size,
      fileType: asset.fileType,
      contentType: asset.contentType
    }))
}

const parseOptionsFromJson = (optionsJson) => {
  if (!optionsJson) {
    return []
  }
  try {
    const parsed = JSON.parse(optionsJson)
    if (!Array.isArray(parsed)) {
      return []
    }
    return parsed
      .filter(item => item && item.value !== undefined && item.value !== null)
      .map((item, index) => ({
        label: item.label || optionLabel(index),
        value: String(item.value)
      }))
  } catch {
    return []
  }
}

const syncAnswerControlsFromRaw = () => {
  singleAnswerValue.value = ''
  multiAnswerValues.value = []
  const raw = (form.answer || '').trim()
  if (!raw) {
    return
  }
  if (isSingleAnswerType.value) {
    singleAnswerValue.value = raw.split(',')[0].trim()
    return
  }
  if (isMultiAnswerType.value) {
    multiAnswerValues.value = raw
      .split(',')
      .map(item => item.trim())
      .filter(item => item)
  }
}

const isImageAsset = (asset) => (asset?.fileType || '').toUpperCase() === 'IMAGE'
const previewAssets = computed(() => normalizeUploadedAssets(previewQuestion.assets))
const previewImageAssets = computed(() => previewAssets.value.filter(asset => isImageAsset(asset)))
const previewOtherAssets = computed(() => previewAssets.value.filter(asset => !isImageAsset(asset)))
const previewImageUrls = computed(() =>
  previewImageAssets.value
    .map(asset => asset.url)
    .filter(url => !!url)
)
const previewOptions = computed(() => parseOptionsFromJson(previewQuestion.optionsJson))

const resetPreviewDialog = () => {
  previewLoadSeq.value += 1
  Object.assign(previewQuestion, createPreviewQuestionDefaults())
  previewLoading.value = false
}

const normalizeQuestionDetail = (detail = {}) => ({
  ...createPreviewQuestionDefaults(),
  ...detail,
  id: normalizeId(detail.id),
  subjectId: normalizeId(detail.subjectId),
  creatorId: normalizeId(detail.creatorId),
  subjectName: detail.subjectName || '',
  type: detail.type || '',
  difficulty: detail.difficulty || '',
  content: detail.content || '',
  optionsJson: detail.optionsJson || '',
  answer: detail.answer || '',
  analysis: detail.analysis || '',
  defaultScore: detail.defaultScore ?? null,
  assets: normalizeUploadedAssets(detail.assets)
})

const openPreviewDialog = async (row) => {
  const questionId = normalizeId(row?.id)
  if (!questionId) {
    ElMessage.warning('题目ID无效，无法查看')
    return
  }

  previewDialogVisible.value = true
  resetPreviewDialog()
  previewLoading.value = true
  const seq = ++previewLoadSeq.value
  try {
    const detail = await getQuestionDetailApi(questionId)
    if (seq !== previewLoadSeq.value) {
      return
    }
    Object.assign(previewQuestion, normalizeQuestionDetail(detail))
  } catch (error) {
    if (seq === previewLoadSeq.value) {
      previewDialogVisible.value = false
    }
    throw error
  } finally {
    if (seq === previewLoadSeq.value) {
      previewLoading.value = false
    }
  }
}

const openEditDialog = async (row) => {
  const questionId = normalizeId(row?.id)
  if (!questionId) {
    ElMessage.warning('题目ID无效，无法修改')
    return
  }
  if (row.canManage === false) {
    ElMessage.warning('无权限修改该题目')
    return
  }

  formHydrating.value = true
  try {
    const detail = await getQuestionDetailApi(questionId)
    dialogMode.value = 'edit'
    editingQuestionId.value = questionId

    Object.assign(form, {
      subjectId: normalizeId(detail.subjectId),
      type: detail.type || 'SINGLE',
      difficulty: detail.difficulty || 'EASY',
      content: detail.content || '',
      answer: detail.answer || '',
      analysis: detail.analysis || '略',
      defaultScore: detail.defaultScore ?? 5
    })

    const parsedOptions = parseOptionsFromJson(detail.optionsJson)
    if (form.type === 'JUDGE') {
      optionItems.value = parsedOptions.length ? parsedOptions : [
        { label: 'A', value: 'true' },
        { label: 'B', value: 'false' }
      ]
    } else if (form.type === 'SINGLE' || form.type === 'MULTI') {
      optionItems.value = parsedOptions.length >= 2 ? parsedOptions : [
        { label: 'A', value: '' },
        { label: 'B', value: '' }
      ]
    } else {
      optionItems.value = []
    }

    uploadedAssets.value = normalizeUploadedAssets(detail.assets)
    syncAnswerControlsFromRaw()
    createDialogVisible.value = true
  } finally {
    await nextTick()
    formHydrating.value = false
  }
}

watch(
  () => form.type,
  (newType) => {
    if (formHydrating.value) {
      return
    }
    singleAnswerValue.value = ''
    multiAnswerValues.value = []
    form.answer = ''
    if (newType === 'JUDGE') {
      resetJudgeOptions()
      return
    }
    if (newType === 'SINGLE' || newType === 'MULTI') {
      resetChoiceOptions()
      return
    }
    optionItems.value = []
  },
  { immediate: true }
)

watch(
  optionItems,
  () => {
    const labels = new Set(optionItems.value.map(item => item.label))
    if (singleAnswerValue.value && !labels.has(singleAnswerValue.value)) {
      singleAnswerValue.value = ''
    }
    if (multiAnswerValues.value.length) {
      multiAnswerValues.value = multiAnswerValues.value.filter(label => labels.has(label))
    }
  },
  { deep: true }
)

const addOption = () => {
  if (!isChoiceType.value) {
    return
  }
  if (optionItems.value.length >= 26) {
    ElMessage.warning('选项最多支持26个')
    return
  }
  optionItems.value = [...optionItems.value, { label: '', value: '' }]
  relabelOptions()
}

const removeOption = (index) => {
  if (!isChoiceType.value) {
    return
  }
  if (optionItems.value.length <= 2) {
    ElMessage.warning('单选/多选至少保留2个选项')
    return
  }
  optionItems.value.splice(index, 1)
  relabelOptions()
}

const buildOptionsJson = () => {
  if (!showOptionEditor.value) {
    return null
  }
  if (isChoiceType.value) {
    const hasEmpty = optionItems.value.some(item => !(item.value || '').trim())
    if (hasEmpty) {
      throw new Error('请完整填写所有选项内容')
    }
  }
  return JSON.stringify(
    optionItems.value.map(item => ({
      label: item.label,
      value: item.value
    }))
  )
}

const buildAnswerValue = () => {
  if (isSingleAnswerType.value) {
    if (!singleAnswerValue.value) {
      throw new Error('请选择答案选项')
    }
    return singleAnswerValue.value
  }
  if (isMultiAnswerType.value) {
    if (!multiAnswerValues.value.length) {
      throw new Error('请至少选择一个答案选项')
    }
    return [...multiAnswerValues.value].sort().join(',')
  }
  return (form.answer || '').trim()
}

const buildPayload = () => {
  let optionsJson = null
  let answer = ''
  if (!form.subjectId) {
    ElMessage.warning('请选择课程')
    return null
  }
  try {
    optionsJson = buildOptionsJson()
    answer = buildAnswerValue()
  } catch (error) {
    ElMessage.warning(error.message)
    return null
  }

  return {
    ...form,
    answer,
    optionsJson,
    assetIds: uploadedAssets.value
      .map(asset => normalizeId(asset.assetId))
      .filter(assetId => !!assetId)
  }
}

const submitQuestion = async () => {
  const payload = buildPayload()
  if (!payload) {
    return
  }

  if (dialogMode.value === 'edit') {
    if (!editingQuestionId.value) {
      ElMessage.warning('缺少题目ID，无法保存修改')
      return
    }
    await updateQuestionApi(editingQuestionId.value, payload)
    ElMessage.success('修改成功')
  } else {
    await createQuestionApi(payload)
    ElMessage.success('新增成功')
  }

  createDialogVisible.value = false
  resetCreateForm()
  await loadQuestions()
}

const removeAsset = (assetId) => {
  uploadedAssets.value = uploadedAssets.value.filter(asset => asset.assetId !== assetId)
}

const del = async (row) => {
  const questionId = normalizeId(row?.id)
  if (!questionId) {
    ElMessage.warning('题目ID无效，无法删除')
    return
  }
  await ElMessageBox.confirm('确认删除该题目吗？若已被试卷引用将无法删除。', '提示')
  await deleteQuestionApi(questionId)
  ElMessage.success('删除成功')
  await loadQuestions()
}

const uploadImage = async (uploadOption) => {
  const formData = new FormData()
  formData.append('file', uploadOption.file)
  try {
    const result = await uploadQuestionImageApi(formData)
    const normalized = normalizeUploadedAssets([result])[0]
    if (!normalized) {
      throw new Error('上传成功但未返回有效附件ID')
    }
    uploadedAssets.value = [...uploadedAssets.value, normalized]
    ElMessage.success('插图上传成功')
    if (uploadOption.onSuccess) {
      uploadOption.onSuccess(normalized)
    }
  } catch (error) {
    if (uploadOption.onError) {
      uploadOption.onError(error)
    }
  }
}

onMounted(async () => {
  await loadSubjectOptions()
  await loadQuestions()
})
</script>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}

.ml-8 {
  margin-left: 8px;
}

.course-option-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.course-option-id {
  color: #606266;
  margin-right: 12px;
  font-family: monospace;
}

.upload-tip {
  margin-left: 12px;
  color: #909399;
  font-size: 12px;
}

.asset-preview-list {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.asset-preview-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.asset-thumb {
  width: 96px;
  height: 96px;
  border-radius: 4px;
  border: 1px solid #dcdfe6;
}

.option-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.option-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.option-tag {
  width: 26px;
  color: #606266;
  font-weight: 600;
}

.question-preview-body {
  min-height: 220px;
}

.question-preview {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.question-preview-header {
  padding: 14px 16px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background-color: #f8fafc;
}

.question-preview-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.question-preview-score {
  color: #475569;
  font-size: 13px;
  font-weight: 600;
}

.question-preview-meta {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 8px 16px;
  color: #64748b;
  font-size: 13px;
  word-break: break-word;
}

.preview-section {
  padding-bottom: 14px;
  border-bottom: 1px solid #eef2f7;
}

.preview-section:last-child {
  padding-bottom: 0;
  border-bottom: none;
}

.preview-section-title {
  margin-bottom: 8px;
  color: #334155;
  font-size: 13px;
  font-weight: 700;
}

.preview-text {
  color: #1f2937;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.preview-option-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.preview-option-item {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background-color: #ffffff;
}

.preview-option-label {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background-color: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 700;
}

.preview-option-value {
  color: #334155;
  line-height: 1.6;
  word-break: break-word;
}

.preview-image-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.preview-image-item {
  width: 160px;
  height: 120px;
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  background-color: #f8fafc;
}

.preview-attachment-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.preview-attachment-item {
  max-width: 100%;
  padding: 6px 10px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background-color: #eff6ff;
  color: #2563eb;
  text-decoration: none;
  word-break: break-all;
}

.preview-attachment-item:hover {
  border-color: #93c5fd;
  color: #1d4ed8;
}

</style>
