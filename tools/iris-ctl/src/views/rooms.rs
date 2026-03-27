use crossterm::event::{KeyCode, KeyEvent};
use ratatui::Frame;
use ratatui::layout::{Rect, Constraint};
use ratatui::style::Stylize;
use ratatui::widgets::{Block, Row, Table, TableState, Cell};
use crate::models::RoomSummary;
use super::{View, ViewAction};

pub struct RoomsView { pub rooms: Vec<RoomSummary>, state: TableState }

impl RoomsView {
    pub fn new() -> Self { Self { rooms: vec![], state: TableState::default() } }
    pub fn set_rooms(&mut self, rooms: Vec<RoomSummary>) {
        self.rooms = rooms;
        if !self.rooms.is_empty() && self.state.selected().is_none() { self.state.select(Some(0)); }
    }
    pub fn selected_chat_id(&self) -> Option<i64> {
        self.state.selected().and_then(|i| self.rooms.get(i)).map(|r| r.chat_id)
    }
}

impl View for RoomsView {
    fn render(&self, frame: &mut Frame, area: Rect) {
        let header = Row::new(["#", "Room", "Type", "Members", "Role", "Link"]).bold().bottom_margin(1);
        let rows: Vec<Row> = self.rooms.iter().enumerate().map(|(i, r)| {
            Row::new([Cell::from(format!("{}", i+1)), Cell::from(r.link_name.as_deref().unwrap_or("?")),
                Cell::from(r.room_type.as_deref().unwrap_or("?")),
                Cell::from(r.active_members_count.map(|c| c.to_string()).unwrap_or_default()),
                Cell::from(r.role_name()), Cell::from(r.link_id.map(|l| l.to_string()).unwrap_or_default())])
        }).collect();
        let table = Table::new(rows, [Constraint::Length(3), Constraint::Min(20), Constraint::Length(6),
            Constraint::Length(8), Constraint::Length(10), Constraint::Length(12)])
            .header(header).block(Block::bordered().title(" Rooms "))
            .row_highlight_style(ratatui::style::Style::default().reversed());
        frame.render_stateful_widget(table, area, &mut self.state.clone());
    }
    fn handle_key(&mut self, key: KeyEvent) -> ViewAction {
        match key.code {
            KeyCode::Up | KeyCode::Char('k') => { let i = self.state.selected().unwrap_or(0);
                self.state.select(Some(if i == 0 { self.rooms.len().saturating_sub(1) } else { i - 1 })); ViewAction::None }
            KeyCode::Down | KeyCode::Char('j') => { let i = self.state.selected().unwrap_or(0);
                self.state.select(Some(if i >= self.rooms.len().saturating_sub(1) { 0 } else { i + 1 })); ViewAction::None }
            KeyCode::Enter => { if let Some(id) = self.selected_chat_id() { ViewAction::SelectRoom(id) } else { ViewAction::None } }
            _ => ViewAction::None,
        }
    }
    fn title(&self) -> &str { "Rooms" }
}
