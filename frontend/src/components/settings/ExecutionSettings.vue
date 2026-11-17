<script setup lang="ts">
import ConfigField from './ConfigField.vue'
import { computed } from 'vue'
import { useConfigStore } from '../../stores/config'
import { useRuntimeStore } from '../../stores/runtime'
import { t } from '../../i18n'
import { isRecord } from '../../utils/unknown'

const config = useConfigStore()
const runtime = useRuntimeStore()
const values = computed(() => {
  const source = config.draft.execution ?? config.effective.execution
  return isRecord(source) ? source : {}
})
const effective = computed(() => runtime.statusline?.execution)
function value(key: string, camel: string, fallback: string) {
  return String(values.value[key] ?? values.value[camel] ?? fallback)
}
function update(key: string, camel: string, next: unknown) {
  const changed = { ...values.value }
  delete changed[camel]
  changed[key] = next
  config.setSection('execution', changed)
}
</script>

<template>
  <article class="execution-settings">
    <h3>{{ t('execution.title') }}</h3>
    <p>{{ t('execution.description') }}</p>
    <div class="fields">
      <ConfigField path="execution.environment" v-slot="{ attrs }">{{ t('execution.environment') }}
        <select v-bind="attrs" :value="value('environment', 'environment', 'host')"
          @change="update('environment', 'environment', ($event.target as HTMLSelectElement).value)">
          <option value="host">{{ t('execution.host') }}</option>
          <option value="docker">Docker</option>
        </select>
      </ConfigField>
      <ConfigField path="execution.sandbox-mode" v-slot="{ attrs }">{{ t('execution.isolation') }}
        <select v-bind="attrs" :value="value('sandbox-mode', 'sandboxMode', 'none')"
          @change="update('sandbox-mode', 'sandboxMode', ($event.target as HTMLSelectElement).value)">
          <option value="none">{{ t('execution.onDemand') }}</option>
          <option value="read-only">{{ t('execution.readOnly') }}</option>
          <option value="workspace-write">{{ t('execution.workspaceWrite') }}</option>
        </select>
      </ConfigField>
      <ConfigField path="execution.sandbox-network" v-slot="{ attrs }">{{ t('execution.network') }}
        <select v-bind="attrs" :value="value('sandbox-network', 'sandboxNetwork', 'deny')"
          @change="update('sandbox-network', 'sandboxNetwork', ($event.target as HTMLSelectElement).value)">
          <option value="deny">{{ t('execution.networkDeny') }}</option>
          <option value="allow" :disabled="value('environment', 'environment', 'host') === 'host'">{{ t('execution.networkAllow') }}</option>
        </select>
      </ConfigField>
      <ConfigField path="execution.image" v-slot="{ attrs }" v-if="value('environment', 'environment', 'host') === 'docker'">{{ t('execution.image') }}
        <input v-bind="attrs" :value="value('image', 'image', 'debian:stable-slim')"
          @input="update('image', 'image', ($event.target as HTMLInputElement).value)" />
      </ConfigField>
    </div>
    <small v-if="value('environment', 'environment', 'host') === 'host'">{{ t('execution.offlineIsolation') }}</small>
    <p v-if="effective" class="active-state">
      {{ t('execution.active') }}: {{ effective.environment }} · {{ effective.sandbox }} · {{ effective.platform }}
    </p>
    <p v-if="effective?.environment === 'host' && value('sandbox-mode', 'sandboxMode', 'none') !== 'none' && effective?.nativeSandbox?.installed === false">{{ t('execution.unavailable') }}</p>
    <small>{{ t('execution.reopen') }}</small>
  </article>
</template>

<style scoped>
.execution-settings { padding:16px; margin-bottom:18px; border:1px solid var(--border); border-radius:10px; }
h3 { margin:0 0 7px; font-size:14px; } p,small { color:var(--text-muted); font-size:12px; line-height:1.6; }
.fields { display:grid; grid-template-columns:repeat(auto-fit,minmax(200px,1fr)); gap:12px; margin:14px 0; }
label { display:flex; flex-direction:column; gap:6px; font-size:12px; }
select,input { background:var(--surface-1); color:var(--text-primary); border:1px solid var(--border); border-radius:6px; padding:8px; }
.active-state { color:var(--text-primary); }
</style>
