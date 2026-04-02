use crossterm::event::{Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use ratatui::Frame;
use ratatui::layout::{Constraint, Direction, Layout};

use crate::views::reply_modal::{ModalAction, ReplyModal};
use crate::views::{ReplyTarget, TabId, View, ViewAction, events, members, messages, rooms, stats};
use iris_common::models::{ReplyRequest, RoomEventRecord, SseEvent};
use ratatui::widgets::{Block, Tabs};

pub use crate::views::reply_modal::ReplyResult;

pub enum AppEvent {
    Terminal(Event),
    Server(SseEvent),
    EventHistoryLoaded(Vec<RoomEventRecord>),
}

pub struct App {
    pub active_tab: TabId,
    pub rooms_view: rooms::RoomsView,
    pub members_view: members::MembersView,
    pub stats_view: stats::StatsView,
    pub messages_view: messages::MessagesView,
    pub events_view: events::EventsView,
    pub status: String,
    pub reply_modal: Option<ReplyModal>,
    pub pending_reply: Option<ReplyRequest>,
    pub pending_thread_fetch: Option<i64>,
    pub pending_event_history_load: bool,
}

impl App {
    pub fn new() -> Self {
        Self {
            active_tab: TabId::Rooms,
            rooms_view: rooms::RoomsView::new(),
            members_view: members::MembersView::new(),
            stats_view: stats::StatsView::new(),
            messages_view: messages::MessagesView::new(),
            events_view: events::EventsView::new(),
            status: "Ready".to_string(),
            reply_modal: None,
            pending_reply: None,
            pending_thread_fetch: None,
            pending_event_history_load: false,
        }
    }
    pub fn render(&self, frame: &mut Frame<'_>) {
        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Length(3),
                Constraint::Min(0),
                Constraint::Length(1),
            ])
            .split(frame.area());
        let tabs = Tabs::new(TabId::all().iter().map(TabId::label))
            .block(Block::bordered().title(" Iris Control "))
            .select(self.active_tab.index())
            .highlight_style(ratatui::style::Style::default().yellow().bold());
        frame.render_widget(tabs, chunks[0]);
        match self.active_tab {
            TabId::Rooms => self.rooms_view.render(frame, chunks[1]),
            TabId::Members => self.members_view.render(frame, chunks[1]),
            TabId::Stats => self.stats_view.render(frame, chunks[1]),
            TabId::Messages => self.messages_view.render(frame, chunks[1]),
            TabId::Events => self.events_view.render(frame, chunks[1]),
        }
        frame.render_widget(
            ratatui::widgets::Paragraph::new(self.status.as_str())
                .style(ratatui::style::Style::default().dim()),
            chunks[2],
        );
        if let Some(modal) = &self.reply_modal {
            modal.render(frame);
        }
    }
    fn bind_room_context(&mut self, chat_id: i64) {
        self.members_view.set_chat_id(chat_id);
        self.stats_view.select_room(chat_id);
        self.messages_view.set_chat_id(chat_id);
    }

    fn has_event_history_target(&self) -> bool {
        self.rooms_view.selected_chat_id().is_some() || !self.rooms_view.rooms.is_empty()
    }

    fn maybe_queue_event_history_load(&mut self) {
        if matches!(self.active_tab, TabId::Events)
            && self.events_view.should_auto_load_history()
            && self.has_event_history_target()
        {
            self.pending_event_history_load = true;
        }
    }

    fn apply_action(&mut self, action: ViewAction) -> bool {
        match action {
            ViewAction::Quit => true,
            ViewAction::SelectRoom(id) => {
                self.bind_room_context(id);
                self.active_tab = TabId::Members;
                false
            }
            ViewAction::ShowRoomStats(id) => {
                self.bind_room_context(id);
                self.active_tab = TabId::Stats;
                false
            }
            ViewAction::SelectMember(chat_id, user_id) => {
                self.status = format!("Loading activity for user {user_id}...");
                self.stats_view.select_member(chat_id, user_id);
                self.active_tab = TabId::Stats;
                false
            }
            ViewAction::SwitchTo(tab) => {
                self.active_tab = tab;
                self.maybe_queue_event_history_load();
                false
            }
            ViewAction::Back => {
                self.active_tab = TabId::Rooms;
                false
            }
            ViewAction::OpenReply(target) => {
                self.open_reply_modal(target);
                false
            }
            ViewAction::LoadEventHistory => {
                if self.has_event_history_target() {
                    self.pending_event_history_load = true;
                    self.status = "Loading event history...".to_string();
                } else {
                    self.status = "Select a room first to load event history".to_string();
                }
                false
            }
            ViewAction::None => false,
        }
    }

    fn open_reply_modal(&mut self, target: ReplyTarget) {
        let room = target.chat_id.and_then(|id| {
            self.rooms_view
                .rooms
                .iter()
                .find(|r| r.chat_id == id)
                .cloned()
        });
        self.reply_modal = Some(ReplyModal::new_with_context(
            room,
            self.rooms_view.rooms.clone(),
            target.thread_id,
        ));
    }

    fn handle_modal_action(&mut self, key: KeyEvent) -> Option<bool> {
        let modal = self.reply_modal.as_mut()?;
        let action = modal.handle_key(key);
        Some(match action {
            ModalAction::Close => {
                self.reply_modal = None;
                false
            }
            ModalAction::Send(req) => {
                self.pending_reply = Some(req);
                false
            }
            ModalAction::FetchThreads(chat_id) => {
                self.pending_thread_fetch = Some(chat_id);
                false
            }
            ModalAction::None => false,
        })
    }

    fn handle_global_key(&mut self, key: KeyEvent) -> Option<bool> {
        if key.modifiers.contains(KeyModifiers::CONTROL) && matches!(key.code, KeyCode::Char('c')) {
            return Some(true);
        }
        match key.code {
            KeyCode::Char('q') => Some(true),
            KeyCode::Char('r') if !matches!(self.active_tab, TabId::Messages) => {
                Some(self.apply_action(ViewAction::OpenReply(ReplyTarget {
                    chat_id: self.reply_chat_id(),
                    thread_id: None,
                })))
            }
            KeyCode::Tab => {
                self.cycle_tab_forward();
                Some(false)
            }
            KeyCode::BackTab => {
                self.cycle_tab_backward();
                Some(false)
            }
            _ => None,
        }
    }

    fn reply_chat_id(&self) -> Option<i64> {
        match self.active_tab {
            TabId::Rooms => self.rooms_view.selected_chat_id(),
            TabId::Members | TabId::Stats => self.members_view.chat_id,
            TabId::Messages | TabId::Events => None,
        }
    }

    fn cycle_tab_forward(&mut self) {
        let tabs = TabId::all();
        self.active_tab = tabs[(self.active_tab.index() + 1) % tabs.len()];
        self.maybe_queue_event_history_load();
    }

    fn cycle_tab_backward(&mut self) {
        let tabs = TabId::all();
        self.active_tab = tabs[if self.active_tab.index() == 0 {
            tabs.len() - 1
        } else {
            self.active_tab.index() - 1
        }];
        self.maybe_queue_event_history_load();
    }

    fn dispatch_active_view_key(&mut self, key: KeyEvent) -> ViewAction {
        match self.active_tab {
            TabId::Rooms => self.rooms_view.handle_key(key),
            TabId::Members => self.members_view.handle_key(key),
            TabId::Stats => self.stats_view.handle_key(key),
            TabId::Messages => self.messages_view.handle_key(key),
            TabId::Events => self.events_view.handle_key(key),
        }
    }

    fn handle_key_event(&mut self, key: KeyEvent) -> bool {
        if key.kind != KeyEventKind::Press {
            return false;
        }
        if let Some(result) = self.handle_modal_action(key) {
            return result;
        }
        if let Some(result) = self.handle_global_key(key) {
            return result;
        }
        let action = self.dispatch_active_view_key(key);
        self.apply_action(action)
    }

    fn handle_terminal_event(&mut self, event: &Event) -> bool {
        match event {
            Event::Key(key) => self.handle_key_event(*key),
            _ => false,
        }
    }

    pub fn handle_app_event(&mut self, event: AppEvent) -> bool {
        match event {
            AppEvent::Terminal(event) => self.handle_terminal_event(&event),
            AppEvent::Server(sse) => {
                self.events_view.push_event(&sse);
                false
            }
            AppEvent::EventHistoryLoaded(records) => {
                self.events_view.push_history(&records);
                self.status = format!("Loaded {} event history records", records.len());
                false
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::Event;
    use iris_common::models::{RoomEventRecord, RoomSummary};

    fn sample_room(chat_id: i64) -> RoomSummary {
        RoomSummary {
            chat_id,
            room_type: Some("open".to_string()),
            link_id: None,
            active_members_count: Some(3),
            link_name: Some(format!("room-{chat_id}")),
            link_url: None,
            member_limit: None,
            searchable: None,
            bot_role: None,
        }
    }

    #[test]
    fn show_room_stats_binds_room_context_and_switches_tab() {
        let mut app = App::new();

        assert!(!app.apply_action(ViewAction::ShowRoomStats(42)));

        assert!(matches!(app.active_tab, TabId::Stats));
        assert_eq!(app.members_view.chat_id, Some(42));
        assert_eq!(app.stats_view.chat_id, Some(42));
        assert_eq!(app.stats_view.selected_member_id, None);
    }

    #[test]
    fn selecting_member_tracks_activity_target() {
        let mut app = App::new();
        app.members_view.chat_id = Some(42);

        assert!(!app.apply_action(ViewAction::SelectMember(42, 7)));

        assert!(matches!(app.active_tab, TabId::Stats));
        assert_eq!(app.stats_view.chat_id, Some(42));
        assert_eq!(app.stats_view.selected_member_id, Some(7));
    }

    #[test]
    fn ctrl_c_requests_quit() {
        let mut app = App::new();

        assert!(app.handle_key_event(KeyEvent::new(KeyCode::Char('c'), KeyModifiers::CONTROL,)));
    }

    #[test]
    fn app_event_routes_terminal_and_server_events() {
        let mut app = App::new();

        assert!(
            !app.handle_app_event(AppEvent::Terminal(Event::Key(KeyEvent::new(
                KeyCode::Tab,
                KeyModifiers::NONE
            ),)))
        );
        assert!(matches!(app.active_tab, TabId::Members));

        assert!(!app.handle_app_event(AppEvent::Server(SseEvent {
            event_type: "member_event".to_string(),
            event: Some("join".to_string()),
            chat_id: Some(1),
            user_id: Some(7),
            nickname: Some("alice".to_string()),
            old_nickname: None,
            new_nickname: None,
            old_role: None,
            new_role: None,
            estimated: None,
            timestamp: Some(0),
        })));
        assert_eq!(app.events_view.event_count(), 1);
    }

    #[test]
    fn messages_tab_reply_opens_modal_with_thread_prefilled() {
        let mut app = App::new();
        app.rooms_view
            .set_rooms(vec![sample_room(1)]);
        app.bind_room_context(1);
        app.active_tab = TabId::Messages;
        app.messages_view.set_messages(vec![messages::ChatMessage {
            id: 10,
            chat_id: 1,
            user_id: 100,
            message: "root".to_string(),
            msg_type: 1,
            created_at: 1000,
            thread_id: Some(10),
        }]);

        assert!(!app.handle_key_event(KeyEvent::new(KeyCode::Char('r'), KeyModifiers::NONE)));
        let modal = app.reply_modal.as_ref().expect("reply modal should open");
        assert_eq!(modal.thread_id_input, "10");
    }

    #[test]
    fn switching_to_events_marks_history_load_pending_when_room_exists() {
        let mut app = App::new();
        app.rooms_view.set_rooms(vec![sample_room(42)]);

        assert!(!app.apply_action(ViewAction::SwitchTo(TabId::Events)));

        assert!(matches!(app.active_tab, TabId::Events));
        assert!(app.pending_event_history_load);
    }

    #[test]
    fn switching_to_events_without_rooms_does_not_mark_history_load_pending() {
        let mut app = App::new();

        assert!(!app.apply_action(ViewAction::SwitchTo(TabId::Events)));

        assert!(matches!(app.active_tab, TabId::Events));
        assert!(!app.pending_event_history_load);
    }

    #[test]
    fn manual_event_history_load_without_room_sets_guidance_status() {
        let mut app = App::new();

        assert!(!app.apply_action(ViewAction::LoadEventHistory));

        assert!(!app.pending_event_history_load);
        assert_eq!(app.status, "Select a room first to load event history");
    }

    #[test]
    fn event_history_loaded_updates_view_and_status() {
        let mut app = App::new();

        assert!(
            !app.handle_app_event(AppEvent::EventHistoryLoaded(vec![RoomEventRecord {
                id: 1,
                chat_id: 1,
                event_type: "member_event".to_string(),
                user_id: 7,
                payload: r#"{"type":"member_event","event":"join","nickname":"alice"}"#.to_string(),
                created_at: 1_000,
            }]))
        );

        assert_eq!(app.events_view.event_count(), 1);
        assert_eq!(app.status, "Loaded 1 event history records");
    }
}
