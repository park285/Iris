use crossterm::event::{KeyCode, KeyEvent};
use ratatui::Frame;
use ratatui::layout::Rect;
use ratatui::style::{Color, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::Paragraph;
use std::path::Path;

pub struct PathInput {
    pub value: String,
    cursor: usize,
    completions: Vec<String>,
    completion_index: Option<usize>,
    pub file_info: Option<FileInfo>,
}

pub struct FileInfo {
    pub size_bytes: u64,
    pub extension: String,
}

impl PathInput {
    pub const fn new() -> Self {
        Self {
            value: String::new(),
            cursor: 0,
            completions: Vec::new(),
            completion_index: None,
            file_info: None,
        }
    }

    pub fn handle_key(&mut self, key: KeyEvent) -> bool {
        match key.code {
            KeyCode::Char(ch) => {
                self.value.insert(self.cursor, ch);
                self.cursor += ch.len_utf8();
                self.clear_completions();
                true
            }
            KeyCode::Backspace => {
                if self.cursor > 0 {
                    let prev = self.value[..self.cursor]
                        .char_indices()
                        .next_back()
                        .map_or(0, |(i, _)| i);
                    self.value.replace_range(prev..self.cursor, "");
                    self.cursor = prev;
                    self.clear_completions();
                }
                true
            }
            KeyCode::Left => {
                if self.cursor > 0 {
                    self.cursor = self.value[..self.cursor]
                        .char_indices()
                        .next_back()
                        .map_or(0, |(i, _)| i);
                }
                true
            }
            KeyCode::Right => {
                if self.cursor < self.value.len() {
                    self.cursor = self.value[self.cursor..]
                        .char_indices()
                        .nth(1)
                        .map_or(self.value.len(), |(i, _)| self.cursor + i);
                }
                true
            }
            KeyCode::Tab => {
                self.tab_complete();
                true
            }
            KeyCode::Home => {
                self.cursor = 0;
                true
            }
            KeyCode::End => {
                self.cursor = self.value.len();
                true
            }
            _ => false,
        }
    }

    fn clear_completions(&mut self) {
        self.completions.clear();
        self.completion_index = None;
    }

    fn tab_complete(&mut self) {
        if let Some(idx) = self.completion_index {
            let next = (idx + 1) % self.completions.len();
            self.completion_index = Some(next);
            self.value = self.completions[next].clone();
        } else {
            let candidates = self.collect_candidates();
            if candidates.is_empty() {
                return;
            }
            if candidates.len() == 1 {
                self.value = candidates[0].clone();
                self.cursor = self.value.len();
                return;
            }
            let common = common_prefix(&candidates);
            if common.len() > self.value.len() {
                self.value = common;
            } else {
                self.completions = candidates;
                self.completion_index = Some(0);
                self.value = self.completions[0].clone();
            }
        }
        self.cursor = self.value.len();
        self.validate_file();
    }

    fn collect_candidates(&self) -> Vec<String> {
        let path = Path::new(&self.value);
        let (dir, prefix) =
            if self.value.ends_with('/') || self.value.ends_with(std::path::MAIN_SEPARATOR) {
                (path.to_path_buf(), String::new())
            } else {
                let dir = path
                    .parent()
                    .unwrap_or_else(|| Path::new("."))
                    .to_path_buf();
                let prefix = path
                    .file_name()
                    .map_or_else(String::new, |f| f.to_string_lossy().to_string());
                (dir, prefix)
            };

        let Ok(entries) = std::fs::read_dir(&dir) else {
            return Vec::new();
        };

        let mut candidates: Vec<String> = entries
            .filter_map(Result::ok)
            .filter(|e| e.file_name().to_string_lossy().starts_with(&prefix))
            .map(|e| {
                let mut full = dir.join(e.file_name()).to_string_lossy().to_string();
                if e.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                    full.push('/');
                }
                full
            })
            .collect();

        candidates.sort();
        candidates
    }

    pub fn validate_file(&mut self) {
        let path = Path::new(&self.value);
        if path.is_file() {
            let size = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
            let ext = path
                .extension()
                .map_or_else(String::new, |e| e.to_string_lossy().to_string());
            self.file_info = Some(FileInfo {
                size_bytes: size,
                extension: ext,
            });
        } else {
            self.file_info = None;
        }
    }

    pub fn render(&self, frame: &mut Frame<'_>, area: Rect, focused: bool) {
        let style = if focused {
            Style::default().fg(Color::Yellow)
        } else {
            Style::default().fg(Color::White)
        };
        let display = if self.value.is_empty() {
            Line::from(Span::styled(
                "경로를 입력하세요...",
                Style::default().fg(Color::DarkGray),
            ))
        } else {
            Line::from(Span::styled(&self.value, style))
        };
        frame.render_widget(Paragraph::new(display), area);
        if focused {
            frame.set_cursor_position((area.x + self.cursor as u16, area.y));
        }
    }

    pub fn clear(&mut self) {
        self.value.clear();
        self.cursor = 0;
        self.clear_completions();
        self.file_info = None;
    }
}

fn common_prefix(strings: &[String]) -> String {
    if strings.is_empty() {
        return String::new();
    }
    let first = &strings[0];
    let mut len = first.len();
    for s in &strings[1..] {
        len = len.min(
            first
                .chars()
                .zip(s.chars())
                .take_while(|(a, b)| a == b)
                .count(),
        );
    }
    first.chars().take(len).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    #[test]
    fn char_input_and_backspace() {
        let mut input = PathInput::new();
        input.handle_key(key(KeyCode::Char('a')));
        input.handle_key(key(KeyCode::Char('b')));
        assert_eq!(input.value, "ab");
        input.handle_key(key(KeyCode::Backspace));
        assert_eq!(input.value, "a");
    }

    #[test]
    fn cursor_movement() {
        let mut input = PathInput::new();
        input.handle_key(key(KeyCode::Char('a')));
        input.handle_key(key(KeyCode::Char('b')));
        input.handle_key(key(KeyCode::Left));
        input.handle_key(key(KeyCode::Char('x')));
        assert_eq!(input.value, "axb");
    }

    #[test]
    fn common_prefix_works() {
        assert_eq!(
            common_prefix(&["abc".into(), "abd".into(), "abe".into()]),
            "ab"
        );
        assert_eq!(common_prefix(&["same".into(), "same".into()]), "same");
        assert_eq!(common_prefix(&[]), "");
    }
}
