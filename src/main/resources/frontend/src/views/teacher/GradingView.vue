<template>
  <div class="grading-shell">
    <section class="panel grading-topbar">
      <div>
        <h2>主观题阅卷</h2>
        <p>{{ modeDescription }}</p>
      </div>
      <div class="mode-actions">
        <el-radio-group v-model="gradingMode" size="large" @change="handleModeChange">
          <el-radio-button label="question">按题目</el-radio-button>
          <el-radio-button label="student">按学生</el-radio-button>
        </el-radio-group>
        <el-button :loading="currentModeLoading" @click="reloadCurrentMode">{{ refreshButtonText }}</el-button>
      </div>
    </section>

    <div class="grading-page">
      <template v-if="gradingMode === 'question'">
        <section class="panel group-panel">
          <div class="panel-header">
            <div>
              <h3>待批题目</h3>
              <p>选择题目后批量处理同题答案</p>
            </div>
          </div>

          <el-table
            ref="groupTableRef"
            v-loading="groupLoading"
            :data="groups"
            row-key="key"
            highlight-current-row
            class="group-table"
            @current-change="handleGroupChange"
          >
            <el-table-column prop="examName" label="考试" min-width="140" />
            <el-table-column label="题目" min-width="220">
              <template #default="{ row }">
                <div class="question-preview">
                  <div class="question-text">{{ row.questionContent || '未命名题目' }}</div>
                  <div class="question-meta">第 {{ row.sortOrder || '-' }} 题</div>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="defaultScore" label="分值" width="80" />
            <el-table-column prop="pendingCount" label="待批" width="80" />
          </el-table>

          <el-empty v-if="!groupLoading && !groups.length" description="当前无待批题目" />
        </section>

        <section class="panel detail-panel">
          <div class="panel-header">
            <div>
              <h3>{{ currentGroup ? currentGroup.examName : '请选择题目' }}</h3>
              <p v-if="currentGroup">
                第 {{ currentGroup.sortOrder || '-' }} 题 · 满分 {{ currentGroup.defaultScore ?? 0 }} 分 · 待批 {{ currentGroup.pendingCount }} 份
              </p>
              <p v-else>从左侧选择一题后开始连续批改</p>
            </div>
            <div class="header-actions">
              <el-button :disabled="!currentGroup" @click="refreshCurrentQuestion">刷新当前题</el-button>
              <el-button :disabled="!hasNextQuestion" @click="goToNextQuestion">下一题</el-button>
            </div>
          </div>

          <template v-if="currentGroup">
            <div class="question-info">
              <div class="info-block">
                <div class="info-label">题目</div>
                <div class="info-content">{{ currentGroup.questionContent || '--' }}</div>
              </div>
              <div class="info-grid">
                <div class="info-block">
                  <div class="info-label">参考答案</div>
                  <div class="info-content multiline">{{ currentGroup.referenceAnswer || '暂无参考答案' }}</div>
                </div>
                <div class="info-block">
                  <div class="info-label">解析</div>
                  <div class="info-content multiline">{{ currentGroup.analysis || '暂无解析' }}</div>
                </div>
              </div>
            </div>

            <div class="toolbar">
              <span class="selected-count">已选 {{ selectedAnswerIds.length }} 份</span>
              <el-input-number
                v-model="batchScore"
                :min="0"
                :max="currentMaxScore"
                :precision="0"
                controls-position="right"
                placeholder="统一分数"
              />
              <el-input
                v-model="batchComment"
                placeholder="可选评语"
                clearable
                maxlength="255"
                show-word-limit
              />
              <el-button type="primary" :loading="submitting" :disabled="!canSubmitQuestionScore" @click="applyScoreToSelected">
                应用到已选
              </el-button>
            </div>

            <el-table
              ref="answerTableRef"
              v-loading="answerLoading"
              :data="answers"
              row-key="submissionAnswerId"
              @selection-change="handleSelectionChange"
            >
              <el-table-column type="selection" width="48" :selectable="(row) => !!leaseTokens[row.submissionAnswerId]" />
              <el-table-column label="阅卷认领" min-width="260">
                <template #default="{ row }">
                  <GradingLease :key="row.submissionAnswerId" :answer-id="row.submissionAnswerId"
                    @change="updateLease(row.submissionAnswerId, $event)" />
                </template>
              </el-table-column>
              <el-table-column prop="studentName" label="学生" width="140" />
              <el-table-column prop="submittedAt" label="提交时间" width="180">
                <template #default="{ row }">
                  {{ formatDateTime(row.submittedAt, '--') }}
                </template>
              </el-table-column>
              <el-table-column label="学生答案" min-width="360">
                <template #default="{ row }">
                  <div class="answer-text">{{ leaseTokens[row.submissionAnswerId] ? (row.answerText || '未作答') : '请先认领后查看答案' }}</div>
                </template>
              </el-table-column>
            </el-table>

            <el-empty v-if="!answerLoading && !answers.length" description="当前题已无待批答案" />
          </template>
        </section>
      </template>

      <template v-else>
        <section class="panel group-panel">
          <div class="panel-header">
            <div>
              <h3>待批学生</h3>
              <p>选择学生后一次提交全部待批题</p>
            </div>
          </div>

          <el-table
            ref="studentTableRef"
            v-loading="studentLoading"
            :data="studentGroups"
            row-key="key"
            highlight-current-row
            class="group-table"
            @current-change="handleStudentChange"
          >
            <el-table-column prop="examName" label="考试" min-width="150" />
            <el-table-column label="学生" min-width="160">
              <template #default="{ row }">
                <div class="question-preview">
                  <div class="question-text">{{ row.studentName || '未命名学生' }}</div>
                  <div class="question-meta">待批 {{ row.pendingCount }} 题</div>
                </div>
              </template>
            </el-table-column>
          </el-table>

          <el-empty v-if="!studentLoading && !studentGroups.length" description="当前无待批学生" />
        </section>

        <section class="panel detail-panel">
          <div class="panel-header">
            <div>
              <h3>{{ currentStudent ? currentStudent.studentName : '请选择学生' }}</h3>
              <p v-if="currentStudent">
                {{ currentStudent.examName || '未命名考试' }} · 待批 {{ currentStudent.pendingCount }} 题
              </p>
              <p v-else>从左侧选择学生后开始逐题评分</p>
            </div>
            <div class="header-actions">
              <el-button :disabled="!currentStudent" @click="refreshCurrentStudent">刷新当前学生</el-button>
              <el-button :disabled="!hasNextStudent" @click="goToNextStudent">下一位</el-button>
            </div>
          </div>

          <template v-if="currentStudent">
            <div class="student-answer-list">
              <article
                v-for="answer in currentStudent.answers"
                :key="answer.submissionAnswerId"
                class="student-answer-item"
              >
                <div class="student-answer-head">
                  <div>
                    <strong>第 {{ answer.sortOrder || '-' }} 题</strong>
                    <span>满分 {{ displayMaxScore(answer) }}</span>
                  </div>
                  <span class="answer-id">#{{ answer.submissionAnswerId }}</span>
                </div>

                <GradingLease :answer-id="answer.submissionAnswerId"
                  @change="updateLease(answer.submissionAnswerId, $event)" />
                <div class="student-answer-grid">
                  <div class="student-answer-block student-answer-block--wide">
                    <div class="info-label">题目</div>
                    <div class="info-content">{{ answer.questionContent || '--' }}</div>
                  </div>
                  <div class="student-answer-block student-answer-block--wide">
                    <div class="info-label">学生答案</div>
                    <div class="info-content multiline">{{ leaseTokens[answer.submissionAnswerId] ? (answer.answerText || '未作答') : '请先认领后查看答案' }}</div>
                  </div>
                  <div class="student-answer-block">
                    <div class="info-label">参考答案</div>
                    <div class="info-content multiline">{{ answer.referenceAnswer || '暂无参考答案' }}</div>
                  </div>
                  <div class="student-answer-block">
                    <div class="info-label">解析</div>
                    <div class="info-content multiline">{{ answer.analysis || '暂无解析' }}</div>
                  </div>
                </div>

                <div class="student-score-row">
                  <el-input-number
                    v-model="studentScoreForm[answer.submissionAnswerId].score"
                    :disabled="!leaseTokens[answer.submissionAnswerId]"
                    :min="0"
                    :max="resolveStudentMaxScore(answer)"
                    :precision="0"
                    controls-position="right"
                    placeholder="分数"
                  />
                  <el-input
                    v-model="studentScoreForm[answer.submissionAnswerId].comment"
                    :disabled="!leaseTokens[answer.submissionAnswerId]"
                    placeholder="可选评语"
                    clearable
                    maxlength="255"
                    show-word-limit
                  />
                </div>
              </article>
            </div>

            <div class="student-submit-bar">
              <span>共 {{ currentStudent.pendingCount }} 题</span>
              <el-button type="primary" :loading="submitting" :disabled="!currentStudent || !currentStudent.answers.every((a) => leaseTokens[a.submissionAnswerId])" @click="submitCurrentStudentScores">
                提交该学生
              </el-button>
            </div>
          </template>
        </section>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  gradingPendingApi,
  gradingPendingQuestionAnswersApi,
  gradingPendingQuestionsApi,
  gradingQuestionBatchScoreApi,
  gradingScoreApi
} from '../../api'
import GradingLease from '../../components/grading/GradingLease.vue'
import { formatDateTime } from '../../utils/datetime'

const MODE_QUESTION = 'question'
const MODE_STUDENT = 'student'

const gradingMode = ref(MODE_QUESTION)
const leaseTokens = ref({})
const updateLease = (answerId, token) => {
  if (token) leaseTokens.value[answerId] = token
  else {
    delete leaseTokens.value[answerId]
    selectedAnswerIds.value = selectedAnswerIds.value.filter((id) => id !== answerId)
  }
}
const groups = ref([])
const answers = ref([])
const currentGroup = ref(null)
const selectedAnswerIds = ref([])
const batchScore = ref(null)
const batchComment = ref('')
const groupLoading = ref(false)
const answerLoading = ref(false)
const submitting = ref(false)
const groupTableRef = ref()
const answerTableRef = ref()

const studentGroups = ref([])
const currentStudent = ref(null)
const studentScoreForm = ref({})
const studentLoading = ref(false)
const studentTableRef = ref()

const currentGroupKey = computed(() => (currentGroup.value ? buildGroupKey(currentGroup.value) : ''))
const currentMaxScore = computed(() => Number(currentGroup.value?.defaultScore ?? 0))
const currentGroupIndex = computed(() => groups.value.findIndex((item) => item.key === currentGroupKey.value))
const hasNextQuestion = computed(() => currentGroupIndex.value >= 0 && currentGroupIndex.value < groups.value.length - 1)
const currentStudentKey = computed(() => (currentStudent.value ? currentStudent.value.key : ''))
const currentStudentIndex = computed(() => studentGroups.value.findIndex((item) => item.key === currentStudentKey.value))
const hasNextStudent = computed(() => currentStudentIndex.value >= 0 && currentStudentIndex.value < studentGroups.value.length - 1)
const currentModeLoading = computed(() => gradingMode.value === MODE_QUESTION
  ? groupLoading.value || answerLoading.value
  : studentLoading.value)
const refreshButtonText = computed(() => gradingMode.value === MODE_QUESTION ? '刷新题目' : '刷新学生')
const modeDescription = computed(() => gradingMode.value === MODE_QUESTION
  ? '按考试和题目连续批改待阅答案'
  : '按学生一次完成该提交的全部待批主观题')
const canSubmitQuestionScore = computed(() => {
  if (batchScore.value === null || batchScore.value === undefined || batchScore.value === '') {
    return false
  }
  const score = Number(batchScore.value)
  return currentGroup.value
    && selectedAnswerIds.value.length > 0
    && selectedAnswerIds.value.every((id) => leaseTokens.value[id])
    && Number.isInteger(score)
    && score >= 0
    && score <= currentMaxScore.value
})

const buildGroupKey = (group) => `${group.examId}-${group.questionId}`
const buildQuestionKey = (examId, questionId) => `${examId}-${questionId}`
const buildStudentKey = (item) => `${item.submissionId}-${item.examId}-${item.studentId}`

const normalizeGroups = (data) => (data || []).map((item) => ({
  ...item,
  key: buildGroupKey(item)
}))

const buildQuestionMetaMap = (questionGroups) => {
  const map = new Map()
  for (const group of questionGroups || []) {
    map.set(buildQuestionKey(group.examId, group.questionId), group)
  }
  return map
}

const compareNullableNumber = (left, right) => {
  const leftValue = left == null ? Number.MAX_SAFE_INTEGER : Number(left)
  const rightValue = right == null ? Number.MAX_SAFE_INTEGER : Number(right)
  return leftValue - rightValue
}

const compareText = (left, right) => String(left || '').localeCompare(String(right || ''), 'zh-CN')

const normalizeStudentGroups = (pendingAnswers, questionMetaMap) => {
  const grouped = new Map()
  for (const item of pendingAnswers || []) {
    const key = buildStudentKey(item)
    if (!grouped.has(key)) {
      grouped.set(key, {
        key,
        submissionId: item.submissionId,
        examId: item.examId,
        examName: item.examName,
        studentId: item.studentId,
        studentName: item.studentName,
        pendingCount: 0,
        answers: []
      })
    }

    const questionMeta = questionMetaMap.get(buildQuestionKey(item.examId, item.questionId))
    grouped.get(key).answers.push({
      ...item,
      key: String(item.submissionAnswerId),
      sortOrder: questionMeta?.sortOrder ?? null,
      defaultScore: questionMeta?.defaultScore ?? null,
      referenceAnswer: questionMeta?.referenceAnswer ?? '',
      analysis: questionMeta?.analysis ?? ''
    })
  }

  return Array.from(grouped.values())
    .map((group) => ({
      ...group,
      pendingCount: group.answers.length,
      answers: group.answers.sort((left, right) => {
        const orderCompare = compareNullableNumber(left.sortOrder, right.sortOrder)
        if (orderCompare !== 0) {
          return orderCompare
        }
        return compareNullableNumber(left.questionId, right.questionId)
      })
    }))
    .sort((left, right) => {
      const examCompare = compareText(left.examName, right.examName)
      if (examCompare !== 0) {
        return examCompare
      }
      const studentCompare = compareText(left.studentName, right.studentName)
      if (studentCompare !== 0) {
        return studentCompare
      }
      return compareNullableNumber(left.submissionId, right.submissionId)
    })
}

const loadQuestionGroupsData = async () => {
  const data = await gradingPendingQuestionsApi()
  groups.value = normalizeGroups(data)
  return groups.value
}

const setCurrentGroupRow = async (group) => {
  await nextTick()
  groupTableRef.value?.setCurrentRow(group || null)
}

const setCurrentStudentRow = async (student) => {
  await nextTick()
  studentTableRef.value?.setCurrentRow(student || null)
}

const resetBatchForm = () => {
  selectedAnswerIds.value = []
  batchScore.value = null
  batchComment.value = ''
}

const resetStudentScoreForm = (student) => {
  const form = {}
  for (const answer of student?.answers || []) {
    form[answer.submissionAnswerId] = {
      score: null,
      comment: ''
    }
  }
  studentScoreForm.value = form
}

const clearCurrentQuestion = async () => {
  currentGroup.value = null
  answers.value = []
  resetBatchForm()
  await setCurrentGroupRow(null)
}

const clearCurrentStudent = async () => {
  currentStudent.value = null
  studentScoreForm.value = {}
  await setCurrentStudentRow(null)
}

const loadQuestionAnswers = async (group) => {
  if (!group) {
    await clearCurrentQuestion()
    return
  }
  answerLoading.value = true
  try {
    const data = await gradingPendingQuestionAnswersApi(group.questionId, group.examId)
    currentGroup.value = group
    answers.value = data || []
    resetBatchForm()
    await nextTick()
    answerTableRef.value?.clearSelection()
  } finally {
    answerLoading.value = false
  }
}

const loadGroups = async ({ preferredKey = '', fallbackIndex = 0, notifyWhenEmpty = false } = {}) => {
  groupLoading.value = true
  try {
    await loadQuestionGroupsData()

    if (!groups.value.length) {
      await clearCurrentQuestion()
      if (notifyWhenEmpty) {
        ElMessage.success('当前无待批题目')
      }
      return
    }

    const preferredGroup = preferredKey
      ? groups.value.find((item) => item.key === preferredKey)
      : null
    const fallbackGroup = groups.value[fallbackIndex] || null
    const nextGroup = preferredGroup || fallbackGroup || groups.value[0]

    await loadQuestionAnswers(nextGroup)
    await setCurrentGroupRow(nextGroup)
  } finally {
    groupLoading.value = false
  }
}

const setCurrentStudent = async (student) => {
  currentStudent.value = student || null
  resetStudentScoreForm(student)
  await setCurrentStudentRow(student || null)
}

const loadStudentGroups = async ({ preferredKey = '', fallbackIndex = 0, notifyWhenEmpty = false } = {}) => {
  studentLoading.value = true
  try {
    const [pendingAnswers, questionGroups] = await Promise.all([
      gradingPendingApi(),
      gradingPendingQuestionsApi()
    ])
    groups.value = normalizeGroups(questionGroups)
    studentGroups.value = normalizeStudentGroups(pendingAnswers, buildQuestionMetaMap(groups.value))

    if (!studentGroups.value.length) {
      await clearCurrentStudent()
      if (notifyWhenEmpty) {
        ElMessage.success('当前无待批学生')
      }
      return
    }

    const preferredStudent = preferredKey
      ? studentGroups.value.find((item) => item.key === preferredKey)
      : null
    const fallbackStudent = studentGroups.value[fallbackIndex] || null
    const nextStudent = preferredStudent || fallbackStudent || studentGroups.value[0]
    await setCurrentStudent(nextStudent)
  } finally {
    studentLoading.value = false
  }
}

const reloadGroups = async () => {
  await loadGroups({
    preferredKey: currentGroupKey.value,
    fallbackIndex: Math.max(currentGroupIndex.value, 0)
  })
}

const reloadStudentGroups = async () => {
  await loadStudentGroups({
    preferredKey: currentStudentKey.value,
    fallbackIndex: Math.max(currentStudentIndex.value, 0)
  })
}

const reloadCurrentMode = async () => {
  if (gradingMode.value === MODE_STUDENT) {
    await reloadStudentGroups()
    return
  }
  await reloadGroups()
}

const handleModeChange = async () => {
  if (gradingMode.value === MODE_STUDENT) {
    await loadStudentGroups({
      preferredKey: currentStudentKey.value,
      fallbackIndex: Math.max(currentStudentIndex.value, 0)
    })
    return
  }
  await loadGroups({
    preferredKey: currentGroupKey.value,
    fallbackIndex: Math.max(currentGroupIndex.value, 0)
  })
}

const handleGroupChange = async (group) => {
  if (!group || group.key === currentGroupKey.value) {
    return
  }
  await loadQuestionAnswers(group)
}

const handleStudentChange = async (student) => {
  if (!student || student.key === currentStudentKey.value) {
    return
  }
  await setCurrentStudent(student)
}

const handleSelectionChange = (selection) => {
  selectedAnswerIds.value = (selection || []).map((item) => item.submissionAnswerId).filter((id) => leaseTokens.value[id])
}

const refreshCurrentQuestion = async () => {
  if (!currentGroup.value) {
    return
  }
  await loadQuestionAnswers(currentGroup.value)
}

const refreshCurrentStudent = async () => {
  if (!currentStudent.value) {
    return
  }
  await reloadStudentGroups()
}

const goToNextQuestion = async () => {
  if (!hasNextQuestion.value) {
    ElMessage.info('已经是最后一题')
    return
  }
  const nextGroup = groups.value[currentGroupIndex.value + 1]
  await loadQuestionAnswers(nextGroup)
  await setCurrentGroupRow(nextGroup)
}

const goToNextStudent = async () => {
  if (!hasNextStudent.value) {
    ElMessage.info('已经是最后一位')
    return
  }
  await setCurrentStudent(studentGroups.value[currentStudentIndex.value + 1])
}

const resolveStudentMaxScore = (answer) => {
  if (answer.defaultScore == null) {
    return undefined
  }
  const maxScore = Number(answer.defaultScore)
  return Number.isFinite(maxScore) ? maxScore : undefined
}

const displayMaxScore = (answer) => {
  const maxScore = resolveStudentMaxScore(answer)
  return maxScore == null ? '--' : `${maxScore} 分`
}

const isValidScoreForAnswer = (answer) => {
  const form = studentScoreForm.value[answer.submissionAnswerId]
  if (!form || form.score === null || form.score === undefined || form.score === '') {
    return false
  }
  if (!leaseTokens.value[answer.submissionAnswerId]) return false
  const score = Number(form.score)
  if (!Number.isInteger(score) || score < 0) {
    return false
  }
  const maxScore = resolveStudentMaxScore(answer)
  return maxScore == null || score <= maxScore
}

const validateStudentScores = () => {
  if (!currentStudent.value) {
    return false
  }
  const invalidAnswer = currentStudent.value.answers.find((answer) => !isValidScoreForAnswer(answer))
  if (!invalidAnswer) {
    return true
  }
  const maxScore = resolveStudentMaxScore(invalidAnswer)
  if (maxScore == null) {
    ElMessage.warning('请为所有题目填写非负整数分数')
  } else {
    ElMessage.warning(`请为第 ${invalidAnswer.sortOrder || '-'} 题填写 0 到 ${maxScore} 的整数分数`)
  }
  return false
}

const applyScoreToSelected = async () => {
  if (!canSubmitQuestionScore.value || !currentGroup.value) {
    return
  }
  submitting.value = true
  const preferredKey = currentGroupKey.value
  const fallbackIndex = Math.max(currentGroupIndex.value, 0)
  try {
    await gradingQuestionBatchScoreApi(currentGroup.value.questionId, {
      examId: currentGroup.value.examId,
      submissionAnswerIds: selectedAnswerIds.value,
      leaseTokens: Object.fromEntries(selectedAnswerIds.value.map((id) => [id, leaseTokens.value[id]])),
      score: Number(batchScore.value),
      comment: batchComment.value?.trim() || ''
    })
    ElMessage.success('评分成功')
    await loadGroups({
      preferredKey,
      fallbackIndex,
      notifyWhenEmpty: true
    })
  } finally {
    submitting.value = false
  }
}

const submitCurrentStudentScores = async () => {
  if (!currentStudent.value || !validateStudentScores()) {
    return
  }
  submitting.value = true
  const preferredKey = currentStudentKey.value
  const fallbackIndex = Math.max(currentStudentIndex.value, 0)
  try {
    await gradingScoreApi(currentStudent.value.submissionId, {
      scores: currentStudent.value.answers.map((answer) => {
        const form = studentScoreForm.value[answer.submissionAnswerId]
        return {
          submissionAnswerId: answer.submissionAnswerId,
          leaseToken: leaseTokens.value[answer.submissionAnswerId],
          score: Number(form.score),
          comment: form.comment?.trim() || ''
        }
      })
    })
    ElMessage.success('评分成功')
    await loadStudentGroups({
      preferredKey,
      fallbackIndex,
      notifyWhenEmpty: true
    })
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadGroups()
})
</script>

<style scoped>
.grading-shell {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.grading-page {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 16px;
}

.panel {
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 16px;
}

.grading-topbar,
.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.panel-header {
  margin-bottom: 16px;
}

.grading-topbar h2,
.panel-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #111827;
}

.grading-topbar p,
.panel-header p {
  margin: 6px 0 0;
  color: #6b7280;
  font-size: 13px;
}

.mode-actions,
.header-actions {
  display: flex;
  gap: 8px;
}

.mode-actions {
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.group-table {
  width: 100%;
}

.question-preview {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.question-text {
  color: #111827;
  line-height: 1.5;
}

.question-meta {
  color: #6b7280;
  font-size: 12px;
}

.question-info {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 16px;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.info-block,
.student-answer-block {
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
}

.info-label {
  font-size: 12px;
  color: #6b7280;
  margin-bottom: 6px;
}

.info-content {
  color: #111827;
  line-height: 1.6;
}

.multiline {
  white-space: pre-wrap;
  word-break: break-word;
}

.toolbar {
  display: grid;
  grid-template-columns: auto 160px minmax(220px, 1fr) auto;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.selected-count {
  color: #374151;
  font-size: 14px;
}

.answer-text {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
}

.student-answer-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.student-answer-item {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 14px;
  background: #ffffff;
}

.student-answer-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.student-answer-head strong,
.student-answer-head span {
  display: block;
}

.student-answer-head strong {
  color: #111827;
  font-size: 15px;
}

.student-answer-head span {
  margin-top: 4px;
  color: #6b7280;
  font-size: 12px;
}

.answer-id {
  color: #9ca3af !important;
  font-size: 12px;
  margin-top: 0 !important;
}

.student-answer-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.student-answer-block--wide {
  grid-column: 1 / -1;
}

.student-score-row {
  display: grid;
  grid-template-columns: 160px minmax(240px, 1fr);
  gap: 12px;
  align-items: center;
  margin-top: 12px;
}

.student-submit-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid #e5e7eb;
  background: #ffffff;
}

.student-submit-bar span {
  color: #374151;
  font-size: 14px;
}

@media (max-width: 1080px) {
  .grading-page {
    grid-template-columns: 1fr;
  }

  .grading-topbar,
  .panel-header {
    flex-direction: column;
  }

  .mode-actions,
  .header-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .info-grid,
  .student-answer-grid {
    grid-template-columns: 1fr;
  }

  .toolbar,
  .student-score-row {
    grid-template-columns: 1fr;
  }

  .student-submit-bar {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
