export type SkillScope = 'USER' | 'WORKSPACE'

export interface SkillSource {
  name: string
  scope: SkillScope
  enabled: boolean
  description?: string
  source?: string
}

export interface EffectiveSkill extends SkillSource {
  effective: boolean
  overrides?: SkillSource
}

/**
 * Resolve Skills using the product rule: workspace wins by name, then enabled is applied.
 * A disabled workspace override suppresses the same-named user Skill; it never falls back.
 */
export function resolveSkills(sources: SkillSource[]): EffectiveSkill[] {
  const byName = new Map<string, { user?: SkillSource; workspace?: SkillSource }>()
  for (const skill of sources) {
    const key = skill.name.trim().toLocaleLowerCase()
    if (!key) continue
    const pair = byName.get(key) || {}
    if (skill.scope === 'WORKSPACE') pair.workspace = skill
    else pair.user = skill
    byName.set(key, pair)
  }

  const result: EffectiveSkill[] = []
  for (const pair of byName.values()) {
    const chosen = pair.workspace || pair.user
    if (!chosen) continue
    result.push({
      ...chosen,
      effective: chosen.enabled,
      overrides: pair.workspace && pair.user ? pair.user : undefined,
    })
    if (pair.workspace && pair.user) {
      result.push({ ...pair.user, effective: false })
    }
  }
  return result.sort((a, b) =>
    a.name.localeCompare(b.name) || (a.scope === 'WORKSPACE' ? -1 : 1))
}
