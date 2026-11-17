import { defineStore } from 'pinia'
import { ref, shallowRef } from 'vue'
import { RuntimeClient, projectRuntimeClient, type UploadInfo } from '../api/client'
import { useWorkspaceStore } from './workspace'
import { useConversationStore } from './conversation'
import type { Msg } from '../domain/chat'
import { errorMessage } from '../utils/unknown'
import { t } from '../i18n'

interface Attachment { id: string; name: string; selected: boolean; blob: Blob | null; error: string; requestId: string }
/** Only the explicitly selected message and assets cross a Scope boundary. No task is started here. */
export const useTaskHandoffStore = defineStore('taskHandoff', () => {
  const workspace = useWorkspaceStore()
  const visible = ref(false), loading = ref(false), busy = ref(false), error = ref('')
  const source = ref(''), text = ref(''), instruction = ref(''), projectId = ref('')
  const attachments = shallowRef<Attachment[]>([])
  let sequence = 0
  function close() { sequence++; visible.value = false; loading.value = false; busy.value = false; attachments.value = [] }
  async function begin(message: Msg, selection?: string) {
    if (busy.value) return
    const version = ++sequence, client = new RuntimeClient()
    visible.value = true; loading.value = true; error.value = ''; instruction.value = ''
    text.value = selection || message.originalInput || message.content
    source.value = workspace.profile === 'PERSONAL' ? t('handoff.personal') : workspace.active?.name || ''
    projectId.value = workspace.activeWorkspaceId || workspace.workspaces[0]?.id || ''
    attachments.value = [...new Set((message.contentParts || []).filter(part => part.type === 'ARTIFACT_REF' && part.artifactId).map(part => part.artifactId!))]
      .map(id => ({ id, name:id, selected:true, blob:null, error:'', requestId:crypto.randomUUID() }))
    const items = attachments.value
    await Promise.all(items.map(async item => {
      try {
        const [metadata, bytes] = await Promise.all([client.api.artifact(item.id), client.api.artifactContent(item.id)])
        if (version === sequence) { item.name = metadata.name; item.blob = bytes }
      } catch (cause) { if (version === sequence) item.error = errorMessage(cause) }
    }))
    if (version === sequence) { attachments.value = [...items]; loading.value = false }
  }
  async function prepare(): Promise<boolean> {
    if (busy.value || loading.value || !instruction.value.trim() || !projectId.value || text.value.length > 100000) return false
    const selected = attachments.value.filter(item => item.selected)
    if (selected.some(item => !item.blob)) { error.value = t('handoff.missingAttachment'); return false }
    const version = sequence, targetId = projectId.value, context = text.value, task = instruction.value.trim()
    const conversation = useConversationStore()
    let draftRequest: number | undefined
    let handedOff = false
    busy.value = true; error.value = ''
    try {
      const target = await projectRuntimeClient(targetId)
      const protocol = await target.api.protocol()
      if (!protocol.capabilities?.includes('agent.execution')) throw new Error(t('handoff.agentUnavailable'))
      const transferred: UploadInfo[] = []
      for (const item of selected) {
        if (version !== sequence) return false
        const asset = await target.api.importArtifact(item.blob!, item.name, item.requestId)
        transferred.push({ artifactId:asset.artifactId, name:asset.name, size:asset.bytes, mime:asset.mime, extracted:false,
          width:asset.width, height:asset.height })
      }
      if (version !== sequence) return false
      const scopeId = target.target.identity?.scopeId
      if (!scopeId) throw new Error(t('handoff.targetChanged'))
      draftRequest = conversation.prepareScopeDraft(scopeId,
        context.trim() ? `${task}\n\n${t('handoff.context')}:\n> ${context.replace(/\n/g, '\n> ')}` : task, transferred, target.target.identity?.instanceId)
      await workspace.open(targetId)
      if (version !== sequence) return false
      if (workspace.scopeId !== target.target.identity?.scopeId || workspace.scopeInstanceId !== target.target.identity?.instanceId) throw new Error(t('handoff.targetChanged'))
      handedOff = true; close(); return true
    } catch (cause) {
      if (draftRequest !== undefined) conversation.clearPreparedDraft(draftRequest)
      if (version === sequence) error.value = errorMessage(cause)
      return false
    }
    finally {
      if (!handedOff && draftRequest !== undefined) conversation.clearPreparedDraft(draftRequest)
      if (version === sequence) busy.value = false
    }
  }
  return { visible, loading, busy, error, source, text, instruction, projectId, attachments, begin, close, prepare }
})
