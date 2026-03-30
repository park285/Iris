pub mod events;
pub mod members;
pub mod messages;
pub mod path_input;
pub mod reply_modal;
pub mod rooms;
pub mod stats;

use crossterm::event::KeyEvent;
use ratatui::Frame;

#[allow(dead_code)]
pub trait View {
    fn render(&self, frame: &mut Frame<'_>, area: ratatui::layout::Rect);
    fn handle_key(&mut self, key: KeyEvent) -> ViewAction;
    fn title(&self) -> &'static str;
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ReplyTarget {
    pub chat_id: Option<i64>,
    pub thread_id: Option<String>,
}

#[allow(dead_code)]
#[derive(Clone)]
pub enum ViewAction {
    None,
    SwitchTo(TabId),
    SelectRoom(i64),
    ShowRoomStats(i64),
    SelectMember(i64, i64),
    Quit,
    Back,
    OpenReply(ReplyTarget),
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum TabId {
    Rooms,
    Members,
    Stats,
    Messages,
    Events,
}

impl TabId {
    pub const fn all() -> &'static [Self] {
        &[
            Self::Rooms,
            Self::Members,
            Self::Stats,
            Self::Messages,
            Self::Events,
        ]
    }
    pub const fn label(&self) -> &str {
        match self {
            Self::Rooms => "Rooms",
            Self::Members => "Members",
            Self::Stats => "Stats",
            Self::Messages => "Messages",
            Self::Events => "Events",
        }
    }
    pub const fn index(self) -> usize {
        match self {
            Self::Rooms => 0,
            Self::Members => 1,
            Self::Stats => 2,
            Self::Messages => 3,
            Self::Events => 4,
        }
    }
}
