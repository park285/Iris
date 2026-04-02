use crate::views::path_input::PathInput;
use iris_common::models::{ReplyRequest, ReplyType, RoomSummary, ThreadSummary};
use ratatui::widgets::{Block, Borders};
use tui_textarea::TextArea;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum FieldFocus {
    Type,
    Room,
    Thread,
    ThreadId,
    Scope,
    Content,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum OverlayState {
    None,
    RoomSelector,
    ThreadSelector,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ThreadMode {
    None,
    Specified,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum ThreadScope {
    Thread,
    Both,
    Room,
}

impl ThreadScope {
    pub(crate) const fn value(self) -> u8 {
        match self {
            Self::Thread => 2,
            Self::Both => 3,
            Self::Room => 1,
        }
    }

    pub(crate) const fn label(self) -> &'static str {
        match self {
            Self::Thread => "thread",
            Self::Both => "both",
            Self::Room => "room",
        }
    }

    pub(crate) const fn next(self) -> Self {
        match self {
            Self::Thread => Self::Both,
            Self::Both => Self::Room,
            Self::Room => Self::Thread,
        }
    }

    pub(crate) const fn prev(self) -> Self {
        match self {
            Self::Thread => Self::Room,
            Self::Room => Self::Both,
            Self::Both => Self::Thread,
        }
    }
}

#[derive(Clone)]
pub enum ReplyResult {
    Success { request_id: String },
    Error { message: String },
}

impl ReplyResult {
    pub fn status_text(&self) -> String {
        match self {
            Self::Success { request_id } => {
                format!("  ✓ 전송 요청 등록 완료 ({request_id})")
            }
            Self::Error { message } => format!("  ✗ {message}"),
        }
    }
}

pub(crate) struct ReplyUiState {
    pub field_focus: FieldFocus,
    pub overlay: OverlayState,
    pub result: Option<ReplyResult>,
    pub sending: bool,
}

impl ReplyUiState {
    pub(crate) fn new(field_focus: FieldFocus) -> Self {
        Self {
            field_focus,
            overlay: OverlayState::None,
            result: None,
            sending: false,
        }
    }
}

pub enum ModalAction {
    None,
    Close,
    Send(ReplyRequest),
    FetchThreads(i64),
}

#[derive(Debug)]
pub(crate) enum ReplyValidationError {
    MissingThreadId,
    InvalidThreadId,
    MissingTextContent,
    MissingImagePath,
    MissingImagePaths,
}

pub(crate) fn is_open_chat(room: Option<&RoomSummary>) -> bool {
    room.and_then(|selected| selected.room_type.as_deref())
        .is_some_and(|room_type| room_type.starts_with('O'))
}

pub(crate) fn cycle_reply_type(current: ReplyType, step_right: bool) -> ReplyType {
    let types = [
        ReplyType::Text,
        ReplyType::Image,
        ReplyType::ImageMultiple,
        ReplyType::Markdown,
    ];
    let cur = types.iter().position(|t| *t == current).unwrap_or(0);
    let next = if step_right {
        (cur + 1) % types.len()
    } else {
        (cur + types.len() - 1) % types.len()
    };
    types[next]
}

pub(crate) struct ReplyDraft {
    pub reply_type: ReplyType,
    pub room: Option<RoomSummary>,
    pub thread_mode: ThreadMode,
    pub thread_id_input: String,
    pub scope: ThreadScope,
    pub text_area: TextArea<'static>,
    pub image_path: PathInput,
    pub image_paths: Vec<PathInput>,
    pub image_paths_cursor: usize,
    pub image_editing: bool,
}

pub(crate) struct ReplySelectionState {
    pub room_selector_cursor: usize,
    pub thread_suggestions: Vec<ThreadSummary>,
    pub thread_selector_cursor: usize,
}

fn new_text_area() -> TextArea<'static> {
    let mut text_area = TextArea::default();
    text_area.set_placeholder_text("메시지를 입력하세요...");
    text_area.set_block(Block::default().borders(Borders::ALL).title(" Content "));
    text_area
}

impl ReplyDraft {
    pub(crate) fn new(room: Option<RoomSummary>, thread_id: Option<String>) -> Self {
        let has_thread = thread_id.is_some();
        Self {
            reply_type: ReplyType::Text,
            room,
            thread_mode: if has_thread {
                ThreadMode::Specified
            } else {
                ThreadMode::None
            },
            thread_id_input: thread_id.unwrap_or_default(),
            scope: ThreadScope::Thread,
            text_area: new_text_area(),
            image_path: PathInput::new(),
            image_paths: vec![PathInput::new()],
            image_paths_cursor: 0,
            image_editing: false,
        }
    }

    pub(crate) fn clear_thread_state(&mut self) {
        self.thread_mode = ThreadMode::None;
        self.thread_id_input.clear();
    }

    pub(crate) fn reset_after_success(&mut self) {
        self.text_area = new_text_area();
        self.image_path.clear();
        self.image_paths = vec![PathInput::new()];
        self.image_paths_cursor = 0;
        self.image_editing = false;
    }
}

impl ReplySelectionState {
    pub(crate) fn new(room: Option<&RoomSummary>, room_list: &[RoomSummary]) -> Self {
        let room_selector_cursor = room
            .and_then(|selected| {
                room_list
                    .iter()
                    .position(|candidate| candidate.chat_id == selected.chat_id)
            })
            .unwrap_or(0);

        Self {
            room_selector_cursor,
            thread_suggestions: Vec::new(),
            thread_selector_cursor: 0,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_room(chat_id: i64, room_type: &str) -> RoomSummary {
        RoomSummary {
            chat_id,
            room_type: Some(room_type.to_string()),
            link_id: None,
            active_members_count: None,
            link_name: Some("room".to_string()),
            link_url: None,
            member_limit: None,
            searchable: None,
            bot_role: None,
        }
    }

    #[test]
    fn reset_after_success_clears_content_inputs() {
        let mut draft = ReplyDraft::new(Some(make_room(10, "OM")), Some("33".to_string()));
        draft.text_area.insert_str("hello");
        draft.image_path.value = "/tmp/example.png".to_string();
        draft.image_paths.push(PathInput::new());
        draft.image_paths_cursor = 1;
        draft.image_editing = true;

        draft.reset_after_success();

        assert!(draft.text_area.lines().join("\n").is_empty());
        assert!(draft.image_path.value.is_empty());
        assert_eq!(draft.image_paths.len(), 1);
        assert_eq!(draft.image_paths_cursor, 0);
        assert!(!draft.image_editing);
    }

    #[test]
    fn selection_state_aligns_with_preselected_room() {
        let open_room = make_room(10, "OM");
        let regular_room = make_room(20, "DirectChat");
        let room_list = vec![open_room, regular_room.clone()];

        let selection = ReplySelectionState::new(Some(&regular_room), &room_list);

        assert_eq!(selection.room_selector_cursor, 1);
        assert!(selection.thread_suggestions.is_empty());
        assert_eq!(selection.thread_selector_cursor, 0);
    }

    #[test]
    fn success_result_uses_admission_language() {
        let result = ReplyResult::Success {
            request_id: "req-1".to_string(),
        };

        assert_eq!(result.status_text(), "  ✓ 전송 요청 등록 완료 (req-1)");
    }
}
