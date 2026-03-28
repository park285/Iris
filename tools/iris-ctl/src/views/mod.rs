pub mod events;
pub mod members;
pub mod rooms;
pub mod stats;

use crossterm::event::KeyEvent;
use ratatui::Frame;

#[allow(dead_code)]
pub trait View {
    fn render(&self, frame: &mut Frame<'_>, area: ratatui::layout::Rect);
    fn handle_key(&mut self, key: KeyEvent) -> ViewAction;
    fn title(&self) -> &str;
}

#[allow(dead_code)]
#[derive(Clone, Copy)]
pub enum ViewAction {
    None,
    SwitchTo(TabId),
    SelectRoom(i64),
    ShowRoomStats(i64),
    SelectMember(i64, i64),
    Quit,
    Back,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum TabId {
    Rooms,
    Members,
    Stats,
    Events,
}

impl TabId {
    pub const fn all() -> &'static [Self] {
        &[Self::Rooms, Self::Members, Self::Stats, Self::Events]
    }
    pub const fn label(&self) -> &str {
        match self {
            Self::Rooms => "Rooms",
            Self::Members => "Members",
            Self::Stats => "Stats",
            Self::Events => "Events",
        }
    }
    pub const fn index(self) -> usize {
        match self {
            Self::Rooms => 0,
            Self::Members => 1,
            Self::Stats => 2,
            Self::Events => 3,
        }
    }
}
