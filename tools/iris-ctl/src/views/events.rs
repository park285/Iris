use super::{View, ViewAction};
use crossterm::event::{KeyCode, KeyEvent};
use iris_common::models::SseEvent;
use ratatui::Frame;
use ratatui::layout::Rect;
use ratatui::widgets::{Block, List, ListItem, ListState};
use std::collections::VecDeque;

pub struct EventsView {
    events: VecDeque<String>,
    state: ListState,
    paused: bool,
    filter_active: bool,
}

impl EventsView {
    pub fn new() -> Self {
        Self {
            events: VecDeque::new(),
            state: ListState::default(),
            paused: false,
            filter_active: false,
        }
    }
    pub fn push_event(&mut self, event: SseEvent) {
        if self.paused {
            return;
        }
        let ts = event
            .timestamp
            .map(|t| {
                let s = t % 86400;
                format!("{:02}:{:02}:{:02}", s / 3600, (s % 3600) / 60, s % 60)
            })
            .unwrap_or_default();
        let line = match (event.event_type.as_str(), event.event.as_deref()) {
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
        };
        self.events.push_back(line);
        if self.events.len() > 500 {
            self.events.pop_front();
        }
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
            .map(|e| e.as_str())
            .collect()
    }

    #[cfg(test)]
    pub(crate) fn event_count(&self) -> usize {
        self.events.len()
    }
}

impl View for EventsView {
    fn render(&self, frame: &mut Frame, area: Rect) {
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
        let items: Vec<ListItem> = self
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
    fn title(&self) -> &str {
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
        view.push_event(member_event("join"));
        view.push_event(SseEvent {
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
            view.push_event(event);
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
}
