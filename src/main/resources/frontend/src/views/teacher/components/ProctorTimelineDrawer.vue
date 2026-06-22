<template>
  <el-drawer
    :model-value="visible"
    @update:model-value="$emit('update:visible', $event)"
    size="560px"
    class="proctoring-drawer"
  >
    <template #header>
      <div class="drawer-title">
        <span>学生监考详情</span>
        <strong v-if="timeline">{{ timeline.studentName }}</strong>
      </div>
    </template>

    <template v-if="timeline">
      <div v-loading="loading" class="drawer-content">
        <div class="timeline-summary">
          <div class="timeline-pill">
            <span>学生</span>
            <strong>{{ timeline.studentName }}</strong>
          </div>
          <div class="timeline-pill">
            <span>风险等级</span>
            <strong>{{ riskLevelLabel(timeline.riskLevel) }} / {{ timeline.riskScore }}</strong>
          </div>
          <div class="timeline-pill">
            <span>快照状态</span>
            <strong>{{ timeline.snapshotAlert ? '异常' : '正常' }}</strong>
          </div>
          <div class="timeline-pill">
            <span>处置状态</span>
            <strong>{{ dispositionLabel(timeline.disposition?.status) }}</strong>
          </div>
        </div>

        <div class="timeline-meta">
          <p>班级：{{ formatClassNames(timeline.classNames) }}</p>
          <p>累计离屏：{{ formatDuration(timeline.totalOffscreenDurationMs) }}</p>
          <p>最近服务端同步：{{ formatDateTime(timeline.lastSnapshotTime) }}</p>
          <p>最长离线：{{ formatDuration(offlineDurationMs) }}</p>
          <p>重放事件：{{ hasReplayedEvents ? '存在' : '无' }}</p>
        </div>

        <div class="disposition-panel">
          <div class="disposition-panel__header">
            <div>
              <h3>处置记录</h3>
              <p>
                最近处置：
                {{ timeline.disposition?.handledByName || '--' }}
                /
                {{ formatDateTime(timeline.disposition?.handledAt) }}
              </p>
            </div>
            <el-button type="primary" :loading="saving" @click="handleSave">保存处置</el-button>
          </div>
          <el-form label-position="top" class="disposition-form">
            <el-form-item label="处置状态">
              <el-select v-model="form.status" style="width: 100%">
                <el-option
                  v-for="item in DISPOSITION_OPTIONS"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="处置备注">
              <el-input
                v-model="form.remark"
                type="textarea"
                :rows="3"
                maxlength="500"
                show-word-limit
                placeholder="记录核查结论、误报原因或后续处理说明"
              />
            </el-form-item>
          </el-form>
        </div>

        <div class="timeline-stats">
          <div v-for="item in timeline.eventTypeStats" :key="item.eventType" class="stat-row">
            <span>{{ formatEventType(item.eventType) }}</span>
            <strong>{{ item.count }}</strong>
          </div>
        </div>

        <div class="timeline-list">
          <div v-for="event in timeline.events" :key="`${event.eventType}-${event.eventTime}-${event.durationMs}`" class="timeline-item">
            <div class="timeline-item__head">
              <strong>{{ formatEventType(event.eventType) }}</strong>
              <span>{{ formatDateTime(event.eventTime) }}</span>
            </div>
            <p class="timeline-item__duration">{{ event.durationMs ? formatDuration(event.durationMs) : '瞬时事件' }}</p>
            <p v-if="formatEventContext(event)" class="timeline-item__context">{{ formatEventContext(event) }}</p>
            <div v-if="parseEvidence(event.evidenceJson).length" class="timeline-evidence">
              <figure
                v-for="item in parseEvidence(event.evidenceJson)"
                :key="item.objectKey || item.url"
                class="timeline-evidence__item"
              >
                <el-tag size="small" effect="light">{{ evidenceSourceLabel(item.source) }}</el-tag>
                <el-image
                  :src="item.url"
                  :preview-src-list="parseEvidence(event.evidenceJson).map(evidence => evidence.url)"
                  fit="cover"
                  class="timeline-evidence__image"
                />
              </figure>
            </div>
            <pre v-if="event.payload" class="timeline-item__payload">{{ formatPayload(event.payload) }}</pre>
          </div>
          <el-empty v-if="!timeline.events.length" description="暂无学生异常事件" />
        </div>
      </div>
    </template>
    <template v-else>
      <div v-loading="loading" class="drawer-content drawer-content--empty">
        <el-empty v-if="!loading" description="学生监考详情加载失败，请重新打开" />
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { reactive, watch } from 'vue'
import { formatDateTime } from '../../../utils/datetime'
import {
  riskLevelLabel,
  dispositionLabel,
  formatClassNames,
  formatDuration,
  formatEventType,
  formatEventContext,
  parseEvidence,
  evidenceSourceLabel,
  formatPayload
} from '../../../utils/proctorUtils'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  timeline: {
    type: Object,
    default: null
  },
  loading: {
    type: Boolean,
    default: false
  },
  saving: {
    type: Boolean,
    default: false
  },
  offlineDurationMs: {
    type: Number,
    default: 0
  },
  hasReplayedEvents: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:visible', 'save'])

const DISPOSITION_OPTIONS = [
  { label: '待核查', value: 'PENDING' },
  { label: '已处理（违纪）', value: 'RESOLVED' },
  { label: '已处理（误报）', value: 'DISMISSED' },
  { label: '已上报', value: 'ESCALATED' }
]

const form = reactive({
  status: 'PENDING',
  remark: ''
})

watch(
  () => props.timeline,
  (newVal) => {
    if (newVal?.disposition) {
      form.status = newVal.disposition.status || 'PENDING'
      form.remark = newVal.disposition.remark || ''
    } else {
      form.status = 'PENDING'
      form.remark = ''
    }
  },
  { deep: true, immediate: true }
)

const handleSave = () => {
  emit('save', { status: form.status, remark: form.remark })
}
</script>

<style scoped>
.proctoring-drawer .drawer-title {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 18px;
  color: #1e293b;
}

.drawer-content {
  display: flex;
  flex-direction: column;
  gap: 20px;
  height: 100%;
}

.drawer-content--empty {
  justify-content: center;
}

.timeline-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding: 16px;
  background: #f8fafc;
  border-radius: 12px;
}

.timeline-pill {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.timeline-pill span {
  font-size: 12px;
  color: #64748b;
}

.timeline-pill strong {
  font-size: 14px;
  color: #0f172a;
}

.timeline-meta {
  padding: 16px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  font-size: 13px;
  color: #475569;
  line-height: 1.6;
}

.timeline-meta p {
  margin: 4px 0;
}

.disposition-panel {
  padding: 20px;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 12px;
}

.disposition-panel__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}

.disposition-panel__header h3 {
  margin: 0 0 4px;
  font-size: 15px;
  color: #166534;
}

.disposition-panel__header p {
  margin: 0;
  font-size: 12px;
  color: #15803d;
}

.disposition-form {
  display: grid;
  gap: 4px;
}

.timeline-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.stat-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  background: #f1f5f9;
  border-radius: 20px;
  font-size: 13px;
}

.stat-row span {
  color: #64748b;
}

.stat-row strong {
  color: #0f172a;
}

.timeline-list {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.timeline-item {
  position: relative;
  padding-left: 20px;
  border-left: 2px solid #e2e8f0;
}

.timeline-item::before {
  content: '';
  position: absolute;
  left: -6px;
  top: 4px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #3b82f6;
  border: 2px solid #ffffff;
}

.timeline-item__head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 4px;
}

.timeline-item__head strong {
  font-size: 14px;
  color: #1e293b;
}

.timeline-item__head span {
  font-size: 12px;
  color: #94a3b8;
}

.timeline-item__duration {
  margin: 0 0 6px;
  font-size: 12px;
  color: #ef4444;
}

.timeline-item__context {
  margin: 0;
  font-size: 13px;
  color: #475569;
}

.timeline-item__payload {
  margin: 10px 0 0;
  padding: 12px;
  overflow: auto;
  border-radius: 14px;
  background: #0f172a;
  color: #e2e8f0;
  font-size: 12px;
}

.timeline-evidence {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 12px;
}

.timeline-evidence__item {
  display: grid;
  gap: 6px;
  width: 160px;
  margin: 0;
}

.timeline-evidence__image {
  width: 160px;
  height: 96px;
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, 0.24);
  border-radius: 8px;
  background: #f8fafc;
}
</style>
