use crossterm::event::{self, Event, KeyCode, KeyEventKind};
use ratatui::Frame;
use ratatui::layout::{Constraint, Layout, Direction};

use ratatui::widgets::{Block, Tabs};
use std::time::Duration;
use tokio::sync::mpsc;
use crate::models::*;
use crate::views::*;

pub struct App {
    pub active_tab: TabId,
    pub rooms_view: rooms::RoomsView,
    pub members_view: members::MembersView,
    pub stats_view: stats::StatsView,
    pub events_view: events::EventsView,
    pub event_rx: mpsc::UnboundedReceiver<SseEvent>,
    pub status: String,
}

impl App {
    pub fn new(event_rx: mpsc::UnboundedReceiver<SseEvent>) -> Self {
        Self { active_tab: TabId::Rooms, rooms_view: rooms::RoomsView::new(), members_view: members::MembersView::new(),
            stats_view: stats::StatsView::new(), events_view: events::EventsView::new(), event_rx, status: "Ready".to_string() }
    }
    pub fn render(&self, frame: &mut Frame) {
        let chunks = Layout::default().direction(Direction::Vertical)
            .constraints([Constraint::Length(3), Constraint::Min(0), Constraint::Length(1)]).split(frame.area());
        let tabs = Tabs::new(TabId::all().iter().map(|t| t.label()))
            .block(Block::bordered().title(" Iris Control ")).select(self.active_tab.index())
            .highlight_style(ratatui::style::Style::default().yellow().bold());
        frame.render_widget(tabs, chunks[0]);
        match self.active_tab {
            TabId::Rooms => self.rooms_view.render(frame, chunks[1]),
            TabId::Members => self.members_view.render(frame, chunks[1]),
            TabId::Stats => self.stats_view.render(frame, chunks[1]),
            TabId::Events => self.events_view.render(frame, chunks[1]),
        }
        frame.render_widget(ratatui::widgets::Paragraph::new(self.status.as_str()).style(ratatui::style::Style::default().dim()), chunks[2]);
    }
    pub fn handle_event(&mut self) -> std::io::Result<bool> {
        while let Ok(sse) = self.event_rx.try_recv() { self.events_view.push_event(sse); }
        if event::poll(Duration::from_millis(100))? {
            if let Event::Key(key) = event::read()? {
                if key.kind != KeyEventKind::Press { return Ok(false); }
                match key.code {
                    KeyCode::Char('q') => return Ok(true),
                    KeyCode::Tab => { let tabs = TabId::all(); self.active_tab = tabs[(self.active_tab.index() + 1) % tabs.len()]; return Ok(false); }
                    KeyCode::BackTab => { let tabs = TabId::all(); self.active_tab = tabs[if self.active_tab.index() == 0 { tabs.len() - 1 } else { self.active_tab.index() - 1 }]; return Ok(false); }
                    _ => {}
                }
                let action = match self.active_tab {
                    TabId::Rooms => self.rooms_view.handle_key(key),
                    TabId::Members => self.members_view.handle_key(key),
                    TabId::Stats => self.stats_view.handle_key(key),
                    TabId::Events => self.events_view.handle_key(key),
                };
                match action {
                    ViewAction::Quit => return Ok(true),
                    ViewAction::SelectRoom(id) => {
                        self.members_view.set_chat_id(id);
                        self.stats_view.chat_id = Some(id);
                        self.stats_view.stats = None;
                        self.stats_view.room_info = None;
                        self.active_tab = TabId::Members;
                    }
                    ViewAction::SelectMember(chat_id, user_id) => {
                        self.status = format!("Loading activity for user {}...", user_id);
                        self.stats_view.chat_id = Some(chat_id);
                        self.active_tab = TabId::Stats;
                    }
                    ViewAction::SwitchTo(tab) => self.active_tab = tab,
                    ViewAction::Back => self.active_tab = TabId::Rooms,
                    _ => {}
                }
            }
        }
        Ok(false)
    }
}
