use std::cell::Cell;

/// View-only scroll state. The renderer records geometry without changing
/// conversation data; a reader stays anchored while the transcript grows.
#[derive(Clone, Debug, Default)]
pub struct TranscriptScroll {
    offset: Cell<usize>,
    top: Cell<Option<usize>>,
    total: Cell<usize>,
    height: Cell<usize>,
    width: Cell<u16>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reading_stays_anchored_until_follow_is_requested() {
        let scroll = TranscriptScroll::default();
        assert_eq!(scroll.position(100, 20, 80), 80);
        scroll.move_up(10);
        assert_eq!(scroll.position(100, 20, 80), 70);
        assert_eq!(scroll.position(150, 20, 80), 70);
        scroll.move_down(5);
        assert_eq!(scroll.position(150, 20, 80), 75);
        scroll.follow();
        assert_eq!(scroll.position(150, 20, 80), 130);
        assert_eq!(scroll.position(160, 20, 80), 140);
        scroll.first();
        assert_eq!(scroll.position(160, 20, 80), 0);
    }

    #[test]
    fn opening_a_view_at_the_top_works_before_its_first_layout() {
        let scroll = TranscriptScroll::default();
        scroll.first();
        assert_eq!(scroll.position(90, 20, 60), 0);
        scroll.move_down(8);
        assert_eq!(scroll.position(90, 20, 60), 8);
    }
}

impl TranscriptScroll {
    pub fn is_following(&self) -> bool {
        self.offset.get() == 0
    }

    pub fn follow(&self) {
        self.offset.set(0);
        self.top.set(None);
    }

    pub fn move_up(&self, lines: usize) {
        let limit = self.total.get().saturating_sub(self.height.get());
        let top = self.top.get().unwrap_or(limit).saturating_sub(lines);
        self.offset.set(limit.saturating_sub(top));
        self.top.set(Some(top));
    }

    pub fn move_down(&self, lines: usize) {
        let limit = self.total.get().saturating_sub(self.height.get());
        let top = self
            .top
            .get()
            .unwrap_or(limit)
            .saturating_add(lines)
            .min(limit);
        self.offset.set(limit - top);
        self.top.set((top < limit).then_some(top));
    }

    pub fn page_size(&self) -> usize {
        self.height.get().saturating_sub(2).max(1)
    }

    pub fn first(&self) {
        self.offset.set(usize::MAX);
        self.top.set(Some(0));
    }

    pub fn position(&self, total: usize, height: u16, width: u16) -> usize {
        let bottom = total.saturating_sub(height as usize);
        let top = if self.is_following() {
            bottom
        } else if let Some(previous) = self.top.get() {
            if self.width.get() != width && self.total.get() > 0 {
                previous.saturating_mul(total) / self.total.get()
            } else if total < self.total.get() {
                previous.saturating_sub(self.total.get() - total)
            } else {
                previous
            }
            .min(bottom)
        } else {
            bottom.saturating_sub(self.offset.get())
        };
        self.offset.set(bottom - top);
        self.top.set((top < bottom).then_some(top));
        self.total.set(total);
        self.height.set(height as usize);
        self.width.set(width);
        top
    }
}
