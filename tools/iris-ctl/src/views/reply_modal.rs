// Task 9 통합 전까지 dead_code 허용
#![allow(dead_code)]

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use iris_common::models::{ReplyRequest, ReplyType, RoomSummary, ThreadSummary};
use ratatui::Frame;
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, Clear, Paragraph, Wrap};
use tui_textarea::{Input, Key, TextArea};

use super::path_input::PathInput;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum ModalFocus {
    Type,
    Room,
    RoomSelector,
    Thread,
    ThreadId,
    ThreadSelector,
    Scope,
    Content,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum ThreadMode {
    None,
    Specified,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum ThreadScope {
    Thread, // 2
    Both,   // 3
    Room,   // 1
}

impl ThreadScope {
    const fn value(self) -> u8 {
        match self {
            Self::Thread => 2,
            Self::Both => 3,
            Self::Room => 1,
        }
    }

    const fn label(self) -> &'static str {
        match self {
            Self::Thread => "thread",
            Self::Both => "both",
            Self::Room => "room",
        }
    }

    const fn next(self) -> Self {
        match self {
            Self::Thread => Self::Both,
            Self::Both => Self::Room,
            Self::Room => Self::Thread,
        }
    }

    const fn prev(self) -> Self {
        match self {
            Self::Thread => Self::Room,
            Self::Room => Self::Both,
            Self::Both => Self::Thread,
        }
    }
}

#[derive(Clone)]
pub enum ReplyResult {
    Success { request_id: String },
    Error { message: String },
}

pub enum ModalAction {
    None,
    Close,
    Send(ReplyRequest),
    FetchThreads(i64),
}

pub struct ReplyModal {
    // 폼 필드
    reply_type: ReplyType,
    room: Option<RoomSummary>,
    thread_mode: ThreadMode,
    thread_id_input: String,
    scope: ThreadScope,

    // 콘텐츠
    text_area: TextArea<'static>,
    image_path: PathInput,
    image_paths: Vec<PathInput>,
    image_paths_cursor: usize,
    image_editing: bool,

    // UI 상태
    pub focus: ModalFocus,
    pub result: Option<ReplyResult>,
    pub sending: bool,

    // 선택기 데이터
    pub room_list: Vec<RoomSummary>,
    room_selector_cursor: usize,
    pub thread_suggestions: Vec<ThreadSummary>,
    thread_selector_cursor: usize,

    // 오픈채팅 여부
    is_open_chat: bool,
}

impl ReplyModal {
    pub fn new(room: Option<RoomSummary>, room_list: Vec<RoomSummary>) -> Self {
        let is_open_chat = room
            .as_ref()
            .and_then(|r| r.room_type.as_deref())
            .is_some_and(|t| t.starts_with('O'));

        let mut text_area = TextArea::default();
        text_area.set_placeholder_text("메시지를 입력하세요...");
        text_area.set_block(Block::default().borders(Borders::ALL).title(" Content "));

        let initial_focus = if room.is_none() {
            ModalFocus::Room
        } else {
            ModalFocus::Type
        };

        Self {
            reply_type: ReplyType::Text,
            room,
            thread_mode: ThreadMode::None,
            thread_id_input: String::new(),
            scope: ThreadScope::Thread,
            text_area,
            image_path: PathInput::new(),
            image_paths: vec![PathInput::new()],
            image_paths_cursor: 0,
            image_editing: false,
            focus: initial_focus,
            result: None,
            sending: false,
            room_list,
            room_selector_cursor: 0,
            thread_suggestions: Vec::new(),
            thread_selector_cursor: 0,
            is_open_chat,
        }
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

        let meta_height = if self.is_open_chat && self.thread_mode == ThreadMode::Specified {
            4
        } else if self.is_open_chat {
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

        if self.focus == ModalFocus::RoomSelector {
            self.render_room_selector(frame, inner);
        }
        if self.focus == ModalFocus::ThreadSelector {
            self.render_thread_selector(frame, inner);
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

        if self.is_open_chat && row_idx < rows.len() {
            frame.render_widget(Paragraph::new(self.render_thread_line()), rows[row_idx]);
            row_idx += 1;
        }

        if self.is_open_chat && self.thread_mode == ThreadMode::Specified && row_idx < rows.len() {
            frame.render_widget(Paragraph::new(self.render_scope_line()), rows[row_idx]);
        }
    }

    fn render_type_line(&self) -> Line<'static> {
        let focused = self.focus == ModalFocus::Type;
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
            let selected = std::mem::discriminant(rt) == std::mem::discriminant(&self.reply_type);
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
        let focused = self.focus == ModalFocus::Room;
        let label_style = if focused {
            Style::default()
                .fg(Color::Yellow)
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default().fg(Color::Gray)
        };
        let room_text = match &self.room {
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
        let focused = matches!(self.focus, ModalFocus::Thread | ModalFocus::ThreadId);
        let label_style = if focused {
            Style::default()
                .fg(Color::Yellow)
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default().fg(Color::Gray)
        };
        let thread_text = match self.thread_mode {
            ThreadMode::None => "없음 (Enter: 지정)".to_string(),
            ThreadMode::Specified => {
                if self.focus == ModalFocus::ThreadId {
                    format!("ID: {}█", self.thread_id_input)
                } else if self.thread_id_input.is_empty() {
                    "지정됨 (Enter: 선택/입력)".to_string()
                } else {
                    format!("ID: {}", self.thread_id_input)
                }
            }
        };
        Line::from(vec![
            Span::styled("  Thread  ", label_style),
            Span::styled(thread_text, Style::default().fg(Color::White)),
        ])
    }

    fn render_scope_line(&self) -> Line<'static> {
        let focused = self.focus == ModalFocus::Scope;
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
            let selected = *s == self.scope;
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
        match self.reply_type {
            ReplyType::Text | ReplyType::Markdown => {
                frame.render_widget(&self.text_area, area);
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

        self.image_path
            .render(frame, chunks[0], self.focus == ModalFocus::Content);
        if let Some(info) = &self.image_path.file_info {
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
            .image_paths
            .iter()
            .enumerate()
            .map(|(i, _)| {
                if i == self.image_paths_cursor && self.image_editing {
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

        for (i, path_input) in self.image_paths.iter().enumerate() {
            if i >= rows.len() - 1 {
                break;
            }
            let marker = if i == self.image_paths_cursor {
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

            let is_focused = self.focus == ModalFocus::Content
                && i == self.image_paths_cursor
                && self.image_editing;
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

        if self.sending {
            frame.render_widget(
                Paragraph::new("  ⟳ 전송 중...").style(Style::default().fg(Color::Cyan)),
                chunks[1],
            );
        } else if let Some(result) = &self.result {
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
                let truncated = if origin.len() > 40 {
                    format!("{}...", &origin[..40])
                } else {
                    origin.to_string()
                };
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
            if !self.sending {
                return self.try_send();
            }
            return ModalAction::None;
        }

        // Content 포커스: textarea/path가 키를 소비
        if self.focus == ModalFocus::Content {
            return self.handle_content_key(key);
        }

        // 선택기
        if self.focus == ModalFocus::RoomSelector {
            return self.handle_room_selector_key(key);
        }
        if self.focus == ModalFocus::ThreadSelector {
            return self.handle_thread_selector_key(key);
        }

        // ThreadId 입력
        if self.focus == ModalFocus::ThreadId {
            return self.handle_thread_id_key(key);
        }

        match key.code {
            KeyCode::Tab => {
                self.focus = self.next_focus();
                ModalAction::None
            }
            KeyCode::BackTab => {
                self.focus = self.prev_focus();
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

    fn next_focus(&self) -> ModalFocus {
        match self.focus {
            ModalFocus::Type => ModalFocus::Room,
            ModalFocus::Room => {
                if self.is_open_chat {
                    ModalFocus::Thread
                } else {
                    ModalFocus::Content
                }
            }
            ModalFocus::Thread => {
                if self.thread_mode == ThreadMode::Specified {
                    ModalFocus::ThreadId
                } else {
                    ModalFocus::Content
                }
            }
            ModalFocus::ThreadId => ModalFocus::Scope,
            ModalFocus::Scope | ModalFocus::Content => ModalFocus::Content, // Esc로만 탈출
            _ => self.focus,
        }
    }

    const fn prev_focus(&self) -> ModalFocus {
        match self.focus {
            ModalFocus::Room => ModalFocus::Type,
            ModalFocus::Thread => ModalFocus::Room,
            ModalFocus::ThreadId => ModalFocus::Thread,
            ModalFocus::Scope => ModalFocus::ThreadId,
            ModalFocus::Content => ModalFocus::Content,
            _ => self.focus,
        }
    }

    fn handle_option_switch(&mut self, code: KeyCode) {
        match self.focus {
            ModalFocus::Type => self.cycle_reply_type(code),
            ModalFocus::Scope => {
                self.scope = match code {
                    KeyCode::Right => self.scope.next(),
                    KeyCode::Left => self.scope.prev(),
                    _ => self.scope,
                };
            }
            ModalFocus::Thread => {
                self.thread_mode = match code {
                    KeyCode::Right | KeyCode::Left => match self.thread_mode {
                        ThreadMode::None => ThreadMode::Specified,
                        ThreadMode::Specified => ThreadMode::None,
                    },
                    _ => self.thread_mode,
                };
            }
            _ => {}
        }
    }

    fn cycle_reply_type(&mut self, code: KeyCode) {
        let types = [
            ReplyType::Text,
            ReplyType::Image,
            ReplyType::ImageMultiple,
            ReplyType::Markdown,
        ];
        let cur = types
            .iter()
            .position(|t| std::mem::discriminant(t) == std::mem::discriminant(&self.reply_type))
            .unwrap_or(0);
        let next = match code {
            KeyCode::Right => (cur + 1) % types.len(),
            KeyCode::Left => (cur + types.len() - 1) % types.len(),
            _ => cur,
        };
        self.reply_type = types[next].clone();
    }

    fn handle_enter(&mut self) -> ModalAction {
        match self.focus {
            ModalFocus::Room => {
                self.focus = ModalFocus::RoomSelector;
                ModalAction::None
            }
            ModalFocus::Thread => {
                if self.thread_mode == ThreadMode::Specified {
                    self.focus = ModalFocus::ThreadSelector;
                    ModalAction::FetchThreads(self.room.as_ref().map_or(0, |r| r.chat_id))
                } else {
                    self.thread_mode = ThreadMode::Specified;
                    self.focus = ModalFocus::ThreadId;
                    ModalAction::None
                }
            }
            _ => ModalAction::None,
        }
    }

    fn handle_content_key(&mut self, key: KeyEvent) -> ModalAction {
        if key.code == KeyCode::Esc {
            self.focus = ModalFocus::Type;
            return ModalAction::None;
        }
        match self.reply_type {
            ReplyType::Text | ReplyType::Markdown => {
                self.text_area.input(crossterm_key_to_input(key));
            }
            ReplyType::Image => {
                self.image_path.handle_key(key);
            }
            ReplyType::ImageMultiple => {
                self.handle_image_multiple_key(key);
            }
        }
        ModalAction::None
    }

    #[allow(clippy::cognitive_complexity)]
    fn handle_image_multiple_key(&mut self, key: KeyEvent) {
        if self.image_editing {
            self.handle_image_editing_key(key);
            return;
        }
        self.handle_image_nav_key(key);
    }

    fn handle_image_editing_key(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Esc | KeyCode::Enter => {
                self.image_editing = false;
                if let Some(p) = self.image_paths.get_mut(self.image_paths_cursor) {
                    p.validate_file();
                }
            }
            _ => {
                if let Some(p) = self.image_paths.get_mut(self.image_paths_cursor) {
                    p.handle_key(key);
                }
            }
        }
    }

    fn handle_image_nav_key(&mut self, key: KeyEvent) {
        if key.modifiers.contains(KeyModifiers::CONTROL) {
            match key.code {
                KeyCode::Char('a') => {
                    self.image_paths.push(PathInput::new());
                    self.image_paths_cursor = self.image_paths.len() - 1;
                    self.image_editing = true;
                }
                KeyCode::Char('d') => {
                    if self.image_paths.len() > 1 {
                        self.image_paths.remove(self.image_paths_cursor);
                        if self.image_paths_cursor >= self.image_paths.len() {
                            self.image_paths_cursor = self.image_paths.len() - 1;
                        }
                    }
                }
                _ => {}
            }
            return;
        }

        match key.code {
            KeyCode::Up => {
                if self.image_paths_cursor > 0 {
                    self.image_paths_cursor -= 1;
                }
            }
            KeyCode::Down => {
                if self.image_paths_cursor + 1 < self.image_paths.len() {
                    self.image_paths_cursor += 1;
                }
            }
            KeyCode::Enter => {
                self.image_editing = true;
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
                    self.is_open_chat = selected
                        .room_type
                        .as_deref()
                        .is_some_and(|t| t.starts_with('O'));
                    self.room = Some(selected);
                    if !self.is_open_chat {
                        self.thread_mode = ThreadMode::None;
                    }
                }
                self.focus = ModalFocus::Room;
            }
            KeyCode::Esc => {
                self.focus = ModalFocus::Room;
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
                    self.thread_id_input = selected.thread_id.clone();
                }
                self.focus = ModalFocus::ThreadId;
            }
            KeyCode::Esc => {
                self.focus = ModalFocus::Thread;
            }
            _ => {}
        }
        ModalAction::None
    }

    fn handle_thread_id_key(&mut self, key: KeyEvent) -> ModalAction {
        match key.code {
            KeyCode::Char(ch) if ch.is_ascii_digit() => {
                self.thread_id_input.push(ch);
            }
            KeyCode::Backspace => {
                self.thread_id_input.pop();
            }
            KeyCode::Tab => {
                self.focus = self.next_focus();
            }
            KeyCode::Esc => {
                self.focus = ModalFocus::Thread;
            }
            _ => {}
        }
        ModalAction::None
    }

    // ======================== Validation + Send ========================

    fn try_send(&mut self) -> ModalAction {
        self.result = None;

        let Some(room_id) = self.room.as_ref().map(|r| r.chat_id.to_string()) else {
            self.result = Some(ReplyResult::Error {
                message: "room을 선택해주세요".to_string(),
            });
            self.focus = ModalFocus::Room;
            return ModalAction::None;
        };

        let Ok((thread_id, thread_scope)) = self.validate_thread() else {
            return ModalAction::None;
        };

        let Ok(data) = self.build_data() else {
            return ModalAction::None;
        };

        self.sending = true;

        ModalAction::Send(ReplyRequest {
            reply_type: self.reply_type.clone(),
            room: room_id,
            data,
            thread_id,
            thread_scope,
        })
    }

    #[allow(clippy::type_complexity)]
    fn validate_thread(&mut self) -> Result<(Option<String>, Option<u8>), ()> {
        match self.thread_mode {
            ThreadMode::None => Ok((None, None)),
            ThreadMode::Specified => {
                if self.thread_id_input.is_empty() {
                    self.result = Some(ReplyResult::Error {
                        message: "threadId를 입력해주세요".to_string(),
                    });
                    self.focus = ModalFocus::ThreadId;
                    return Err(());
                }
                if self.thread_id_input.parse::<i64>().is_err() {
                    self.result = Some(ReplyResult::Error {
                        message: "threadId는 숫자여야 합니다".to_string(),
                    });
                    self.focus = ModalFocus::ThreadId;
                    return Err(());
                }
                Ok((Some(self.thread_id_input.clone()), Some(self.scope.value())))
            }
        }
    }

    fn build_data(&mut self) -> Result<serde_json::Value, ()> {
        match &self.reply_type {
            ReplyType::Text | ReplyType::Markdown => {
                let text = self.text_area.lines().join("\n");
                if text.trim().is_empty() {
                    self.result = Some(ReplyResult::Error {
                        message: "메시지를 입력해주세요".to_string(),
                    });
                    return Err(());
                }
                Ok(serde_json::Value::String(text))
            }
            ReplyType::Image => {
                if self.image_path.value.is_empty() {
                    self.result = Some(ReplyResult::Error {
                        message: "이미지 경로를 입력해주세요".to_string(),
                    });
                    return Err(());
                }
                Ok(serde_json::Value::String(self.image_path.value.clone()))
            }
            ReplyType::ImageMultiple => {
                let paths: Vec<String> = self
                    .image_paths
                    .iter()
                    .filter(|p| !p.value.is_empty())
                    .map(|p| p.value.clone())
                    .collect();
                if paths.is_empty() {
                    self.result = Some(ReplyResult::Error {
                        message: "이미지 경로를 하나 이상 입력해주세요".to_string(),
                    });
                    return Err(());
                }
                Ok(serde_json::Value::Array(
                    paths.into_iter().map(serde_json::Value::String).collect(),
                ))
            }
        }
    }

    pub fn set_result(&mut self, result: ReplyResult) {
        self.sending = false;
        if matches!(result, ReplyResult::Success { .. }) {
            self.text_area = TextArea::default();
            self.text_area
                .set_placeholder_text("메시지를 입력하세요...");
            self.text_area
                .set_block(Block::default().borders(Borders::ALL).title(" Content "));
            self.image_path.clear();
            self.image_paths = vec![PathInput::new()];
            self.image_paths_cursor = 0;
        }
        self.result = Some(result);
    }
}

/// crossterm `KeyEvent` → `tui_textarea` `Input` 변환 (크레이트 버전 차이 우회)
const fn crossterm_key_to_input(key: KeyEvent) -> Input {
    let ctrl = key.modifiers.contains(KeyModifiers::CONTROL);
    let alt = key.modifiers.contains(KeyModifiers::ALT);
    let shift = key.modifiers.contains(KeyModifiers::SHIFT);
    let tui_key = match key.code {
        KeyCode::Char(c) => Key::Char(c),
        KeyCode::Backspace => Key::Backspace,
        KeyCode::Enter => Key::Enter,
        KeyCode::Left => Key::Left,
        KeyCode::Right => Key::Right,
        KeyCode::Up => Key::Up,
        KeyCode::Down => Key::Down,
        KeyCode::Tab => Key::Tab,
        KeyCode::Delete => Key::Delete,
        KeyCode::Home => Key::Home,
        KeyCode::End => Key::End,
        KeyCode::PageUp => Key::PageUp,
        KeyCode::PageDown => Key::PageDown,
        KeyCode::Esc => Key::Esc,
        KeyCode::F(n) => Key::F(n),
        _ => Key::Null,
    };
    Input {
        key: tui_key,
        ctrl,
        alt,
        shift,
    }
}

fn centered_rect(area: Rect, percent_x: u16, percent_y: u16) -> Rect {
    let vertical = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Percentage((100 - percent_y) / 2),
            Constraint::Percentage(percent_y),
            Constraint::Percentage((100 - percent_y) / 2),
        ])
        .split(area);
    Layout::default()
        .direction(Direction::Horizontal)
        .constraints([
            Constraint::Percentage((100 - percent_x) / 2),
            Constraint::Percentage(percent_x),
            Constraint::Percentage((100 - percent_x) / 2),
        ])
        .split(vertical[1])[1]
}
