use crossterm::event::{KeyCode, KeyEvent};
use ratatui::Frame;
use ratatui::layout::Rect;
use ratatui::widgets::{Block, List, ListItem, ListState};
use crate::models::SseEvent;
use super::{View, ViewAction};

pub struct EventsView { events: Vec<String>, state: ListState, paused: bool }

impl EventsView {
    pub fn new() -> Self { Self { events: vec![], state: ListState::default(), paused: false } }
    pub fn push_event(&mut self, event: SseEvent) {
        if self.paused { return; }
        let ts = event.timestamp.map(|t| { let s = t % 86400; format!("{:02}:{:02}:{:02}", s/3600, (s%3600)/60, s%60) }).unwrap_or_default();
        let line = match (event.event_type.as_str(), event.event.as_deref()) {
            ("member_event", Some("join")) => format!("{} JOIN   {} entered", ts, event.nickname.as_deref().unwrap_or("?")),
            ("member_event", Some("leave")) => format!("{} LEAVE  {} left{}", ts, event.nickname.as_deref().unwrap_or("?"),
                if event.estimated == Some(true) { " (est)" } else { "" }),
            ("member_event", Some("kick")) => format!("{} KICK   {} kicked", ts, event.nickname.as_deref().unwrap_or("?")),
            ("nickname_change", _) => format!("{} NICK   {} -> {}", ts, event.old_nickname.as_deref().unwrap_or("?"), event.new_nickname.as_deref().unwrap_or("?")),
            ("role_change", _) => format!("{} ROLE   {} -> {}", ts, event.old_role.as_deref().unwrap_or("?"), event.new_role.as_deref().unwrap_or("?")),
            ("profile_change", _) => format!("{} PROF   user {} changed", ts, event.user_id.unwrap_or(0)),
            _ => format!("{} ???    {:?}", ts, event.event_type),
        };
        self.events.push(line);
        if self.events.len() > 500 { self.events.remove(0); }
        self.state.select(Some(self.events.len().saturating_sub(1)));
    }
}

impl View for EventsView {
    fn render(&self, frame: &mut Frame, area: Rect) {
        let title = if self.paused { " Events (PAUSED) " } else { " Events (LIVE) " };
        let items: Vec<ListItem> = self.events.iter().map(|e| ListItem::new(e.as_str())).collect();
        let list = List::new(items).block(Block::bordered().title(title))
            .highlight_style(ratatui::style::Style::default().reversed());
        frame.render_stateful_widget(list, area, &mut self.state.clone());
    }
    fn handle_key(&mut self, key: KeyEvent) -> ViewAction {
        match key.code {
            KeyCode::Char('p') => { self.paused = !self.paused; ViewAction::None }
            KeyCode::Char('c') => { self.events.clear(); ViewAction::None }
            KeyCode::Up | KeyCode::Char('k') => { let i = self.state.selected().unwrap_or(0); self.state.select(Some(i.saturating_sub(1))); ViewAction::None }
            KeyCode::Down | KeyCode::Char('j') => { let i = self.state.selected().unwrap_or(0);
                self.state.select(Some((i + 1).min(self.events.len().saturating_sub(1)))); ViewAction::None }
            KeyCode::Esc => ViewAction::Back, _ => ViewAction::None,
        }
    }
    fn title(&self) -> &str { "Events" }
}
