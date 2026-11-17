use std::io;

use crossterm::cursor::{Hide, MoveTo, RestorePosition, SavePosition, Show};
use crossterm::queue;
use crossterm::style::{
    Attribute as CrosstermAttribute, Attributes, Color as CrosstermColor, ContentStyle,
    PrintStyledContent, ResetColor, SetAttribute, SetBackgroundColor,
};
use crossterm::terminal::{Clear, ClearType};
use ratatui::backend::TestBackend;
use ratatui::buffer::{Buffer, CellDiffOption, CellWidth};
use ratatui::layout::{Position, Rect};
use ratatui::style::{Color, Modifier};
use ratatui::Terminal;

use super::app::App;
use super::ui::{self, Theme};


/// Renders the same Ratatui visual tree as the normal backend, then serializes
/// each row as contiguous style runs. Cursor positioning happens only at run
/// boundaries, never between same-style CJK/emoji graphemes.
pub struct StableRenderer {
    terminal: Terminal<TestBackend>,
    size: (u16, u16),
    last_frame: Buffer,
}

impl StableRenderer {
    pub fn new(width: u16, height: u16) -> Self {
        let size = normalized_size(width, height);
        let terminal =
            Terminal::new(TestBackend::new(size.0, size.1)).unwrap_or_else(|error| match error {});
        Self {
            terminal,
            size,
            last_frame: Buffer::empty(Rect::new(0, 0, size.0, size.1)),
        }
    }

    pub fn render(
        &mut self,
        app: &App,
        theme: Theme,
        width: u16,
        height: u16,
    ) -> io::Result<Vec<u8>> {
        self.resize(width, height);
        let frame = self
            .terminal
            .draw(|frame| ui::draw(frame, app, theme))
            .unwrap_or_else(|error| match error {})
            .buffer
            .clone();
        let backend = self.terminal.backend();
        let cursor = backend.cursor_visible().then(|| backend.cursor_position());
        let output = encode_frame(&frame, cursor)?;
        self.last_frame = frame;
        Ok(output)
    }

    fn resize(&mut self, width: u16, height: u16) {
        let size = normalized_size(width, height);
        if size == self.size {
            return;
        }
        self.terminal =
            Terminal::new(TestBackend::new(size.0, size.1)).unwrap_or_else(|error| match error {});
        self.size = size;
        self.last_frame = Buffer::empty(Rect::new(0, 0, size.0, size.1));
    }

    #[cfg(test)]
    fn buffer(&self) -> &Buffer {
        &self.last_frame
    }
}

fn normalized_size(width: u16, height: u16) -> (u16, u16) {
    (width.max(1), height.max(1))
}

fn encode_frame(buffer: &Buffer, cursor: Option<Position>) -> io::Result<Vec<u8>> {
    let mut output = Vec::with_capacity(buffer.content.len().saturating_mul(2));
    queue!(
        output,
        Hide,
        ResetColor,
        SetAttribute(CrosstermAttribute::Reset)
    )?;
    let mut cursor_saved = false;
    for y in buffer.area.top()..buffer.area.bottom() {
        let row_background = buffer[(buffer.area.left(), y)].bg;
        queue!(
            output,
            MoveTo(0, y),
            ResetColor,
            SetAttribute(CrosstermAttribute::Reset),
            SetBackgroundColor(terminal_color(row_background).unwrap_or(CrosstermColor::Reset)),
            Clear(ClearType::CurrentLine)
        )?;
        // The row was cleared above. Reprinting default trailing blanks can
        // overwrite a wide glyph or trigger Windows' right-margin wrap.
        let mut end = buffer.area.right();
        while end > buffer.area.left() {
            let cell = &buffer[(end - 1, y)];
            if cell.symbol() != " "
                || cell.bg != row_background
                || cell
                    .modifier
                    .intersects(Modifier::REVERSED | Modifier::UNDERLINED | Modifier::CROSSED_OUT)
                || cursor.is_some_and(|position| position.y == y && position.x >= end - 1)
            {
                break;
            }
            end -= 1;
        }
        cursor_saved |= encode_row(
            &mut output,
            buffer,
            y,
            buffer.area.left(),
            end,
            cursor
                .filter(|position| position.y == y)
                .map(|position| position.x),
        )?;
    }

    queue!(output, ResetColor, SetAttribute(CrosstermAttribute::Reset))?;
    if cursor_saved {
        queue!(
            output,
            RestorePosition,
            ResetColor,
            SetAttribute(CrosstermAttribute::Reset),
            Show
        )?;
    } else {
        queue!(output, Hide)?;
    }
    Ok(output)
}

fn encode_row(
    output: &mut Vec<u8>,
    buffer: &Buffer,
    y: u16,
    start: u16,
    end: u16,
    cursor_x: Option<u16>,
) -> io::Result<bool> {
    let mut x = start;
    let mut active_style = None;
    let mut run_start = start;
    let mut run = String::new();
    let mut cursor_saved = false;
    while x < end {
        if !cursor_saved && cursor_x == Some(x) {
            if let Some(style) = active_style {
                flush_run(output, style, &mut run, run_start, y)?;
            }
            queue!(output, SavePosition)?;
            run_start = x;
            cursor_saved = true;
        }
        let cell = &buffer[(x, y)];
        let style = RenderStyle::from_cell(cell);
        if active_style.is_some_and(|active| active != style) {
            flush_run(
                output,
                active_style.expect("style exists"),
                &mut run,
                run_start,
                y,
            )?;
            run_start = x;
        }
        active_style = Some(style);

        if cell.diff_option == CellDiffOption::Skip {
            run.push(' ');
            x = x.saturating_add(1);
            continue;
        }
        run.push_str(cell.symbol());
        x = x.saturating_add(cell.cell_width().max(1));
    }
    if let Some(style) = active_style {
        flush_run(output, style, &mut run, run_start, y)?;
    }
    if !cursor_saved && cursor_x == Some(end) {
        queue!(output, SavePosition)?;
        cursor_saved = true;
    }
    Ok(cursor_saved)
}

fn flush_run(
    output: &mut Vec<u8>,
    style: RenderStyle,
    run: &mut String,
    x: u16,
    y: u16,
) -> io::Result<()> {
    if run.is_empty() {
        return Ok(());
    }
    queue!(
        output,
        MoveTo(x, y),
        PrintStyledContent(style.into_crossterm().apply(run.as_str()))
    )?;
    run.clear();
    Ok(())
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct RenderStyle {
    foreground: Color,
    background: Color,
    underline: Color,
    modifier: Modifier,
}

impl RenderStyle {
    fn from_cell(cell: &ratatui::buffer::Cell) -> Self {
        Self {
            foreground: cell.fg,
            background: cell.bg,
            underline: cell.underline_color,
            modifier: cell.modifier,
        }
    }

    fn into_crossterm(self) -> ContentStyle {
        let mut attributes = Attributes::default();
        for (modifier, attribute) in [
            (Modifier::BOLD, CrosstermAttribute::Bold),
            (Modifier::DIM, CrosstermAttribute::Dim),
            (Modifier::ITALIC, CrosstermAttribute::Italic),
            (Modifier::UNDERLINED, CrosstermAttribute::Underlined),
            (Modifier::SLOW_BLINK, CrosstermAttribute::SlowBlink),
            (Modifier::RAPID_BLINK, CrosstermAttribute::RapidBlink),
            (Modifier::REVERSED, CrosstermAttribute::Reverse),
            (Modifier::HIDDEN, CrosstermAttribute::Hidden),
            (Modifier::CROSSED_OUT, CrosstermAttribute::CrossedOut),
        ] {
            if self.modifier.contains(modifier) {
                attributes.set(attribute);
            }
        }
        ContentStyle {
            foreground_color: terminal_color(self.foreground),
            background_color: terminal_color(self.background),
            underline_color: terminal_color(self.underline),
            attributes,
        }
    }
}

fn terminal_color(color: Color) -> Option<CrosstermColor> {
    Some(match color {
        Color::Reset => return None,
        Color::Black => CrosstermColor::Black,
        Color::Red => CrosstermColor::DarkRed,
        Color::Green => CrosstermColor::DarkGreen,
        Color::Yellow => CrosstermColor::DarkYellow,
        Color::Blue => CrosstermColor::DarkBlue,
        Color::Magenta => CrosstermColor::DarkMagenta,
        Color::Cyan => CrosstermColor::DarkCyan,
        Color::Gray => CrosstermColor::Grey,
        Color::DarkGray => CrosstermColor::DarkGrey,
        Color::LightRed => CrosstermColor::Red,
        Color::LightGreen => CrosstermColor::Green,
        Color::LightYellow => CrosstermColor::Yellow,
        Color::LightBlue => CrosstermColor::Blue,
        Color::LightMagenta => CrosstermColor::Magenta,
        Color::LightCyan => CrosstermColor::Cyan,
        Color::White => CrosstermColor::White,
        Color::Rgb(r, g, b) => CrosstermColor::Rgb { r, g, b },
        Color::Indexed(value) => CrosstermColor::AnsiValue(value),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tui::api::{RunView, WorkspaceView};
    use crate::tui::app::{TimelineItem, TimelineKind};

    fn sample_app() -> App {
        let mut app = App::new(
            WorkspaceView {
                name: "Harnex".to_string(),
                root: "D:/code/Harnex".to_string(),
                ..WorkspaceView::default()
            },
            Vec::new(),
            None,
            Some(RunView {
                run_id: "run-1".to_string(),
                thread_id: "thread-1".to_string(),
                status: "RUNNING".to_string(),
                ..RunView::default()
            }),
            false,
        );
        app.timeline = vec![
            TimelineItem {
                kind: TimelineKind::User,
                title: "You".to_string(),
                content: "请检查跨平台视觉。".to_string(),
                purpose: None,
                tool_input: None,
                queued: false,
                sequence: 0,
                content_complete: false,
                call_id: None,
                running: false,
                failed: false,
            },
            TimelineItem {
                kind: TimelineKind::Assistant,
                title: "Harnex".to_string(),
                content: "中文流式输出保持连续。".to_string(),
                purpose: None,
                tool_input: None,
                queued: false,
                sequence: 0,
                content_complete: false,
                call_id: None,
                running: true,
                failed: false,
            },
        ];
        app.details_open = true;
        app
    }

    #[test]
    fn stable_renderer_uses_the_rich_visual_tree() {
        let app = sample_app();
        let theme = Theme::named("dark", false);
        let mut stable = StableRenderer::new(140, 36);
        let _ = stable.render(&app, theme, 140, 36).unwrap();

        let backend = TestBackend::new(140, 36);
        let mut expected = Terminal::new(backend).unwrap();
        let expected_frame = expected
            .draw(|frame| ui::draw(frame, &app, theme))
            .unwrap()
            .buffer
            .clone();
        assert_eq!(stable.buffer(), &expected_frame);
    }

    #[test]
    fn stable_frame_keeps_cjk_contiguous_with_run_level_positioning() {
        let app = sample_app();
        let mut stable = StableRenderer::new(80, 24);
        let output = stable
            .render(&app, Theme::named("ansi", false), 80, 24)
            .unwrap();
        let output = String::from_utf8(output).unwrap();
        assert!(output.contains("请检查跨平台视觉。"));
        assert!(output.contains("中文流式输出保持连续。"));
        assert!(!output.contains("请\x1b["));
        assert!(!output.contains("中\x1b["));
    }

    #[test]
    fn stable_stream_redraw_fully_replaces_the_previous_frame() {
        let mut app = sample_app();
        let mut stable = StableRenderer::new(80, 24);
        app.timeline[1].content = "Z".repeat(400);
        let first = stable
            .render(&app, Theme::named("ansi", false), 80, 24)
            .unwrap();
        assert!(String::from_utf8(first).unwrap().contains("ZZZZ"));

        app.timeline[1].content = "新的流式内容只显示一次".to_string();
        let second = stable
            .render(&app, Theme::named("ansi", false), 80, 24)
            .unwrap();
        let second = String::from_utf8(second).unwrap();
        assert!(!second.contains('Z'));
        assert_eq!(second.matches("新的流式内容只显示一次").count(), 1);
        assert_eq!(second.matches("\x1b[2K").count(), 24);
    }

    #[test]
    fn stable_cursor_does_not_replay_composer_text() {
        let mut app = sample_app();
        app.composer.text = "你好".to_string();
        app.composer.cursor = app.composer.text.len();
        let mut stable = StableRenderer::new(80, 24);
        let output = stable
            .render(&app, Theme::named("ansi", false), 80, 24)
            .unwrap();
        let output = String::from_utf8(output).unwrap();
        assert_eq!(output.matches("你好").count(), 1);
        assert!(output.contains("\x1b7"));
        assert!(output.contains("\x1b8"));
    }
}
