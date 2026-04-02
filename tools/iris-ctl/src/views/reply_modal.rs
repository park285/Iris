mod state;
mod util;
mod validate;

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use iris_common::models::{ReplyRequest, ReplyType, RoomSummary, ThreadSummary};
use ratatui::Frame;
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, Clear, Paragraph, Wrap};
use tui_textarea::TextArea;

use super::path_input::PathInput;
use state::{
    FieldFocus, OverlayState, ReplyUiState, ReplyValidationError, ThreadMode, ThreadScope,
    cycle_reply_type, is_open_chat,
};
pub use state::{ModalAction, ReplyResult};
use util::{centered_rect, truncate_thread_origin};
use validate::{build_data, validate_thread, validation_message_and_focus};

struct ReplyDraft {
    reply_type: ReplyType,
    room: Option<RoomSummary>,
    thread_mode: ThreadMode,
    thread_id_input: String,
    scope: ThreadScope,
    text_area: TextArea<'static>,
    image_path: PathInput,
    image_paths: Vec<PathInput>,
    image_paths_cursor: usize,
    image_editing: bool,
}

impl ReplyDraft {
    fn new(room: Option<RoomSummary>, thread_id: Option<String>) -> Self {
        let mut text_area = TextArea::default();
        text_area.set_placeholder_text("메시지를 입력하세요...");
        text_area.set_block(Block::default().borders(Borders::ALL).title(" Content "));

        let has_thread = thread_id.is_some();
        Self {
            reply_type: ReplyType::Text,
            room,
            thread_mode: if has_thread {
                ThreadMode::Specified
            } else {
                ThreadMode::None
            },
            thread_id_input: thread_id.unwrap_or_default(),
            scope: ThreadScope::Thread,
            text_area,
            image_path: PathInput::new(),
            image_paths: vec![PathInput::new()],
            image_paths_cursor: 0,
            image_editing: false,
        }
    }

    fn clear_thread_state(&mut self) {
        self.thread_mode = ThreadMode::None;
        self.thread_id_input.clear();
    }

    fn reset_after_success(&mut self) {
        self.text_area = TextArea::default();
        self.text_area
            .set_placeholder_text("메시지를 입력하세요...");
        self.text_area
            .set_block(Block::default().borders(Borders::ALL).title(" Content "));
        self.image_path.clear();
        self.image_paths = vec![PathInput::new()];
        self.image_paths_cursor = 0;
        self.image_editing = false;
    }
}

pub struct ReplyModal {
    draft: ReplyDraft,
    pub ui: ReplyUiState,

    // 선택기 데이터
    pub room_list: Vec<RoomSummary>,
    room_selector_cursor: usize,
    pub thread_suggestions: Vec<ThreadSummary>,
    thread_selector_cursor: usize,
}

impl ReplyModal {
    pub fn new_with_context(
        room: Option<RoomSummary>,
        room_list: Vec<RoomSummary>,
        thread_id: Option<String>,
    ) -> Self {
        let initial_focus = if room.is_none() {
            FieldFocus::Room
        } else {
            FieldFocus::Type
        };
        let room_selector_cursor = room
            .as_ref()
            .and_then(|selected| {
                room_list
                    .iter()
                    .position(|candidate| candidate.chat_id == selected.chat_id)
            })
            .unwrap_or(0);

        Self {
            draft: ReplyDraft::new(room, thread_id),
            ui: ReplyUiState::new(initial_focus),
            room_list,
            room_selector_cursor,
            thread_suggestions: Vec::new(),
            thread_selector_cursor: 0,
        }
    }

    pub fn thread_id_input(&self) -> &str {
        &self.draft.thread_id_input
    }

    fn field_focus(&self) -> FieldFocus {
        self.ui.field_focus
    }

    fn set_field_focus(&mut self, focus: FieldFocus) {
        self.ui.field_focus = focus;
    }

    fn overlay(&self) -> OverlayState {
        self.ui.overlay
    }

    fn set_overlay(&mut self, overlay: OverlayState) {
        self.ui.overlay = overlay;
    }

    // ======================== Rendering ========================

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
        #[allow(clippy::needless_collect)] // ratatui 0.29 Layout::constraints() 는 Vec 을 요구함
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
                ReplyResult::Success { request_id } => (
                    format!("  ✓ 전송 완료 ({request_id})"),
                    Style::default().fg(Color::Green),
                ),
                ReplyResult::Error { message } => {
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
                let style = if i == self.room_selector_cursor {
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

        if self.thread_suggestions.is_empty() {
            frame.render_widget(
                Paragraph::new("  스레드 없음").style(Style::default().fg(Color::DarkGray)),
                inner,
            );
            return;
        }

        let rows: Vec<Line<'_>> = self
            .thread_suggestions
            .iter()
            .enumerate()
            .map(|(i, t)| {
                let origin = t.origin_message.as_deref().unwrap_or("(원본 없음)");
                let truncated = truncate_thread_origin(origin);
                let style = if i == self.thread_selector_cursor {
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

    // ======================== Key Handling ========================

    pub fn handle_key(&mut self, key: KeyEvent) -> ModalAction {
        // Ctrl+S: 전송
        if key.modifiers.contains(KeyModifiers::CONTROL) && matches!(key.code, KeyCode::Char('s')) {
            if !self.ui.sending {
                return self.try_send();
            }
            return ModalAction::None;
        }

        if self.overlay() == OverlayState::RoomSelector {
            return self.handle_room_selector_key(key);
        }
        if self.overlay() == OverlayState::ThreadSelector {
            return self.handle_thread_selector_key(key);
        }

        // Content 포커스: textarea/path가 키를 소비
        if self.field_focus() == FieldFocus::Content {
            return self.handle_content_key(key);
        }

        // ThreadId 입력
        if self.field_focus() == FieldFocus::ThreadId {
            return self.handle_thread_id_key(key);
        }

        match key.code {
            KeyCode::Tab => {
                self.set_field_focus(self.next_focus());
                ModalAction::None
            }
            KeyCode::BackTab => {
                self.set_field_focus(self.prev_focus());
                ModalAction::None
            }
            KeyCode::Left | KeyCode::Right => {
                self.handle_option_switch(key.code);
                ModalAction::None
            }
            KeyCode::Enter => self.handle_enter(),
            KeyCode::Esc => ModalAction::Close,
            _ => ModalAction::None,
        }
    }

    fn next_focus(&self) -> FieldFocus {
        match self.field_focus() {
            FieldFocus::Type => FieldFocus::Room,
            FieldFocus::Room => {
                if is_open_chat(self.draft.room.as_ref()) {
                    FieldFocus::Thread
                } else {
                    FieldFocus::Content
                }
            }
            FieldFocus::Thread => {
                if self.draft.thread_mode == ThreadMode::Specified {
                    FieldFocus::ThreadId
                } else {
                    FieldFocus::Content
                }
            }
            FieldFocus::ThreadId => FieldFocus::Scope,
            FieldFocus::Scope | FieldFocus::Content => FieldFocus::Content, // Esc로만 탈출
        }
    }

    fn prev_focus(&self) -> FieldFocus {
        match self.field_focus() {
            FieldFocus::Type => FieldFocus::Type,
            FieldFocus::Room => FieldFocus::Type,
            FieldFocus::Thread => FieldFocus::Room,
            FieldFocus::ThreadId => FieldFocus::Thread,
            FieldFocus::Scope => FieldFocus::ThreadId,
            FieldFocus::Content => FieldFocus::Content,
        }
    }

    fn handle_option_switch(&mut self, code: KeyCode) {
        match self.field_focus() {
            FieldFocus::Type => self.cycle_reply_type(code),
            FieldFocus::Scope => {
                self.draft.scope = match code {
                    KeyCode::Right => self.draft.scope.next(),
                    KeyCode::Left => self.draft.scope.prev(),
                    _ => self.draft.scope,
                };
            }
            FieldFocus::Thread => {
                self.draft.thread_mode = match code {
                    KeyCode::Right | KeyCode::Left => match self.draft.thread_mode {
                        ThreadMode::None => ThreadMode::Specified,
                        ThreadMode::Specified => ThreadMode::None,
                    },
                    _ => self.draft.thread_mode,
                };
            }
            _ => {}
        }
    }

    fn cycle_reply_type(&mut self, code: KeyCode) {
        self.draft.reply_type = match code {
            KeyCode::Right => cycle_reply_type(self.draft.reply_type, true),
            KeyCode::Left => cycle_reply_type(self.draft.reply_type, false),
            _ => self.draft.reply_type,
        };
    }

    fn handle_enter(&mut self) -> ModalAction {
        match self.field_focus() {
            FieldFocus::Room => {
                self.set_overlay(OverlayState::RoomSelector);
                ModalAction::None
            }
            FieldFocus::Thread => {
                if self.draft.thread_mode == ThreadMode::Specified {
                    if let Some(chat_id) = self.draft.room.as_ref().map(|room| room.chat_id) {
                        self.set_overlay(OverlayState::ThreadSelector);
                        ModalAction::FetchThreads(chat_id)
                    } else {
                        ModalAction::None
                    }
                } else {
                    self.draft.thread_mode = ThreadMode::Specified;
                    self.set_field_focus(FieldFocus::ThreadId);
                    ModalAction::None
                }
            }
            _ => ModalAction::None,
        }
    }

    fn handle_content_key(&mut self, key: KeyEvent) -> ModalAction {
        if key.code == KeyCode::Esc {
            self.set_field_focus(FieldFocus::Type);
            return ModalAction::None;
        }
        match self.draft.reply_type {
            ReplyType::Text | ReplyType::Markdown => {
                self.draft.text_area.input(key);
            }
            ReplyType::Image => {
                self.draft.image_path.handle_key(key);
            }
            ReplyType::ImageMultiple => {
                self.handle_image_multiple_key(key);
            }
        }
        ModalAction::None
    }

    #[allow(clippy::cognitive_complexity)]
    fn handle_image_multiple_key(&mut self, key: KeyEvent) {
        if self.draft.image_editing {
            self.handle_image_editing_key(key);
            return;
        }
        self.handle_image_nav_key(key);
    }

    fn handle_image_editing_key(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Esc | KeyCode::Enter => {
                self.draft.image_editing = false;
                if let Some(p) = self
                    .draft
                    .image_paths
                    .get_mut(self.draft.image_paths_cursor)
                {
                    p.validate_file();
                }
            }
            _ => {
                if let Some(p) = self
                    .draft
                    .image_paths
                    .get_mut(self.draft.image_paths_cursor)
                {
                    p.handle_key(key);
                }
            }
        }
    }

    fn handle_image_nav_key(&mut self, key: KeyEvent) {
        if key.modifiers.contains(KeyModifiers::CONTROL) {
            match key.code {
                KeyCode::Char('a') => {
                    self.draft.image_paths.push(PathInput::new());
                    self.draft.image_paths_cursor = self.draft.image_paths.len() - 1;
                    self.draft.image_editing = true;
                }
                KeyCode::Char('d') => {
                    if self.draft.image_paths.len() > 1 {
                        self.draft.image_paths.remove(self.draft.image_paths_cursor);
                        if self.draft.image_paths_cursor >= self.draft.image_paths.len() {
                            self.draft.image_paths_cursor = self.draft.image_paths.len() - 1;
                        }
                    }
                }
                _ => {}
            }
            return;
        }

        match key.code {
            KeyCode::Up => {
                if self.draft.image_paths_cursor > 0 {
                    self.draft.image_paths_cursor -= 1;
                }
            }
            KeyCode::Down => {
                if self.draft.image_paths_cursor + 1 < self.draft.image_paths.len() {
                    self.draft.image_paths_cursor += 1;
                }
            }
            KeyCode::Enter => {
                self.draft.image_editing = true;
            }
            _ => {}
        }
    }

    fn handle_room_selector_key(&mut self, key: KeyEvent) -> ModalAction {
        match key.code {
            KeyCode::Up => {
                if self.room_selector_cursor > 0 {
                    self.room_selector_cursor -= 1;
                }
            }
            KeyCode::Down => {
                if self.room_selector_cursor + 1 < self.room_list.len() {
                    self.room_selector_cursor += 1;
                }
            }
            KeyCode::Enter => {
                if let Some(selected) = self.room_list.get(self.room_selector_cursor).cloned() {
                    let previous_chat_id = self.draft.room.as_ref().map(|room| room.chat_id);
                    let selected_is_open_chat = selected
                        .room_type
                        .as_deref()
                        .is_some_and(|t| t.starts_with('O'));
                    let room_changed = previous_chat_id != Some(selected.chat_id);

                    self.draft.room = Some(selected);

                    if room_changed || !selected_is_open_chat {
                        self.draft.clear_thread_state();
                        self.thread_suggestions.clear();
                        self.thread_selector_cursor = 0;
                    }
                }
                self.set_overlay(OverlayState::None);
                self.set_field_focus(FieldFocus::Room);
            }
            KeyCode::Esc => {
                self.set_overlay(OverlayState::None);
                self.set_field_focus(FieldFocus::Room);
            }
            _ => {}
        }
        ModalAction::None
    }

    fn handle_thread_selector_key(&mut self, key: KeyEvent) -> ModalAction {
        match key.code {
            KeyCode::Up => {
                if self.thread_selector_cursor > 0 {
                    self.thread_selector_cursor -= 1;
                }
            }
            KeyCode::Down => {
                if self.thread_selector_cursor + 1 < self.thread_suggestions.len() {
                    self.thread_selector_cursor += 1;
                }
            }
            KeyCode::Enter => {
                if let Some(selected) = self.thread_suggestions.get(self.thread_selector_cursor) {
                    self.draft.thread_id_input = selected.thread_id.clone();
                }
                self.set_overlay(OverlayState::None);
                self.set_field_focus(FieldFocus::ThreadId);
            }
            KeyCode::Esc => {
                self.set_overlay(OverlayState::None);
                self.set_field_focus(FieldFocus::Thread);
            }
            _ => {}
        }
        ModalAction::None
    }

    fn handle_thread_id_key(&mut self, key: KeyEvent) -> ModalAction {
        match key.code {
            KeyCode::Char(ch) if ch.is_ascii_digit() => {
                self.draft.thread_id_input.push(ch);
            }
            KeyCode::Backspace => {
                self.draft.thread_id_input.pop();
            }
            KeyCode::Tab => {
                self.set_field_focus(self.next_focus());
            }
            KeyCode::Esc => {
                self.set_field_focus(FieldFocus::Thread);
            }
            _ => {}
        }
        ModalAction::None
    }

    // ======================== Validation + Send ========================

    fn try_send(&mut self) -> ModalAction {
        self.ui.result = None;
        self.set_overlay(OverlayState::None);

        let Some(room_id) = self.draft.room.as_ref().map(|r| r.chat_id.to_string()) else {
            self.ui.result = Some(ReplyResult::Error {
                message: "room을 선택해주세요".to_string(),
            });
            self.set_field_focus(FieldFocus::Room);
            return ModalAction::None;
        };

        let (thread_id, thread_scope) = match self.validate_thread() {
            Ok(thread) => thread,
            Err(error) => {
                self.apply_validation_error(error);
                return ModalAction::None;
            }
        };

        let data = match self.build_data() {
            Ok(data) => data,
            Err(error) => {
                self.apply_validation_error(error);
                return ModalAction::None;
            }
        };

        self.ui.sending = true;

        ModalAction::Send(ReplyRequest {
            reply_type: self.draft.reply_type.clone(),
            room: room_id,
            data,
            thread_id,
            thread_scope,
        })
    }

    fn apply_validation_error(&mut self, error: ReplyValidationError) {
        let (message, next_focus) = validation_message_and_focus(error);
        self.ui.result = Some(ReplyResult::Error { message });
        self.set_overlay(OverlayState::None);
        self.set_field_focus(next_focus);
    }

    fn validate_thread(&self) -> Result<(Option<String>, Option<u8>), ReplyValidationError> {
        validate_thread(
            self.draft.thread_mode,
            &self.draft.thread_id_input,
            self.draft.scope,
        )
    }

    fn build_data(&self) -> Result<serde_json::Value, ReplyValidationError> {
        let text = self.draft.text_area.lines().join("\n");
        let image_paths: Vec<String> = self
            .draft
            .image_paths
            .iter()
            .map(|path| path.value.clone())
            .collect();
        build_data(
            self.draft.reply_type.clone(),
            &text,
            &self.draft.image_path.value,
            &image_paths,
        )
    }

    pub fn set_result(&mut self, result: ReplyResult) {
        self.ui.sending = false;
        if matches!(result, ReplyResult::Success { .. }) {
            self.draft.reset_after_success();
        }
        self.ui.result = Some(result);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};

    fn make_room(chat_id: i64, room_type: &str) -> RoomSummary {
        RoomSummary {
            chat_id,
            room_type: Some(room_type.to_string()),
            link_id: None,
            active_members_count: None,
            link_name: Some("room".to_string()),
            link_url: None,
            member_limit: None,
            searchable: None,
            bot_role: None,
        }
    }

    #[test]
    fn new_with_context_prefills_thread_id() {
        let room = RoomSummary {
            chat_id: 42,
            room_type: Some("OM".to_string()),
            link_id: None,
            active_members_count: None,
            link_name: Some("room".to_string()),
            link_url: None,
            member_limit: None,
            searchable: None,
            bot_role: None,
        };

        let modal =
            ReplyModal::new_with_context(Some(room.clone()), vec![room], Some("777".to_string()));

        assert_eq!(modal.thread_id_input(), "777");
        assert_eq!(modal.draft.thread_mode, ThreadMode::Specified);
    }

    #[test]
    fn new_with_context_aligns_room_selector_cursor_with_preselected_room() {
        let open_room = make_room(10, "OM");
        let regular_room = make_room(20, "DirectChat");
        let modal = ReplyModal::new_with_context(
            Some(regular_room.clone()),
            vec![open_room, regular_room],
            None,
        );

        assert_eq!(modal.room_selector_cursor, 1);
    }

    #[test]
    fn switching_to_non_open_chat_clears_thread_state() {
        let open_room = make_room(10, "OM");
        let regular_room = make_room(20, "DirectChat");
        let room_list = vec![open_room.clone(), regular_room];

        let mut modal = ReplyModal::new_with_context(
            Some(open_room),
            room_list.clone(),
            Some("999".to_string()),
        );
        modal.thread_suggestions = vec![ThreadSummary {
            thread_id: "999".to_string(),
            origin_message: Some("hello".to_string()),
            message_count: 2,
            last_active_at: None,
        }];
        modal.thread_selector_cursor = 1;

        modal.room_list = room_list;
        modal.room_selector_cursor = 1;
        modal.ui.overlay = OverlayState::RoomSelector;
        modal.ui.field_focus = FieldFocus::Room;
        modal.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));

        assert_eq!(modal.draft.thread_mode, ThreadMode::None);
        assert!(modal.thread_id_input().is_empty());
        assert!(modal.thread_suggestions.is_empty());
        assert_eq!(modal.thread_selector_cursor, 0);
    }

    #[test]
    fn switching_between_open_chats_clears_stale_thread_state() {
        let open_room_a = make_room(10, "OM");
        let open_room_b = make_room(20, "OM");
        let room_list = vec![open_room_a.clone(), open_room_b];

        let mut modal = ReplyModal::new_with_context(
            Some(open_room_a),
            room_list.clone(),
            Some("999".to_string()),
        );
        modal.thread_suggestions = vec![ThreadSummary {
            thread_id: "999".to_string(),
            origin_message: Some("hello".to_string()),
            message_count: 2,
            last_active_at: None,
        }];
        modal.thread_selector_cursor = 1;

        modal.room_list = room_list;
        modal.room_selector_cursor = 1;
        modal.ui.overlay = OverlayState::RoomSelector;
        modal.ui.field_focus = FieldFocus::Room;
        modal.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));

        assert_eq!(modal.draft.thread_mode, ThreadMode::None);
        assert!(modal.thread_id_input().is_empty());
        assert!(modal.thread_suggestions.is_empty());
        assert_eq!(modal.thread_selector_cursor, 0);
    }

    #[test]
    fn origin_message_truncation_is_char_safe() {
        // 멀티바이트 한글 문자 40자 초과 origin_message 처리
        // 한글 1자 = 3 bytes; 45자면 byte len=135, char len=45
        let long_korean: String = "가".repeat(45);
        let thread = ThreadSummary {
            thread_id: "1".to_string(),
            message_count: 3,
            origin_message: Some(long_korean),
            last_active_at: None,
        };

        // render_thread_selector 를 직접 호출할 수 없으므로
        // 트런케이션 로직만 인라인으로 검증
        let origin = thread.origin_message.as_deref().unwrap_or("(원본 없음)");
        let truncated = if origin.chars().count() > 40 {
            let cut: String = origin.chars().take(40).collect();
            format!("{cut}...")
        } else {
            origin.to_string()
        };

        assert_eq!(truncated.chars().count(), 43); // 40 chars + "..." (3 chars)
        assert!(truncated.ends_with("가..."));
    }

    #[test]
    fn thread_selector_does_not_fetch_threads_without_room_context() {
        let mut modal = ReplyModal::new_with_context(None, Vec::new(), None);
        modal.ui.field_focus = FieldFocus::Thread;
        modal.draft.thread_mode = ThreadMode::Specified;

        let action = modal.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));

        assert!(matches!(action, ModalAction::None));
    }

    #[test]
    fn room_selector_overlay_preserves_field_focus() {
        let room = make_room(10, "OM");
        let mut modal = ReplyModal::new_with_context(Some(room.clone()), vec![room], None);
        modal.ui.field_focus = FieldFocus::Room;
        modal.ui.overlay = OverlayState::RoomSelector;

        let action = modal.handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));

        assert!(matches!(action, ModalAction::None));
        assert_eq!(modal.ui.field_focus, FieldFocus::Room);
        assert_eq!(modal.ui.overlay, OverlayState::None);
    }

    #[test]
    fn set_result_success_resets_draft_but_keeps_ui_state() {
        let room = make_room(10, "OM");
        let mut modal =
            ReplyModal::new_with_context(Some(room), Vec::new(), Some("33".to_string()));
        modal.draft.text_area.insert_str("hello");
        modal.draft.image_path.value = "/tmp/example.png".to_string();
        modal.ui.field_focus = FieldFocus::Content;
        modal.ui.sending = true;

        modal.set_result(ReplyResult::Success {
            request_id: "req-1".to_string(),
        });

        assert!(modal.draft.text_area.lines().join("\n").is_empty());
        assert!(modal.draft.image_path.value.is_empty());
        assert_eq!(modal.ui.field_focus, FieldFocus::Content);
        assert!(!modal.ui.sending);
    }
}
