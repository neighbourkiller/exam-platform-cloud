<template>
  <el-card class="page-card">
    <template #header>
      <div class="page-header">
        <div>
          <h1 class="header">组卷管理</h1>
          <p class="header-subtitle">维护试卷模板、手动选题与自动组卷规则</p>
        </div>
        <el-button type="success" size="large" @click="openCreateManualDialog">手动组卷</el-button>
      </div>
    </template>

    <el-card class="section-card auto-section">
      <template #header>
        <div class="section-header">
          <div>
            <span class="section-title">智能组卷</span>
            <span class="section-note">按题型、难度、数量和分值生成试卷</span>
          </div>
          <div class="section-actions">
            <el-button @click="addRule">新增规则</el-button>
            <el-button type="primary" @click="autoCreate">生成试卷</el-button>
          </div>
        </div>
      </template>
      <el-form :model="autoForm" label-width="90px" class="auto-form">
        <el-row :gutter="12">
          <el-col :xs="24" :md="8">
            <el-form-item label="试卷名称"><el-input v-model="autoForm.name" /></el-form-item>
          </el-col>
          <el-col :xs="24" :md="8">
            <el-form-item label="课程">
              <el-select
                v-model="autoForm.subjectId"
                filterable
                clearable
                placeholder="请选择课程"
                style="width: 100%"
              >
                <el-option
                  v-for="course in subjectOptions"
                  :key="course.id"
                  :label="courseLabel(course)"
                  :value="course.id"
                >
                  <div class="course-option-row">
                    <span class="course-option-id">{{ course.id }}</span>
                    <span>{{ course.name }}</span>
                  </div>
                </el-option>
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :md="8">
            <el-form-item label="描述"><el-input v-model="autoForm.description" /></el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <el-table :data="autoForm.rules">
        <el-table-column label="题型">
          <template #default="scope">
            <el-select v-model="scope.row.type" size="small">
              <el-option value="SINGLE" label="单选" />
              <el-option value="MULTI" label="多选" />
              <el-option value="JUDGE" label="判断" />
              <el-option value="BLANK" label="填空" />
              <el-option value="SHORT" label="简答" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="难度">
          <template #default="scope">
            <el-select v-model="scope.row.difficulty" size="small">
              <el-option :value="AUTO_DIFFICULTY_ALL" label="不限" />
              <el-option value="EASY" label="简单" />
              <el-option value="MEDIUM" label="中等" />
              <el-option value="HARD" label="困难" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="150">
          <template #default="scope"><el-input-number v-model="scope.row.count" :min="1" size="small" /></template>
        </el-table-column>
        <el-table-column label="分值" width="150">
          <template #default="scope"><el-input-number v-model="scope.row.score" :min="1" size="small" /></template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="scope">
            <el-button type="danger" text @click="removeRule(scope.$index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="section-card paper-list-card">
      <template #header>
        <div class="list-header">
          <span>试卷列表</span>
        </div>
      </template>

      <el-form :inline="true" :model="paperQuery" class="mb-12">
        <el-form-item label="课程">
          <el-select
            v-model="paperQuery.subjectId"
            filterable
            clearable
            placeholder="请选择课程"
            style="width: 240px"
          >
            <el-option
              v-for="course in subjectOptions"
              :key="course.id"
              :label="courseLabel(course)"
              :value="course.id"
            >
              <div class="course-option-row">
                <span class="course-option-id">{{ course.id }}</span>
                <span>{{ course.name }}</span>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="试卷名">
          <el-input v-model="paperQuery.name" clearable placeholder="输入试卷名称" style="width: 240px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="searchPaperList">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="paperList">
        <el-table-column prop="id" label="试卷ID" width="140" />
        <el-table-column prop="name" label="试卷名称" min-width="220" />
        <el-table-column label="课程" width="180">
          <template #default="scope">{{ scope.row.subjectName || scope.row.subjectId }}</template>
        </el-table-column>
        <el-table-column prop="totalScore" label="总分" width="90" />
        <el-table-column prop="teacherId" label="出卷人" width="100" />
        <el-table-column label="创建时间" width="190">
          <template #default="scope">{{ formatDateTime(scope.row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="320">
          <template #default="scope">
            <el-button size="small" @click="openPreviewDialog(scope.row)">预览</el-button>
            <el-button
              size="small"
              type="success"
              plain
              :loading="exportingPaperId === scope.row.id"
              @click="exportPaperPdf(scope.row)"
            >
              导出PDF
            </el-button>
            <el-button
              size="small"
              type="primary"
              :disabled="scope.row.canManage === false"
              @click="openEditManualDialog(scope.row)"
            >
              修改
            </el-button>
            <el-button
              size="small"
              type="danger"
              :disabled="scope.row.canManage === false"
              @click="deletePaper(scope.row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager-row">
        <el-pagination
          v-model:current-page="paperQuery.pageNum"
          v-model:page-size="paperQuery.pageSize"
          :page-sizes="[10, 20, 50]"
          :total="paperTotal"
          layout="total, sizes, prev, pager, next"
          @size-change="loadPaperList"
          @current-change="loadPaperList"
        />
      </div>
    </el-card>

    <el-dialog
      v-model="manualDialogVisible"
      :title="manualDialogTitle"
      width="1100px"
      destroy-on-close
    >
      <el-form :model="manualForm" label-width="90px">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="试卷名称"><el-input v-model="manualForm.name" /></el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="课程">
              <el-select
                v-model="manualForm.subjectId"
                filterable
                clearable
                placeholder="请选择课程"
                style="width: 100%"
              >
                <el-option
                  v-for="course in subjectOptions"
                  :key="course.id"
                  :label="courseLabel(course)"
                  :value="course.id"
                >
                  <div class="course-option-row">
                    <span class="course-option-id">{{ course.id }}</span>
                    <span>{{ course.name }}</span>
                  </div>
                </el-option>
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="描述"><el-input v-model="manualForm.description" /></el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <el-divider>题库选择</el-divider>

      <el-form :inline="true" :model="manualQuestionQuery" class="mb-12">
        <el-form-item label="关键字">
          <el-input v-model="manualQuestionQuery.keyword" clearable placeholder="题干关键字" style="width: 220px" />
        </el-form-item>
        <el-form-item label="题型">
          <el-select v-model="manualQuestionQuery.type" clearable style="width: 120px">
            <el-option value="SINGLE" label="单选" />
            <el-option value="MULTI" label="多选" />
            <el-option value="JUDGE" label="判断" />
            <el-option value="BLANK" label="填空" />
            <el-option value="SHORT" label="简答" />
          </el-select>
        </el-form-item>
        <el-form-item label="难度">
          <el-select v-model="manualQuestionQuery.difficulty" clearable style="width: 120px">
            <el-option value="EASY" label="简单" />
            <el-option value="MEDIUM" label="中等" />
            <el-option value="HARD" label="困难" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="searchManualQuestions">查询题目</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="candidateQuestions" max-height="250">
        <el-table-column prop="id" label="题目ID" width="120" />
        <el-table-column prop="type" label="题型" width="90" />
        <el-table-column prop="difficulty" label="难度" width="90" />
        <el-table-column prop="content" label="题目" min-width="360" show-overflow-tooltip />
        <el-table-column prop="defaultScore" label="默认分值" width="100" />
        <el-table-column label="操作" width="100">
          <template #default="scope">
            <el-button
              size="small"
              type="primary"
              :disabled="selectedQuestionIdSet.has(scope.row.id)"
              @click="addQuestionToManual(scope.row)"
            >
              添加
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-divider>已选题目</el-divider>

      <el-table :data="selectedQuestions" max-height="260">
        <el-table-column prop="sortOrder" label="序号" width="70" />
        <el-table-column prop="questionId" label="题目ID" width="120" />
        <el-table-column prop="type" label="题型" width="90" />
        <el-table-column prop="content" label="题目" min-width="360" show-overflow-tooltip />
        <el-table-column label="分值" width="130">
          <template #default="scope">
            <el-input-number v-model="scope.row.score" :min="1" :max="100" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170">
          <template #default="scope">
            <el-button text :disabled="scope.$index === 0" @click="moveQuestion(scope.$index, -1)">上移</el-button>
            <el-button text :disabled="scope.$index === selectedQuestions.length - 1" @click="moveQuestion(scope.$index, 1)">下移</el-button>
            <el-button text type="danger" @click="removeSelectedQuestion(scope.$index)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="manualDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitManualPaper">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="previewDialogVisible"
      title="试卷预览"
      width="900px"
      destroy-on-close
      @closed="resetPreviewDialog"
    >
      <div v-loading="previewLoading" class="preview-body">
        <div v-if="previewPaper.id" class="preview-paper">
          <div class="preview-paper-header">
            <h2 class="preview-paper-title">{{ previewPaper.name }}</h2>
            <p class="preview-paper-subtitle">
              课程：{{ previewPaper.subjectName || previewPaper.subjectId }} | 出卷人：{{ previewPaper.teacherId || '-' }} | 总分：{{ previewPaper.totalScore }}
            </p>
            <p v-if="previewPaper.description" class="preview-paper-description">
              说明：{{ previewPaper.description }}
            </p>
          </div>
          <el-divider />
          <div class="preview-question-list">
            <section
              v-for="question in previewQuestions"
              :key="question.previewKey"
              class="preview-question-item"
            >
              <div class="preview-question-title">
                <span class="preview-question-index">{{ question.displayOrder }}.</span>
                <span class="preview-question-type">[{{ question.typeLabel }}]</span>
                <span class="preview-question-content">{{ question.content || '-' }}</span>
                <span class="preview-question-score">（{{ question.score || 0 }}分）</span>
              </div>
              <div v-if="question.options.length" class="preview-option-list">
                <p
                  v-for="option in question.options"
                  :key="`${question.previewKey}-${option.label}`"
                  class="preview-option-item"
                >
                  {{ option.label }}. {{ option.value }}
                </p>
              </div>
              <div v-if="question.imageAssets.length" class="preview-image-list">
                <el-image
                  v-for="asset in question.imageAssets"
                  :key="asset.assetId || asset.url"
                  :src="asset.url"
                  fit="contain"
                  class="preview-image-item"
                  :preview-src-list="question.imageAssets.map(item => item.url)"
                />
              </div>
              <div v-if="question.otherAssets.length" class="preview-attachment-list">
                <a
                  v-for="asset in question.otherAssets"
                  :key="asset.assetId || asset.url"
                  :href="asset.url"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="preview-attachment-item"
                >
                  {{ asset.originalName || asset.url }}
                </a>
              </div>
            </section>
          </div>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createAutoPaperApi,
  createManualPaperApi,
  deletePaperApi,
  listQuestionSubjectsApi,
  paperDetailApi,
  queryPapersApi,
  queryQuestionsApi,
  updatePaperApi
} from '../../api'
import { formatDateTime } from '../../utils/datetime'
import { questionTypeLabel } from '../../utils/formatters'

const AUTO_DIFFICULTY_ALL = 'ALL'
const DEFAULT_AUTO_RULES = [
  { type: 'SINGLE', difficulty: AUTO_DIFFICULTY_ALL, count: 5, score: 5 },
  { type: 'MULTI', difficulty: AUTO_DIFFICULTY_ALL, count: 5, score: 5 },
  { type: 'JUDGE', difficulty: AUTO_DIFFICULTY_ALL, count: 3, score: 2 },
  { type: 'BLANK', difficulty: AUTO_DIFFICULTY_ALL, count: 2, score: 2 },
  { type: 'SHORT', difficulty: AUTO_DIFFICULTY_ALL, count: 4, score: 10 }
]

const autoForm = reactive({
  name: 'Java 自动组卷',
  subjectId: null,
  description: '自动生成',
  rules: DEFAULT_AUTO_RULES.map(rule => ({ ...rule }))
})

const subjectOptions = ref([])

const paperQuery = reactive({
  pageNum: 1,
  pageSize: 10,
  subjectId: null,
  name: ''
})
const paperList = ref([])
const paperTotal = ref(0)
const exportingPaperId = ref(null)
const previewDialogVisible = ref(false)
const previewLoading = ref(false)
const previewLoadSeq = ref(0)
const previewPaper = reactive({
  id: null,
  name: '',
  subjectId: null,
  subjectName: '',
  description: '',
  totalScore: 0,
  teacherId: null,
  questions: []
})

const manualDialogVisible = ref(false)
const manualMode = ref('create')
const editingPaperId = ref(null)

const manualForm = reactive({
  name: '',
  subjectId: null,
  description: ''
})

const manualQuestionQuery = reactive({
  keyword: '',
  type: null,
  difficulty: null
})

const candidateQuestions = ref([])
const selectedQuestions = ref([])

const manualDialogTitle = computed(() => (manualMode.value === 'edit' ? '修改试卷' : '手动组卷'))
const selectedQuestionIdSet = computed(() => new Set(selectedQuestions.value.map(item => item.questionId)))

const manualTypeOrderMap = {
  SINGLE: 1,
  MULTI: 2,
  BLANK: 3,
  JUDGE: 4,
  SHORT: 5
}
const previewQuestions = computed(() =>
  (previewPaper.questions || [])
    .slice()
    .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
    .map((question, index) => ({
      ...splitQuestionAssets(question.assets),
      ...question,
      displayOrder: index + 1,
      typeLabel: questionTypeLabel(question.type),
      options: parseQuestionOptions(question.optionsJson),
      previewKey: question.questionId || `${question.sortOrder || index + 1}-${index}`
    }))
)

const courseLabel = (course) => `${course.id} - ${course.name}`

const parseQuestionOptions = (optionsJson) => {
  if (!optionsJson) {
    return []
  }
  try {
    const parsed = JSON.parse(optionsJson)
    if (!Array.isArray(parsed)) {
      return []
    }
    return parsed
      .map((item, index) => ({
        label: item?.label || String.fromCharCode(65 + index),
        value: item?.value == null ? '' : String(item.value)
      }))
      .filter(option => option.value)
  } catch (error) {
    return []
  }
}

const splitQuestionAssets = (assets) => {
  if (!Array.isArray(assets) || !assets.length) {
    return { imageAssets: [], otherAssets: [] }
  }
  const normalized = assets
    .filter(item => item && item.url)
    .map(item => ({
      assetId: item.assetId,
      url: item.url,
      fileType: (item.fileType || '').toUpperCase(),
      originalName: item.originalName,
      size: item.size
    }))
  return {
    imageAssets: normalized.filter(item => item.fileType === 'IMAGE'),
    otherAssets: normalized.filter(item => item.fileType !== 'IMAGE')
  }
}

const loadSubjectOptions = async () => {
  subjectOptions.value = await listQuestionSubjectsApi()
}

const addRule = () => {
  autoForm.rules.push({ type: 'SINGLE', difficulty: AUTO_DIFFICULTY_ALL, count: 1, score: 2 })
}

const removeRule = (index) => {
  if (autoForm.rules.length <= 1) {
    ElMessage.warning('至少保留1条规则')
    return
  }
  autoForm.rules.splice(index, 1)
}

const autoCreate = async () => {
  if (!autoForm.subjectId) {
    ElMessage.warning('请选择课程')
    return
  }
  const payload = {
    ...autoForm,
    rules: autoForm.rules.map(rule => ({
      ...rule,
      difficulty: rule.difficulty === AUTO_DIFFICULTY_ALL ? null : rule.difficulty
    }))
  }
  const id = await createAutoPaperApi(payload)
  ElMessage.success(`组卷成功，试卷ID=${id}`)
  await loadPaperList()
}

const loadPaperList = async () => {
  const payload = {
    pageNum: paperQuery.pageNum,
    pageSize: paperQuery.pageSize,
    subjectId: paperQuery.subjectId || null,
    name: (paperQuery.name || '').trim() || null
  }
  const data = await queryPapersApi(payload)
  paperList.value = data.records || []
  paperTotal.value = data.total || 0
}

const searchPaperList = async () => {
  paperQuery.pageNum = 1
  await loadPaperList()
}

const resetPreviewDialog = () => {
  previewLoadSeq.value += 1
  Object.assign(previewPaper, {
    id: null,
    name: '',
    subjectId: null,
    subjectName: '',
    description: '',
    totalScore: 0,
    teacherId: null,
    questions: []
  })
  previewLoading.value = false
}

const openPreviewDialog = async (row) => {
  const id = row?.id
  if (!id) {
    ElMessage.warning('试卷ID无效，无法预览')
    return
  }
  previewDialogVisible.value = true
  resetPreviewDialog()
  previewLoading.value = true
  const seq = ++previewLoadSeq.value
  try {
    const detail = await paperDetailApi(id)
    if (seq !== previewLoadSeq.value) {
      return
    }
    Object.assign(previewPaper, detail)
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

const escapeHtml = (value) => String(value ?? '')
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;')

const buildPrintablePaperHtml = (detail) => {
  const questions = (detail.questions || [])
    .slice()
    .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
    .map((question, index) => ({
      ...splitQuestionAssets(question.assets),
      ...question,
      displayOrder: index + 1,
      typeLabel: questionTypeLabel(question.type),
      options: parseQuestionOptions(question.optionsJson)
    }))

  const questionHtml = questions.map(question => {
    const optionsHtml = question.options.length
      ? `<div class="options">${question.options.map(option => `
          <p>${escapeHtml(option.label)}. ${escapeHtml(option.value)}</p>
        `).join('')}</div>`
      : ''
    const imagesHtml = question.imageAssets.length
      ? `<div class="images">${question.imageAssets.map(asset => `
          <img src="${escapeHtml(asset.url)}" alt="${escapeHtml(asset.originalName || '题目插图')}" />
        `).join('')}</div>`
      : ''
    return `
      <section class="question">
        <div class="question-title">
          <strong>${question.displayOrder}.</strong>
          <span class="type">[${escapeHtml(question.typeLabel)}]</span>
          <span>${escapeHtml(question.content || '-')}</span>
          <span class="score">（${escapeHtml(question.score || 0)}分）</span>
        </div>
        ${optionsHtml}
        ${imagesHtml}
      </section>
    `
  }).join('')

  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <title>${escapeHtml(detail.name || '试卷')}</title>
  <style>
    @page { margin: 18mm 16mm; }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      color: #111827;
      font-family: "Microsoft YaHei", "SimSun", serif;
      line-height: 1.75;
      background: #fff;
    }
    .paper {
      max-width: 860px;
      margin: 0 auto;
    }
    header {
      text-align: center;
      padding-bottom: 16px;
      border-bottom: 2px solid #111827;
      margin-bottom: 20px;
    }
    h1 {
      margin: 0 0 10px;
      font-size: 26px;
      letter-spacing: 1px;
    }
    .meta {
      margin: 0;
      color: #4b5563;
      font-size: 13px;
    }
    .description {
      margin: 10px 0 0;
      color: #374151;
      font-size: 14px;
    }
    .question {
      break-inside: avoid;
      padding: 12px 0;
      border-bottom: 1px dashed #d1d5db;
    }
    .question-title {
      font-size: 15px;
    }
    .type {
      margin: 0 8px;
      color: #1d4ed8;
      font-weight: 700;
    }
    .score {
      margin-left: 6px;
      color: #6b7280;
    }
    .options {
      margin: 8px 0 0 28px;
      color: #374151;
    }
    .options p {
      margin: 2px 0;
    }
    .images {
      margin: 10px 0 0 28px;
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
    }
    .images img {
      max-width: 260px;
      max-height: 180px;
      object-fit: contain;
      border: 1px solid #e5e7eb;
    }
    .empty {
      margin-top: 40px;
      text-align: center;
      color: #6b7280;
    }
  </style>
</head>
<body>
  <article class="paper">
    <header>
      <h1>${escapeHtml(detail.name || '未命名试卷')}</h1>
      <p class="meta">课程：${escapeHtml(detail.subjectName || detail.subjectId || '-')} | 出卷人：${escapeHtml(detail.teacherId || '-')} | 总分：${escapeHtml(detail.totalScore || 0)}</p>
      ${detail.description ? `<p class="description">说明：${escapeHtml(detail.description)}</p>` : ''}
    </header>
    ${questionHtml || '<p class="empty">暂无题目</p>'}
  </article>
  <script>
    const waitForImages = () => Promise.all(Array.from(document.images).map(image => {
      if (image.complete) {
        return Promise.resolve();
      }
      return new Promise(resolve => {
        image.onload = resolve;
        image.onerror = resolve;
      });
    }));
    window.addEventListener('load', async () => {
      await waitForImages();
      setTimeout(() => window.print(), 200);
    });
  <\/script>
</body>
</html>`
}

const exportPaperPdf = async (row) => {
  const id = row?.id
  if (!id) {
    ElMessage.warning('试卷ID无效，无法导出')
    return
  }
  const printWindow = window.open('', '_blank')
  if (!printWindow) {
    ElMessage.warning('浏览器拦截了导出窗口，请允许弹窗后重试')
    return
  }
  exportingPaperId.value = id
  printWindow.document.write('<p style="font-family: sans-serif; padding: 24px;">正在生成试卷 PDF...</p>')
  try {
    const detail = await paperDetailApi(id)
    printWindow.document.open()
    printWindow.document.write(buildPrintablePaperHtml(detail))
    printWindow.document.close()
  } catch (error) {
    printWindow.close()
    throw error
  } finally {
    exportingPaperId.value = null
  }
}

const resetManualDialog = () => {
  manualMode.value = 'create'
  editingPaperId.value = null
  manualForm.name = ''
  manualForm.subjectId = null
  manualForm.description = ''
  manualQuestionQuery.keyword = ''
  manualQuestionQuery.type = null
  manualQuestionQuery.difficulty = null
  candidateQuestions.value = []
  selectedQuestions.value = []
}

const openCreateManualDialog = () => {
  resetManualDialog()
  manualDialogVisible.value = true
}

const normalizeSelectedQuestions = (questions) => {
  const sorted = (questions || [])
    .slice()
    .sort((a, b) => {
      const typeDiff = (manualTypeOrderMap[a.type] || 99) - (manualTypeOrderMap[b.type] || 99)
      if (typeDiff !== 0) {
        return typeDiff
      }
      return (a.sortOrder || 0) - (b.sortOrder || 0)
    })
  selectedQuestions.value = sorted.map((item, index) => ({
    sortOrder: index + 1,
    questionId: item.questionId,
    type: item.type,
    difficulty: item.difficulty,
    content: item.content,
    score: item.score || 1
  }))
}

const openEditManualDialog = async (row) => {
  if (row.canManage === false) {
    ElMessage.warning('无权限修改该试卷')
    return
  }

  resetManualDialog()
  manualMode.value = 'edit'
  editingPaperId.value = row.id

  const detail = await paperDetailApi(row.id)
  manualForm.name = detail.name || ''
  manualForm.subjectId = detail.subjectId || null
  manualForm.description = detail.description || ''
  normalizeSelectedQuestions((detail.questions || []).slice().sort((a, b) => a.sortOrder - b.sortOrder))

  manualDialogVisible.value = true
  await searchManualQuestions()
}

const searchManualQuestions = async () => {
  if (!manualForm.subjectId) {
    ElMessage.warning('请先选择课程')
    return
  }
  const payload = {
    pageNum: 1,
    pageSize: 100,
    subjectId: manualForm.subjectId,
    keyword: (manualQuestionQuery.keyword || '').trim() || null,
    type: manualQuestionQuery.type || null,
    difficulty: manualQuestionQuery.difficulty || null
  }
  const data = await queryQuestionsApi(payload)
  candidateQuestions.value = data.records || []
}

const addQuestionToManual = (question) => {
  if (selectedQuestionIdSet.value.has(question.id)) {
    return
  }
  const newItem = {
    sortOrder: selectedQuestions.value.length + 1,
    questionId: question.id,
    type: question.type,
    difficulty: question.difficulty,
    content: question.content,
    score: question.defaultScore || 1
  }
  if (manualMode.value === 'edit') {
    normalizeSelectedQuestions([...selectedQuestions.value, newItem])
    return
  }
  selectedQuestions.value.push(newItem)
}

const renumberSelectedQuestions = () => {
  selectedQuestions.value = selectedQuestions.value.map((item, index) => ({
    ...item,
    sortOrder: index + 1
  }))
}

const removeSelectedQuestion = (index) => {
  selectedQuestions.value.splice(index, 1)
  renumberSelectedQuestions()
}

const moveQuestion = (index, delta) => {
  const target = index + delta
  if (target < 0 || target >= selectedQuestions.value.length) {
    return
  }
  const list = [...selectedQuestions.value]
  const current = list[index]
  list[index] = list[target]
  list[target] = current
  selectedQuestions.value = list
  renumberSelectedQuestions()
}

const buildManualPayload = () => {
  if (!manualForm.name.trim()) {
    ElMessage.warning('请输入试卷名称')
    return null
  }
  if (!manualForm.subjectId) {
    ElMessage.warning('请选择课程')
    return null
  }
  if (!selectedQuestions.value.length) {
    ElMessage.warning('请至少添加一道题目')
    return null
  }

  const questions = selectedQuestions.value.map((item, index) => ({
    questionId: item.questionId,
    score: Number(item.score),
    sortOrder: index + 1
  }))

  if (questions.some(item => !item.score || item.score < 1)) {
    ElMessage.warning('每道题分值必须大于0')
    return null
  }

  return {
    name: manualForm.name.trim(),
    subjectId: manualForm.subjectId,
    description: (manualForm.description || '').trim() || null,
    questions
  }
}

const submitManualPaper = async () => {
  const payload = buildManualPayload()
  if (!payload) {
    return
  }

  if (manualMode.value === 'edit') {
    await updatePaperApi(editingPaperId.value, payload)
    ElMessage.success('试卷更新成功')
  } else {
    const id = await createManualPaperApi(payload)
    ElMessage.success(`组卷成功，试卷ID=${id}`)
  }

  manualDialogVisible.value = false
  await loadPaperList()
}

const deletePaper = async (row) => {
  if (row.canManage === false) {
    ElMessage.warning('无权限删除该试卷')
    return
  }

  await ElMessageBox.confirm('确认删除该试卷吗？若已被考试引用将无法删除。', '提示')
  try {
    await deletePaperApi(row.id)
    ElMessage.success('删除成功')
    await loadPaperList()
  } catch (error) {
    if (error?.code === 'PAPER_REFERENCED_BY_EXAM') {
      return
    }
  }
}

onMounted(async () => {
  await loadSubjectOptions()
  await loadPaperList()
})
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.header-subtitle {
  margin: 6px 0 0;
  color: #64748b;
  font-size: 13px;
}

.mb-12 {
  margin-bottom: 12px;
}

.section-card {
  margin-top: 16px;
  border: none;
  border-radius: 14px;
  box-shadow: 0 6px 24px -18px rgba(15, 23, 42, 0.45);
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.section-title {
  margin-right: 12px;
  color: #1e293b;
  font-size: 16px;
  font-weight: 700;
}

.section-note {
  color: #64748b;
  font-size: 13px;
}

.section-actions {
  display: flex;
  gap: 8px;
}

.auto-form {
  margin-bottom: 8px;
}

.paper-list-card {
  margin-top: 16px;
}

.list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.pager-row {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

.course-option-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.course-option-id {
  color: #606266;
  min-width: 64px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
}

.preview-body {
  min-height: 220px;
}

.preview-paper {
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  padding: 18px 20px;
  background: #fff;
}

.preview-paper-header {
  text-align: center;
}

.preview-paper-title {
  margin: 0 0 8px;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 1px;
}

.preview-paper-subtitle {
  margin: 0;
  color: #606266;
}

.preview-paper-description {
  margin: 10px 0 0;
  color: #303133;
}

.preview-question-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.preview-question-item {
  padding-bottom: 10px;
  border-bottom: 1px dashed #e4e7ed;
}

.preview-question-item:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.preview-question-title {
  line-height: 1.8;
  color: #303133;
}

.preview-question-index {
  margin-right: 6px;
  font-weight: 700;
}

.preview-question-type {
  margin-right: 8px;
  color: #409eff;
  font-weight: 600;
}

.preview-question-score {
  margin-left: 8px;
  color: #909399;
}

.preview-option-list {
  margin-top: 6px;
  padding-left: 26px;
}

.preview-option-item {
  margin: 4px 0;
  color: #606266;
}

.preview-image-list {
  margin-top: 8px;
  padding-left: 26px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.preview-image-item {
  width: 150px;
  height: 100px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #f5f7fa;
}

.preview-attachment-list {
  margin-top: 8px;
  padding-left: 26px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.preview-attachment-item {
  color: #409eff;
  text-decoration: none;
}

.preview-attachment-item:hover {
  text-decoration: underline;
}



@media (max-width: 900px) {
  .page-header,
  .section-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .section-actions {
    flex-wrap: wrap;
  }
}

</style>
