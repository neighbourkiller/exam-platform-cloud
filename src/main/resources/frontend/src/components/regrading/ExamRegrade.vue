<script setup>
import { computed, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useRegrade } from '../../composables/useRegrade'
import RegradeAnswerForm from './RegradeAnswerForm.vue'
import RegradeHistory from './RegradeHistory.vue'
import RegradeTaskPanel from './RegradeTaskPanel.vue'
const props = defineProps({ examId: { type: [String, Number], required: true } })
const state = useRegrade(computed(() => props.examId))
const { key, history, detail, page, historyPage, busy, error, unfinished } = state
const tab = ref('answers')
const bankReview = ref(null)
const disabled = computed(() => busy.value || unfinished.value || !key.value?.accepting)
async function submit(event) { await state.submit(event.answers, event.reason); if (!error.value) tab.value = 'task' }
async function restore(event) { await state.submit({}, event.reason, event.version); if (!error.value) tab.value = 'task' }
function select(id) { state.select(id); tab.value = 'task' }
async function review(row) {
  try {
    if (row.status === 'FAILED') {
      await ElMessageBox.confirm('重试原题库同步请求，系统会核实上次操作是否已成功，避免重复修改。', '重试题库同步')
      await state.retryBank(row.id, 'retry-original-operation')
      return
    }
    const context = await state.bankContext(row.id)
    bankReview.value = { ...context, id: row.id }
  } catch (e) { if (e instanceof Error) error.value = e.message }
}
async function confirmBank() {
  await state.retryBank(bankReview.value.id, bankReview.value.question.fingerprint)
  if (!error.value) bankReview.value = null
}
</script>

<template>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-button :loading="busy" @click="state.refresh">刷新</el-button>
  <el-tabs v-if="key" v-model="tab">
    <el-tab-pane :label="`答案纠错（当前版本 ${key.version}）`" name="answers">
      <RegradeAnswerForm :answer-key="key" :disabled="disabled" @submit="submit" />
    </el-tab-pane>
    <el-tab-pane label="任务进度" name="task">
      <RegradeTaskPanel v-if="detail" :task="detail" :page="page" :busy="busy" @retry="state.retry" @page="page = $event" @bank-retry="review" />
      <el-empty v-else description="暂无重判任务" />
    </el-tab-pane>
    <el-tab-pane label="版本历史" name="history">
      <RegradeHistory :jobs="history" :version="Number(key.version)" :disabled="disabled" :page="historyPage" @select="select" @restore="restore" @page="historyPage = $event" />
    </el-tab-pane>
  </el-tabs>
  <el-skeleton v-else-if="!error" :rows="5" animated />
  <el-dialog :model-value="Boolean(bankReview)" title="核对当前题库题目" append-to-body @close="bankReview = null">
    <template v-if="bankReview">
      <template v-if="bankReview.question">
        <p>{{ bankReview.question.content }}</p>
        <p>选项：{{ bankReview.question.optionsJson }}</p>
        <p>当前答案：{{ bankReview.question.answer }} → 目标答案：{{ bankReview.targetAnswer }}</p>
        <el-button type="primary" :loading="busy" @click="confirmBank">确认当前题目并重试同步</el-button>
      </template>
      <el-alert v-else title="题库题目已删除，无法同步。本场考试重判结果不受影响。" type="warning" :closable="false" />
    </template>
  </el-dialog>
</template>
