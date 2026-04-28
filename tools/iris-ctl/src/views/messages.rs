use super::{ReplyTarget, View, ViewAction};
use super::{messages_formatter, messages_projection};
use crossterm::event::{KeyCode, KeyEvent};
use ratatui::Frame;
use ratatui::layout::Rect;
use ratatui::widgets::{Block, List, ListItem, ListState, Paragraph};
use std::collections::{HashMap, HashSet};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ChatMessage {
    pub id: i64,
    pub chat_id: i64,
    pub user_id: i64,
    pub message: String,
    pub msg_type: i32,
    pub created_at: i64,
    pub thread_id: Option<i64>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum VisibleRow {
    Message {
        index: usize,
        message_id: i64,
    },
    ThreadHeader {
        thread_id: i64,
        root_index: usize,
        message_id: i64,
    },
    ThreadChild {
        thread_id: i64,
        index: usize,
        message_id: i64,
    },
}

pub struct MessagesView {
    pub chat_id: Option<i64>,
    messages: Vec<ChatMessage>,
    state: ListState,
    expanded_threads: HashSet<i64>,
    nicknames: HashMap<i64, String>,
}

impl MessagesView {
    pub fn new() -> Self {
        Self {
            chat_id: None,
            messages: Vec::new(),
            state: ListState::default(),
            expanded_threads: HashSet::new(),
            nicknames: HashMap::new(),
        }
    }

    pub fn set_chat_id(&mut self, chat_id: i64) {
        self.chat_id = Some(chat_id);
        self.messages.clear();
        self.expanded_threads.clear();
        self.nicknames.clear();
        self.state.select(None);
    }

    pub fn clear(&mut self) {
        self.chat_id = None;
        self.messages.clear();
        self.expanded_threads.clear();
        self.nicknames.clear();
        self.state.select(None);
    }

    pub fn set_messages(&mut self, messages: Vec<ChatMessage>) {
        self.messages = messages;
        let len = self.visible_rows().len();
        if len == 0 {
            self.state.select(None);
        } else if let Some(prev) = self.state.selected() {
            // 선택 인덱스를 새 목록 크기에 맞게 클램프
            self.state.select(Some(prev.min(len - 1)));
        } else {
            self.state.select(Some(0));
        }
    }

    pub fn set_nicknames(&mut self, nicknames: HashMap<i64, String>) {
        self.nicknames = nicknames;
    }

    pub fn visible_rows(&self) -> Vec<VisibleRow> {
        messages_projection::visible_rows(&self.messages, &self.expanded_threads)
    }

    pub fn render_line(&self, row: &VisibleRow) -> String {
        messages_formatter::render_line(
            &self.messages,
            row,
            &self.nicknames,
            &self.expanded_threads,
        )
    }

    fn selected_target(&self) -> ReplyTarget {
        let rows = self.visible_rows();
        let selected = self.state.selected().and_then(|index| rows.get(index));
        match selected {
            Some(VisibleRow::Message { index, .. }) => ReplyTarget {
                chat_id: self.chat_id,
                thread_id: self.messages[*index].thread_id.map(|id| id.to_string()),
            },
            Some(
                VisibleRow::ThreadHeader { thread_id, .. }
                | VisibleRow::ThreadChild { thread_id, .. },
            ) => ReplyTarget {
                chat_id: self.chat_id,
                thread_id: Some(thread_id.to_string()),
            },
            None => ReplyTarget {
                chat_id: self.chat_id,
                thread_id: None,
            },
        }
    }

    fn move_selection(&mut self, delta: isize) {
        let len = self.visible_rows().len();
        if len == 0 {
            self.state.select(None);
            return;
        }
        let next = self
            .state
            .selected()
            .unwrap_or(0)
            .saturating_add_signed(delta)
            .min(len - 1);
        self.state.select(Some(next));
    }

    fn toggle_selected_thread(&mut self) {
        let rows = self.visible_rows();
        if let Some(VisibleRow::ThreadHeader { thread_id, .. }) =
            self.state.selected().and_then(|i| rows.get(i))
            && !self.expanded_threads.insert(*thread_id)
        {
            self.expanded_threads.remove(thread_id);
        }
    }
}

impl View for MessagesView {
    fn render(&self, frame: &mut Frame<'_>, area: Rect) {
        let block = Block::bordered().title(" Messages ");
        let inner = block.inner(area);
        frame.render_widget(block, area);

        if self.chat_id.is_none() {
            frame.render_widget(
                Paragraph::new("No room selected. Choose a room in Rooms tab."),
                inner,
            );
            return;
        }
        let rows = self.visible_rows();
        if rows.is_empty() {
            frame.render_widget(Paragraph::new("No messages"), inner);
            return;
        }
        let items: Vec<ListItem<'_>> = rows
            .iter()
            .map(|row| ListItem::new(self.render_line(row)))
            .collect();
        let list = List::new(items).highlight_style(ratatui::style::Style::default().reversed());
        frame.render_stateful_widget(list, inner, &mut self.state.clone());
    }

    fn handle_key(&mut self, key: KeyEvent) -> ViewAction {
        match key.code {
            KeyCode::Up => {
                self.move_selection(-1);
                ViewAction::None
            }
            KeyCode::Down => {
                self.move_selection(1);
                ViewAction::None
            }
            KeyCode::PageUp => {
                self.move_selection(-10);
                ViewAction::None
            }
            KeyCode::PageDown => {
                self.move_selection(10);
                ViewAction::None
            }
            KeyCode::Home => {
                if !self.visible_rows().is_empty() {
                    self.state.select(Some(0));
                }
                ViewAction::None
            }
            KeyCode::End => {
                let len = self.visible_rows().len();
                if len > 0 {
                    self.state.select(Some(len - 1));
                }
                ViewAction::None
            }
            KeyCode::Enter => {
                self.toggle_selected_thread();
                ViewAction::None
            }
            KeyCode::Char('r') => ViewAction::OpenReply(self.selected_target()),
            KeyCode::Esc => ViewAction::Back,
            _ => ViewAction::None,
        }
    }

    fn title(&self) -> &'static str {
        "Messages"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
    use std::collections::HashMap;

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn sample_messages() -> Vec<ChatMessage> {
        vec![
            ChatMessage {
                id: 10,
                chat_id: 1,
                user_id: 100,
                message: "root".to_string(),
                msg_type: 1,
                created_at: 1000,
                thread_id: Some(10),
            },
            ChatMessage {
                id: 11,
                chat_id: 1,
                user_id: 101,
                message: "child".to_string(),
                msg_type: 1,
                created_at: 1010,
                thread_id: Some(10),
            },
            ChatMessage {
                id: 12,
                chat_id: 1,
                user_id: 102,
                message: "plain".to_string(),
                msg_type: 1,
                created_at: 1020,
                thread_id: None,
            },
        ]
    }

    fn seeded_view() -> MessagesView {
        let mut view = MessagesView::new();
        view.chat_id = Some(1);
        view.set_messages(sample_messages());
        view
    }

    fn assert_collapsed_rows(rows: &[VisibleRow]) {
        assert_eq!(rows.len(), 2);
        assert!(matches!(
            rows[0],
            VisibleRow::ThreadHeader { thread_id: 10, .. }
        ));
        assert!(matches!(
            rows[1],
            VisibleRow::Message { message_id: 12, .. }
        ));
    }

    fn assert_expanded_rows(rows: &[VisibleRow]) {
        assert_eq!(rows.len(), 3);
        assert!(matches!(
            rows[1],
            VisibleRow::ThreadChild {
                thread_id: 10,
                message_id: 11,
                ..
            }
        ));
    }

    #[test]
    fn messages_view_flattens_threads_and_plain_messages() {
        let mut view = seeded_view();

        let rows = view.visible_rows();
        assert_collapsed_rows(&rows);

        view.handle_key(key(KeyCode::Enter));
        let expanded = view.visible_rows();
        assert_expanded_rows(&expanded);
    }

    #[test]
    fn messages_view_returns_reply_target_for_selected_child_row() {
        let mut view = MessagesView::new();
        view.chat_id = Some(1);
        view.set_messages(sample_messages());
        view.handle_key(key(KeyCode::Enter));
        view.handle_key(key(KeyCode::Down));

        let action = view.handle_key(key(KeyCode::Char('r')));
        match action {
            ViewAction::OpenReply(target) => {
                assert_eq!(target.chat_id, Some(1));
                assert_eq!(target.thread_id.as_deref(), Some("10"));
            }
            _ => panic!("expected OpenReply action"),
        }
    }

    #[test]
    fn set_messages_clamps_selection_when_list_shrinks() {
        let mut view = MessagesView::new();
        view.chat_id = Some(1);
        // 3개 메시지 로드 후 인덱스 2(마지막)를 선택
        view.set_messages(sample_messages());
        view.state.select(Some(1)); // 인덱스 1 선택 (2개 visible row 기준 마지막)

        // 1개 메시지로 축소
        view.set_messages(vec![ChatMessage {
            id: 99,
            chat_id: 1,
            user_id: 100,
            message: "only".to_string(),
            msg_type: 1,
            created_at: 2000,
            thread_id: None,
        }]);

        // 선택이 클램프되어 0이어야 함 (범위 초과 없음)
        assert_eq!(view.state.selected(), Some(0));
        // reply target이 thread_id None을 반환해야 함 (정상 범위이므로 crash 없음)
        let target = view.selected_target();
        assert_eq!(target.chat_id, Some(1));
    }

    #[test]
    fn switching_rooms_clears_stale_nickname_cache() {
        let mut view = MessagesView::new();
        view.set_chat_id(1);
        view.set_nicknames(HashMap::from([(100, "alice".to_string())]));
        view.set_messages(vec![ChatMessage {
            id: 1,
            chat_id: 1,
            user_id: 100,
            message: "hello".to_string(),
            msg_type: 1,
            created_at: 1000,
            thread_id: None,
        }]);

        view.set_chat_id(2);
        view.set_messages(vec![ChatMessage {
            id: 2,
            chat_id: 2,
            user_id: 100,
            message: "world".to_string(),
            msg_type: 1,
            created_at: 2000,
            thread_id: None,
        }]);

        let rows = view.visible_rows();
        let label = view.render_line(&rows[0]);
        assert!(label.starts_with("100: "));
    }

    #[test]
    fn reply_messages_render_as_reply_placeholder() {
        let mut view = MessagesView::new();
        view.chat_id = Some(1);
        view.set_nicknames(HashMap::from([(100, "alice".to_string())]));
        view.set_messages(vec![ChatMessage {
            id: 21,
            chat_id: 1,
            user_id: 100,
            message: "quoted other person's text".to_string(),
            msg_type: 26,
            created_at: 1000,
            thread_id: None,
        }]);

        let rows = view.visible_rows();
        let label = view.render_line(&rows[0]);
        assert!(label.contains("[reply]"));
        assert!(!label.contains("quoted other person's text"));
    }

    #[test]
    fn messages_view_formats_non_text_messages_as_placeholders() {
        let mut view = MessagesView::new();
        view.chat_id = Some(1);
        view.set_nicknames(HashMap::from([(100, "alice".to_string())]));
        view.set_messages(vec![ChatMessage {
            id: 20,
            chat_id: 1,
            user_id: 100,
            message: "ignored".to_string(),
            msg_type: 2,
            created_at: 1000,
            thread_id: None,
        }]);

        let rows = view.visible_rows();
        let label = view.render_line(&rows[0]);
        assert!(label.contains("[image]"));
        assert!(label.contains("alice"));
    }
}
