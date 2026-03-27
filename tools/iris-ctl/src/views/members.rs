use crossterm::event::{KeyCode, KeyEvent};
use ratatui::Frame;
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::Stylize;
use ratatui::widgets::{Block, Cell, Paragraph, Row, Table, TableState};
use crate::models::MemberInfo;
use super::{View, ViewAction};

pub struct MembersView { pub chat_id: Option<i64>, pub members: Vec<MemberInfo>, state: TableState, search: String, searching: bool }

impl MembersView {
    pub fn new() -> Self { Self { chat_id: None, members: vec![], state: TableState::default(), search: String::new(), searching: false } }
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

#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::KeyModifiers;

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn member(user_id: i64, nickname: &str) -> MemberInfo {
        MemberInfo {
            user_id,
            nickname: Some(nickname.to_string()),
            role: "member".to_string(),
            role_code: 0,
            profile_image_url: None,
        }
    }

    #[test]
    fn slash_enters_search_mode_and_typing_filters_members() {
        let mut view = MembersView::new();
        view.set_members(vec![member(1, "alice"), member(2, "bob")]);

        assert!(matches!(view.handle_key(key(KeyCode::Char('/'))), ViewAction::None));
        assert!(view.searching);
        assert!(matches!(view.handle_key(key(KeyCode::Char('b'))), ViewAction::None));
        assert_eq!(view.search, "b");
        assert_eq!(view.filtered().len(), 1);
        assert_eq!(view.filtered()[0].user_id, 2);
    }

    #[test]
    fn escape_clears_search_and_enter_selects_member() {
        let mut view = MembersView::new();
        view.set_chat_id(77);
        view.set_members(vec![member(1, "alice"), member(2, "bob")]);

        view.handle_key(key(KeyCode::Char('/')));
        view.handle_key(key(KeyCode::Char('b')));
        assert!(view.searching);
        assert!(matches!(view.handle_key(key(KeyCode::Esc)), ViewAction::None));
        assert!(!view.searching);
        assert!(view.search.is_empty());

        view.handle_key(key(KeyCode::Down));
        assert!(matches!(view.handle_key(key(KeyCode::Enter)), ViewAction::SelectMember(77, 2)));
    }
}

impl View for MembersView {
    fn render(&self, frame: &mut Frame, area: Rect) {
        let (table_area, search_area) = if self.searching || !self.search.is_empty() {
            let chunks = Layout::default()
                .direction(Direction::Vertical)
                .constraints([Constraint::Min(0), Constraint::Length(1)])
                .split(area);
            (chunks[0], Some(chunks[1]))
        } else {
            (area, None)
        };
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
        frame.render_stateful_widget(table, table_area, &mut self.state.clone());
        if let Some(search_area) = search_area {
            let search_text = format!("/{}", self.search);
            frame.render_widget(Paragraph::new(search_text), search_area);
        }
    }
    fn handle_key(&mut self, key: KeyEvent) -> ViewAction {
        if self.searching {
            match key.code {
                KeyCode::Esc => { self.searching = false; self.search.clear(); self.state.select(Some(0)); ViewAction::None }
                KeyCode::Backspace => { self.search.pop(); self.state.select(Some(0)); ViewAction::None }
                KeyCode::Enter => { self.searching = false; ViewAction::None }
                KeyCode::Char(c) => { self.search.push(c); self.state.select(Some(0)); ViewAction::None }
                _ => ViewAction::None,
            }
        } else {
            match key.code {
                KeyCode::Up | KeyCode::Char('k') => { let len = self.filtered().len(); let i = self.state.selected().unwrap_or(0);
                    self.state.select(Some(if i == 0 { len.saturating_sub(1) } else { i - 1 })); ViewAction::None }
                KeyCode::Down | KeyCode::Char('j') => { let len = self.filtered().len(); let i = self.state.selected().unwrap_or(0);
                    self.state.select(Some(if i >= len.saturating_sub(1) { 0 } else { i + 1 })); ViewAction::None }
                KeyCode::Char('/') => { self.searching = true; ViewAction::None }
                KeyCode::Enter => {
                    if let Some(chat_id) = self.chat_id {
                        let filtered = self.filtered();
                        if let Some(member) = self.state.selected().and_then(|i| filtered.get(i)) {
                            return ViewAction::SelectMember(chat_id, member.user_id);
                        }
                    }
                    ViewAction::None
                }
                KeyCode::Esc => ViewAction::Back,
                _ => ViewAction::None,
            }
        }
    }
    fn title(&self) -> &str { "Members" }
}
