use crossterm::event::{KeyCode, KeyEvent};
use ratatui::Frame;
use ratatui::layout::{Rect, Constraint};
use ratatui::style::Stylize;
use ratatui::widgets::{Block, Row, Table, TableState, Cell};
use crate::models::MemberInfo;
use super::{View, ViewAction};

pub struct MembersView { pub chat_id: Option<i64>, pub members: Vec<MemberInfo>, state: TableState, search: String }

impl MembersView {
    pub fn new() -> Self { Self { chat_id: None, members: vec![], state: TableState::default(), search: String::new() } }
    pub fn set_chat_id(&mut self, chat_id: i64) { self.chat_id = Some(chat_id); self.members.clear(); self.state.select(None); }
    pub fn set_members(&mut self, members: Vec<MemberInfo>) {
        self.members = members;
        if !self.members.is_empty() && self.state.selected().is_none() { self.state.select(Some(0)); }
    }
    fn filtered(&self) -> Vec<&MemberInfo> {
        if self.search.is_empty() { self.members.iter().collect() }
        else { let q = self.search.to_lowercase(); self.members.iter().filter(|m| m.nickname.as_deref().unwrap_or("").to_lowercase().contains(&q)).collect() }
    }
}

impl View for MembersView {
    fn render(&self, frame: &mut Frame, area: Rect) {
        let filtered = self.filtered();
        let title = format!(" Members ({}) ", filtered.len());
        let header = Row::new(["#", "Nickname", "Role", "User ID"]).bold().bottom_margin(1);
        let rows: Vec<Row> = filtered.iter().enumerate().map(|(i, m)| {
            let rd = match m.role_code { 1 => "* owner", 4 => "# admin", 8 => "@ bot", _ => "  member" };
            Row::new([Cell::from(format!("{}", i+1)), Cell::from(m.nickname.as_deref().unwrap_or("?")),
                Cell::from(rd), Cell::from(m.user_id.to_string())])
        }).collect();
        let table = Table::new(rows, [Constraint::Length(4), Constraint::Min(20), Constraint::Length(12), Constraint::Length(22)])
            .header(header).block(Block::bordered().title(title))
            .row_highlight_style(ratatui::style::Style::default().reversed());
        frame.render_stateful_widget(table, area, &mut self.state.clone());
    }
    fn handle_key(&mut self, key: KeyEvent) -> ViewAction {
        match key.code {
            KeyCode::Up | KeyCode::Char('k') => { let len = self.filtered().len(); let i = self.state.selected().unwrap_or(0);
                self.state.select(Some(if i == 0 { len.saturating_sub(1) } else { i - 1 })); ViewAction::None }
            KeyCode::Down | KeyCode::Char('j') => { let len = self.filtered().len(); let i = self.state.selected().unwrap_or(0);
                self.state.select(Some(if i >= len.saturating_sub(1) { 0 } else { i + 1 })); ViewAction::None }
            KeyCode::Esc => ViewAction::Back,
            _ => ViewAction::None,
        }
    }
    fn title(&self) -> &str { "Members" }
}
