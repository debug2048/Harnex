<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../../api/client'
import type { MemoryDocument, RuntimeWorkspace } from '../../api/contracts'
import type { MessageAttachmentView } from '../../domain/chat'
import { locale, t } from '../../i18n'
import { useSkillStore } from '../../stores/skills'
import { useWorkspaceStore } from '../../stores/workspace'
import { errorMessage } from '../../utils/unknown'

const props = defineProps<{
  explicitContexts: string[]
  preparedAttachments: MessageAttachmentView[]
  conversationAttachments: MessageAttachmentView[]
  invokedSkill?: string
}>()

type SourceRow = {
  key: string
  label: string
  source: string
  scope: string
  bytes?: number
  characters?: number
  tokens?: number
  modifiedAt?: number
  mode?: string
}

const workspace = useWorkspaceStore()
const skills = useSkillStore()
const workspaceDocument = ref<RuntimeWorkspace | null>(null)
const memory = ref<MemoryDocument | null>(null)
const loading = ref(false)
const loadError = ref('')

function memoryRow(key: string, label: string, scope: string, content: string, mode: string): SourceRow | null {
  if (!content.trim()) return null
  const metadata = memory.value?.metadata?.[key]
  return {
    key,
    label,
    source: metadata?.path || label,
    scope,
    bytes: metadata?.bytes,
    characters: metadata?.characters ?? content.length,
    tokens: metadata?.tokensEstimate ?? Math.ceil(content.length / 4),
    modifiedAt: metadata?.modifiedAt,
    mode,
  }
}

const instructionRows = computed<SourceRow[]>(() => {
  const document = workspaceDocument.value
  if (!document?.agentsMdSource || !document.agentsMdBytes) return []
  const normalizedRoot = document.root.replace(/\\/g, '/').toLowerCase()
  const normalizedSource = document.agentsMdSource.replace(/\\/g, '/').toLowerCase()
  return [{
    key: 'agents',
    label: 'AGENTS.md',
    source: document.agentsMdSource,
    scope: normalizedSource.startsWith(normalizedRoot) ? t('context.workspaceScope') : t('context.userScope'),
    bytes: document.agentsMdBytes,
    tokens: Math.ceil(document.agentsMdBytes / 4),
    modifiedAt: document.agentsMdModifiedAt,
    mode: t('context.autoInjected'),
  }]
})

const memoryRows = computed<SourceRow[]>(() => {
  if (!memory.value) return []
  const rows = [
    memoryRow('userProfile', t('context.userProfile'), t('context.userScope'),
      memory.value.user.profile,
      memory.value.effectiveProfileSource === 'USER' ? t('context.autoInjected') : t('context.overridden')),
    memoryRow('workspaceProfile', t('context.workspaceProfile'), t('context.workspaceScope'),
      memory.value.workspace.profile,
      memory.value.effectiveProfileSource === 'WORKSPACE' ? t('context.autoInjected') : t('context.inactive')),
    memoryRow('workspaceMemory', t('context.workspaceMemory'), t('context.workspaceScope'),
      memory.value.workspace.memory, t('context.autoInjected')),
    memoryRow('workspaceLessons', t('context.workspaceLessons'), t('context.workspaceScope'),
      memory.value.workspace.lessons, t('context.autoInjected')),
  ]
  return rows.filter((row): row is SourceRow => Boolean(row))
})

// The global skill catalog is merely an availability summary in the system prompt; listing every
// installed SKILL.md here was noisy and its full-file byte count overstated the actual context cost.
// Only surface a skill when this turn explicitly invoked its full body through `/skill`.
const skillRows = computed<SourceRow[]>(() => skills.items
  .filter(skill => skill.effective && skill.enabled && props.invokedSkill === skill.name)
  .map(skill => ({
    key: `${skill.source}:${skill.name}`,
    label: skill.name,
    source: skill.path || `${skill.source}/skills/${skill.name}/SKILL.md`,
    scope: skill.source === 'WORKSPACE' ? t('context.workspaceScope') : t('context.userScope'),
    bytes: skill.bytes,
    tokens: skill.bytes ? Math.ceil(skill.bytes / 4) : undefined,
    modifiedAt: skill.modifiedAt,
    mode: t('context.invokedBody'),
  })))

async function load() {
  const epoch = workspace.epoch
  loading.value = true
  loadError.value = ''
  try {
    const [nextWorkspace, nextMemory] = await Promise.all([
      api.workspace(),
      api.memory(),
      props.invokedSkill ? skills.load() : Promise.resolve(),
    ])
    if (epoch !== workspace.epoch) return
    workspaceDocument.value = nextWorkspace
    memory.value = nextMemory
  } catch (cause: unknown) {
    if (epoch === workspace.epoch) loadError.value = errorMessage(cause)
  } finally {
    if (epoch === workspace.epoch) loading.value = false
  }
}

function formatSize(bytes?: number, characters?: number) {
  if (bytes != null) {
    if (bytes < 1024) return `${bytes} B`
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return characters != null ? t('context.characters', { n: characters }) : '—'
}

function formatTime(value?: number) {
  if (!value) return t('context.unknownTime')
  return new Intl.DateTimeFormat(locale.value, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function attachmentSource(attachment: MessageAttachmentView) {
  return attachment.path || attachment.name
}

watch(() => [workspace.epoch, props.invokedSkill], load)
onMounted(load)
</script>

<template>
  <div class="inspector-body context-sources">
    <header class="context-head">
      <div>
        <strong>{{ t('chat.contextSources') }}</strong>
        <small>{{ t('context.traceabilityDesc') }}</small>
      </div>
      <button type="button" :disabled="loading" @click="load">{{ t('chat.refresh') }}</button>
    </header>
    <p v-if="loadError" class="panel-error">{{ loadError }}</p>

    <section>
      <div class="drawer-eyebrow">{{ t('chat.explicitContext') }}</div>
      <p v-if="!explicitContexts.length" class="empty-panel">{{ t('chat.noExplicitContext') }}</p>
      <article v-for="path in explicitContexts" :key="path" class="context-source-row">
        <span class="context-badge">⌁</span>
        <div><strong>{{ path }}</strong><small>{{ t('context.referenceOnly') }}</small></div>
        <em>{{ t('context.explicit') }}</em>
      </article>
    </section>

    <section>
      <div class="drawer-eyebrow">{{ t('context.attachments') }}</div>
      <p class="context-note">{{ t('context.attachmentDecision') }}</p>
      <p v-if="!preparedAttachments.length && !conversationAttachments.length" class="empty-panel">
        {{ t('context.noAttachments') }}
      </p>
      <article v-for="attachment in preparedAttachments" :key="`prepared:${attachmentSource(attachment)}`"
        class="context-source-row">
        <span class="context-badge">＋</span>
        <div>
          <strong>{{ attachment.name }}</strong><code>{{ attachmentSource(attachment) }}</code>
          <small>{{ attachment.mime || t('context.unknownMime') }} · {{ formatSize(attachment.size) }}</small>
        </div>
        <em>{{ t('context.preparedAttachment') }}</em>
      </article>
      <article v-for="attachment in conversationAttachments"
        :key="`conversation:${attachmentSource(attachment)}`" class="context-source-row">
        <span class="context-badge">▧</span>
        <div>
          <strong>{{ attachment.name }}</strong><code>{{ attachmentSource(attachment) }}</code>
          <small>{{ attachment.mime || t('context.unknownMime') }} · {{ formatSize(attachment.size) }}</small>
        </div>
        <em>{{ t('context.conversationAttachment') }}</em>
      </article>
    </section>

    <section>
      <div class="drawer-eyebrow">{{ t('context.instructions') }}</div>
      <p v-if="!loading && !instructionRows.length" class="empty-panel">{{ t('context.noInstructions') }}</p>
      <article v-for="source in instructionRows" :key="source.key" class="context-source-row">
        <span class="context-badge">A</span>
        <div>
          <strong>{{ source.label }}</strong><code>{{ source.source }}</code>
          <small>{{ source.scope }} · {{ source.mode }} · {{ formatSize(source.bytes, source.characters) }}
            · ≈{{ source.tokens || 0 }} tok · {{ formatTime(source.modifiedAt) }}</small>
        </div>
      </article>
    </section>

    <section>
      <div class="drawer-eyebrow">{{ t('context.memory') }}</div>
      <p v-if="!loading && !memoryRows.length" class="empty-panel">{{ t('context.noMemory') }}</p>
      <article v-for="source in memoryRows" :key="source.key" class="context-source-row"
        :class="{ inactive: source.mode === t('context.inactive') || source.mode === t('context.overridden') }">
        <span class="context-badge">M</span>
        <div>
          <strong>{{ source.label }}</strong><code>{{ source.source }}</code>
          <small>{{ source.scope }} · {{ source.mode }} · {{ formatSize(source.bytes, source.characters) }}
            · ≈{{ source.tokens || 0 }} tok · {{ formatTime(source.modifiedAt) }}</small>
        </div>
      </article>
    </section>

    <section v-if="skillRows.length">
      <div class="drawer-eyebrow">{{ t('context.skills') }}</div>
      <article v-for="source in skillRows" :key="source.key" class="context-source-row"
        :class="{ invoked: source.mode === t('context.invokedBody') }">
        <span class="context-badge">S</span>
        <div>
          <strong>{{ source.label }}</strong><code>{{ source.source }}</code>
          <small>{{ source.scope }} · {{ source.mode }} · {{ formatSize(source.bytes) }}
            <template v-if="source.tokens"> · ≈{{ source.tokens }} tok</template>
            · {{ formatTime(source.modifiedAt) }}</small>
        </div>
      </article>
    </section>
  </div>
</template>

<style scoped>
.context-sources { padding:14px 14px 28px; overflow:auto; }
.context-head { display:flex; align-items:flex-start; gap:10px; margin-bottom:16px; }
.context-head > div { min-width:0; flex:1; display:grid; gap:3px; }
.context-head strong { font-size:13px; }
.context-head small { color:var(--text-muted); font-size:12px; line-height:1.45; }
.context-head button { padding:5px 8px; border:1px solid var(--border); border-radius:6px; }
.context-sources section + section { margin-top:18px; }
.context-note { margin:0 0 7px; color:var(--text-muted); font-size:11px; line-height:1.45; }
.context-source-row {
  display:grid;
  grid-template-columns:24px minmax(0,1fr) auto;
  gap:8px;
  align-items:start;
  padding:8px 0;
  border-bottom:1px solid var(--border-soft);
}
.context-source-row.inactive { opacity:.58; }
.context-source-row.invoked { color:var(--accent-hover); }
.context-badge {
  width:22px;
  height:22px;
  display:grid;
  place-items:center;
  border-radius:6px;
  background:var(--accent-soft);
  color:var(--accent);
  font-size:12px;
  font-weight:750;
}
.context-source-row > div { min-width:0; display:grid; gap:3px; }
.context-source-row strong { font-size:12px; }
.context-source-row code {
  overflow:hidden;
  color:var(--text-muted);
  font:12px var(--font-mono);
  text-overflow:ellipsis;
  white-space:nowrap;
}
.context-source-row small { color:var(--text-muted); font-size:12px; line-height:1.4; }
.context-source-row em {
  padding:2px 5px;
  border:1px solid var(--border);
  border-radius:5px;
  color:var(--text-muted);
  font-size:12px;
  font-style:normal;
}
</style>
