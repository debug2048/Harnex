/** Runtime wire contracts are generated from Java DTOs and OpenAPI. */
export * from './workbench'
import type * as Wire from './workbench'
export type StreamEventType = Wire.StreamEvent['type']
export type StreamEventEnvelope<T = Record<string, unknown>> = Omit<Wire.StreamEvent, 'data'> & { data: T }
export type RunStatus = Wire.RunView['status']
export type PlanReviewPolicy = NonNullable<Wire.AgentSpec['planReview']>
export type RunInputMode = Wire.RunInputView['mode']
export type RunInputStatus = Wire.RunInputView['status']
export type RunUsage = Wire.Usage
export type GoalExecution = Wire.GoalExecutionView
export type GoalCriterion = Wire.GoalView['criteria'][number]
export type GoalCriterionResult = Wire.CriterionResult
export type GoalCriterionStatus = Wire.CriterionResult['status']
export type GoalBudget = Wire.GoalView['budget']

// ─── Workspace / files / settings ──────────────────────────────────────────

/** GET /api/v1/workspace/git: live worktree state, independent of conversation history. */
export interface WorkspaceGit {
  state: 'branch' | 'detached' | 'none' | 'unavailable'
  branch: string | null
  commit: string | null
}

export interface WorkspaceIdentity {
  workspaceId: string
  instanceId: string
}

/** GET /api/v1/workspace when a client is attached directly to one Runtime. */
export interface RuntimeWorkspace extends WorkspaceIdentity {
  name: string
  root: string
  dataDir?: string
  hostMode: 'fixed'
  agentsMdSource?: string | null
  agentsMdBytes?: number
  preview?: string
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

export interface EffectiveSkill {
  name: string
  description: string
  source: 'USER' | 'WORKSPACE'
  overridden: boolean
  enabled: boolean
  effective: boolean
}

export interface ConfigDocument {
  scope: 'user'
  revision: string
  values: Record<string, unknown>
  effective: Record<string, unknown>
  reloadPolicy: string
  fileExists: boolean
}

/** Stable Node Supervisor control-plane projection. */
export type WorkspaceLifecycle = 'CLOSED' | 'DRAINING' | 'CLOSING' | 'OPENING' | 'READY' | 'FAILED'

export interface SupervisorWorkspace {
  id: string
  name: string
  root: string
  state: WorkspaceLifecycle
  instanceId: string | null
  lastOpened: string | null
  createdAt: string
  isActive: boolean
}

export interface SupervisorWorkspaceList {
  workspaces: SupervisorWorkspace[]
  activeWorkspaceId: string | null
  lastWorkspaceId: string | null
  lifecycle: WorkspaceLifecycle
  instanceId: string | null
}

// ─── Errors (§3.3 unified envelope) ─────────────────────────────────────────

/** Every non-2xx body. Branch on error.code, never on message text. */
export interface ApiError {
  error: {
    code: string
    message: string
    details?: Record<string, unknown>
  }
}
