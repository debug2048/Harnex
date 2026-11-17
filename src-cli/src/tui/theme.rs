use std::path::PathBuf;

use ratatui::style::{Color, Modifier, Style};

/// Every colored surface has a paired foreground; terminal defaults are used
/// together only in monochrome mode. Values match the approved visual design.
#[derive(Clone, Copy, Debug)]
pub struct Theme {
    pub background: Color,
    pub foreground: Color,
    pub surface: Color,
    pub inset: Color,
    pub user_background: Color,
    pub secondary: Color,
    pub muted: Color,
    pub placeholder: Color,
    pub accent: Color,
    pub selection_background: Color,
    pub selection_foreground: Color,
    pub success: Color,
    pub warning: Color,
    pub danger: Color,
    pub border: Color,
    pub monochrome: bool,
}

impl Theme {
    #[cfg(test)]
    pub fn named(name: &str, no_color: bool) -> Self {
        Self::resolve(name, no_color, terminal_is_light())
    }

    pub fn resolve(name: &str, no_color: bool, terminal_light: bool) -> Self {
        if no_color {
            return Self {
                background: Color::Reset,
                foreground: Color::Reset,
                surface: Color::Reset,
                inset: Color::Reset,
                user_background: Color::Reset,
                secondary: Color::Reset,
                muted: Color::Reset,
                placeholder: Color::Reset,
                accent: Color::Reset,
                selection_background: Color::Reset,
                selection_foreground: Color::Reset,
                success: Color::Reset,
                warning: Color::Reset,
                danger: Color::Reset,
                border: Color::Reset,
                monochrome: true,
            };
        }
        match name {
            "light" => Self {
                background: rgb(0xf5f6f8),
                foreground: rgb(0x202631),
                surface: rgb(0xffffff),
                inset: rgb(0xedeff3),
                user_background: rgb(0xe6ebf6),
                secondary: rgb(0x556173),
                muted: rgb(0x657186),
                placeholder: rgb(0x707783),
                accent: rgb(0x4055a8),
                selection_background: rgb(0xe1e7f4),
                selection_foreground: rgb(0x263957),
                success: rgb(0x27613d),
                warning: rgb(0x805700),
                danger: rgb(0xa32c3d),
                border: rgb(0xd0d6df),
                monochrome: false,
            },
            "ansi" => Self {
                background: Color::Black,
                foreground: Color::Gray,
                surface: Color::Black,
                inset: Color::Black,
                user_background: Color::Black,
                secondary: Color::Gray,
                muted: Color::Gray,
                placeholder: Color::DarkGray,
                accent: Color::LightBlue,
                selection_background: Color::Gray,
                selection_foreground: Color::Black,
                success: Color::LightGreen,
                warning: Color::LightYellow,
                danger: Color::LightRed,
                border: Color::DarkGray,
                monochrome: false,
            },
            "auto" if terminal_light => Self::resolve("light", false, false),
            _ => Self {
                background: rgb(0x17191f),
                foreground: rgb(0xe6e9f0),
                surface: rgb(0x20242d),
                inset: rgb(0x1b1e25),
                user_background: rgb(0x252d3e),
                secondary: rgb(0xaab4c4),
                muted: rgb(0x98a3b6),
                placeholder: rgb(0x818c9e),
                accent: rgb(0xa7b8ff),
                selection_background: rgb(0x2c354a),
                selection_foreground: rgb(0xf2f5fc),
                success: rgb(0x91c8a1),
                warning: rgb(0xe8c078),
                danger: rgb(0xf0a0a8),
                border: rgb(0x3b4352),
                monochrome: false,
            },
        }
    }

    pub fn base_style(self) -> Style {
        Style::default().fg(self.foreground).bg(self.background)
    }

    pub fn surface_style(self) -> Style {
        Style::default().fg(self.foreground).bg(self.surface)
    }

    pub fn selection_style(self) -> Style {
        if self.monochrome {
            self.base_style()
                .add_modifier(Modifier::REVERSED | Modifier::BOLD)
        } else {
            Style::default()
                .fg(self.selection_foreground)
                .bg(self.selection_background)
        }
    }
}

const fn rgb(value: u32) -> Color {
    Color::Rgb((value >> 16) as u8, (value >> 8) as u8, value as u8)
}

fn preference_path() -> PathBuf {
    crate::non_blank_env("HARNEX_USER_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| crate::home_dir().join(".harnex"))
        .join("tui-theme")
}

pub fn initial_name(explicit: Option<String>) -> String {
    explicit.unwrap_or_else(|| {
        std::fs::read_to_string(preference_path())
            .ok()
            .map(|value| value.trim().to_string())
            .filter(|value| ["auto", "dark", "light", "ansi"].contains(&value.as_str()))
            .unwrap_or_else(|| "auto".into())
    })
}

pub fn save(name: &str) -> Result<(), String> {
    if !["auto", "dark", "light", "ansi"].contains(&name) {
        return Err("Unknown theme".into());
    }
    let path = preference_path();
    std::fs::create_dir_all(path.parent().unwrap()).map_err(|error| error.to_string())?;
    std::fs::write(path, name).map_err(|error| error.to_string())
}

/// Read the terminal's original background before painting the first frame.
/// COLORFGBG also covers Unix terminals; unknown hosts use the paired dark theme.
pub fn terminal_is_light() -> bool {
    if let Some(value) = crate::non_blank_env("COLORFGBG") {
        if let Some(light) = colorfgbg_light(&value) {
            return light;
        }
    }
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::System::Console::{
            GetConsoleScreenBufferInfoEx, GetStdHandle, CONSOLE_SCREEN_BUFFER_INFOEX,
            STD_OUTPUT_HANDLE,
        };
        let mut info = CONSOLE_SCREEN_BUFFER_INFOEX {
            cbSize: std::mem::size_of::<CONSOLE_SCREEN_BUFFER_INFOEX>() as u32,
            ..Default::default()
        };
        if GetConsoleScreenBufferInfoEx(GetStdHandle(STD_OUTPUT_HANDLE), &mut info) != 0 {
            let color = info.ColorTable[((info.wAttributes >> 4) & 0xf) as usize];
            return background_is_light(
                (color & 255) as u8,
                ((color >> 8) & 255) as u8,
                ((color >> 16) & 255) as u8,
            );
        }
    }
    false
}

fn colorfgbg_light(value: &str) -> Option<bool> {
    value
        .rsplit(';')
        .next()?
        .parse::<u8>()
        .ok()
        .map(|index| matches!(index, 7 | 15))
}

#[cfg(any(windows, test))]
fn background_is_light(r: u8, g: u8, b: u8) -> bool {
    (u32::from(r) * 2126 + u32::from(g) * 7152 + u32::from(b) * 722) > 128 * 10000
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    fn luminance(color: Color) -> f64 {
        let Color::Rgb(r, g, b) = color else {
            panic!("RGB palette expected")
        };
        let linear = |v: u8| {
            let v = f64::from(v) / 255.0;
            if v <= 0.04045 {
                v / 12.92
            } else {
                ((v + 0.055) / 1.055).powf(2.4)
            }
        };
        linear(r) * 0.2126 + linear(g) * 0.7152 + linear(b) * 0.0722
    }

    pub fn contrast(a: Color, b: Color) -> f64 {
        let (a, b) = (luminance(a), luminance(b));
        (a.max(b) + 0.05) / (a.min(b) + 0.05)
    }

    #[test]
    fn paired_palettes_keep_text_readable_on_their_surfaces() {
        for name in ["dark", "light"] {
            let theme = Theme::resolve(name, false, false);
            for background in [theme.background, theme.surface] {
                assert!(contrast(theme.foreground, background) >= 7.0, "{name} body");
                for text in [
                    theme.secondary,
                    theme.muted,
                    theme.accent,
                    theme.success,
                    theme.warning,
                    theme.danger,
                ] {
                    assert!(
                        contrast(text, background) >= 4.5,
                        "{name} {text:?} on {background:?}"
                    );
                }
            }
            assert!(contrast(theme.placeholder, theme.surface) >= 4.5);
            assert!(contrast(theme.secondary, theme.inset) >= 4.5);
            assert!(contrast(theme.selection_foreground, theme.selection_background) >= 7.0);
        }
    }

    #[test]
    fn auto_uses_the_original_terminal_background_and_plain_mode_preserves_defaults() {
        assert!(background_is_light(245, 246, 248));
        assert!(!background_is_light(23, 25, 31));
        assert_eq!(colorfgbg_light("0;15"), Some(true));
        assert_eq!(colorfgbg_light("15;0"), Some(false));
        assert_eq!(colorfgbg_light("invalid"), None);
        assert_eq!(
            Theme::resolve("auto", false, true).background,
            rgb(0xf5f6f8)
        );
        assert_eq!(
            Theme::resolve("auto", false, false).background,
            rgb(0x17191f)
        );
        let plain = Theme::resolve("light", true, true);
        assert_eq!(plain.foreground, Color::Reset);
        assert_eq!(plain.background, Color::Reset);
        assert!(plain
            .selection_style()
            .add_modifier
            .contains(Modifier::REVERSED));
    }
}
