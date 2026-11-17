<script setup lang="ts">
import { t } from '../../i18n'

export type RestoreMode = 'conversation_only' | 'files_only' | 'conversation_and_files'
export interface RestoreFilePlan {
  path?: string
  scope?: string
  action?: string
  conflict?: boolean
  reason?: string
  changeType?: string
  linesAdded?: number
  linesDeleted?: number
  diffAvailable?: boolean
  diffBinary?: boolean
  diffWhitespaceOnly?: boolean
  diffTruncated?: boolean
}
export interface RestorePlan {
  historyRevision?: number
  seq: number
  mode: RestoreMode | string
  conversationRestorable?: boolean
  fileCoverage?: string
  canRestoreFiles?: boolean
  warnings?: string[]
  files?: RestoreFilePlan[]
  conflicts?: RestoreFilePlan[]
}
export interface RestoreDialogState {
  commandId?: string
  commandHash?: string
  seq: number
  mode: RestoreMode
  force: boolean
  plan: RestorePlan | null
  loading: boolean
  paths?: string[] | null
  intent?: 'snapshot' | 'file' | 'run'
  error?: string | null
}

const props = defineProps<{
  state: RestoreDialogState | null
  busy: boolean
  modes: RestoreMode[]
  modeDisabled: (mode: RestoreMode) => boolean
  confirmDisabled: () => boolean
  conflictCount: (plan?: RestorePlan | null) => number
  fileCount: (plan?: RestorePlan | null) => number
  actionSummary: (plan?: RestorePlan | null) => string
  previewFiles: (plan?: RestorePlan | null) => RestoreFilePlan[]
  previewMore: (plan?: RestorePlan | null) => number
  displayPath: (file: RestoreFilePlan) => string
  fileDiffLabel: (file: RestoreFilePlan) => string
  coverageLabel: (value: { fileCoverage?: string; fileRestorable?: boolean }) => string
}>()

const emit = defineEmits<{
  close: []
  mode: [mode: RestoreMode]
  confirm: []
  force: [value: boolean]
}>()

function updateForce(event: Event) {
  emit('force', (event.target as HTMLInputElement).checked)
}
</script>

<template>
  <div v-if="state" class="modal-backdrop" @click.self="$emit('close')">
    <div class="restore-modal" role="dialog" aria-modal="true" aria-labelledby="restore-dialog-title">
      <div class="restore-head">
        <div>
          <strong id="restore-dialog-title">{{ state.intent === 'run'
            ? t('chat.undoRunDialogTitle')
            : t('chat.snapshotRestoreDialogTitle', { seq: state.seq }) }}</strong>
          <p>{{ state.intent === 'run' ? t('chat.undoRunDialogDesc') : t('chat.snapshotRestoreDialogDesc') }}</p>
        </div>
        <button class="modal-close" @click="$emit('close')" :title="t('chat.panelClose')">×</button>
      </div>

      <div v-if="!state.intent || state.intent === 'snapshot'" class="restore-modes">
        <label v-for="modeValue in modes" :key="modeValue">
          <input type="radio" name="restore-mode" :value="modeValue" :checked="state.mode === modeValue"
            :disabled="modeDisabled(modeValue)" @change="$emit('mode', modeValue)" />
          <span>{{ t(`chat.snapshotRestoreMode.${modeValue}`) }}</span>
        </label>
      </div>

      <div v-if="state.loading" class="restore-loading">{{ t('common.loading') }}</div>
      <div v-else-if="state.error" class="restore-warning">{{ state.error }}</div>
      <template v-else>
        <div class="restore-summary">
          <span>{{ state.intent === 'run' && !state.plan?.canRestoreFiles
            ? t('chat.undoRunCoverageUnavailable')
            : coverageLabel({ fileCoverage: state.plan?.fileCoverage, fileRestorable: state.plan?.canRestoreFiles }) }}</span>
          <span v-if="state.mode !== 'files_only'">{{ state.plan?.conversationRestorable === false ? t('chat.snapshotConversationUnavailable') : t('chat.snapshotConversationAvailable') }}</span>
          <span>{{ t('chat.snapshotRestoreFiles', { n: fileCount(state.plan), coverage: state.plan?.fileCoverage || 'unverified' }) }}</span>
          <span>{{ actionSummary(state.plan) }}</span>
        </div>

        <div v-if="state.plan?.fileCoverage === 'partial'" class="restore-warning">{{ t('chat.snapshotRestorePartialWarning') }}</div>
        <div v-if="state.mode !== 'conversation_only' && !state.plan?.canRestoreFiles" class="restore-warning">
          {{ state.intent === 'run' ? t('chat.undoRunFilesUnavailable') : t('chat.snapshotRestoreFilesUnavailable') }}
        </div>

        <div v-if="conflictCount(state.plan)" class="restore-conflicts">
          <strong>{{ t('chat.snapshotRestoreConflictTitle') }}</strong>
          <p>{{ t('chat.snapshotRestoreConflicts', { n: conflictCount(state.plan) }) }}</p>
          <ul>
            <li v-for="file in state.plan?.conflicts || []" :key="file.path">
              <code>{{ file.path }}</code><span>{{ file.reason }}</span>
            </li>
          </ul>
          <label class="restore-force">
            <input type="checkbox" :checked="state.force" @change="updateForce" />
            <span>{{ t('chat.snapshotRestoreForce') }}</span>
          </label>
        </div>

        <div v-if="state.mode !== 'conversation_only' && fileCount(state.plan)" class="restore-file-preview">
          <div class="restore-file-preview-head">{{ t('chat.snapshotRestorePlanFiles') }}</div>
          <ul>
            <li v-for="file in previewFiles(state.plan)" :key="`${file.path}:${file.action}`">
              <span class="restore-file-action">{{ file.action || 'restore' }}</span>
              <code :title="displayPath(file)">{{ displayPath(file) }}</code>
              <span v-if="fileDiffLabel(file)" class="restore-file-diff">{{ fileDiffLabel(file) }}</span>
              <span v-if="file.reason" class="restore-file-reason">{{ file.reason }}</span>
            </li>
          </ul>
          <div v-if="previewMore(state.plan)" class="restore-file-more">
            {{ t('chat.snapshotRestoreMoreFiles', { n: previewMore(state.plan) }) }}
          </div>
        </div>

        <div v-if="state.plan?.warnings?.length" class="restore-warning">
          <div v-for="warning in state.plan.warnings" :key="warning">{{ warning }}</div>
        </div>
      </template>

      <div class="restore-actions">
        <button class="sm" @click="$emit('close')">{{ t('common.cancel') }}</button>
        <button class="btn-approve" :disabled="busy || confirmDisabled()" @click="$emit('confirm')">
          {{ state.intent === 'run' ? t('chat.undoRunConfirm') : t('chat.snapshotRestore') }}
        </button>
      </div>
    </div>
  </div>
</template>
