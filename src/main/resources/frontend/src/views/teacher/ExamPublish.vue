<template>
  <el-card class="page-card" v-loading="loading">
    <template #header><h1 class="header">考试发布</h1></template>

    <el-form :model="form" label-width="110px">
      <el-row :gutter="12">
        <el-col :span="8"><el-form-item label="考试名称"><el-input v-model="form.name" /></el-form-item></el-col>
        <el-col :span="8">
          <el-form-item label="试卷">
            <el-select
              v-model="form.paperId"
              filterable
              clearable
              placeholder="请选择试卷"
              style="width: 100%"
            >
              <el-option
                v-for="paper in paperOptions"
                :key="paper.id"
                :label="paperLabel(paper)"
                :value="paper.id"
              >
                <div class="paper-option-row">
                  <span class="paper-option-id">{{ paper.id }}</span>
                  <span>{{ paper.name }}</span>
                </div>
              </el-option>
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="8"><el-form-item label="及格线"><el-input v-model.number="form.passScore" /></el-form-item></el-col>
      </el-row>
      <el-row :gutter="12">
        <el-col :span="8">
          <el-form-item label="开考时间">
            <el-date-picker
              v-model="form.startTime"
              type="datetime"
              format="YYYY-MM-DD HH:mm"
              value-format="YYYY-MM-DDTHH:mm:ss"
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="结束时间">
            <el-date-picker
              v-model="form.endTime"
              type="datetime"
              format="YYYY-MM-DD HH:mm"
              value-format="YYYY-MM-DDTHH:mm:ss"
              disabled
            />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="时长(分钟)">
            <el-input-number v-model="form.durationMinutes" :min="1" :step="1" controls-position="right" style="width: 100%" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="目标教学班">
        <el-select
          v-model="form.targetClassIds"
          multiple
          filterable
          clearable
          placeholder="请选择目标教学班"
          style="width: 100%"
        >
          <el-option
            v-for="item in teachingClassOptions"
            :key="item.id"
            :label="teachingClassLabel(item)"
            :value="item.id"
          >
            <div class="paper-option-row">
              <span class="paper-option-id">{{ item.id }}</span>
              <span>{{ item.name }}（{{ item.subjectName || '未知课程' }}）</span>
            </div>
          </el-option>
        </el-select>
      </el-form-item>
      <el-form-item label="防作弊策略">
        <div class="policy-editor">
          <el-radio-group v-model="form.proctoringLevel" @change="applyPolicyPreset">
            <el-radio-button label="LOW">宽松</el-radio-button>
            <el-radio-button label="STANDARD">标准</el-radio-button>
            <el-radio-button label="STRICT">严格</el-radio-button>
            <el-radio-button label="CUSTOM">自定义</el-radio-button>
          </el-radio-group>
          <div class="policy-grid">
            <el-checkbox v-model="form.proctoringPolicy.trackWindowBlur" :disabled="form.proctoringLevel !== 'CUSTOM'">记录窗口失焦</el-checkbox>
            <el-checkbox v-model="form.proctoringPolicy.trackPageHidden" :disabled="form.proctoringLevel !== 'CUSTOM'">记录切屏/隐藏</el-checkbox>
            <el-checkbox v-model="form.proctoringPolicy.trackNavigationLeave" :disabled="form.proctoringLevel !== 'CUSTOM'">记录离开页面</el-checkbox>
            <el-checkbox v-model="form.proctoringPolicy.trackCopyPaste" :disabled="form.proctoringLevel !== 'CUSTOM'">记录复制粘贴</el-checkbox>
            <el-checkbox v-model="form.proctoringPolicy.trackLongInactivity" :disabled="form.proctoringLevel !== 'CUSTOM'">记录长时间无操作</el-checkbox>
            <el-checkbox v-model="form.proctoringPolicy.requireFullscreen" :disabled="form.proctoringLevel !== 'CUSTOM'">要求全屏</el-checkbox>
            <el-checkbox v-model="form.proctoringPolicy.requireCamera" :disabled="form.proctoringLevel !== 'CUSTOM'">要求摄像头</el-checkbox>
            <el-checkbox v-model="form.proctoringPolicy.requireMicrophone" :disabled="form.proctoringLevel !== 'CUSTOM'">要求麦克风</el-checkbox>
            <el-checkbox v-model="form.proctoringPolicy.requireScreenShare" :disabled="form.proctoringLevel !== 'CUSTOM'">要求屏幕共享</el-checkbox>
            <el-checkbox v-model="form.proctoringPolicy.blockMultiMonitor" :disabled="form.proctoringLevel !== 'CUSTOM'">拦截多显示器</el-checkbox>
            <el-checkbox v-model="form.proctoringPolicy.captureEvidence" :disabled="form.proctoringLevel !== 'CUSTOM'">高风险抓取证据</el-checkbox>
          </div>
          <el-row :gutter="12" class="policy-numbers">
            <el-col :span="8">
              <el-input-number
                v-model="form.proctoringPolicy.inactivityThresholdSeconds"
                :disabled="form.proctoringLevel !== 'CUSTOM'"
                :min="30"
                :max="1800"
                :step="30"
                controls-position="right"
              />
              <span>无操作阈值(秒)</span>
            </el-col>
            <el-col :span="8">
              <el-input-number
                v-model="form.proctoringPolicy.offscreenLongThresholdSeconds"
                :disabled="form.proctoringLevel !== 'CUSTOM'"
                :min="5"
                :max="600"
                :step="5"
                controls-position="right"
              />
              <span>离屏长时阈值(秒)</span>
            </el-col>
            <el-col :span="8">
              <el-input-number
                v-model="form.proctoringPolicy.repeatEventThreshold"
                :disabled="form.proctoringLevel !== 'CUSTOM'"
                :min="2"
                :max="20"
                :step="1"
                controls-position="right"
              />
              <span>重复事件升级次数</span>
            </el-col>
          </el-row>
        </div>
      </el-form-item>
      <el-button type="primary" @click="createExam">创建考试</el-button>
      <el-button @click="loadPaperOptions">刷新试卷选项</el-button>
      <el-button @click="loadTeachingClasses">刷新教学班选项</el-button>
    </el-form>

    <el-divider />

    <el-button @click="loadExams">刷新考试列表</el-button>
    <el-table v-if="exams.length || loading" :data="exams" style="margin-top: 10px">
      <el-table-column prop="examId" label="考试ID" width="120" />
      <el-table-column prop="name" label="名称" />
      <el-table-column label="开始时间" width="190">
        <template #default="{ row }">{{ formatDateTime(row.startTime) }}</template>
      </el-table-column>
      <el-table-column label="结束时间" width="190">
        <template #default="{ row }">{{ formatDateTime(row.endTime) }}</template>
      </el-table-column>
      <el-table-column label="防作弊策略" width="120">
        <template #default="{ row }">
          <el-tag effect="plain">{{ policyLevelLabel(row.proctoringLevel || row.proctoringPolicy?.level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column label="操作" width="240">
        <template #default="scope">
          <div class="action-row">
            <el-button v-if="scope.row.status === 'DRAFT'" type="primary" size="small" @click="publish(scope.row.examId)">发布</el-button>
            <el-button
              v-if="scope.row.status === 'PUBLISHED' || scope.row.status === 'ONGOING'"
              type="danger"
              size="small"
              @click="terminate(scope.row.examId)"
            >
              终止
            </el-button>
            <el-button
              v-if="canOpenProctoring(scope.row.status)"
              type="primary"
              plain
              size="small"
              @click="openProctoring(scope.row.examId)"
            >
              监考
            </el-button>
            <span v-if="scope.row.status === 'TERMINATED'">已终止</span>
            <span v-else-if="scope.row.status === 'FINISHED'">已结束</span>
            <span
              v-else-if="scope.row.status !== 'DRAFT'
                && scope.row.status !== 'PUBLISHED'
                && scope.row.status !== 'ONGOING'"
            >
              --
            </span>
          </div>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-else description="暂无考试，点击上方「创建考试」开始" />
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { createExamApi, examTeachingClassesApi, publishExamApi, queryPapersApi, teacherExamsApi, terminateExamApi } from '../../api'
import { addMinutesToDateTime, formatDateTime, normalizeDateTimeToMinute } from '../../utils/datetime'

const loading = ref(false)

const defaultStartTime = normalizeDateTimeToMinute(new Date())
const router = useRouter()
const POLICY_PRESETS = {
  LOW: {
    level: 'LOW',
    trackWindowBlur: true,
    trackPageHidden: true,
    trackNavigationLeave: true,
    trackFullscreenExit: false,
    trackCopyPaste: true,
    trackContextMenu: true,
    trackNetworkOffline: true,
    trackLongInactivity: true,
    requireFullscreen: false,
    requireCamera: false,
    requireMicrophone: false,
    requireScreenShare: false,
    blockMultiMonitor: false,
    captureEvidence: false,
    inactivityThresholdSeconds: 300,
    offscreenLongThresholdSeconds: 60,
    repeatEventWindowMinutes: 10,
    repeatEventThreshold: 4
  },
  STANDARD: {
    level: 'STANDARD',
    trackWindowBlur: true,
    trackPageHidden: true,
    trackNavigationLeave: true,
    trackFullscreenExit: true,
    trackCopyPaste: true,
    trackContextMenu: true,
    trackNetworkOffline: true,
    trackLongInactivity: true,
    requireFullscreen: true,
    requireCamera: true,
    requireMicrophone: true,
    requireScreenShare: true,
    blockMultiMonitor: true,
    captureEvidence: true,
    inactivityThresholdSeconds: 180,
    offscreenLongThresholdSeconds: 30,
    repeatEventWindowMinutes: 10,
    repeatEventThreshold: 3
  },
  STRICT: {
    level: 'STRICT',
    trackWindowBlur: true,
    trackPageHidden: true,
    trackNavigationLeave: true,
    trackFullscreenExit: true,
    trackCopyPaste: true,
    trackContextMenu: true,
    trackNetworkOffline: true,
    trackLongInactivity: true,
    requireFullscreen: true,
    requireCamera: true,
    requireMicrophone: true,
    requireScreenShare: true,
    blockMultiMonitor: true,
    captureEvidence: true,
    inactivityThresholdSeconds: 90,
    offscreenLongThresholdSeconds: 15,
    repeatEventWindowMinutes: 10,
    repeatEventThreshold: 2
  }
}

const clonePolicy = (level) => ({ ...POLICY_PRESETS[level || 'STANDARD'] })

const form = reactive({
  name: 'Java阶段测验',
  paperId: null,
  startTime: defaultStartTime,
  endTime: addMinutesToDateTime(defaultStartTime, 60),
  durationMinutes: 60,
  passScore: 60,
  targetClassIds: [],
  proctoringLevel: 'STANDARD',
  proctoringPolicy: clonePolicy('STANDARD')
})

const exams = ref([])
const paperOptions = ref([])
const teachingClassOptions = ref([])

const paperLabel = (paper) => `${paper.id} - ${paper.name}`
const teachingClassLabel = (item) => `${item.id} - ${item.name || ''} (${item.subjectName || '未知课程'})`
const canOpenProctoring = (status) => ['PUBLISHED', 'ONGOING'].includes(status)
const applyPolicyPreset = (level) => {
  if (level === 'CUSTOM') {
    form.proctoringPolicy.level = 'CUSTOM'
    return
  }
  Object.assign(form.proctoringPolicy, clonePolicy(level))
}
const policyLevelLabel = (level) => {
  switch (level) {
    case 'LOW': return '宽松'
    case 'STRICT': return '严格'
    case 'CUSTOM': return '自定义'
    default: return '标准'
  }
}

const syncExamTimeRange = () => {
  const normalizedStart = normalizeDateTimeToMinute(form.startTime)
  if (!normalizedStart) {
    form.endTime = ''
    return
  }
  if (form.startTime !== normalizedStart) {
    form.startTime = normalizedStart
    return
  }
  const duration = Number(form.durationMinutes || 0)
  if (!Number.isFinite(duration) || duration <= 0) {
    form.endTime = ''
    return
  }
  form.endTime = addMinutesToDateTime(normalizedStart, duration)
}

watch(
  () => form.startTime,
  () => {
    syncExamTimeRange()
  }
)

watch(
  () => form.durationMinutes,
  () => {
    syncExamTimeRange()
  }
)

const loadPaperOptions = async () => {
  loading.value = true
  try {
    const data = await queryPapersApi({
      pageNum: 1,
      pageSize: 200,
      subjectId: null,
      name: null
    })
    paperOptions.value = data.records || []
  } finally {
    loading.value = false
  }
}

const loadTeachingClasses = async () => {
  loading.value = true
  try {
    teachingClassOptions.value = await examTeachingClassesApi()
  } finally {
    loading.value = false
  }
}

const createExam = async () => {
  syncExamTimeRange()
  if (!form.paperId) {
    ElMessage.warning('请选择试卷')
    return
  }
  if (!form.startTime || !form.endTime) {
    ElMessage.warning('请先选择开考时间并填写有效时长')
    return
  }
  if (!form.targetClassIds.length) {
    ElMessage.warning('请选择目标教学班')
    return
  }
  const payload = {
    ...form,
    proctoringPolicy: {
      ...form.proctoringPolicy,
      level: form.proctoringLevel
    }
  }
  const id = await createExamApi(payload)
  ElMessage.success(`考试创建成功: ${id}`)
  await loadExams()
}

const publish = async (id) => {
  await publishExamApi(id)
  ElMessage.success('发布成功')
  await loadExams()
}

const terminate = async (id) => {
  await ElMessageBox.confirm('确认终止该考试吗？终止后学生将无法开始或继续考试。', '提示')
  await terminateExamApi(id)
  ElMessage.success('终止成功')
  await loadExams()
}

const openProctoring = (examId) => {
  router.push({
    path: '/teacher/proctoring',
    query: {
      examId: String(examId)
    }
  })
}

const loadExams = async () => {
  loading.value = true
  try {
    exams.value = await teacherExamsApi()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadPaperOptions()
  await loadTeachingClasses()
  await loadExams()
})
</script>

<style scoped>


.paper-option-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.paper-option-id {
  color: #606266;
  min-width: 84px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
}

.action-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.policy-editor {
  width: 100%;
  display: grid;
  gap: 14px;
}

.policy-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 8px 14px;
}

.policy-numbers :deep(.el-col) {
  display: grid;
  gap: 6px;
}

.policy-numbers span {
  color: #64748b;
  font-size: 12px;
}
</style>
