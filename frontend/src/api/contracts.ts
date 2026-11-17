import type * as Workbench from '../../../packages/protocol/workbench'
import type { WorkspaceGit as ProtocolWorkspaceGit } from '../../../packages/protocol'

export type ProtocolDocument = Workbench.ProtocolInfo
export type WorkspaceGit = ProtocolWorkspaceGit

export interface RuntimeStatusline {
  provider?: string
  model?: string
  execution?: {
    environment: 'host' | 'docker'
    sandbox: 'none' | 'read-only' | 'workspace-write'
    network?: string
    platform?: string
    nativeSandbox?: { installed: boolean; implementation: string; note?: string }
  }
  workspace?: string | null
  providers?: string[]
  approval?: string
  upload?: {
    maxSingleFileBytes?: number
    perThreadMaxBytes?: number
    maxAttachmentsPerMessage?: number
    nativeMaxAttachmentBytes?: number
    localExtractionMaxFileBytes?: number
    nativeAttachmentsEnabled?: boolean
    ocrFallbackEnabled?: boolean
  }
  [key: string]: unknown
}

export type RuntimeDiagnostics = Record<string, unknown>

export interface McpServerStatus {
  name: string
  connected: boolean
  status?: 'not_started' | 'connecting' | 'connected' | 'disconnected'
  tools: Array<{ name: string; originalName?: string; description?: string }>
}

export interface WorkspaceIdentity {
  workspaceId: string
  instanceId: string
}

export interface RuntimeWorkspace {
  workspaceId: string
  instanceId: string
  name: string
  root: string
  dataDir?: string
  hostMode: 'fixed'
  agentsMdSource?: string | null
  agentsMdBytes?: number
  agentsMdModifiedAt?: number
  preview?: string
}

export type RunView = Workbench.RunView

export type GoalCriterionStatus = 'PASS' | 'FAIL' | 'REVIEW_REQUIRED'

export interface GoalCriterionView {
  criterionId: string
  type: string
  ref?: string
  required: boolean
  assertion?: string
  expected?: string
}

export interface GoalCriterionResultView {
  criterionId: string
  type: string
  status: GoalCriterionStatus
  required: boolean
  checkedAt: number
  detail?: string
  evidence?: Record<string, unknown>
}

export interface GoalBudgetView {
  maxGoalTurns: number
  maxModelCalls: number
  maxToolCalls: number
  tokenBudget: number
  costBudget: number
  activeWallClockBudgetMs: number
  elapsedDeadline?: number
}

export interface GoalExecutionView {
  turnsUsed: number
  modelCalls: number
  toolCalls: number
  activeWallClockMs: number
  retryAttempts: number
  retryAt?: number
  consecutiveNoProgress: number
  blockerCount: number
  stopReason?: string
  cost?: { known: boolean; amount?: number; provider?: string; model?: string; calculatedAt?: number }
  criteriaResults: GoalCriterionResultView[]
  completionRequest?: { status: string; note?: string; submittedAt: number }
  steps?: Array<Record<string, unknown>>
}

export type GoalView = Workbench.GoalView

export type RunInputView = Workbench.RunInputView
export type RunInputMode = RunInputView['mode']
export type RunInputStatus = RunInputView['status']

export interface SubagentStateDto {
  id?: string
  eventType?: string
  subagentThreadId?: string
  parentThreadId?: string
  delegateCallId?: string
  goal?: string
  model?: string
  toolsets?: string[]
  depth?: number
  maxIterations?: number
  completionCriteria?: string
  output?: string
  terminalStatus?: string
  error?: boolean
  outcome: string
  startedSeq?: number
  startedAt?: number
  finishedSeq?: number
  finishedAt?: number
  activityTitle?: string
  activitySummary?: string
  details?: SemanticActivityDto[]
  detailsTruncated?: boolean
}

export interface SemanticActivityDto {
  seq: number
  ts?: number
  eventType: string
  activityKind: string
  activityTitle: string
  activitySummary?: string
  activityTargets?: string[]
  activityOutcome: string
  id?: string
  callId?: string
  name?: string
  toolName?: string
  subagentThreadId?: string
  parentThreadId?: string
  delegateCallId?: string
  childEventType?: string
  trigger?: string
  outcome?: string
  summarySource?: string
  beforeTokens?: number
  afterTokens?: number
  reductionPercent?: number
  sizeEffective?: boolean
}

export interface FileNode {
  path: string
  name: string
  type: 'file' | 'directory'
  size?: number
  modifiedAt?: number
  gitStatus?: string
}

export interface FilePage {
  path: string
  entries: FileNode[]
  nextCursor?: string
}

export interface FilePreview {
  path: string
  mime: string
  size: number
  binary: boolean
  truncated: boolean
  content?: string
  modifiedAt?: number
  gitStatus?: string
}

export interface FileSearchHit {
  path: string
  name: string
  match: 'name' | 'content'
  snippet?: string
}

export interface FileSearchPage {
  query: string
  entries: FileSearchHit[]
  nextCursor?: string
  scannedFiles: number
  truncatedScan: boolean
}

export interface WorkspaceChange {
  path: string
  status: string
  origin: 'this_run' | 'existing'
  added?: number
  removed?: number
  diffAvailable?: boolean
}

export interface ChangesView {
  runId: string
  entries: WorkspaceChange[]
  thisRun: WorkspaceChange[]
  existing: WorkspaceChange[]
  repositoryType?: 'git' | 'filesystem'
  baseline?: { snapshotSeq: number; takenAt: string; kind: string }
}

export interface FileDiff {
  path: string
  binary: boolean
  truncated: boolean
  unifiedDiff: string
  gitManaged?: boolean
  available?: boolean
}

export interface EffectiveSkillDto {
  name: string
  description: string
  source: 'USER' | 'WORKSPACE'
  overridden: boolean
  enabled: boolean
  effective: boolean
  path?: string
  bytes?: number
  modifiedAt?: number
}

export interface ConfigDocument {
  scope: 'user'
  revision: string
  values: Record<string, unknown>
  effective: Record<string, unknown>
  reloadPolicy: 'restart' | string
  restartRequired?: boolean
  configurationRevision?: string
  reconnectSections?: string[]
  deferredSections?: string[]
  reloadError?: string
  fileExists: boolean
}

export interface MemoryDocument {
  effectiveProfileSource: 'USER' | 'WORKSPACE' | 'NONE'
  user: { profile: string }
  workspace: { profile: string; memory: string; lessons: string }
  limits: { perFileCharacters: number; storedCharacters: number }
  metadata?: Record<string, {
    path?: string
    exists?: boolean
    characters?: number
    tokensEstimate?: number
    bytes?: number
    modifiedAt?: number
  }>
}
