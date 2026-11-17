<script setup lang="ts">
import { computed } from 'vue'
import { useConfigStore } from '../../stores/config'
import { t } from '../../i18n'

const props = defineProps<{ path: string; prefix?: boolean }>()
const config = useConfigStore()
const id = computed(() => `config-field-${props.path}`)
const issues = computed(() => config.issues.filter(issue => issue.path === props.path
  || props.prefix && issue.path.startsWith(`${props.path}.`)))
const message = computed(() => issues.value.map(issue => /requires profile|must not be blank|is required/.test(issue.message)
  ? t('settings.fieldRequired') : issue.message).join('\n'))
const attrs = computed(() => ({ id: id.value, 'aria-invalid': message.value ? 'true' as const : undefined,
  'aria-describedby': message.value ? `${id.value}-error` : undefined }))
</script>

<template>
  <label class="config-field" :data-config-field="path" :for="id">
    <slot :attrs="attrs" />
    <small v-if="message" :id="`${id}-error`" class="config-field-error" role="alert">{{ message }}</small>
  </label>
</template>

<style scoped>
.config-field { min-width:0; scroll-margin-block:28px; }
.config-field :deep([aria-invalid=true]) { border-color:var(--danger)!important; box-shadow:0 0 0 1px var(--danger); }
.config-field-error { color:var(--danger)!important; white-space:pre-wrap; overflow-wrap:anywhere; font-size:12px; line-height:1.5; }
</style>
