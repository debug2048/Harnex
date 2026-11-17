<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import type { ContextPlan, ModelCallRecord, UsageReport } from '../../../../packages/protocol/workbench'
import { api } from '../../api/client'
import { t } from '../../i18n'
import { useWorkspaceStore } from '../../stores/workspace'
import { errorMessage } from '../../utils/unknown'
import { summarizeModelUsage, summarizeContextBlocks } from '../../domain/usageSummary'

const props = defineProps<{ runId: string | null; executing: boolean }>()
const workspace = useWorkspaceStore()
const report = ref<UsageReport | null>(null)
const calls = ref<ModelCallRecord[]>([])
const plans = ref<ContextPlan[]>([])
const selectedPlanId = ref('')
const contextSection = ref<HTMLElement | null>(null)
const loading = ref(false)
const errors = ref<string[]>([])
const droppedWrites = ref(0)
const page = ref(0)
const blockPage = ref(0)
const showCalls = ref(false), showPlans = ref(false), showBlocks = ref(false)
const CALL_PAGE_SIZE = 10
let generation = 0
const entries = computed(() => report.value?.entries || [])
const modelUsage = computed(() => summarizeModelUsage(entries.value))
const visibleEntries = computed(() => [...entries.value].reverse().slice(page.value * CALL_PAGE_SIZE, (page.value + 1) * CALL_PAGE_SIZE))
const plan = computed(() => plans.value.find(item => item.contextPlanId === selectedPlanId.value) || plans.value[plans.value.length - 1])
const contextSummary = computed(() => summarizeContextBlocks(plan.value?.blocks || []))
const blocks = computed(() => plan.value?.blocks.slice(blockPage.value * 40, (blockPage.value + 1) * 40) || [])
const attempts = computed(() => new Map(calls.value.flatMap(call => (call.attempts || []).map(attempt => [attempt.attemptId, attempt] as const))))
function money(value: number | undefined, currency?: string) { return `${Number(value || 0).toFixed(6)} ${currency || ''}`.trim() }
function sourceLabel(kind: string) {
  const normalized = kind === 'history-message' ? 'message' : kind
  return ['system', 'tool-schema', 'latest-input', 'message', 'summary', 'history-prefix', 'artifact'].includes(normalized)
    ? t('diagnostics.source.' + normalized) : kind
}
function state(value: string) {
  const labels: Record<string, string> = {
    RUNNING: 'diagnostics.running', SUCCEEDED: 'diagnostics.succeeded', FAILED: 'diagnostics.failed',
    CANCELLED: 'diagnostics.cancelled', UNKNOWN: 'diagnostics.unknown',
  }
  return labels[value] ? t(labels[value]) : value
}
async function load() {
  const runId = props.runId
  if (!runId) return
  const request = ++generation
  const epoch = workspace.epoch
  loading.value = true
  const results = await Promise.allSettled([api.usage(runId),
    showCalls.value ? api.modelCalls(runId) : Promise.resolve(calls.value),
    showPlans.value ? api.contextPlans(runId) : Promise.resolve(plans.value),
    showCalls.value ? api.callDiagnosticsStatus(runId) : Promise.resolve({ droppedWrites: droppedWrites.value })])
  if (request !== generation || props.runId !== runId || workspace.epoch !== epoch) return
  const [usageResult, callResult, planResult, statusResult] = results
  if (usageResult.status === 'fulfilled') report.value = usageResult.value
  if (callResult.status === 'fulfilled') calls.value = callResult.value
  if (planResult.status === 'fulfilled') {
    plans.value = planResult.value
    if (selectedPlanId.value && !plans.value.some(item => item.contextPlanId === selectedPlanId.value)) selectedPlanId.value = ''
  }
  if (statusResult.status === 'fulfilled') droppedWrites.value = statusResult.value.droppedWrites || 0
  errors.value = results.filter(result => result.status === 'rejected').map(result => errorMessage((result as PromiseRejectedResult).reason))
  loading.value = false
}
const timingOrder = ['dispatchedAt', 'callStartedAt', 'dnsStartedAt', 'dnsFinishedAt', 'connectStartedAt', 'connectFinishedAt',
  'tlsStartedAt', 'tlsFinishedAt', 'connectionAcquiredAt', 'requestHeadersStartedAt', 'requestHeadersFinishedAt',
  'requestBodyFinishedAt', 'responseHeadersAt', 'responseBodyStartedAt', 'transportFinishedAt']
function timings(id: string) {
  const attempt = attempts.value.get(id)
  return timingOrder.filter(key => attempt?.timings?.[key] != null).map(key => ({ key,
    milliseconds: Math.max(0, Number(attempt!.timings![key]) - Number(attempt!.startedAt || 0)) }))
}
async function showContext(id: string) {
  selectedPlanId.value = id; showPlans.value = true
  await load(); await nextTick(); contextSection.value?.scrollIntoView({ block: 'start' })
}
function toggleDetails(event: Event, section: 'calls' | 'plans') {
  const opened = (event.target as HTMLDetailsElement).open
  const target = section === 'calls' ? showCalls : showPlans
  if (target.value === opened) return
  target.value = opened
  if (opened) void load()
}
watch(() => [props.runId, workspace.epoch], () => {
  generation++
  loading.value = false
  report.value = null; calls.value = []; plans.value = []; errors.value = []; droppedWrites.value = 0
  page.value = 0; blockPage.value = 0; selectedPlanId.value = ''
  showCalls.value = false; showPlans.value = false; showBlocks.value = false
  void load()
}, { immediate: true })
watch(selectedPlanId, () => { blockPage.value = 0 })
const timer = setInterval(() => { if (props.executing && !loading.value && !document.hidden) void load() }, 3000)
watch(() => props.executing, value => { if (!value) void load() })
onUnmounted(() => { generation++; clearInterval(timer) })
</script>

<template>
  <div class="inspector-body run-diagnostics">
    <div class="diagnostic-heading">
      <strong>{{ t('diagnostics.usage') }}</strong>
      <button class="sm" :disabled="loading" @click="load">{{ t('diagnostics.refresh') }}</button>
    </div>
    <p class="muted">{{ t('diagnostics.accountingHint') }}</p>
    <p v-for="error in errors" :key="error" class="panel-error" role="alert">{{ error }}</p>
    <p v-if="droppedWrites" class="muted">{{ t('diagnostics.dropped', { count: droppedWrites }) }}</p>
    <p v-if="!report && loading" class="muted">{{ t('diagnostics.loading') }}</p>
    <template v-if="report">
      <div class="usage-totals">
        <span>{{ t('diagnostics.input') }} <strong>{{ report.summary.promptTokens.toLocaleString() }}</strong></span>
        <span>{{ t('diagnostics.output') }} <strong>{{ report.summary.completionTokens.toLocaleString() }}</strong></span>
        <span>{{ t('diagnostics.images') }} <strong>{{ report.summary.images }}</strong></span>
      </div>
      <p class="cost-total">
        {{ report.summary.costComplete ? t('diagnostics.costTotal') : t('diagnostics.knownSubtotal') }}
        <strong v-for="(amount, currency) in report.summary.knownAmounts" :key="currency">{{ money(amount, String(currency)) }}</strong>
        <strong v-if="!Object.keys(report.summary.knownAmounts).length">{{ report.summary.costComplete ? '0' : t('diagnostics.unknown') }}</strong>
      </p>
      <p v-if="!report.summary.usageComplete || !report.summary.costComplete" class="muted">
        {{ t('diagnostics.partial', { usage: report.summary.unknownUsageAttempts, cost: report.summary.unknownCostAttempts }) }}
      </p>
      <p v-if="!entries.length" class="muted">{{ t('diagnostics.empty') }}</p>
      <div v-if="entries.length" class="model-usage-summary">
        <div v-for="model in modelUsage" :key="model.key" class="model-usage-row">
          <strong>{{ model.model }}</strong>
          <small>{{ model.provider }} · {{ t('diagnostics.callCount', { count: model.count }) }}</small>
          <span>{{ t('diagnostics.input') }} {{ model.input.toLocaleString() }} · {{ t('diagnostics.output') }} {{ model.output.toLocaleString() }}</span>
          <span v-if="model.images">{{ t('diagnostics.images') }} {{ model.images }}</span>
          <span v-for="(amount, currency) in model.knownAmounts" :key="currency">{{ money(amount, String(currency)) }}</span>
          <small v-if="!model.complete">{{ t('diagnostics.partialCost') }}</small>
        </div>
      </div>
      <details v-if="entries.length" class="call-details" :open="showCalls" @toggle="toggleDetails($event, 'calls')">
        <summary>{{ t('diagnostics.callDetails', { count: entries.length }) }}</summary>
        <template v-if="showCalls">
        <p class="muted">{{ t('diagnostics.recentFirst') }}</p>
      <details v-for="entry in visibleEntries" :key="entry.attemptId" class="attempt-card">
        <summary>
          <span><strong>{{ entry.model }}</strong><small>{{ entry.provider }} · {{ entry.purpose }}</small></span>
          <span class="attempt-state">{{ state(entry.state) }}<small>{{ entry.costComplete ? money(entry.knownAmount, entry.price?.currency) : t('diagnostics.partialCost') }}</small></span>
        </summary>
        <dl>
          <dt>{{ t('diagnostics.attempt') }}</dt><dd><code>{{ entry.attemptId }}</code></dd>
          <dt>{{ t('diagnostics.owner') }}</dt><dd><code>{{ entry.conversationId }}</code></dd>
          <template v-if="entry.stepId"><dt>{{ t('diagnostics.step') }}</dt><dd><code>{{ entry.stepId }}</code></dd></template>
          <dt>{{ t('diagnostics.acceptance') }}</dt><dd>{{ entry.acceptance }}</dd>
          <template v-if="attempts.get(entry.attemptId)?.firstOutputAfterMillis != null">
            <dt>{{ t('diagnostics.firstOutput') }}</dt><dd>{{ attempts.get(entry.attemptId)?.firstOutputAfterMillis }} ms</dd>
          </template>
          <template v-if="attempts.get(entry.attemptId)?.errorCode"><dt>{{ t('diagnostics.error') }}</dt><dd>{{ attempts.get(entry.attemptId)?.errorCode }}</dd></template>
          <template v-if="attempts.get(entry.attemptId)?.upstreamRequestId"><dt>{{ t('diagnostics.upstream') }}</dt><dd><code>{{ attempts.get(entry.attemptId)?.upstreamRequestId }}</code></dd></template>
          <template v-if="attempts.get(entry.attemptId)?.timings?.queueMillis != null"><dt>{{ t('diagnostics.queueTime') }}</dt><dd>{{ attempts.get(entry.attemptId)?.timings?.queueMillis }} ms</dd></template>
          <template v-for="timing in timings(entry.attemptId)" :key="timing.key"><dt>{{ t('diagnostics.timing.' + timing.key) }}</dt><dd>+{{ timing.milliseconds }} ms</dd></template>
          <template v-if="attempts.get(entry.attemptId)?.contextPlanId"><dt>{{ t('diagnostics.context') }}</dt><dd><button class="sm" @click="showContext(attempts.get(entry.attemptId)!.contextPlanId!)">{{ t('diagnostics.showContext') }}</button></dd></template>
          <template v-for="(quantity, name) in entry.quantities" :key="name"><dt>{{ name }}</dt><dd>{{ quantity }}</dd></template>
          <template v-if="entry.price"><dt>{{ t('diagnostics.priceVersion') }}</dt><dd><code>{{ entry.price.version || '—' }}</code></dd>
            <dt>{{ t('diagnostics.priceBasis') }}</dt><dd>{{ entry.price.basis }}</dd>
            <template v-for="(rate, name) in entry.price.rates" :key="name"><dt>{{ name }}</dt><dd>{{ rate }} {{ entry.price.currency }} / {{ name === 'image' ? t('diagnostics.oneImage') : '1M Token' }}</dd></template>
          </template>
        </dl>
      </details>
      <nav v-if="entries.length > CALL_PAGE_SIZE" class="diagnostic-pages" :aria-label="t('diagnostics.attempts')">
        <button class="sm" :disabled="page === 0" @click="page--">{{ t('diagnostics.previous') }}</button>
        <span>{{ page + 1 }} / {{ Math.ceil(entries.length / CALL_PAGE_SIZE) }}</span>
        <button class="sm" :disabled="(page + 1) * CALL_PAGE_SIZE >= entries.length" @click="page++">{{ t('diagnostics.next') }}</button>
      </nav>
        </template>
      </details>
    </template>
    <details ref="contextSection" class="context-plans" :open="showPlans" @toggle="toggleDetails($event, 'plans')">
      <summary>{{ t('diagnostics.context') }}</summary>
      <template v-if="showPlans">
      <p class="muted">{{ t('diagnostics.contextEstimate') }}</p>
      <p v-if="!plans.length" class="muted">{{ t('diagnostics.noContext') }}</p>
      <template v-if="plan">
        <select v-if="plans.length > 1" v-model="selectedPlanId" :aria-label="t('diagnostics.context')">
          <option value="">{{ t('diagnostics.latestContext') }}</option>
          <option v-for="item in plans" :key="item.contextPlanId" :value="item.contextPlanId">{{ item.provider }} / {{ item.model }} · {{ item.contextPlanId }}</option>
        </select>
        <dl>
          <dt>{{ t('diagnostics.target') }}</dt><dd>{{ plan.provider }} / {{ plan.model }}</dd>
          <dt>{{ t('diagnostics.inputBudget') }}</dt><dd>{{ plan.estimatedTokens }} / {{ plan.inputBudget }}</dd>
          <dt>{{ t('diagnostics.reservedOutput') }}</dt><dd>{{ plan.outputReserve }}</dd>
          <dt>{{ t('diagnostics.safetyReserve') }}</dt><dd>{{ plan.safetyReserve }}</dd>
          <dt>{{ t('diagnostics.policy') }}</dt><dd>{{ plan.policyVersion }}</dd>
          <template v-if="plan.summaryId"><dt>{{ t('diagnostics.summary') }}</dt><dd><code>{{ plan.summaryId }}</code></dd></template>
        </dl>
        <ul class="context-summary">
          <li v-for="group in contextSummary" :key="group.kind">
            <strong>{{ sourceLabel(group.kind) }}</strong>
            <span>{{ group.tokens.toLocaleString() }} Token</span>
            <small>{{ t('diagnostics.blockCount', { included: group.included, omitted: group.omitted }) }}</small>
          </li>
        </ul>
        <details class="block-details" :open="showBlocks" @toggle="showBlocks = ($event.target as HTMLDetailsElement).open">
          <summary>{{ t('diagnostics.blockDetails', { count: plan.blocks.length }) }}</summary>
          <template v-if="showBlocks">
        <ul class="context-blocks">
          <li v-for="(block, index) in blocks" :key="`${block.sourceKind}:${block.sourceRef}:${index}`">
            <div><strong>{{ block.sourceKind }}</strong><span>{{ block.included ? t('diagnostics.included') : t('diagnostics.omitted') }} · {{ block.estimatedTokens }}</span></div>
            <code>{{ block.sourceRef }}</code>
            <small v-if="block.omissionReason">{{ block.omissionReason }}</small>
          </li>
        </ul>
        <nav v-if="plan.blocks.length > 40" class="diagnostic-pages" :aria-label="t('diagnostics.context')">
          <button class="sm" :disabled="blockPage === 0" @click="blockPage--">{{ t('diagnostics.previous') }}</button>
          <span>{{ blockPage + 1 }} / {{ Math.ceil(plan.blocks.length / 40) }}</span>
          <button class="sm" :disabled="(blockPage + 1) * 40 >= plan.blocks.length" @click="blockPage++">{{ t('diagnostics.next') }}</button>
        </nav>
          </template>
        </details>
      </template>
      </template>
    </details>
  </div>
</template>

<style scoped>
.run-diagnostics { font-size: 12px; overflow-wrap: anywhere; }
.diagnostic-heading, .usage-totals, .diagnostic-pages, .context-blocks li > div { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.usage-totals { margin: 14px 0; flex-wrap: wrap; }
.usage-totals span { display: grid; gap: 4px; color: var(--text-muted); }
.usage-totals strong { font-size: 18px; color: var(--text-primary); font-variant-numeric: tabular-nums; }
.cost-total { display: flex; flex-wrap: wrap; gap: 8px; }
.muted, small { color: var(--text-muted); line-height: 1.6; }
.attempt-card { border: 1px solid var(--border); border-radius: 7px; margin: 8px 0; }
.attempt-card summary { cursor: pointer; display: flex; justify-content: space-between; gap: 12px; padding: 11px; }
.attempt-card summary small { display: block; }
.attempt-state { text-align: right; white-space: nowrap; }
dl { display: grid; grid-template-columns: minmax(80px, 1fr) minmax(0, 2fr); gap: 7px 12px; padding: 0 12px 10px; }
dt { color: var(--text-muted); } dd { margin: 0; } code { font-size: 11px; }
.diagnostic-pages { margin: 12px 0; }
.context-plans { border-top: 1px solid var(--border); padding-top: 18px; margin-top: 20px; }
.context-plans select { width: 100%; margin-bottom: 12px; }
.context-blocks { list-style: none; padding: 0; }
.context-blocks li { padding: 10px 0; border-top: 1px solid var(--border); }
.context-blocks code, .context-blocks small { display: block; margin-top: 5px; }
.context-blocks span { color: var(--text-muted); }
.call-details > summary, .context-plans > summary, .block-details > summary { cursor:pointer; padding:12px 0; color:var(--text-primary); }
.model-usage-summary { margin:16px 0; }
.model-usage-row { display:grid; gap:4px; padding:12px 0; border-top:1px solid var(--border); }
.model-usage-row > span { color:var(--text-secondary); font-variant-numeric:tabular-nums; }
.context-summary { padding:0; list-style:none; }
.context-summary li { display:grid; grid-template-columns:1fr auto; gap:4px 12px; padding:8px 0; }
.context-summary small { grid-column:1 / -1; }
</style>
