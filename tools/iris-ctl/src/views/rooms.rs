use super::{View, ViewAction};
use iris_common::models::RoomSummary;
use crossterm::event::{KeyCode, KeyEvent};
use ratatui::Frame;
use ratatui::layout::{Constraint, Rect};
use ratatui::style::Stylize;
use ratatui::widgets::{Block, Cell, Row, Table, TableState};

pub struct RoomsView {
    pub rooms: Vec<RoomSummary>,
    state: TableState,
}

impl RoomsView {
    pub fn new() -> Self {
        Self {
            rooms: vec![],
            state: TableState::default(),
        }
    }
    pub fn set_rooms(&mut self, rooms: Vec<RoomSummary>) {
        let selected_chat_id = self.selected_chat_id();
        self.rooms = rooms;
        let next_selection = selected_chat_id
            .and_then(|chat_id| self.rooms.iter().position(|room| room.chat_id == chat_id))
            .or_else(|| (!self.rooms.is_empty()).then_some(0));
        self.state.select(next_selection);
    }
    pub fn selected_chat_id(&self) -> Option<i64> {
        self.state
            .selected()
            .and_then(|i| self.rooms.get(i))
            .map(|r| r.chat_id)
    }
}

impl View for RoomsView {
    fn render(&self, frame: &mut Frame, area: Rect) {
        let header = Row::new(["#", "Room", "Type", "Members", "Role", "Link"])
            .bold()
            .bottom_margin(1);
        let rows: Vec<Row> = self
            .rooms
            .iter()
            .enumerate()
            .map(|(i, r)| {
                Row::new([
                    Cell::from(format!("{}", i + 1)),
                    Cell::from(r.link_name.as_deref().unwrap_or("?")),
                    Cell::from(r.room_type.as_deref().unwrap_or("?")),
                    Cell::from(
                        r.active_members_count
                            .map(|c| c.to_string())
                            .unwrap_or_default(),
                    ),
                    Cell::from(r.role_name()),
                    Cell::from(r.link_id.map(|l| l.to_string()).unwrap_or_default()),
                ])
            })
            .collect();
        let table = Table::new(
            rows,
            [
                Constraint::Length(3),
                Constraint::Min(20),
                Constraint::Length(6),
                Constraint::Length(8),
                Constraint::Length(10),
                Constraint::Length(12),
            ],
        )
        .header(header)
        .block(Block::bordered().title(" Rooms "))
        .row_highlight_style(ratatui::style::Style::default().reversed());
        frame.render_stateful_widget(table, area, &mut self.state.clone());
    }
    fn handle_key(&mut self, key: KeyEvent) -> ViewAction {
        match key.code {
            KeyCode::Up | KeyCode::Char('k') => {
                let i = self.state.selected().unwrap_or(0);
                self.state.select(Some(if i == 0 {
                    self.rooms.len().saturating_sub(1)
                } else {
                    i - 1
                }));
                ViewAction::None
            }
            KeyCode::Down | KeyCode::Char('j') => {
                let i = self.state.selected().unwrap_or(0);
                self.state
                    .select(Some(if i >= self.rooms.len().saturating_sub(1) {
                        0
                    } else {
                        i + 1
                    }));
                ViewAction::None
            }
            KeyCode::Char('s') => {
                if let Some(chat_id) = self.selected_chat_id() {
                    ViewAction::ShowRoomStats(chat_id)
                } else {
                    ViewAction::None
                }
            }
            KeyCode::Char('i') => {
                if let Some(chat_id) = self.selected_chat_id() {
                    ViewAction::ShowRoomStats(chat_id)
                } else {
                    ViewAction::None
                }
            }
            KeyCode::Enter => {
                if let Some(id) = self.selected_chat_id() {
                    ViewAction::SelectRoom(id)
                } else {
                    ViewAction::None
                }
            }
            _ => ViewAction::None,
        }
    }
    fn title(&self) -> &str {
        "Rooms"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::KeyModifiers;

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    #[test]
    fn shortcuts_switch_to_stats_and_info_when_room_selected() {
        let mut view = RoomsView::new();
        view.set_rooms(vec![RoomSummary {
            chat_id: 42,
            room_type: Some("open".to_string()),
            link_id: None,
            active_members_count: Some(3),
            link_name: Some("room".to_string()),
            link_url: None,
            member_limit: None,
            searchable: None,
            bot_role: None,
        }]);

        assert!(matches!(
            view.handle_key(key(KeyCode::Char('s'))),
            ViewAction::ShowRoomStats(42)
        ));
        assert!(matches!(
            view.handle_key(key(KeyCode::Char('i'))),
            ViewAction::ShowRoomStats(42)
        ));
    }

    #[test]
    fn set_rooms_preserves_selected_chat_id_across_refresh() {
        let mut view = RoomsView::new();
        view.set_rooms(vec![
            RoomSummary {
                chat_id: 1,
                room_type: Some("open".to_string()),
                link_id: None,
                active_members_count: Some(3),
                link_name: Some("one".to_string()),
                link_url: None,
                member_limit: None,
                searchable: None,
                bot_role: None,
            },
            RoomSummary {
                chat_id: 2,
                room_type: Some("open".to_string()),
                link_id: None,
                active_members_count: Some(4),
                link_name: Some("two".to_string()),
                link_url: None,
                member_limit: None,
                searchable: None,
                bot_role: None,
            },
        ]);

        view.handle_key(key(KeyCode::Down));
        assert_eq!(view.selected_chat_id(), Some(2));

        view.set_rooms(vec![
            RoomSummary {
                chat_id: 2,
                room_type: Some("open".to_string()),
                link_id: None,
                active_members_count: Some(5),
                link_name: Some("two".to_string()),
                link_url: None,
                member_limit: None,
                searchable: None,
                bot_role: None,
            },
            RoomSummary {
                chat_id: 1,
                room_type: Some("open".to_string()),
                link_id: None,
                active_members_count: Some(3),
                link_name: Some("one".to_string()),
                link_url: None,
                member_limit: None,
                searchable: None,
                bot_role: None,
            },
        ]);

        assert_eq!(view.selected_chat_id(), Some(2));
    }
}
