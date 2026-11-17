<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { useConversationStore } from '../../stores/conversation'
import { imageFailureKey } from '../../domain/imageFailure'
import { t } from '../../i18n'

const props = defineProps<{ code: string; runId?: string; detail?: string | null }>()
const route = useRoute(), router = useRouter(), conversation = useConversationStore()
function configure() {
  if (!conversation.draftText.trim()) {
    const original = conversation.messages.find(message => message.role === 'user' && message.runId === props.runId)
    if (original) conversation.draftText = original.content
  }
  sessionStorage.setItem('harnex_settings_return', route.fullPath)
  void router.push('/settings?section=models&tab=images')
}
</script>

<template>
  <div class="image-failure" role="status">
    <p>{{ t(imageFailureKey(code)) }}</p>
    <button v-if="code !== 'media_tool_call_not_emitted'" type="button" @click="configure">{{ t('image.configure') }}</button>
    <details v-if="detail"><summary>{{ t('imageSettings.diagnostics') }}</summary><p>{{ detail }}</p></details>
  </div>
</template>

<style scoped>
.image-failure { margin:12px auto; max-width:880px; padding:14px 16px; border:1px solid var(--border); border-radius:12px; background:var(--surface-1); color:var(--text-secondary); font-size:13px; }
p { margin:0; line-height:1.6; overflow-wrap:anywhere; }
button { margin-top:10px; padding:6px 10px; color:var(--text-primary); background:var(--surface-2); border:1px solid var(--border); border-radius:6px; cursor:pointer; }
details { margin-top:10px; color:var(--text-muted); font-size:12px; } summary { cursor:pointer; }
</style>
