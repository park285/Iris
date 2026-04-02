use super::{View, ViewAction};
use crossterm::event::{KeyCode, KeyEvent};
use iris_common::models::{RoomEventRecord, SseEvent};
use ratatui::Frame;
use ratatui::layout::Rect;
use ratatui::widgets::{Block, List, ListItem, ListState};
use std::collections::VecDeque;

const MAX_EVENTS: usize = 500;

pub struct EventsView {
    events: VecDeque<String>,
    state: ListState,
    paused: bool,
    filter_active: bool,
    history_loaded: bool,
}

impl EventsView {
    pub fn new() -> Self {
        Self {
            events: VecDeque::new(),
            state: ListState::default(),
            paused: false,
            filter_active: false,
            history_loaded: false,
        }
    }

    pub fn push_event(&mut self, event: &SseEvent) {
        if self.paused {
            return;
        }
        self.events.push_back(format_event_line(event));
        self.trim_to_limit();
        self.select_last_visible();
    }

    pub fn push_history(&mut self, records: &[RoomEventRecord]) {
        let mut lines = Vec::new();
        for record in records {
            if let Ok(mut event) = serde_json::from_str::<SseEvent>(&record.payload) {
                event.timestamp = Some(record.created_at / 1000);
                event.chat_id = Some(record.chat_id);
                if event.user_id.is_none() {
                    event.user_id = Some(record.user_id);
                }
                lines.push(format_event_line(&event));
            }
        }

        for line in lines.into_iter().rev() {
            self.events.push_front(line);
        }
        self.trim_to_limit();
        self.history_loaded = true;
        if self.state.selected().is_none() {
            let len = self.visible_events().len();
            self.state.select(if len == 0 { None } else { Some(0) });
        }
    }

    pub const fn should_auto_load_history(&self) -> bool {
        !self.history_loaded
    }

    pub fn mark_history_unavailable(&mut self) {
        self.history_loaded = true;
    }

    fn trim_to_limit(&mut self) {
        while self.events.len() > MAX_EVENTS {
            self.events.pop_front();
        }
    }

    fn select_last_visible(&mut self) {
        self.state
            .select(Some(self.visible_events().len().saturating_sub(1)));
    }

    fn visible_events(&self) -> Vec<&str> {
        self.events
            .iter()
            .filter(|e| {
                !self.filter_active
                    || e.contains("JOIN")
                    || e.contains("LEAVE")
                    || e.contains("KICK")
                    || e.contains("NICK")
            })
            .map(String::as_str)
            .collect()
    }

    #[cfg(test)]
    pub(crate) fn event_count(&self) -> usize {
        self.events.len()
    }
}

fn format_event_line(event: &SseEvent) -> String {
    let ts = event
        .timestamp
        .map(|t| {
            let s = t % 86400;
            format!("{:02}:{:02}:{:02}", s / 3600, (s % 3600) / 60, s % 60)
        })
        .unwrap_or_default();
    match (event.event_type.as_str(), event.event.as_deref()) {
        ("member_event", Some("join")) => format!(
            "{} JOIN   {} entered",
            ts,
            event.nickname.as_deref().unwrap_or("?")
        ),
        ("member_event", Some("leave")) => format!(
            "{} LEAVE  {} left{}",
            ts,
            event.nickname.as_deref().unwrap_or("?"),
            if event.estimated == Some(true) {
                " (est)"
            } else {
                ""
            }
        ),
        ("member_event", Some("kick")) => format!(
            "{} KICK   {} kicked",
            ts,
            event.nickname.as_deref().unwrap_or("?")
        ),
        ("nickname_change", _) => format!(
            "{} NICK   {} -> {}",
            ts,
            event.old_nickname.as_deref().unwrap_or("?"),
            event.new_nickname.as_deref().unwrap_or("?")
        ),
        ("role_change", _) => format!(
            "{} ROLE   {} -> {}",
            ts,
            event.old_role.as_deref().unwrap_or("?"),
            event.new_role.as_deref().unwrap_or("?")
        ),
        ("profile_change", _) => {
            format!("{} PROF   user {} changed", ts, event.user_id.unwrap_or(0))
        }
        _ => format!("{} ???    {:?}", ts, event.event_type),
    }
}

impl View for EventsView {
    fn render(&self, frame: &mut Frame<'_>, area: Rect) {
        let title = if self.paused {
            if self.filter_active {
                " Events (PAUSED, FILTERED) "
            } else {
                " Events (PAUSED) "
            }
        } else if self.filter_active {
            " Events (LIVE, FILTERED) "
        } else {
            " Events (LIVE) "
        };
        let items: Vec<ListItem<'_>> = self
            .visible_events()
            .into_iter()
            .map(ListItem::new)
            .collect();
        let list = List::new(items)
            .block(Block::bordered().title(title))
            .highlight_style(ratatui::style::Style::default().reversed());
        frame.render_stateful_widget(list, area, &mut self.state.clone());
    }
    fn handle_key(&mut self, key: KeyEvent) -> ViewAction {
        match key.code {
            KeyCode::Char('p') => {
                self.paused = !self.paused;
                ViewAction::None
            }
            KeyCode::Char('c') => {
                self.events.clear();
                self.state.select(None);
                ViewAction::None
            }
            KeyCode::Char('f') => {
                self.filter_active = !self.filter_active;
                let len = self.visible_events().len();
                self.state.select(if len == 0 { None } else { Some(0) });
                ViewAction::None
            }
            KeyCode::Char('h') => ViewAction::LoadEventHistory,
            KeyCode::Up | KeyCode::Char('k') => {
                let i = self.state.selected().unwrap_or(0);
                self.state.select(Some(i.saturating_sub(1)));
                ViewAction::None
            }
            KeyCode::Down | KeyCode::Char('j') => {
                let i = self.state.selected().unwrap_or(0);
                self.state.select(Some(
                    (i + 1).min(self.visible_events().len().saturating_sub(1)),
                ));
                ViewAction::None
            }
            KeyCode::Esc => ViewAction::Back,
            _ => ViewAction::None,
        }
    }
    fn title(&self) -> &'static str {
        "Events"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::KeyModifiers;

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn member_event(event: &str) -> SseEvent {
        SseEvent {
            event_type: "member_event".to_string(),
            event: Some(event.to_string()),
            chat_id: Some(1),
            user_id: Some(2),
            nickname: Some("alice".to_string()),
            old_nickname: None,
            new_nickname: None,
            old_role: None,
            new_role: None,
            estimated: None,
            timestamp: Some(0),
        }
    }

    #[test]
    fn filter_toggle_limits_visible_events_to_member_changes() {
        let mut view = EventsView::new();
        view.push_event(&member_event("join"));
        view.push_event(&SseEvent {
            event_type: "profile_change".to_string(),
            event: None,
            chat_id: Some(1),
            user_id: Some(2),
            nickname: None,
            old_nickname: None,
            new_nickname: None,
            old_role: None,
            new_role: None,
            estimated: None,
            timestamp: Some(0),
        });

        assert_eq!(view.visible_events().len(), 2);
        view.handle_key(key(KeyCode::Char('f')));
        assert!(view.filter_active);
        assert_eq!(view.visible_events().len(), 1);
        assert!(view.visible_events()[0].contains("JOIN"));
    }

    #[test]
    fn event_buffer_keeps_latest_500_entries() {
        let mut view = EventsView::new();

        for idx in 0..505 {
            let mut event = member_event("join");
            event.timestamp = Some(idx);
            event.nickname = Some(format!("user-{idx}"));
            view.push_event(&event);
        }

        assert_eq!(view.visible_events().len(), 500);
        assert!(
            view.visible_events()
                .first()
                .is_some_and(|line| line.contains("user-5"))
        );
        assert!(
            view.visible_events()
                .last()
                .is_some_and(|line| line.contains("user-504"))
        );
    }

    #[test]
    fn history_records_are_prepended_in_time_order() {
        let mut view = EventsView::new();
        view.push_event(&member_event("join"));

        view.push_history(&[
            RoomEventRecord {
                id: 1,
                chat_id: 1,
                event_type: "member_event".to_string(),
                user_id: 2,
                payload: r#"{"type":"member_event","event":"join","nickname":"alice"}"#.to_string(),
                created_at: 1_000,
            },
            RoomEventRecord {
                id: 2,
                chat_id: 1,
                event_type: "member_event".to_string(),
                user_id: 3,
                payload: r#"{"type":"member_event","event":"leave","nickname":"bob"}"#.to_string(),
                created_at: 2_000,
            },
        ]);

        assert!(!view.should_auto_load_history());
        assert_eq!(view.visible_events().len(), 3);
        assert!(view.visible_events()[0].contains("alice"));
        assert!(view.visible_events()[1].contains("bob"));
    }

    #[test]
    fn history_key_requests_load() {
        let mut view = EventsView::new();

        assert!(matches!(
            view.handle_key(key(KeyCode::Char('h'))),
            ViewAction::LoadEventHistory
        ));
    }
}
