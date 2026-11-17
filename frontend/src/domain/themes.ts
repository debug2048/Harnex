export const THEME_IDS = ['system', 'light', 'dark', 'liuguang', 'changan', 'yanzhi', 'vitesse'] as const
export type Theme = typeof THEME_IDS[number]
export type ThemeMode = 'light' | 'dark'

export function isTheme(value: unknown): value is Theme {
  return typeof value === 'string' && (THEME_IDS as readonly string[]).includes(value)
}

export function resolveTheme(theme: Theme, prefersDark: boolean): Exclude<Theme, 'system'> {
  return theme === 'system' ? (prefersDark ? 'dark' : 'light') : theme
}

export function themeFollowsSystem(theme: Theme): boolean {
  return theme === 'system' || theme === 'liuguang' || theme === 'changan' || theme === 'yanzhi' || theme === 'vitesse'
}

/**
 * Theme identity and colour mode are deliberately separate. Most presets have a
 * fixed mode, while Liuguang, Danque (persisted id: changan), Yanzhi, and Vitesse contain
 * paired light/dark palettes and follow the OS preference without losing their
 * identity. "System" is the only preference that changes the theme identity
 * itself, resolving to the base Light or Dark theme.
 */
export function resolveThemeMode(theme: Theme, prefersDark: boolean): ThemeMode {
  if (themeFollowsSystem(theme)) {
    return prefersDark ? 'dark' : 'light'
  }
  return theme === 'dark' ? 'dark' : 'light'
}
