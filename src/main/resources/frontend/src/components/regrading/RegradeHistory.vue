<script setup>
import { ElMessageBox } from 'element-plus'
const props = defineProps({ jobs: { type: Array, required: true }, version: { type: Number, required: true }, disabled: Boolean, page: Number })
const emit = defineEmits(['select', 'restore', 'page'])
async function restore(version) {
  try {
    const { value } = await ElMessageBox.prompt(`恢复版本 ${version} 的考试答案并重新判分，保留当前主观题分数，不修改题库。请填写原因。`, '恢复历史答案', {
      inputValidator: value => Boolean(value?.trim()) && value.trim().length <= 1000 || '请填写 1 至 1000 字的原因'
    })
    emit('restore', { version, reason: value.trim() })
  } catch { /* Dialog cancelled. */ }
}
function changes(row) {
  try {
    const before = JSON.parse(row.previous_answers_json || '{}')
    return Object.entries(JSON.parse(row.answers_json)).filter(([id, answer]) => before[id] !== answer)
      .map(([id, answer]) => `${id}: ${before[id] ?? '—'} → ${answer}`).join('；')
  } catch { return '' }
}
</script>

<template>
  <el-button :disabled="disabled || version === 0" @click="restore(0)">恢复原始版本 0</el-button>
  <el-table :data="jobs" empty-text="暂无纠错历史">
    <el-table-column prop="answer_version" label="版本" width="75" />
    <el-table-column label="原因" min-width="180"><template #default="{ row }"><span v-if="row.restored_from_version !== null">恢复版本 {{ row.restored_from_version }}：</span>{{ row.reason }}</template></el-table-column>
    <el-table-column prop="operator_id" label="操作人" width="160" />
    <el-table-column prop="created_at" label="时间" width="185" />
    <el-table-column prop="status" label="状态" width="115" />
    <el-table-column label="操作" width="165"><template #default="{ row }">
      <el-button text @click="emit('select', row.id)">查看</el-button>
      <el-button text :disabled="disabled || Number(row.answer_version) === version" @click="restore(Number(row.answer_version))">恢复</el-button>
    </template></el-table-column>
    <el-table-column type="expand"><template #default="{ row }"><p class="answers">相较上一版本的答案变化：{{ changes(row) }}</p></template></el-table-column>
  </el-table>
  <el-button :disabled="page <= 1" @click="emit('page', page - 1)">上一页</el-button>
  <el-button :disabled="jobs.length < 20" @click="emit('page', page + 1)">下一页</el-button>
</template>

<style scoped>
.answers { padding: 0 20px; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
