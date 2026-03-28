use super::{View, ViewAction};
use crossterm::event::{KeyCode, KeyEvent};
use iris_common::models::{MemberActivityResponse, RoomInfoResponse, StatsResponse};
use ratatui::Frame;
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::Stylize;
use ratatui::widgets::{BarChart, Block, Paragraph};

pub struct StatsView {
    pub chat_id: Option<i64>,
    pub stats: Option<StatsResponse>,
    pub room_info: Option<RoomInfoResponse>,
    pub member_activity: Option<MemberActivityResponse>,
    pub selected_member_id: Option<i64>,
    pub period: String,
}

impl StatsView {
    pub fn new() -> Self {
        Self {
            chat_id: None,
            stats: None,
            room_info: None,
            member_activity: None,
            selected_member_id: None,
            period: "7d".to_string(),
        }
    }
    pub fn select_room(&mut self, chat_id: i64) {
        self.chat_id = Some(chat_id);
        self.stats = None;
        self.room_info = None;
        self.member_activity = None;
        self.selected_member_id = None;
    }
    pub fn select_member(&mut self, chat_id: i64, user_id: i64) {
        self.chat_id = Some(chat_id);
        self.selected_member_id = Some(user_id);
        self.member_activity = None;
    }
    pub fn set_stats(&mut self, stats: StatsResponse) {
        self.stats = Some(stats);
    }
    pub fn set_room_info(&mut self, room_info: RoomInfoResponse) {
        self.room_info = Some(room_info);
    }
    pub fn set_member_activity(&mut self, activity: MemberActivityResponse) {
        self.member_activity = Some(activity);
    }
}

impl View for StatsView {
    fn render(&self, frame: &mut Frame, area: Rect) {
        let block = Block::bordered().title(format!(" Stats ({}) ", self.period));
        let inner = block.inner(area);
        frame.render_widget(block, area);
        if let Some(stats) = &self.stats {
            let info_lines = if let Some(info) = &self.room_info {
                vec![
                    format!(
                        "Room: {} notices | {} blinded | {} bot commands",
                        info.notices.len(),
                        info.blinded_member_ids.len(),
                        info.bot_commands.len()
                    ),
                    format!(
                        "Type: {} | Open link: {}",
                        info.room_type.as_deref().unwrap_or("?"),
                        info.open_link
                            .as_ref()
                            .and_then(|link| link.name.as_deref())
                            .unwrap_or("none")
                    ),
                ]
            } else {
                vec!["Room info unavailable".to_string()]
            };
            let chunks = Layout::default()
                .direction(Direction::Vertical)
                .constraints([
                    Constraint::Length((1 + info_lines.len()) as u16),
                    Constraint::Min(0),
                ])
                .split(inner);
            let mut summary_lines = vec![format!(
                "Total: {} msgs | Active: {} members",
                stats.total_messages, stats.active_members
            )];
            summary_lines.extend(info_lines);
            frame.render_widget(Paragraph::new(summary_lines.join("\n")).bold(), chunks[0]);
            if let Some(activity) = &self.member_activity {
                let active_hours = activity
                    .active_hours
                    .iter()
                    .enumerate()
                    .filter(|(_, count)| **count > 0)
                    .map(|(hour, count)| format!("{hour:02}={count}"))
                    .collect::<Vec<_>>();
                let mut message_types = activity
                    .message_types
                    .iter()
                    .map(|(kind, count)| format!("{kind}={count}"))
                    .collect::<Vec<_>>();
                message_types.sort();
                let details = [
                    format!(
                        "Member: {} ({})",
                        activity.nickname.as_deref().unwrap_or("?"),
                        activity.user_id
                    ),
                    format!(
                        "Msgs: {} | First: {} | Last: {}",
                        activity.message_count,
                        activity
                            .first_message_at
                            .map(|v| v.to_string())
                            .unwrap_or_else(|| "-".to_string()),
                        activity
                            .last_message_at
                            .map(|v| v.to_string())
                            .unwrap_or_else(|| "-".to_string()),
                    ),
                    format!(
                        "Types: {}",
                        if message_types.is_empty() {
                            "none".to_string()
                        } else {
                            message_types.join(", ")
                        }
                    ),
                    format!(
                        "Hours: {}",
                        if active_hours.is_empty() {
                            "none".to_string()
                        } else {
                            active_hours.join(", ")
                        }
                    ),
                ];
                frame.render_widget(Paragraph::new(details.join("\n")), chunks[1]);
            } else {
                let data: Vec<(String, u64)> = stats
                    .top_members
                    .iter()
                    .take(15)
                    .map(|m| {
                        (
                            m.nickname.as_deref().unwrap_or("?").to_string(),
                            m.message_count as u64,
                        )
                    })
                    .collect();
                let bar_data: Vec<(&str, u64)> =
                    data.iter().map(|(n, c)| (n.as_str(), *c)).collect();
                let chart = BarChart::default().data(&bar_data).bar_width(5).bar_gap(1);
                frame.render_widget(chart, chunks[1]);
            }
        } else {
            frame.render_widget(Paragraph::new("No data. Select a room first."), inner);
        }
    }
    fn handle_key(&mut self, key: KeyEvent) -> ViewAction {
        match key.code {
            KeyCode::Char('7') => {
                self.period = "7d".to_string();
                ViewAction::None
            }
            KeyCode::Char('3') => {
                self.period = "30d".to_string();
                ViewAction::None
            }
            KeyCode::Char('a') => {
                self.period = "all".to_string();
                ViewAction::None
            }
            KeyCode::Esc => ViewAction::Back,
            _ => ViewAction::None,
        }
    }
    fn title(&self) -> &str {
        "Stats"
    }
}
