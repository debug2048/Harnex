<script setup lang="ts">
import { computed } from 'vue'
import { t } from '../i18n'

const props = defineProps<{ profile: 'PERSONAL' | 'PROJECT' | null; phase: string }>()
const text = computed(() => props.profile === 'PROJECT'
  ? t(['DRAINING', 'CLOSING'].includes(props.phase) ? 'workspace.closing' : 'workspace.opening')
  : t(props.profile === 'PERSONAL' ? 'chat.loadingWorkbench' : 'common.loading'))
</script>

<template>
  <div class="workspace-transition-card">
    <span class="workspace-transition-spinner" aria-hidden="true" />
    <strong>{{ text }}</strong>
    <small v-if="profile === 'PROJECT'">{{ t('workspace.transitionNote') }}</small>
    <small v-if="profile === 'PROJECT'">{{ t('workspace.transitionSlowHint') }}</small>
  </div>
</template>
