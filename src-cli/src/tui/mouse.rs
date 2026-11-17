//! Viewport selection keeps the displayed text stable while output arrives.
use std::cell::RefCell;

use crossterm::event::{MouseButton, MouseEvent, MouseEventKind};
use ratatui::{
    buffer::Buffer,
    layout::{Position, Rect},
    style::{Modifier, Style},
};
use unicode_width::UnicodeWidthStr;

#[derive(Default)]
pub struct MouseState {
    frame: RefCell<Option<Buffer>>,
    regions: RefCell<Vec<Rect>>,
    pub selection: Option<Selection>,
}

pub enum Action {
    None,
    Redraw,
    Copy(String),
    Paste,
    Scroll(bool),
}

pub struct Selection {
    frame: Buffer,
    region: Rect,
    anchor: Position,
    head: Position,
    dragging: bool,
}

impl MouseState {
    pub fn capture(&self, frame: &Buffer, regions: Vec<Rect>) {
        *self.frame.borrow_mut() = Some(frame.clone());
        *self.regions.borrow_mut() = regions;
    }

    pub fn paint(&self, buffer: &mut Buffer) -> bool {
        let Some(selection) = &self.selection else {
            return false;
        };
        if selection.frame.area != buffer.area {
            return false;
        }
        buffer.clone_from(&selection.frame);
        let (start, end) = selection.bounds();
        for y in start.y..=end.y {
            let left = if y == start.y {
                start.x
            } else {
                selection.region.x
            };
            let right = if y == end.y {
                (end.x + selection.frame[(end.x, y)].symbol().width().max(1) as u16)
                    .min(selection.region.right())
            } else {
                selection.region.right()
            };
            for x in left..right {
                buffer[(x, y)].set_style(Style::default().add_modifier(Modifier::REVERSED));
            }
        }
        true
    }

    pub fn take_text(&mut self) -> Option<String> {
        self.selection
            .take()
            .filter(|s| s.anchor != s.head)
            .map(|s| s.text())
    }

    pub fn handle(&mut self, event: MouseEvent) -> Action {
        let position = Position::new(event.column, event.row);
        match event.kind {
            MouseEventKind::ScrollUp | MouseEventKind::ScrollDown => {
                self.selection = None;
                Action::Scroll(event.kind == MouseEventKind::ScrollUp)
            }
            MouseEventKind::Down(MouseButton::Right) => {
                self.take_text().map(Action::Copy).unwrap_or(Action::Paste)
            }
            MouseEventKind::Down(MouseButton::Left) => {
                self.selection = None;
                if let Some(frame) = self
                    .frame
                    .borrow()
                    .as_ref()
                    .filter(|f| f.area.contains(position))
                {
                    let region = self
                        .regions
                        .borrow()
                        .iter()
                        .copied()
                        .find(|r| r.contains(position))
                        .unwrap_or(frame.area);
                    let mut selection = Selection {
                        frame: frame.clone(),
                        region,
                        anchor: position,
                        head: position,
                        dragging: true,
                    };
                    selection.anchor = selection.clamp(position);
                    selection.head = selection.anchor;
                    self.selection = Some(selection);
                }
                Action::Redraw
            }
            MouseEventKind::Drag(MouseButton::Left) | MouseEventKind::Up(MouseButton::Left) => {
                if let Some(selection) = self.selection.as_mut().filter(|s| s.dragging) {
                    selection.head = selection.clamp(position);
                    if event.kind == MouseEventKind::Up(MouseButton::Left) {
                        selection.dragging = false;
                        if selection.head == selection.anchor {
                            self.selection = None;
                        }
                    }
                    Action::Redraw
                } else {
                    Action::None
                }
            }
            _ => Action::None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::KeyModifiers;

    #[test]
    fn backward_selection_keeps_unicode_and_excludes_adjacent_rail() {
        let mut frame = Buffer::empty(Rect::new(0, 0, 30, 3));
        frame.set_string(2, 0, "中文😀 end", Style::default());
        frame.set_string(2, 1, "next line", Style::default());
        frame.set_string(16, 0, "PRIVATE_RAIL", Style::default());
        let mut state = MouseState::default();
        state.capture(&frame, vec![Rect::new(2, 0, 12, 3)]);
        let event = |kind, column, row| MouseEvent {
            kind,
            column,
            row,
            modifiers: KeyModifiers::NONE,
        };
        state.handle(event(MouseEventKind::Down(MouseButton::Left), 5, 1));
        state.handle(event(MouseEventKind::Drag(MouseButton::Left), 3, 0));
        state.handle(event(MouseEventKind::Up(MouseButton::Left), 3, 0));
        let mut changed = Buffer::empty(frame.area);
        changed.set_string(2, 0, "new output", Style::default());
        state.capture(&changed, vec![]);
        assert!(state.paint(&mut changed));
        assert_eq!(changed[(2, 0)].symbol(), "中");
        assert_eq!(state.take_text().as_deref(), Some("中文😀 end\nnext"));
    }

    #[test]
    fn click_release_does_not_leave_a_selection_and_wheel_clears_it() {
        let mut state = MouseState::default();
        state.capture(&Buffer::empty(Rect::new(0, 0, 10, 5)), vec![]);
        let event = |kind| MouseEvent {
            kind,
            column: 2,
            row: 2,
            modifiers: KeyModifiers::NONE,
        };
        state.handle(event(MouseEventKind::Down(MouseButton::Left)));
        state.handle(event(MouseEventKind::Up(MouseButton::Left)));
        assert!(state.selection.is_none());
        state.handle(event(MouseEventKind::Down(MouseButton::Left)));
        assert!(matches!(
            state.handle(event(MouseEventKind::ScrollUp)),
            Action::Scroll(true)
        ));
        assert!(state.selection.is_none());
        assert!(matches!(
            state.handle(event(MouseEventKind::Down(MouseButton::Right))),
            Action::Paste
        ));
    }
}

impl Selection {
    fn clamp(&self, position: Position) -> Position {
        let y = position.y.clamp(self.region.y, self.region.bottom() - 1);
        let target = position.x.clamp(self.region.x, self.region.right() - 1);
        let mut x = self.region.x;
        while x < target {
            let next = x.saturating_add(self.frame[(x, y)].symbol().width().max(1) as u16);
            if next > target {
                break;
            }
            x = next;
        }
        Position::new(x, y)
    }

    fn bounds(&self) -> (Position, Position) {
        if (self.anchor.y, self.anchor.x) <= (self.head.y, self.head.x) {
            (self.anchor, self.head)
        } else {
            (self.head, self.anchor)
        }
    }

    fn text(&self) -> String {
        let (start, end) = self.bounds();
        (start.y..=end.y)
            .map(|y| {
                let mut x = if y == start.y { start.x } else { self.region.x };
                let right = if y == end.y {
                    end.x + 1
                } else {
                    self.region.right()
                };
                let mut text = String::new();
                while x < right {
                    let symbol = self.frame[(x, y)].symbol();
                    text.push_str(symbol);
                    x = x.saturating_add(symbol.width().max(1) as u16);
                }
                text.trim_end().to_owned()
            })
            .collect::<Vec<_>>()
            .join("\n")
    }
}
