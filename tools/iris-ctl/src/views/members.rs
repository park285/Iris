use super::{View, ViewAction};
use super::{members_projection, members_render};
use crossterm::event::{KeyCode, KeyEvent};
use iris_common::models::MemberInfo;
use ratatui::Frame;
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::Stylize;
use ratatui::widgets::{Block, Paragraph, Row, Table, TableState};

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum RoleFilter {
    All,
    Owners,
    Admins,
    Bots,
    Members,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum SortMode {
    Activity,
    Name,
}

pub struct MembersView {
    pub chat_id: Option<i64>,
    pub members: Vec<MemberInfo>,
    state: TableState,
    search: String,
    searching: bool,
    role_filter: RoleFilter,
    sort_mode: SortMode,
    filtered_indices: Vec<usize>,
}

impl MembersView {
    pub fn new() -> Self {
        Self {
            chat_id: None,
            members: vec![],
            state: TableState::default(),
            search: String::new(),
            searching: false,
            role_filter: RoleFilter::All,
            sort_mode: SortMode::Activity,
            filtered_indices: vec![],
        }
    }

    pub fn set_chat_id(&mut self, chat_id: i64) {
        self.chat_id = Some(chat_id);
        self.members.clear();
        self.filtered_indices.clear();
        self.state.select(None);
    }

    pub fn set_members(&mut self, members: Vec<MemberInfo>) {
        let selected_user_id = self.selected_user_id();
        self.members = members;
        self.rebuild_filtered_cache();
        self.restore_selection(selected_user_id);
    }

    fn rebuild_filtered_cache(&mut self) {
        self.filtered_indices = members_projection::rebuild_filtered_indices(
            &self.members,
            &self.search,
            self.role_filter,
            self.sort_mode,
        );
    }

    fn restore_selection(&mut self, selected_user_id: Option<i64>) {
        let next_selection = selected_user_id
            .and_then(|user_id| {
                self.filtered_indices
                    .iter()
                    .position(|&index| self.members[index].user_id == user_id)
            })
            .or_else(|| (!self.filtered_indices.is_empty()).then_some(0));
        self.state.select(next_selection);
    }

    fn refresh_filtered_cache(&mut self) {
        self.rebuild_filtered_cache();
        self.state
            .select((!self.filtered_indices.is_empty()).then_some(0));
    }

    fn filtered_member(&self, index: usize) -> Option<&MemberInfo> {
        self.filtered_indices
            .get(index)
            .and_then(|&member_index| self.members.get(member_index))
    }

    fn filtered_len(&self) -> usize {
        self.filtered_indices.len()
    }

    fn filtered(&self) -> Vec<&MemberInfo> {
        self.filtered_indices
            .iter()
            .filter_map(|&index| self.members.get(index))
            .collect()
    }

    fn selected_user_id(&self) -> Option<i64> {
        self.state
            .selected()
            .and_then(|index| self.filtered_member(index))
            .map(|member| member.user_id)
    }

    fn render_title(&self, filtered_count: usize) -> String {
        members_render::render_title(filtered_count, self.role_filter, self.sort_mode)
    }

    fn render_rows<'a>(filtered: &[&'a MemberInfo]) -> Vec<Row<'a>> {
        members_render::render_rows(filtered)
    }

    fn render_table(&self, frame: &mut Frame<'_>, area: Rect, filtered: &[&MemberInfo]) {
        let header = Row::new(["#", "Nickname", "Role", "Msgs", "Last Active", "User ID"])
            .bold()
            .bottom_margin(1);
        let table = Table::new(
            Self::render_rows(filtered),
            [
                Constraint::Length(4),
                Constraint::Min(18),
                Constraint::Length(12),
                Constraint::Length(8),
                Constraint::Length(12),
                Constraint::Length(22),
            ],
        )
        .header(header)
        .block(Block::bordered().title(self.render_title(filtered.len())))
        .row_highlight_style(ratatui::style::Style::default().reversed());
        frame.render_stateful_widget(table, area, &mut self.state.clone());
    }

    fn search_layout(&self, area: Rect) -> (Rect, Option<Rect>) {
        if self.searching || !self.search.is_empty() {
            let chunks = Layout::default()
                .direction(Direction::Vertical)
                .constraints([Constraint::Min(0), Constraint::Length(1)])
                .split(area);
            (chunks[0], Some(chunks[1]))
        } else {
            (area, None)
        }
    }

    fn handle_search_key(&mut self, key: KeyEvent) -> ViewAction {
        match key.code {
            KeyCode::Esc => {
                self.searching = false;
                self.search.clear();
                self.refresh_filtered_cache();
                ViewAction::None
            }
            KeyCode::Backspace => {
                self.search.pop();
                self.refresh_filtered_cache();
                ViewAction::None
            }
            KeyCode::Enter => {
                self.searching = false;
                ViewAction::None
            }
            KeyCode::Char(c) => {
                self.search.push(c);
                self.refresh_filtered_cache();
                ViewAction::None
            }
            _ => ViewAction::None,
        }
    }

    fn move_selection_up(&mut self) {
        let len = self.filtered_len();
        let index = self.state.selected().unwrap_or(0);
        self.state.select(Some(if index == 0 {
            len.saturating_sub(1)
        } else {
            index - 1
        }));
    }

    fn move_selection_down(&mut self) {
        let len = self.filtered_len();
        let index = self.state.selected().unwrap_or(0);
        self.state.select(Some(if index >= len.saturating_sub(1) {
            0
        } else {
            index + 1
        }));
    }

    fn cycle_role_filter(&mut self) {
        self.role_filter = match self.role_filter {
            RoleFilter::All => RoleFilter::Owners,
            RoleFilter::Owners => RoleFilter::Admins,
            RoleFilter::Admins => RoleFilter::Bots,
            RoleFilter::Bots => RoleFilter::Members,
            RoleFilter::Members => RoleFilter::All,
        };
        self.refresh_filtered_cache();
    }

    fn set_sort_mode(&mut self, sort_mode: SortMode) {
        self.sort_mode = sort_mode;
        self.refresh_filtered_cache();
    }

    fn selected_member_action(&self) -> ViewAction {
        if let Some(chat_id) = self.chat_id
            && let Some(member) = self
                .state
                .selected()
                .and_then(|index| self.filtered_member(index))
        {
            ViewAction::SelectMember(chat_id, member.user_id)
        } else {
            ViewAction::None
        }
    }

    fn handle_browse_key(&mut self, key: KeyEvent) -> ViewAction {
        match key.code {
            KeyCode::Up | KeyCode::Char('k') => {
                self.move_selection_up();
                ViewAction::None
            }
            KeyCode::Down | KeyCode::Char('j') => {
                self.move_selection_down();
                ViewAction::None
            }
            KeyCode::Char('/') => {
                self.searching = true;
                ViewAction::None
            }
            KeyCode::Char('r') => {
                self.cycle_role_filter();
                ViewAction::None
            }
            KeyCode::Char('a') => {
                self.set_sort_mode(SortMode::Activity);
                ViewAction::None
            }
            KeyCode::Char('n') => {
                self.set_sort_mode(SortMode::Name);
                ViewAction::None
            }
            KeyCode::Enter => self.selected_member_action(),
            KeyCode::Esc => ViewAction::Back,
            _ => ViewAction::None,
        }
    }

    #[cfg(test)]
    fn filtered_user_ids(&self) -> Vec<i64> {
        self.filtered()
            .into_iter()
            .map(|member| member.user_id)
            .collect()
    }

    #[cfg(test)]
    fn filtered_cache_len(&self) -> usize {
        self.filtered_indices.len()
    }
}

impl View for MembersView {
    fn render(&self, frame: &mut Frame<'_>, area: Rect) {
        let (table_area, search_area) = self.search_layout(area);
        let filtered = self.filtered();
        self.render_table(frame, table_area, &filtered);
        if let Some(search_area) = search_area {
            frame.render_widget(Paragraph::new(format!("/{}", self.search)), search_area);
        }
    }

    fn handle_key(&mut self, key: KeyEvent) -> ViewAction {
        if self.searching {
            self.handle_search_key(key)
        } else {
            self.handle_browse_key(key)
        }
    }

    fn title(&self) -> &'static str {
        "Members"
    }
}

#[cfg(test)]
mod tests {
    use super::members_render::format_last_active;
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
            message_count: 0,
            last_active_at: None,
        }
    }

    fn start_search(view: &mut MembersView, query: &str) {
        assert!(matches!(
            view.handle_key(key(KeyCode::Char('/'))),
            ViewAction::None
        ));
        for character in query.chars() {
            assert!(matches!(
                view.handle_key(key(KeyCode::Char(character))),
                ViewAction::None
            ));
        }
    }

    #[test]
    fn role_filter_cycles_visible_members() {
        let mut view = MembersView::new();
        let mut owner = member(1, "owner");
        owner.role = "owner".to_string();
        owner.role_code = 1;
        let mut admin = member(2, "admin");
        admin.role = "admin".to_string();
        admin.role_code = 4;
        let mut bot = member(3, "bot");
        bot.role = "bot".to_string();
        bot.role_code = 8;
        let regular = member(4, "member");
        view.set_members(vec![owner, admin, bot, regular]);

        assert_eq!(view.filtered().len(), 4);
        view.handle_key(key(KeyCode::Char('r')));
        assert_eq!(view.filtered()[0].user_id, 1);
        view.handle_key(key(KeyCode::Char('r')));
        assert_eq!(view.filtered()[0].user_id, 2);
        view.handle_key(key(KeyCode::Char('r')));
        assert_eq!(view.filtered()[0].user_id, 3);
        view.handle_key(key(KeyCode::Char('r')));
        assert_eq!(view.filtered()[0].user_id, 4);
    }

    #[test]
    fn sorting_shortcuts_switch_between_activity_and_name() {
        let mut view = MembersView::new();
        let mut alice = member(1, "alice");
        alice.message_count = 1;
        alice.last_active_at = Some(10);
        let mut bob = member(2, "bob");
        bob.message_count = 5;
        bob.last_active_at = Some(20);
        view.set_members(vec![alice, bob]);

        assert_eq!(view.filtered()[0].user_id, 2);
        view.handle_key(key(KeyCode::Char('n')));
        assert_eq!(view.filtered()[0].user_id, 1);
        view.handle_key(key(KeyCode::Char('a')));
        assert_eq!(view.filtered()[0].user_id, 2);
    }

    #[test]
    fn slash_enters_search_mode() {
        let mut view = MembersView::new();
        view.set_members(vec![member(1, "alice"), member(2, "bob")]);

        assert!(matches!(
            view.handle_key(key(KeyCode::Char('/'))),
            ViewAction::None
        ));
        assert!(view.searching);
    }

    #[test]
    fn typing_filters_members_while_searching() {
        let mut view = MembersView::new();
        view.set_members(vec![member(1, "alice"), member(2, "bob")]);

        start_search(&mut view, "b");

        assert_eq!(view.search, "b");
        assert_eq!(view.filtered().len(), 1);
        assert_eq!(view.filtered()[0].user_id, 2);
    }

    #[test]
    fn escape_clears_search() {
        let mut view = MembersView::new();
        view.set_members(vec![member(1, "alice"), member(2, "bob")]);
        start_search(&mut view, "b");

        assert!(matches!(
            view.handle_key(key(KeyCode::Esc)),
            ViewAction::None
        ));
        assert!(!view.searching);
        assert!(view.search.is_empty());
    }

    #[test]
    fn enter_selects_member_after_search_clears() {
        let mut view = MembersView::new();
        view.set_chat_id(77);
        view.set_members(vec![member(1, "alice"), member(2, "bob")]);
        start_search(&mut view, "b");
        view.handle_key(key(KeyCode::Esc));

        view.handle_key(key(KeyCode::Down));

        assert!(matches!(
            view.handle_key(key(KeyCode::Enter)),
            ViewAction::SelectMember(77, 2)
        ));
    }

    #[test]
    fn format_last_active_renders_human_readable_relative_time() {
        let now = 1_774_626_000;

        assert_eq!("just now", format_last_active(Some(now - 10), now));
        assert_eq!("1m ago", format_last_active(Some(now - 60), now));
        assert_eq!("1h ago", format_last_active(Some(now - 3_600), now));
        assert_eq!("2d ago", format_last_active(Some(now - 172_800), now));
        assert_eq!("-", format_last_active(None, now));
    }

    #[test]
    fn set_members_preserves_selected_member_across_refresh_and_sort() {
        let mut view = MembersView::new();
        view.set_chat_id(77);
        let mut alice = member(1, "alice");
        alice.message_count = 1;
        let mut bob = member(2, "bob");
        bob.message_count = 5;
        view.set_members(vec![alice.clone(), bob.clone()]);

        assert!(matches!(
            view.handle_key(key(KeyCode::Enter)),
            ViewAction::SelectMember(_, 2)
        ));

        alice.message_count = 10;
        bob.message_count = 3;
        view.set_members(vec![alice, bob]);

        assert!(matches!(
            view.handle_key(key(KeyCode::Enter)),
            ViewAction::SelectMember(_, 2)
        ));
    }

    #[test]
    fn cached_filter_rebuilds_after_member_refresh() {
        let mut view = MembersView::new();
        view.set_members(vec![member(1, "alice"), member(2, "bob")]);

        start_search(&mut view, "b");
        assert_eq!(view.filtered_user_ids(), vec![2]);
        assert_eq!(view.filtered_cache_len(), 1);

        view.set_members(vec![member(3, "bravo"), member(4, "charlie")]);

        assert_eq!(view.filtered_user_ids(), vec![3]);
        assert_eq!(view.filtered_cache_len(), 1);
    }
}
