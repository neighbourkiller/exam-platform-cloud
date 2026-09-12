<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
const props = defineProps({ answerKey: { type: Object, required: true }, disabled: Boolean })
const emit = defineEmits(['submit'])
const draft = reactive({})
const reason = ref('')
const questions = computed(() => props.answerKey.snapshot.questions.filter(q => ['SINGLE', 'MULTI', 'JUDGE', 'BLANK'].includes(q.type)))
watch(() => props.answerKey.version, () => {
  Object.keys(draft).forEach(key => delete draft[key])
  Object.assign(draft, props.answerKey.answers)
}, { immediate: true })
const changes = computed(() => Object.fromEntries(Object.entries(draft).filter(([id, answer]) => answer !== props.answerKey.answers[id])))
function options(question) { try { return JSON.parse(question.optionsJson || '[]') } catch { return [] } }
function multiValue(id) { return (draft[id] || '').split(',').filter(Boolean) }
function updateMulti(id, values) { draft[id] = [...values].sort().join(',') }
async function submit() {
  try {
    await ElMessageBox.confirm(`将纠正 ${Object.keys(changes.value).length} 道题并对本场已交卷记录重新判分。成绩可能升高或降低，题库答案也将同步修改；处理期间统计逐份更新。`, '确认答案纠错', { type: 'warning' })
    emit('submit', { answers: { ...changes.value }, reason: reason.value.trim() })
  } catch { /* Dialog cancelled. */ }
}
</script>

<template>
  <el-alert title="原始试卷快照保持不变。题库同步异常不影响本场成绩纠正；填空题沿用现有全文匹配规则。" type="info" :closable="false" />
  <el-table :data="questions" max-height="440">
    <el-table-column label="题目" min-width="200"><template #default="{ row }">{{ row.content }}</template></el-table-column>
    <el-table-column prop="type" label="题型" width="85" />
    <el-table-column prop="answer" label="原始答案" width="110" />
    <el-table-column label="当前答案" width="110"><template #default="{ row }">{{ answerKey.answers[row.questionId] }}</template></el-table-column>
    <el-table-column label="纠正为" min-width="220">
      <template #default="{ row }">
        <el-input v-if="row.type === 'BLANK'" v-model="draft[row.questionId]" :disabled="disabled" maxlength="10000" />
        <el-select v-else-if="row.type === 'MULTI'" :model-value="multiValue(row.questionId)" multiple :disabled="disabled" @update:model-value="updateMulti(row.questionId, $event)">
          <el-option v-for="option in options(row)" :key="option.label" :label="`${option.label}: ${option.value}`" :value="option.label" />
        </el-select>
        <el-select v-else v-model="draft[row.questionId]" :disabled="disabled">
          <el-option v-for="option in options(row)" :key="option.label" :label="`${option.label}: ${option.value}`" :value="option.label" />
        </el-select>
      </template>
    </el-table-column>
  </el-table>
  <el-input v-model="reason" class="reason" placeholder="填写纠错原因（必填）" type="textarea" maxlength="1000" show-word-limit :disabled="disabled" />
  <el-button type="primary" :disabled="disabled || !reason.trim() || !Object.keys(changes).length" @click="submit">确认并重新判分</el-button>
</template>

<style scoped>
.reason { margin: 16px 0; }
</style>
