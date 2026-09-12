<script setup>
import { computed } from 'vue'
const props = defineProps({ task: { type: Object, required: true }, page: Number, busy: Boolean })
const emit = defineEmits(['retry', 'page', 'bank-retry'])
const done = computed(() => Number(props.task.counts.find(item => item.status === 'DONE')?.total || 0))
const failed = computed(() => Number(props.task.counts.find(item => item.status === 'FAILED')?.total || 0))
const percent = computed(() => props.task.status === 'COMPLETED' ? 100 : Math.min(99, Math.round(done.value * 100 / Math.max(Number(props.task.total), 1))))
function score(value) {
  try { const parsed = JSON.parse(value); return parsed.total_score === undefined ? '未初判' : `${parsed.total_score}（客观 ${parsed.objective_score} / 主观 ${parsed.subjective_score}）` } catch { return '—' }
}
const statusLabel = status => ({ RUNNING: '处理中', COMPLETED: '重判完成', FAILED: '失败', PENDING: '等待同步',
  APPLIED: '已同步', CONFLICT: '题库冲突', FORBIDDEN: '无题库权限', DELETED: '题目已删除', SUPERSEDED: '已被新版本取代', DONE: '已重判' }[status] || status)
</script>

<template>
  <p>答案版本 {{ task.answer_version }} · {{ statusLabel(task.status) }} · 已处理 {{ done }} / {{ task.total }} 份 · 失败 {{ failed }} 份</p>
  <el-progress :percentage="percent" />
  <el-alert v-if="task.last_error" :title="task.last_error" type="error" :closable="false" />
  <p>成绩统计：{{ task.status !== 'COMPLETED' ? '重判尚未完成' : Number(task.projectionPending) ? `等待同步 ${task.projectionPending} 份` : '已完成试卷的统计已同步' }}；另有 {{ task.subjectivePending }} 份等待主观题批阅。</p>
  <el-button v-if="task.status === 'FAILED'" :loading="busy" @click="emit('retry')">重试失败试卷</el-button>
  <el-table :data="task.items" empty-text="任务正在读取交卷记录">
    <el-table-column prop="student_id" label="学生ID" width="165" />
    <el-table-column label="重判前" min-width="170"><template #default="{ row }">{{ score(row.before_json) }}</template></el-table-column>
    <el-table-column label="重判后" min-width="170"><template #default="{ row }">{{ score(row.after_json) }}</template></el-table-column>
    <el-table-column label="状态" width="95"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column>
    <el-table-column prop="last_error" label="失败原因" />
  </el-table>
  <el-pagination :current-page="page" :page-size="50" :total="Number(task.itemTotal)" layout="prev, pager, next" @update:current-page="emit('page', $event)" />
  <p>题库同步（历史版本恢复不修改题库）</p>
  <el-table :data="task.bankSync" empty-text="本任务无需修改题库">
    <el-table-column prop="question_id" label="题目ID" width="170" />
    <el-table-column prop="answer" label="目标答案" width="120" />
    <el-table-column label="状态" width="135"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column>
    <el-table-column prop="last_error" label="原因" />
    <el-table-column label="操作" width="145"><template #default="{ row }">
      <el-button v-if="['CONFLICT', 'FORBIDDEN', 'DELETED', 'FAILED'].includes(row.status)" text :disabled="busy" @click="emit('bank-retry', row)">核对并重试</el-button>
    </template></el-table-column>
  </el-table>
</template>
