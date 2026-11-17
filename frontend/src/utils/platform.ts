export function isMacPlatform(platform = globalThis.navigator?.platform || ''): boolean {
  return /Mac|iPhone|iPad|iPod/i.test(platform)
}

export function primaryModifierLabel(platform = globalThis.navigator?.platform || ''): string {
  return isMacPlatform(platform) ? '⌘' : 'Ctrl'
}
