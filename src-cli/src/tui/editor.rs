use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use std::time::{Duration, Instant};
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Draft {
    pub text: String,
    pub cursor: usize,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Edit {
    Typing,
    Backspace,
    Delete,
    Other,
}

#[derive(Clone, Debug, Default)]
pub struct Composer {
    pub text: String,
    pub cursor: usize,
    pub revision: u64,
    undo: Vec<Draft>,
    redo: Vec<Draft>,
    killed: String,
    group: Option<(Edit, Instant, usize)>,
    preferred_column: Option<usize>,
}

impl Composer {
    pub fn draft(&self) -> Draft {
        Draft {
            text: self.text.clone(),
            cursor: self.cursor,
        }
    }

    fn checkpoint(&mut self, kind: Edit) {
        let grouped = kind != Edit::Other
            && self.group.is_some_and(|(previous, at, cursor)| {
                previous == kind
                    && cursor == self.cursor
                    && at.elapsed() < Duration::from_millis(750)
            });
        if !grouped {
            self.undo.push(self.draft());
            let mut bytes: usize = self.undo.iter().map(|entry| entry.text.len()).sum();
            while self.undo.len() > 1 && (self.undo.len() > 100 || bytes > 16 * 1024 * 1024) {
                bytes -= self.undo.remove(0).text.len();
            }
        }
        self.redo.clear();
        self.group = Some((kind, Instant::now(), self.cursor));
        self.preferred_column = None;
    }

    fn edited(&mut self) {
        self.revision = self.revision.wrapping_add(1);
        if let Some((kind, _, _)) = self.group {
            self.group = Some((kind, Instant::now(), self.cursor));
        }
    }

    pub fn load(&mut self, text: String, cursor: usize) {
        self.revision = self.revision.wrapping_add(1);
        self.text = text;
        self.cursor = cursor.min(self.text.len());
        while !self.text.is_char_boundary(self.cursor) {
            self.cursor -= 1;
        }
        self.group = None;
        self.preferred_column = None;
    }

    pub fn replace(&mut self, text: String) {
        if self.text == text {
            return;
        }
        self.checkpoint(Edit::Other);
        let cursor = text.len();
        self.load(text, cursor);
    }

    pub fn reset(&mut self) {
        let revision = self.revision.wrapping_add(1);
        *self = Self {
            revision,
            ..Self::default()
        };
    }
    pub fn insert_text(&mut self, text: &str) {
        if text.is_empty() {
            return;
        }
        self.checkpoint(Edit::Other);
        self.text.insert_str(self.cursor, text);
        self.cursor += text.len();
        self.edited();
    }
    pub fn insert(&mut self, character: char) {
        self.checkpoint(if character == '\n' {
            Edit::Other
        } else {
            Edit::Typing
        });
        self.text.insert(self.cursor, character);
        self.cursor += character.len_utf8();
        self.edited();
    }
    pub fn insert_newline(&mut self) {
        self.insert('\n');
    }
    fn previous(&self) -> usize {
        self.text[..self.cursor]
            .grapheme_indices(true)
            .next_back()
            .map(|(at, _)| at)
            .unwrap_or(0)
    }
    fn following(&self) -> usize {
        self.text[self.cursor..]
            .grapheme_indices(true)
            .nth(1)
            .map(|(at, _)| at + self.cursor)
            .unwrap_or(self.text.len())
    }
    pub fn backspace(&mut self) {
        if self.cursor == 0 {
            return;
        }
        self.checkpoint(Edit::Backspace);
        let previous = self.previous();
        self.text.replace_range(previous..self.cursor, "");
        self.cursor = previous;
        self.edited();
    }
    pub fn delete(&mut self) {
        if self.cursor >= self.text.len() {
            return;
        }
        self.checkpoint(Edit::Delete);
        self.text.replace_range(self.cursor..self.following(), "");
        self.edited();
    }
    pub fn left(&mut self) {
        self.cursor = self.previous();
        self.group = None;
        self.preferred_column = None;
    }
    pub fn right(&mut self) {
        self.cursor = self.following();
        self.group = None;
        self.preferred_column = None;
    }
    pub fn take(&mut self) -> String {
        let text = std::mem::take(&mut self.text);
        self.reset();
        text
    }
    pub fn clear(&mut self) {
        if self.text.is_empty() {
            return;
        }
        self.kill(0, self.text.len());
    }
    fn kill(&mut self, start: usize, end: usize) {
        if start == end {
            return;
        }
        self.checkpoint(Edit::Other);
        self.killed = self.text[start..end].to_string();
        self.text.replace_range(start..end, "");
        self.cursor = start;
        self.edited();
    }
    fn line_start(&self) -> usize {
        self.text[..self.cursor]
            .rfind('\n')
            .map(|at| at + 1)
            .unwrap_or(0)
    }
    fn line_end(&self) -> usize {
        self.text[self.cursor..]
            .find('\n')
            .map(|at| at + self.cursor)
            .unwrap_or(self.text.len())
    }
    fn word_start(&self) -> usize {
        let mut at = self.cursor;
        let mut found = false;
        for (index, grapheme) in self.text[..self.cursor].grapheme_indices(true).rev() {
            let whitespace = grapheme.chars().all(char::is_whitespace);
            if found && whitespace {
                break;
            }
            found |= !whitespace;
            at = index;
        }
        at
    }
    fn word_end(&self) -> usize {
        let mut at = self.cursor;
        let mut found = false;
        for (index, grapheme) in self.text[self.cursor..].grapheme_indices(true) {
            let whitespace = grapheme.chars().all(char::is_whitespace);
            if found && whitespace {
                break;
            }
            found |= !whitespace;
            at = self.cursor + index + grapheme.len();
        }
        at
    }
    pub fn undo(&mut self) {
        if let Some(previous) = self.undo.pop() {
            self.redo.push(self.draft());
            self.load(previous.text, previous.cursor);
        }
    }
    fn redo(&mut self) {
        if let Some(next) = self.redo.pop() {
            self.undo.push(self.draft());
            self.load(next.text, next.cursor);
        }
    }
    /// Editing-only shortcuts; submission, history and application actions stay outside.
    pub fn edit_key(&mut self, key: KeyEvent) -> bool {
        use KeyCode::*;
        use KeyModifiers as M;
        match (key.code, key.modifiers) {
            (Char('a'), M::CONTROL) => self.cursor = self.line_start(),
            (Char('e'), M::CONTROL) => self.cursor = self.line_end(),
            (Char('u'), M::CONTROL) => {
                let start = self.line_start();
                self.kill(
                    if start == self.cursor {
                        start.saturating_sub(1)
                    } else {
                        start
                    },
                    self.cursor,
                );
                return true;
            }
            (Char('k'), M::CONTROL) => {
                let end = self.line_end();
                self.kill(
                    self.cursor,
                    if end == self.cursor && end < self.text.len() {
                        end + 1
                    } else {
                        end
                    },
                );
                return true;
            }
            (Char('w'), M::CONTROL) | (Backspace, M::CONTROL | M::ALT) => {
                self.kill(self.word_start(), self.cursor);
                return true;
            }
            (Delete, M::CONTROL | M::ALT) | (Char('d'), M::ALT) => {
                self.kill(self.cursor, self.word_end());
                return true;
            }
            (Left, M::CONTROL | M::ALT) | (Char('b'), M::ALT) => self.cursor = self.word_start(),
            (Right, M::CONTROL | M::ALT) | (Char('f'), M::ALT) => self.cursor = self.word_end(),
            (Char('b'), M::CONTROL) => {
                self.left();
                return true;
            }
            (Char('f'), M::CONTROL) => {
                self.right();
                return true;
            }
            (Char('y'), M::CONTROL) => {
                let text = self.killed.clone();
                self.insert_text(&text);
                return true;
            }
            (Char('_'), M::CONTROL) | (Char('z'), M::CONTROL) => {
                self.undo();
                return true;
            }
            (Char('z' | 'Z'), modifiers) if modifiers == M::CONTROL | M::SHIFT => {
                self.redo();
                return true;
            }
            _ => return false,
        }
        self.group = None;
        self.preferred_column = None;
        true
    }

    pub fn vertical(&mut self, up: bool, width: usize) -> bool {
        let width = width.max(1);
        let mut positions = vec![(0, 0_usize, 0_usize)];
        let (mut row, mut col) = (0, 0);
        for (at, grapheme) in self.text.grapheme_indices(true) {
            if grapheme == "\n" {
                row += 1;
                col = 0;
            } else {
                let size = UnicodeWidthStr::width(grapheme);
                if col > 0 && col + size > width {
                    row += 1;
                    col = 0;
                }
                col += size;
            }
            positions.push((at + grapheme.len(), row, col));
        }
        let (_, current_row, current_col) = positions
            .iter()
            .find(|(at, _, _)| *at == self.cursor)
            .copied()
            .unwrap_or((self.cursor, row, col));
        if up && current_row == 0 || !up && current_row == row {
            return false;
        }
        let target = if up { current_row - 1 } else { current_row + 1 };
        let column = self.preferred_column.unwrap_or(current_col);
        if let Some((at, _, _)) = positions
            .iter()
            .filter(|(_, row, _)| *row == target)
            .min_by_key(|(_, _, col)| col.abs_diff(column))
        {
            self.cursor = *at;
            self.preferred_column = Some(column);
            self.group = None;
        }
        true
    }
}

#[derive(Clone, Debug, Default)]
pub struct InputHistory {
    pub entries: Vec<String>,
    index: Option<usize>,
    saved: Option<Draft>,
}
impl InputHistory {
    pub fn record(&mut self, text: &str) {
        self.detach();
        if text.trim().is_empty() || self.entries.last().is_some_and(|last| last == text) {
            return;
        }
        self.entries.push(text.to_string());
        let mut size: usize = self.entries.iter().map(String::len).sum();
        while self.entries.len() > 1 && (self.entries.len() > 500 || size > 8 * 1024 * 1024) {
            size -= self.entries.remove(0).len();
        }
    }
    pub fn detach(&mut self) {
        self.index = None;
        self.saved = None;
    }
    pub fn previous(&mut self, composer: &mut Composer) {
        if self.entries.is_empty() {
            return;
        }
        let index = if let Some(index) = self.index {
            index.saturating_sub(1)
        } else {
            self.saved = Some(composer.draft());
            composer.checkpoint(Edit::Other);
            self.entries.len() - 1
        };
        self.index = Some(index);
        let text = self.entries[index].clone();
        composer.load(text.clone(), text.len());
    }
    pub fn next(&mut self, composer: &mut Composer) {
        let Some(index) = self.index else {
            return;
        };
        if index + 1 < self.entries.len() {
            self.index = Some(index + 1);
            let text = self.entries[index + 1].clone();
            composer.load(text.clone(), text.len());
        } else {
            if let Some(saved) = self.saved.take() {
                composer.load(saved.text, saved.cursor);
            }
            self.index = None;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn unicode_kill_clear_yank_and_undo_preserve_whole_draft() {
        let mut editor = Composer::default();
        editor.insert_text("first\n中文😀 last");
        editor.edit_key(KeyEvent::new(KeyCode::Char('w'), KeyModifiers::CONTROL));
        assert_eq!(editor.text, "first\n中文😀 ");
        editor.edit_key(KeyEvent::new(KeyCode::Char('y'), KeyModifiers::CONTROL));
        assert_eq!(editor.text, "first\n中文😀 last");
        editor.clear();
        editor.undo();
        assert_eq!(editor.text, "first\n中文😀 last");
        editor.edit_key(KeyEvent::new(KeyCode::Char('u'), KeyModifiers::CONTROL));
        assert_eq!(editor.text, "first\n");
        editor.undo();
        editor.backspace();
        editor.undo();
        assert_eq!(editor.text, "first\n中文😀 last");
    }
    #[test]
    fn visual_rows_precede_history_and_return_restores_draft() {
        let mut editor = Composer::default();
        editor.insert_text("abcdef\nxy");
        assert!(editor.vertical(true, 4));
        assert!(editor.vertical(true, 4));
        assert!(!editor.vertical(true, 4));
        let saved = editor.draft();
        let mut history = InputHistory::default();
        history.record("old\nrequest");
        history.record("recent");
        history.previous(&mut editor);
        assert_eq!(editor.text, "recent");
        history.previous(&mut editor);
        assert_eq!(editor.text, "old\nrequest");
        history.next(&mut editor);
        history.next(&mut editor);
        assert_eq!(editor.draft(), saved);
    }
}
