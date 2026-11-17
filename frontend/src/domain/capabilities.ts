export const CAPABILITIES = {
  workspaceLifecycle: 'workspace.lifecycle',
  runProjection: 'run.projection',
  semanticActivity: 'activity.semantic',
  filesRead: 'files.read',
  filesSearch: 'files.search',
  changesRead: 'changes.read',
  changesDiff: 'changes.diff',
  configRead: 'config.read',
  configWrite: 'config.write',
  skillsToggle: 'skills.toggle',
  automationsWrite: 'automations.write',
  snapshots: 'snapshots',
} as const

export type Capability = typeof CAPABILITIES[keyof typeof CAPABILITIES]

export function hasCapability(
  document: { capabilities?: string[] } | null | undefined,
  capability: Capability,
): boolean {
  return Boolean(document?.capabilities?.includes(capability))
}
