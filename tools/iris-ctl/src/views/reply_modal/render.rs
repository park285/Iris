use super::ReplyModal;
use super::state::{FieldFocus, OverlayState, ThreadMode, ThreadScope, is_open_chat};
use super::{centered_rect, truncate_thread_origin};
use iris_common::models::ReplyType;
use ratatui::Frame;
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, Clear, Paragraph, Wrap};

impl ReplyModal {
    pub fn render(&self, frame: &mut Frame<'_>) {
        let area = centered_rect(frame.area(), 70, 80);
        frame.render_widget(Clear, area);

        let block = Block::default()
            .borders(Borders::ALL)
            .title(" Reply ")
            .style(Style::default().fg(Color::White));
        let inner = block.inner(area);
        frame.render_widget(block, area);

        let meta_height = if is_open_chat(self.draft.room.as_ref())
            && self.draft.thread_mode == ThreadMode::Specified
        {
            4
        } else if is_open_chat(self.draft.room.as_ref()) {
            3
        } else {
            2
        };

        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Length(meta_height),
                Constraint::Min(3),
                Constraint::Length(2),
            ])
            .split(inner);

        self.render_meta(frame, chunks[0]);
        self.render_content(frame, chunks[1]);
        self.render_footer(frame, chunks[2]);

        match self.overlay() {
            OverlayState::RoomSelector => self.render_room_selector(frame, inner),
            OverlayState::ThreadSelector => self.render_thread_selector(frame, inner),
            OverlayState::None => {}
        }
    }

    fn render_meta(&self, frame: &mut Frame<'_>, area: Rect) {
        #[allow(clippy::needless_collect)]
        let constraints: Vec<Constraint> =
            (0..area.height).map(|_| Constraint::Length(1)).collect();
        let rows = Layout::default()
            .direction(Direction::Vertical)
            .constraints(constraints)
            .split(area);

        let mut row_idx = 0;

        if row_idx < rows.len() {
            frame.render_widget(Paragraph::new(self.render_type_line()), rows[row_idx]);
            row_idx += 1;
        }

        if row_idx < rows.len() {
            frame.render_widget(Paragraph::new(self.render_room_line()), rows[row_idx]);
            row_idx += 1;
        }

        if is_open_chat(self.draft.room.as_ref()) && row_idx < rows.len() {
            frame.render_widget(Paragraph::new(self.render_thread_line()), rows[row_idx]);
            row_idx += 1;
        }

        if is_open_chat(self.draft.room.as_ref())
            && self.draft.thread_mode == ThreadMode::Specified
            && row_idx < rows.len()
        {
            frame.render_widget(Paragraph::new(self.render_scope_line()), rows[row_idx]);
        }
    }

    fn render_type_line(&self) -> Line<'static> {
        let focused = self.field_focus() == FieldFocus::Type;
        let label_style = if focused {
            Style::default()
                .fg(Color::Yellow)
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default().fg(Color::Gray)
        };
        let types = [
            (ReplyType::Text, "text"),
            (ReplyType::Image, "image"),
            (ReplyType::ImageMultiple, "images"),
            (ReplyType::Markdown, "markdown"),
        ];
        let mut spans = vec![Span::styled("  Type    ", label_style)];
        for (rt, label) in &types {
            let selected = *rt == self.draft.reply_type;
            let marker = if selected { "●" } else { "○" };
            let style = if selected {
                Style::default().fg(Color::Cyan)
            } else {
                Style::default().fg(Color::DarkGray)
            };
            spans.push(Span::styled(format!("{marker} {label}  "), style));
        }
        Line::from(spans)
    }

    fn render_room_line(&self) -> Line<'static> {
        let focused = self.field_focus() == FieldFocus::Room;
        let label_style = if focused {
            Style::default()
                .fg(Color::Yellow)
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default().fg(Color::Gray)
        };
        let room_text = match &self.draft.room {
            Some(r) => {
                let name = r.link_name.as_deref().unwrap_or("Unknown");
                format!("{name} ({})", r.chat_id)
            }
            None => "선택 안 됨 (Enter)".to_string(),
        };
        Line::from(vec![
            Span::styled("  Room    ", label_style),
            Span::styled(room_text, Style::default().fg(Color::White)),
        ])
    }

    fn render_thread_line(&self) -> Line<'static> {
        let focused = matches!(
            self.field_focus(),
            FieldFocus::Thread | FieldFocus::ThreadId
        );
        let label_style = if focused {
            Style::default()
                .fg(Color::Yellow)
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default().fg(Color::Gray)
        };
        let thread_text = match self.draft.thread_mode {
            ThreadMode::None => "없음 (Enter: 지정)".to_string(),
            ThreadMode::Specified => {
                if self.field_focus() == FieldFocus::ThreadId {
                    format!("ID: {}█", self.draft.thread_id_input)
                } else if self.draft.thread_id_input.is_empty() {
                    "지정됨 (Enter: 선택/입력)".to_string()
                } else {
                    format!("ID: {}", self.draft.thread_id_input)
                }
            }
        };
        Line::from(vec![
            Span::styled("  Thread  ", label_style),
            Span::styled(thread_text, Style::default().fg(Color::White)),
        ])
    }

    fn render_scope_line(&self) -> Line<'static> {
        let focused = self.field_focus() == FieldFocus::Scope;
        let label_style = if focused {
            Style::default()
                .fg(Color::Yellow)
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default().fg(Color::Gray)
        };
        let scopes = [ThreadScope::Thread, ThreadScope::Both, ThreadScope::Room];
        let mut spans = vec![Span::styled("  Scope   ", label_style)];
        for s in &scopes {
            let selected = *s == self.draft.scope;
            let marker = if selected { "●" } else { "○" };
            let style = if selected {
                Style::default().fg(Color::Cyan)
            } else {
                Style::default().fg(Color::DarkGray)
            };
            spans.push(Span::styled(format!("{marker} {}  ", s.label()), style));
        }
        Line::from(spans)
    }

    fn render_content(&self, frame: &mut Frame<'_>, area: Rect) {
        match self.draft.reply_type {
            ReplyType::Text | ReplyType::Markdown => {
                frame.render_widget(&self.draft.text_area, area);
            }
            ReplyType::Image => {
                self.render_image_single(frame, area);
            }
            ReplyType::ImageMultiple => {
                self.render_image_multiple(frame, area);
            }
        }
    }

    fn render_image_single(&self, frame: &mut Frame<'_>, area: Rect) {
        let block = Block::default().borders(Borders::ALL).title(" Image Path ");
        let inner = block.inner(area);
        frame.render_widget(block, area);

        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([Constraint::Length(1), Constraint::Min(0)])
            .split(inner);

        self.draft
            .image_path
            .render(frame, chunks[0], self.field_focus() == FieldFocus::Content);
        if let Some(info) = &self.draft.image_path.file_info {
            let size_kb = info.size_bytes / 1024;
            let text = format!("  ✓ ({size_kb} KB, {})", info.extension.to_uppercase());
            frame.render_widget(
                Paragraph::new(text).style(Style::default().fg(Color::Green)),
                chunks[1],
            );
        }
    }

    fn render_image_multiple(&self, frame: &mut Frame<'_>, area: Rect) {
        let block = Block::default()
            .borders(Borders::ALL)
            .title(" Image Paths ");
        let inner = block.inner(area);
        frame.render_widget(block, area);

        let constraints: Vec<Constraint> = self
            .draft
            .image_paths
            .iter()
            .enumerate()
            .map(|(i, _)| {
                if i == self.draft.image_paths_cursor && self.draft.image_editing {
                    Constraint::Length(2)
                } else {
                    Constraint::Length(1)
                }
            })
            .chain(std::iter::once(Constraint::Min(0)))
            .collect();

        let rows = Layout::default()
            .direction(Direction::Vertical)
            .constraints(constraints)
            .split(inner);

        for (i, path_input) in self.draft.image_paths.iter().enumerate() {
            if i >= rows.len() - 1 {
                break;
            }
            let marker = if i == self.draft.image_paths_cursor {
                "▶"
            } else {
                " "
            };
            let idx_text = format!("{marker}{}. ", i + 1);

            let row_chunks = Layout::default()
                .direction(Direction::Horizontal)
                .constraints([Constraint::Length(4), Constraint::Min(0)])
                .split(rows[i]);

            frame.render_widget(
                Paragraph::new(idx_text).style(Style::default().fg(Color::DarkGray)),
                row_chunks[0],
            );

            let is_focused = self.field_focus() == FieldFocus::Content
                && i == self.draft.image_paths_cursor
                && self.draft.image_editing;
            path_input.render(frame, row_chunks[1], is_focused);
        }

        if let Some(last_row) = rows.last() {
            frame.render_widget(
                Paragraph::new("  Ctrl+A 추가 │ Ctrl+D 삭제 │ ↑↓ 이동 │ Enter 편집")
                    .style(Style::default().fg(Color::DarkGray)),
                *last_row,
            );
        }
    }

    fn render_footer(&self, frame: &mut Frame<'_>, area: Rect) {
        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([Constraint::Length(1), Constraint::Length(1)])
            .split(area);

        frame.render_widget(
            Paragraph::new("  Ctrl+S 전송 │ Tab 다음 │ Esc 취소")
                .style(Style::default().fg(Color::DarkGray)),
            chunks[0],
        );

        if self.ui.sending {
            frame.render_widget(
                Paragraph::new("  ⟳ 전송 중...").style(Style::default().fg(Color::Cyan)),
                chunks[1],
            );
        } else if let Some(result) = &self.ui.result {
            let (text, style) = match result {
                super::ReplyResult::Success { request_id } => (
                    format!("  ✓ 전송 완료 ({request_id})"),
                    Style::default().fg(Color::Green),
                ),
                super::ReplyResult::Error { message } => {
                    (format!("  ✗ {message}"), Style::default().fg(Color::Red))
                }
            };
            frame.render_widget(Paragraph::new(text).style(style), chunks[1]);
        }
    }

    fn render_room_selector(&self, frame: &mut Frame<'_>, parent: Rect) {
        let area = centered_rect(parent, 60, 50);
        frame.render_widget(Clear, area);
        let block = Block::default().borders(Borders::ALL).title(" 방 선택 ");
        let inner = block.inner(area);
        frame.render_widget(block, area);

        let rows: Vec<Line<'_>> = self
            .room_list
            .iter()
            .enumerate()
            .map(|(i, r)| {
                let name = r.link_name.as_deref().unwrap_or("Unknown");
                let style = if i == self.selectors.room_selector_cursor {
                    Style::default()
                        .fg(Color::Yellow)
                        .add_modifier(Modifier::BOLD)
                } else {
                    Style::default().fg(Color::White)
                };
                Line::styled(format!("  {} ({})", name, r.chat_id), style)
            })
            .collect();

        frame.render_widget(Paragraph::new(rows).wrap(Wrap { trim: false }), inner);
    }

    fn render_thread_selector(&self, frame: &mut Frame<'_>, parent: Rect) {
        let area = centered_rect(parent, 70, 50);
        frame.render_widget(Clear, area);
        let block = Block::default()
            .borders(Borders::ALL)
            .title(" 스레드 선택 ");
        let inner = block.inner(area);
        frame.render_widget(block, area);

        if self.selectors.thread_suggestions.is_empty() {
            frame.render_widget(
                Paragraph::new("  스레드 없음").style(Style::default().fg(Color::DarkGray)),
                inner,
            );
            return;
        }

        let rows: Vec<Line<'_>> = self
            .selectors
            .thread_suggestions
            .iter()
            .enumerate()
            .map(|(i, t)| {
                let origin = t.origin_message.as_deref().unwrap_or("(원본 없음)");
                let truncated = truncate_thread_origin(origin);
                let style = if i == self.selectors.thread_selector_cursor {
                    Style::default()
                        .fg(Color::Yellow)
                        .add_modifier(Modifier::BOLD)
                } else {
                    Style::default().fg(Color::White)
                };
                Line::styled(format!("  [{}건] {truncated}", t.message_count), style)
            })
            .collect();

        frame.render_widget(Paragraph::new(rows), inner);
    }
}
