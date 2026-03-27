pub mod rooms;
pub mod members;
pub mod stats;
pub mod events;

use ratatui::Frame;
use crossterm::event::KeyEvent;

pub trait View {
    fn render(&self, frame: &mut Frame, area: ratatui::layout::Rect);
    fn handle_key(&mut self, key: KeyEvent) -> ViewAction;
    fn title(&self) -> &str;
}

pub enum ViewAction { None, SwitchTo(TabId), SelectRoom(i64), SelectMember(i64, i64), Quit, Back }

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum TabId { Rooms, Members, Stats, Events }

impl TabId {
    pub fn all() -> &'static [TabId] { &[TabId::Rooms, TabId::Members, TabId::Stats, TabId::Events] }
    pub fn label(&self) -> &str {
        match self { TabId::Rooms => "Rooms", TabId::Members => "Members", TabId::Stats => "Stats", TabId::Events => "Events" }
    }
    pub fn index(&self) -> usize {
        match self { TabId::Rooms => 0, TabId::Members => 1, TabId::Stats => 2, TabId::Events => 3 }
    }
}
