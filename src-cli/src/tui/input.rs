//! Preserve paste boundaries before interpreting keys as application commands.
use crossterm::event::Event;
use std::io;
use std::io::Write;
use std::sync::atomic::{AtomicBool, Ordering};

static MOUSE_TRACKING: AtomicBool = AtomicBool::new(false);

fn enable_mouse_tracking() -> io::Result<()> {
    MOUSE_TRACKING.store(true, Ordering::Release);
    let mut output = io::stdout();
    let result = output
        .write_all(b"\x1b[?1000h\x1b[?1002h\x1b[?1006h")
        .and_then(|_| output.flush());
    if result.is_err() {
        restore_console_mode();
    }
    result
}

#[cfg(not(windows))]
pub struct TerminalInput(crossterm::event::EventStream);

#[cfg(not(windows))]
impl TerminalInput {
    pub fn new() -> io::Result<Self> {
        enable_mouse_tracking()?;
        Ok(Self(crossterm::event::EventStream::new()))
    }
    pub async fn next(&mut self) -> Option<io::Result<Event>> {
        use futures_util::StreamExt;
        self.0.next().await
    }
}

#[cfg(not(windows))]
impl Drop for TerminalInput {
    fn drop(&mut self) {
        restore_console_mode();
    }
}

#[cfg(windows)]
pub use windows::TerminalInput;

pub fn restore_console_mode() {
    if MOUSE_TRACKING.swap(false, Ordering::AcqRel) {
        let mut output = io::stdout();
        let _ = output.write_all(b"\x1b[?1006l\x1b[?1002l\x1b[?1000l");
        let _ = output.flush();
    }
    #[cfg(windows)]
    windows::restore_console_mode();
}

pub fn preserve_console_mode<T>(operation: impl FnOnce() -> io::Result<T>) -> io::Result<T> {
    #[cfg(windows)]
    {
        windows::preserve_console_mode(operation)
    }
    #[cfg(not(windows))]
    {
        operation()
    }
}

#[cfg(any(windows, test))]
mod vt {
    use super::*;
    use crossterm::event::{
        KeyCode, KeyEvent, KeyEventKind, KeyModifiers, MouseButton, MouseEvent, MouseEventKind,
    };
    use std::time::{Duration, Instant};

    #[derive(Default)]
    pub(super) struct Parser {
        #[cfg(windows)]
        pub(super) native_mouse_buttons: u32,
        sequence: String,
        paste: Option<String>,
        escape_at: Option<Instant>,
    }

    impl Parser {
        pub fn in_sequence(&self) -> bool {
            !self.sequence.is_empty() || self.paste.is_some()
        }
        pub fn in_paste(&self) -> bool {
            self.paste.is_some()
        }

        pub fn character(&mut self, character: char) -> Vec<Event> {
            if let Some(paste) = self.paste.as_mut() {
                paste.push(character);
                if paste.ends_with("\x1b[201~") {
                    paste.truncate(paste.len() - 6);
                    return vec![Event::Paste(self.paste.take().unwrap())];
                }
                // Stream large pastes as data while retaining the boundary suffix.
                // No key, including newline, is dispatched while paste is open.
                if paste.len() > 64 * 1024 {
                    let mut cut = paste.len() - 6;
                    while !paste.is_char_boundary(cut) {
                        cut -= 1;
                    }
                    if paste[..cut].ends_with('\r') && paste[cut..].starts_with('\n') {
                        cut -= 1;
                    }
                    let tail = paste.split_off(cut);
                    return vec![Event::Paste(std::mem::replace(paste, tail))];
                }
                return Vec::new();
            }
            if self.sequence.is_empty() {
                if character == '\x1b' {
                    self.sequence.push(character);
                    self.escape_at = Some(Instant::now());
                    return Vec::new();
                }
                return key(character, KeyModifiers::NONE).into_iter().collect();
            }
            if self.sequence == "\x1b" && !matches!(character, '[' | 'O') {
                self.sequence.clear();
                self.escape_at = None;
                if character == '\x1b' {
                    self.sequence.push(character);
                    self.escape_at = Some(Instant::now());
                    return vec![Event::Key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE))];
                }
                return key(character, KeyModifiers::ALT).into_iter().collect();
            }
            self.sequence.push(character);
            if self.sequence == "\x1b[200~" {
                self.sequence.clear();
                self.escape_at = None;
                self.paste = Some(String::new());
                return Vec::new();
            }
            if self.sequence.len() > 64 {
                self.sequence.clear();
                self.escape_at = None;
                return Vec::new();
            }
            if self.sequence.len() > 2 && ('@'..='~').contains(&character) {
                let sequence = std::mem::take(&mut self.sequence);
                self.escape_at = None;
                return decode(&sequence).into_iter().collect();
            }
            Vec::new()
        }

        pub fn take_escape(&mut self) -> Option<Event> {
            if self.sequence != "\x1b" {
                return None;
            }
            self.sequence.clear();
            self.escape_at = None;
            Some(Event::Key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE)))
        }

        pub fn expire_escape(&mut self) -> Option<Event> {
            if self.sequence == "\x1b"
                && self
                    .escape_at
                    .is_some_and(|at| at.elapsed() >= Duration::from_millis(100))
            {
                self.take_escape()
            } else {
                None
            }
        }
    }

    pub(super) fn key(character: char, modifiers: KeyModifiers) -> Option<Event> {
        let (code, extra) = match character {
            '\0' => return None,
            '\r' => (KeyCode::Enter, KeyModifiers::NONE),
            '\n' => (KeyCode::Char('j'), KeyModifiers::CONTROL),
            '\t' => (KeyCode::Tab, KeyModifiers::NONE),
            '\x08' | '\x7f' => (KeyCode::Backspace, KeyModifiers::NONE),
            '\x1b' => (KeyCode::Esc, KeyModifiers::NONE),
            '\x01'..='\x1a' => (
                KeyCode::Char(char::from_u32(character as u32 + 96).unwrap()),
                KeyModifiers::CONTROL,
            ),
            '\x1c' => (KeyCode::Char('\\'), KeyModifiers::CONTROL),
            '\x1d' => (KeyCode::Char(']'), KeyModifiers::CONTROL),
            '\x1e' => (KeyCode::Char('^'), KeyModifiers::CONTROL),
            '\x1f' => (KeyCode::Char('_'), KeyModifiers::CONTROL),
            value if value.is_control() => return None,
            value => (KeyCode::Char(value), KeyModifiers::NONE),
        };
        Some(Event::Key(KeyEvent::new(code, modifiers | extra)))
    }

    fn modifiers(value: u32) -> KeyModifiers {
        let bits = value.saturating_sub(1);
        let mut modifiers = KeyModifiers::NONE;
        if bits & 1 != 0 {
            modifiers |= KeyModifiers::SHIFT;
        }
        if bits & 2 != 0 {
            modifiers |= KeyModifiers::ALT;
        }
        if bits & 4 != 0 {
            modifiers |= KeyModifiers::CONTROL;
        }
        modifiers
    }

    fn decode(sequence: &str) -> Option<Event> {
        let end = sequence.chars().last()?;
        let body = &sequence[2..sequence.len() - 1];
        if sequence.starts_with("\x1b[<") && matches!(end, 'M' | 'm') {
            let parts = body[1..]
                .split(';')
                .map(str::parse::<u16>)
                .collect::<Result<Vec<_>, _>>()
                .ok()?;
            if parts.len() != 3 {
                return None;
            }
            let code = parts[0];
            let button = match code & 3 {
                0 => MouseButton::Left,
                1 => MouseButton::Middle,
                _ => MouseButton::Right,
            };
            let kind = if code & 64 != 0 {
                match code & 3 {
                    0 => MouseEventKind::ScrollUp,
                    1 => MouseEventKind::ScrollDown,
                    2 => MouseEventKind::ScrollLeft,
                    _ => MouseEventKind::ScrollRight,
                }
            } else if end == 'm' {
                MouseEventKind::Up(button)
            } else if code & 32 != 0 {
                if code & 3 == 3 {
                    MouseEventKind::Moved
                } else {
                    MouseEventKind::Drag(button)
                }
            } else {
                MouseEventKind::Down(button)
            };
            let mut mods = KeyModifiers::NONE;
            if code & 4 != 0 {
                mods |= KeyModifiers::SHIFT;
            }
            if code & 8 != 0 {
                mods |= KeyModifiers::ALT;
            }
            if code & 16 != 0 {
                mods |= KeyModifiers::CONTROL;
            }
            return Some(Event::Mouse(MouseEvent {
                kind,
                column: parts[1].saturating_sub(1),
                row: parts[2].saturating_sub(1),
                modifiers: mods,
            }));
        }
        if sequence == "\x1b[I" {
            return Some(Event::FocusGained);
        }
        if sequence == "\x1b[O" {
            return Some(Event::FocusLost);
        }
        let parts = body.split(';').collect::<Vec<_>>();
        let number = |index: usize| {
            parts
                .get(index)
                .and_then(|part| part.split(':').next()?.parse::<u32>().ok())
        };
        let mods = modifiers(number(1).unwrap_or(1));
        if end == 'u' || end == '~' && number(0) == Some(27) && parts.len() == 3 {
            let point = if end == 'u' { number(0)? } else { number(2)? };
            let Event::Key(mut event) = key(char::from_u32(point)?, mods)? else {
                return None;
            };
            event.kind = match parts.get(1).and_then(|part| part.split(':').nth(1)) {
                Some("2") => KeyEventKind::Repeat,
                Some("3") => KeyEventKind::Release,
                _ => KeyEventKind::Press,
            };
            return Some(Event::Key(event));
        }
        let code = match end {
            'A' => KeyCode::Up,
            'B' => KeyCode::Down,
            'C' => KeyCode::Right,
            'D' => KeyCode::Left,
            'H' => KeyCode::Home,
            'F' => KeyCode::End,
            'Z' => {
                return Some(Event::Key(KeyEvent::new(
                    KeyCode::BackTab,
                    KeyModifiers::SHIFT,
                )))
            }
            'P' => KeyCode::F(1),
            'Q' => KeyCode::F(2),
            'R' if sequence.starts_with("\x1bO") => KeyCode::F(3),
            'S' => KeyCode::F(4),
            '~' => match number(0)? {
                1 | 7 => KeyCode::Home,
                2 => KeyCode::Insert,
                3 => KeyCode::Delete,
                4 | 8 => KeyCode::End,
                5 => KeyCode::PageUp,
                6 => KeyCode::PageDown,
                value => {
                    let function = [
                        11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 23, 24, 25, 26, 28, 29, 31, 32, 33,
                        34,
                    ]
                    .iter()
                    .position(|code| *code == value)?;
                    KeyCode::F(function as u8 + 1)
                }
            },
            _ => return None,
        };
        Some(Event::Key(KeyEvent::new(code, mods)))
    }
}

#[cfg(windows)]
mod windows {
    use super::*;
    use crossterm::event::{
        KeyCode, KeyEvent, KeyModifiers, MouseButton, MouseEvent, MouseEventKind,
    };
    use std::mem::zeroed;
    use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
    use std::ptr::{null, null_mut};
    use std::sync::{
        atomic::{AtomicBool, AtomicU32, Ordering},
        Arc,
    };
    use std::thread::{self, JoinHandle};
    use tokio::sync::mpsc;
    use windows_sys::Win32::{
        Foundation::{INVALID_HANDLE_VALUE, WAIT_FAILED},
        Storage::FileSystem::CreateFileW,
        System::{Console::*, Threading::WaitForSingleObject},
    };

    const MODE_BITS: u32 = ENABLE_VIRTUAL_TERMINAL_INPUT
        | ENABLE_WINDOW_INPUT
        | ENABLE_MOUSE_INPUT
        | ENABLE_QUICK_EDIT_MODE
        | ENABLE_EXTENDED_FLAGS;
    static ORIGINAL_BITS: AtomicU32 = AtomicU32::new(0);

    fn console() -> io::Result<OwnedHandle> {
        console_named("CONIN$\0")
    }

    fn console_named(name: &str) -> io::Result<OwnedHandle> {
        let name = name.encode_utf16().collect::<Vec<_>>();
        let raw = unsafe { CreateFileW(name.as_ptr(), 0xc0000000, 3, null(), 3, 0, null_mut()) };
        if raw == INVALID_HANDLE_VALUE {
            return Err(io::Error::last_os_error());
        }
        Ok(unsafe { OwnedHandle::from_raw_handle(raw) })
    }

    pub(super) fn preserve_console_mode<T>(
        operation: impl FnOnce() -> io::Result<T>,
    ) -> io::Result<T> {
        let input = console()?;
        let mut mode = 0;
        if unsafe { GetConsoleMode(input.as_raw_handle(), &mut mode) } == 0 {
            return Err(io::Error::last_os_error());
        }
        let result = operation();
        // Some editors and cmd.exe change mouse/quick-edit flags on exit.
        // Re-enter raw mode from the user's original cooked mode, not theirs.
        if unsafe { SetConsoleMode(input.as_raw_handle(), mode) } == 0 {
            return Err(io::Error::last_os_error());
        }
        result
    }

    pub(super) fn restore_console_mode() {
        let original = ORIGINAL_BITS.swap(0, Ordering::AcqRel);
        if original == 0 {
            return;
        }
        if let Ok(input) = console() {
            let mut mode = 0;
            unsafe {
                if GetConsoleMode(input.as_raw_handle(), &mut mode) != 0 {
                    SetConsoleMode(
                        input.as_raw_handle(),
                        (mode & !MODE_BITS) | (original & MODE_BITS),
                    );
                }
            }
        }
    }

    pub struct TerminalInput {
        events: mpsc::UnboundedReceiver<io::Result<Event>>,
        stop: Arc<AtomicBool>,
        worker: Option<JoinHandle<()>>,
    }

    impl TerminalInput {
        pub fn new() -> io::Result<Self> {
            let input = console()?;
            let output = console_named("CONOUT$\0")?;
            let mut mode = 0;
            unsafe {
                if GetConsoleMode(input.as_raw_handle(), &mut mode) == 0
                    || SetConsoleMode(
                        input.as_raw_handle(),
                        (mode | MODE_BITS) & !ENABLE_QUICK_EDIT_MODE,
                    ) == 0
                {
                    return Err(io::Error::last_os_error());
                }
            }
            // Legacy ReadConsoleInput consumes the paste markers before crossterm
            // sees them. VT input preserves those markers as Unicode key records.
            ORIGINAL_BITS.store(0x80000000 | (mode & MODE_BITS), Ordering::Release);
            enable_mouse_tracking()?;
            let (sender, events) = mpsc::unbounded_channel();
            let stop = Arc::new(AtomicBool::new(false));
            let stopped = stop.clone();
            let worker = thread::Builder::new()
                .name("harnex-input".into())
                .spawn(move || {
                    let mut parser = vt::Parser::default();
                    let mut surrogate = None;
                    while !stopped.load(Ordering::Acquire) {
                        let waited = unsafe { WaitForSingleObject(input.as_raw_handle(), 20) };
                        if waited == WAIT_FAILED {
                            let _ = sender.send(Err(io::Error::last_os_error()));
                            break;
                        }
                        if waited == 0 {
                            let mut records = match read_pending(&input) {
                                Ok(records) => records,
                                Err(error) => {
                                    let _ = sender.send(Err(error));
                                    break;
                                }
                            };
                            // Native mouse coordinates refer to the console buffer,
                            // while VT mouse coordinates already refer to the viewport.
                            let mut info = unsafe { zeroed::<CONSOLE_SCREEN_BUFFER_INFO>() };
                            if unsafe {
                                GetConsoleScreenBufferInfo(output.as_raw_handle(), &mut info)
                            } != 0
                            {
                                for record in &mut records {
                                    if record.EventType as u32 == MOUSE_EVENT {
                                        unsafe {
                                            record.Event.MouseEvent.dwMousePosition.X -=
                                                info.srWindow.Left;
                                            record.Event.MouseEvent.dwMousePosition.Y -=
                                                info.srWindow.Top;
                                        }
                                    }
                                }
                            }
                            for event in decode_batch(&records, &mut parser, &mut surrogate) {
                                if sender.send(Ok(event)).is_err() {
                                    return;
                                }
                            }
                        }
                        if let Some(event) = parser.expire_escape() {
                            if sender.send(Ok(event)).is_err() {
                                break;
                            }
                        }
                    }
                });
            match worker {
                Ok(worker) => Ok(Self {
                    events,
                    stop,
                    worker: Some(worker),
                }),
                Err(error) => {
                    super::restore_console_mode();
                    Err(error)
                }
            }
        }

        pub async fn next(&mut self) -> Option<io::Result<Event>> {
            self.events.recv().await
        }
    }

    impl Drop for TerminalInput {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::Release);
            if let Some(worker) = self.worker.take() {
                let _ = worker.join();
            }
            super::restore_console_mode();
        }
    }

    fn read_pending(input: &OwnedHandle) -> io::Result<Vec<INPUT_RECORD>> {
        let mut pending = 0;
        if unsafe { GetNumberOfConsoleInputEvents(input.as_raw_handle(), &mut pending) } == 0 {
            return Err(io::Error::last_os_error());
        }
        // Native conhost queues a clipboard paste atomically. Capture that
        // entire queue snapshot before interpreting any CR as a submit key;
        // the final newline must not become a separate 128-record read.
        let mut result = Vec::with_capacity(pending as usize);
        while pending > 0 {
            let mut records = [unsafe { zeroed::<INPUT_RECORD>() }; 128];
            let mut read = 0;
            if unsafe {
                ReadConsoleInputW(
                    input.as_raw_handle(),
                    records.as_mut_ptr(),
                    pending.min(records.len() as u32),
                    &mut read,
                )
            } == 0
            {
                return Err(io::Error::last_os_error());
            }
            if read == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "Console input closed",
                ));
            }
            result.extend_from_slice(&records[..read as usize]);
            pending -= read;
        }
        Ok(result)
    }

    fn decode_batch(
        records: &[INPUT_RECORD],
        parser: &mut vt::Parser,
        surrogate: &mut Option<u16>,
    ) -> Vec<Event> {
        // Explicit bracketed-paste boundaries take precedence. The fallback is
        // for older standalone PowerShell consoles that ignore DECSET 2004.
        let mut events = Vec::new();
        let mut at = 0;
        while at < records.len() {
            if parser.in_sequence()
                || surrogate.is_some()
                || native_text_unit(&records[at]).is_err()
            {
                events.extend(unsafe { decode_record(&records[at], parser, surrogate) });
                at += 1;
                continue;
            }
            // Unrelated queued arrows/shortcuts must not disable paste handling
            // for the text that follows or precedes them in the same snapshot.
            let start = at;
            while at < records.len() && native_text_unit(&records[at]).is_ok() {
                at += 1;
            }
            let text_records = &records[start..at];
            if let Some(text) = native_multiline_paste(text_records) {
                events.push(Event::Paste(text));
            } else {
                for record in text_records {
                    events.extend(unsafe { decode_record(record, parser, surrogate) });
                }
            }
        }
        events
    }

    fn native_multiline_paste(records: &[INPUT_RECORD]) -> Option<String> {
        let mut text = Vec::new();
        let mut newline = false;
        for record in records {
            if let Some((unit, repeat)) = native_text_unit(record).ok()? {
                newline |= matches!(unit, 10 | 13);
                text.extend(std::iter::repeat_n(unit, repeat as usize));
            }
        }
        // A lone Enter is deliberately still a key. INPUT_RECORD has no source
        // marker to distinguish it from a clipboard containing only one CR.
        (newline && text.len() > 1).then(|| String::from_utf16_lossy(&text))
    }

    fn native_text_unit(record: &INPUT_RECORD) -> Result<Option<(u16, u16)>, ()> {
        if record.EventType as u32 != KEY_EVENT {
            return Err(());
        }
        let key = unsafe { record.Event.KeyEvent };
        let unit = unsafe { key.uChar.UnicodeChar };
        // Legacy console paste may synthesize Alt+numpad for characters
        // outside its code page, committing Unicode on the Alt key-up.
        let alt_commit = key.bKeyDown == 0 && key.wVirtualKeyCode == 0x12 && unit != 0;
        if key.bKeyDown == 0 && !alt_commit {
            return Ok(None);
        }
        if unit == 0 {
            if matches!(key.wVirtualKeyCode, 0x10..=0x12 | 0xa0..=0xa5)
                || matches!(key.wVirtualKeyCode, 0x60..=0x69)
                    && key.dwControlKeyState & (LEFT_ALT_PRESSED | RIGHT_ALT_PRESSED) != 0
            {
                return Ok(None);
            }
            return Err(());
        }
        let alt_gr = key.dwControlKeyState & (RIGHT_ALT_PRESSED | LEFT_CTRL_PRESSED)
            == (RIGHT_ALT_PRESSED | LEFT_CTRL_PRESSED)
            && unit >= 0x20;
        let shortcut = key.dwControlKeyState
            & (LEFT_CTRL_PRESSED | RIGHT_CTRL_PRESSED | LEFT_ALT_PRESSED | RIGHT_ALT_PRESSED)
            != 0;
        if shortcut && !alt_gr && !alt_commit
            || unit < 0x20 && !matches!(unit, 9 | 10 | 13)
            || unit == 0x7f
        {
            return Err(());
        }
        Ok(Some((unit, key.wRepeatCount.max(1))))
    }

    fn modifiers(state: u32) -> KeyModifiers {
        let mut value = KeyModifiers::NONE;
        if state & SHIFT_PRESSED != 0 {
            value |= KeyModifiers::SHIFT;
        }
        if state & (LEFT_CTRL_PRESSED | RIGHT_CTRL_PRESSED) != 0 {
            value |= KeyModifiers::CONTROL;
        }
        if state & (LEFT_ALT_PRESSED | RIGHT_ALT_PRESSED) != 0 {
            value |= KeyModifiers::ALT;
        }
        value
    }

    unsafe fn decode_record(
        record: &INPUT_RECORD,
        parser: &mut vt::Parser,
        surrogate: &mut Option<u16>,
    ) -> Vec<Event> {
        match record.EventType as u32 {
            KEY_EVENT => {
                let key = record.Event.KeyEvent;
                let unit = key.uChar.UnicodeChar;
                if key.bKeyDown == 0 && !(key.wVirtualKeyCode == 0x12 && unit != 0) {
                    return Vec::new();
                }
                let mut mods = modifiers(key.dwControlKeyState);
                if unit == 0 {
                    if parser.in_paste() {
                        return Vec::new();
                    }
                    let code = match key.wVirtualKeyCode {
                        0x21 => KeyCode::PageUp,
                        0x22 => KeyCode::PageDown,
                        0x23 => KeyCode::End,
                        0x24 => KeyCode::Home,
                        0x25 => KeyCode::Left,
                        0x26 => KeyCode::Up,
                        0x27 => KeyCode::Right,
                        0x28 => KeyCode::Down,
                        0x2d => KeyCode::Insert,
                        0x2e => KeyCode::Delete,
                        0x70..=0x87 => KeyCode::F((key.wVirtualKeyCode - 0x70 + 1) as u8),
                        _ => return Vec::new(),
                    };
                    let mut events = parser.take_escape().into_iter().collect::<Vec<_>>();
                    events.extend(std::iter::repeat_n(
                        Event::Key(KeyEvent::new(code, mods)),
                        key.wRepeatCount.max(1) as usize,
                    ));
                    return events;
                }
                let character = match unit {
                    0xd800..=0xdbff => {
                        *surrogate = Some(unit);
                        return Vec::new();
                    }
                    0xdc00..=0xdfff => {
                        let Some(high) = surrogate.take() else {
                            return Vec::new();
                        };
                        char::from_u32(
                            0x10000 + ((high as u32 - 0xd800) << 10) + (unit as u32 - 0xdc00),
                        )
                        .unwrap_or('\u{fffd}')
                    }
                    _ => {
                        *surrogate = None;
                        char::from_u32(unit as u32).unwrap()
                    }
                };
                // AltGr produces printable text, not an application Ctrl+Alt command.
                if !character.is_control()
                    && key.dwControlKeyState & RIGHT_ALT_PRESSED != 0
                    && key.dwControlKeyState & LEFT_CTRL_PRESSED != 0
                {
                    mods.remove(KeyModifiers::ALT | KeyModifiers::CONTROL);
                }
                let mut events = Vec::new();
                for _ in 0..key.wRepeatCount.max(1) {
                    if !parser.in_sequence()
                        && key.wVirtualKeyCode != 0
                        && !mods.is_empty()
                        && character != '\x1b'
                    {
                        let event = match key.wVirtualKeyCode {
                            0x0d => Some(Event::Key(KeyEvent::new(KeyCode::Enter, mods))),
                            0x09 if mods.contains(KeyModifiers::SHIFT) => {
                                Some(Event::Key(KeyEvent::new(KeyCode::BackTab, mods)))
                            }
                            0x41..=0x5a
                                if mods.contains(KeyModifiers::CONTROL)
                                    && character.is_control() =>
                            {
                                Some(Event::Key(KeyEvent::new(
                                    KeyCode::Char(
                                        (key.wVirtualKeyCode as u8 as char).to_ascii_lowercase(),
                                    ),
                                    mods,
                                )))
                            }
                            _ => vt::key(character, mods),
                        };
                        events.extend(event);
                    } else {
                        events.extend(parser.character(character));
                    }
                }
                events
            }
            WINDOW_BUFFER_SIZE_EVENT => {
                let size = record.Event.WindowBufferSizeEvent.dwSize;
                vec![Event::Resize(size.X.max(1) as u16, size.Y.max(1) as u16)]
            }
            FOCUS_EVENT => vec![if record.Event.FocusEvent.bSetFocus != 0 {
                Event::FocusGained
            } else {
                Event::FocusLost
            }],
            MOUSE_EVENT => {
                let mouse = record.Event.MouseEvent;
                let previous = parser.native_mouse_buttons;
                let current = mouse.dwButtonState & 0xffff;
                parser.native_mouse_buttons = current;
                let mut kinds = Vec::new();
                if mouse.dwEventFlags & MOUSE_WHEELED != 0 {
                    kinds.push(if (mouse.dwButtonState >> 16) as i16 > 0 {
                        MouseEventKind::ScrollUp
                    } else {
                        MouseEventKind::ScrollDown
                    });
                } else {
                    for (mask, button) in [
                        (FROM_LEFT_1ST_BUTTON_PRESSED, MouseButton::Left),
                        (RIGHTMOST_BUTTON_PRESSED, MouseButton::Right),
                        (FROM_LEFT_2ND_BUTTON_PRESSED, MouseButton::Middle),
                    ] {
                        if previous & mask != current & mask {
                            kinds.push(if current & mask != 0 {
                                MouseEventKind::Down(button)
                            } else {
                                MouseEventKind::Up(button)
                            });
                        } else if mouse.dwEventFlags & MOUSE_MOVED != 0 && current & mask != 0 {
                            kinds.push(MouseEventKind::Drag(button));
                        }
                    }
                }
                kinds
                    .into_iter()
                    .map(|kind| {
                        Event::Mouse(MouseEvent {
                            kind,
                            column: mouse.dwMousePosition.X.max(0) as u16,
                            row: mouse.dwMousePosition.Y.max(0) as u16,
                            modifiers: modifiers(mouse.dwControlKeyState),
                        })
                    })
                    .collect()
            }
            _ => Vec::new(),
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        fn character_records(text: &str) -> Vec<INPUT_RECORD> {
            text.encode_utf16()
                .map(|unit| {
                    let mut record = unsafe { zeroed::<INPUT_RECORD>() };
                    record.EventType = KEY_EVENT as u16;
                    record.Event.KeyEvent = KEY_EVENT_RECORD {
                        bKeyDown: 1,
                        wRepeatCount: 1,
                        wVirtualKeyCode: if unit == 13 { 0x0d } else { 0 },
                        wVirtualScanCode: if unit == 13 { 0x1c } else { 0 },
                        uChar: KEY_EVENT_RECORD_0 { UnicodeChar: unit },
                        dwControlKeyState: 0,
                    };
                    record
                })
                .collect()
        }

        #[test]
        fn native_multiline_batch_does_not_dispatch_enter_or_slash_commands() {
            let text = "\r/goal 中文😀\r\nend\r";
            assert_eq!(
                decode_batch(
                    &character_records(text),
                    &mut vt::Parser::default(),
                    &mut None
                ),
                [Event::Paste(text.into())]
            );
        }

        #[test]
        fn native_batch_keeps_separate_enter_and_command_shortcuts() {
            assert_eq!(
                decode_batch(
                    &character_records("\r"),
                    &mut vt::Parser::default(),
                    &mut None
                ),
                [Event::Key(KeyEvent::new(
                    KeyCode::Enter,
                    KeyModifiers::NONE
                ))]
            );
            let mut records = character_records("\x10theme\r");
            records[0].Event.KeyEvent.wVirtualKeyCode = 0x50;
            records[0].Event.KeyEvent.dwControlKeyState = LEFT_CTRL_PRESSED;
            let events = decode_batch(&records, &mut vt::Parser::default(), &mut None);
            assert_eq!(
                events.first(),
                Some(&Event::Key(KeyEvent::new(
                    KeyCode::Char('p'),
                    KeyModifiers::CONTROL
                )))
            );
            assert_eq!(events.last(), Some(&Event::Paste("theme\r".into())));
            let typed = decode_batch(
                &character_records("theme"),
                &mut vt::Parser::default(),
                &mut None,
            );
            assert_eq!(typed.len(), 5);
            assert!(!typed.iter().any(|event| matches!(event, Event::Paste(_))));
            let mut modified_enter = character_records("text\r");
            modified_enter
                .last_mut()
                .unwrap()
                .Event
                .KeyEvent
                .dwControlKeyState = LEFT_CTRL_PRESSED;
            let events = decode_batch(&modified_enter, &mut vt::Parser::default(), &mut None);
            assert_eq!(
                events.last(),
                Some(&Event::Key(KeyEvent::new(
                    KeyCode::Enter,
                    KeyModifiers::CONTROL
                )))
            );
            let framed = character_records("\x1b[200~one\rtwo\x1b[201~");
            assert_eq!(
                decode_batch(&framed, &mut vt::Parser::default(), &mut None),
                [Event::Paste("one\rtwo".into())]
            );
        }

        #[test]
        fn native_modified_enter_and_control_letters_remain_distinct() {
            for (code, unit, state, expected, mods) in [
                (0x0d, 13, 0, KeyCode::Enter, KeyModifiers::NONE),
                (
                    0x0d,
                    13,
                    LEFT_CTRL_PRESSED,
                    KeyCode::Enter,
                    KeyModifiers::CONTROL,
                ),
                (0x0d, 13, SHIFT_PRESSED, KeyCode::Enter, KeyModifiers::SHIFT),
                (
                    0x4d,
                    13,
                    LEFT_CTRL_PRESSED,
                    KeyCode::Char('m'),
                    KeyModifiers::CONTROL,
                ),
                (
                    0x4a,
                    10,
                    LEFT_CTRL_PRESSED,
                    KeyCode::Char('j'),
                    KeyModifiers::CONTROL,
                ),
                (
                    0x50,
                    16,
                    LEFT_CTRL_PRESSED,
                    KeyCode::Char('p'),
                    KeyModifiers::CONTROL,
                ),
                (
                    0x09,
                    9,
                    SHIFT_PRESSED,
                    KeyCode::BackTab,
                    KeyModifiers::SHIFT,
                ),
                (
                    0x45,
                    0x20ac,
                    RIGHT_ALT_PRESSED | LEFT_CTRL_PRESSED,
                    KeyCode::Char('€'),
                    KeyModifiers::NONE,
                ),
            ] {
                let mut record = unsafe { zeroed::<INPUT_RECORD>() };
                record.EventType = KEY_EVENT as u16;
                record.Event.KeyEvent = KEY_EVENT_RECORD {
                    bKeyDown: 1,
                    wRepeatCount: 1,
                    wVirtualKeyCode: code,
                    wVirtualScanCode: 0,
                    uChar: KEY_EVENT_RECORD_0 { UnicodeChar: unit },
                    dwControlKeyState: state,
                };
                assert_eq!(
                    unsafe { decode_record(&record, &mut vt::Parser::default(), &mut None) },
                    [Event::Key(KeyEvent::new(expected, mods))]
                );
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::vt::Parser;
    use crossterm::event::{Event, KeyCode, KeyEvent, KeyModifiers, MouseEventKind};

    #[test]
    fn paste_boundaries_keep_newlines_and_control_keys_out_of_key_dispatch() {
        let mut parser = Parser::default();
        let mut events = Vec::new();
        for chunk in ["\x1b[20", "0~中文\r\n/goal ", "\x10\n\x1b[201", "~"] {
            events.extend(
                chunk
                    .chars()
                    .flat_map(|character| parser.character(character)),
            );
        }
        assert_eq!(events, [Event::Paste("中文\r\n/goal \x10\n".into())]);
        assert_eq!(
            parser.character('\r'),
            [Event::Key(KeyEvent::new(
                KeyCode::Enter,
                KeyModifiers::NONE
            ))]
        );
    }

    #[test]
    fn large_paste_chunks_preserve_unicode_and_crlf_normalization() {
        let body = format!("{}\r\n😀\nend\n", "x".repeat(65530));
        let mut parser = Parser::default();
        let mut output = String::new();
        let mut chunks = 0;
        for character in format!("\x1b[200~{body}\x1b[201~").chars() {
            for event in parser.character(character) {
                let Event::Paste(text) = event else {
                    panic!("paste escaped into key dispatch")
                };
                chunks += 1;
                output.push_str(&text.replace("\r\n", "\n").replace('\r', "\n"));
            }
        }
        assert!(chunks > 1);
        assert_eq!(output, body.replace("\r\n", "\n"));
    }

    #[test]
    fn vt_navigation_modifiers_and_wheel_still_work() {
        let mut parser = Parser::default();
        let mut parse = |text: &str| {
            text.chars()
                .flat_map(|character| parser.character(character))
                .collect::<Vec<_>>()
        };
        assert_eq!(
            parse("\x1b[1;5H"),
            [Event::Key(KeyEvent::new(
                KeyCode::Home,
                KeyModifiers::CONTROL
            ))]
        );
        assert_eq!(
            parse("\x1bOQ"),
            [Event::Key(KeyEvent::new(KeyCode::F(2), KeyModifiers::NONE))]
        );
        assert_eq!(
            parse("\x1b[17~"),
            [Event::Key(KeyEvent::new(KeyCode::F(6), KeyModifiers::NONE))]
        );
        assert_eq!(
            parse("\x1b[13;2u"),
            [Event::Key(KeyEvent::new(
                KeyCode::Enter,
                KeyModifiers::SHIFT
            ))]
        );
        assert!(
            matches!(parse("\x1b[<64;12;8M").as_slice(), [Event::Mouse(event)] if event.kind == MouseEventKind::ScrollUp && event.column == 11 && event.row == 7)
        );
    }
}
