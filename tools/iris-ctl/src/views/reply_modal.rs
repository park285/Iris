mod input;
mod render;
mod state;
mod util;
mod validate;

use iris_common::models::{ReplyRequest, RoomSummary};

use state::{
    FieldFocus, OverlayState, ReplyDraft, ReplySelectionState, ReplyUiState, ReplyValidationError,
};
pub use state::{ModalAction, ReplyResult};
use util::{centered_rect, truncate_thread_origin};
use validate::{build_data, validate_thread, validation_message_and_focus};

pub struct ReplyModal {
    draft: ReplyDraft,
    pub ui: ReplyUiState,
    pub room_list: Vec<RoomSummary>,
    selectors: ReplySelectionState,
}

impl ReplyModal {
    pub fn new_with_context(
        room: Option<RoomSummary>,
        room_list: Vec<RoomSummary>,
        thread_id: Option<String>,
    ) -> Self {
        let initial_focus = if room.is_none() {
            FieldFocus::Room
        } else {
            FieldFocus::Type
        };

        Self {
            draft: ReplyDraft::new(room.clone(), thread_id),
            ui: ReplyUiState::new(initial_focus),
            selectors: ReplySelectionState::new(room.as_ref(), &room_list),
            room_list,
        }
    }

    #[cfg(test)]
    pub fn thread_id_input(&self) -> &str {
        &self.draft.thread_id_input
    }

    fn field_focus(&self) -> FieldFocus {
        self.ui.field_focus
    }

    fn set_field_focus(&mut self, focus: FieldFocus) {
        self.ui.field_focus = focus;
    }

    fn overlay(&self) -> OverlayState {
        self.ui.overlay
    }

    fn set_overlay(&mut self, overlay: OverlayState) {
        self.ui.overlay = overlay;
    }

    fn try_send(&mut self) -> ModalAction {
        self.ui.result = None;
        self.set_overlay(OverlayState::None);

        let Some(room_id) = self.draft.room.as_ref().map(|r| r.chat_id.to_string()) else {
            self.ui.result = Some(ReplyResult::Error {
                message: "room을 선택해주세요".to_string(),
            });
            self.set_field_focus(FieldFocus::Room);
            return ModalAction::None;
        };

        let (thread_id, thread_scope) = match self.validate_thread() {
            Ok(thread) => thread,
            Err(error) => {
                self.apply_validation_error(error);
                return ModalAction::None;
            }
        };

        let data = match self.build_data() {
            Ok(data) => data,
            Err(error) => {
                self.apply_validation_error(error);
                return ModalAction::None;
            }
        };

        self.ui.sending = true;

        ModalAction::Send(ReplyRequest {
            reply_type: self.draft.reply_type.clone(),
            room: room_id,
            data,
            thread_id,
            thread_scope,
        })
    }

    fn validate_thread(&self) -> Result<(Option<String>, Option<u8>), ReplyValidationError> {
        validate_thread(
            self.draft.thread_mode,
            &self.draft.thread_id_input,
            self.draft.scope,
        )
    }

    fn build_data(&self) -> Result<serde_json::Value, ReplyValidationError> {
        let text = self.draft.text_area.lines().join("\n");
        let image_paths: Vec<String> = self
            .draft
            .image_paths
            .iter()
            .map(|path| path.value.clone())
            .collect();
        build_data(
            self.draft.reply_type.clone(),
            &text,
            &self.draft.image_path.value,
            &image_paths,
        )
    }

    pub fn set_result(&mut self, result: ReplyResult) {
        self.ui.sending = false;
        if matches!(result, ReplyResult::Success { .. }) {
            self.draft.reset_after_success();
        }
        self.ui.result = Some(result);
    }

    pub(crate) fn set_thread_suggestions(
        &mut self,
        thread_suggestions: Vec<iris_common::models::ThreadSummary>,
    ) {
        self.selectors.thread_suggestions = thread_suggestions;
        self.selectors.thread_selector_cursor = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
    use iris_common::models::ThreadSummary;
    use state::ThreadMode;

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
    fn new_with_context_prefills_thread_id() {
        let room = make_room(42, "OM");

        let modal =
            ReplyModal::new_with_context(Some(room.clone()), vec![room], Some("777".to_string()));

        assert_eq!(modal.thread_id_input(), "777");
        assert_eq!(modal.draft.thread_mode, ThreadMode::Specified);
    }

    #[test]
    fn switching_to_non_open_chat_clears_thread_state() {
        let open_room = make_room(10, "OM");
        let regular_room = make_room(20, "DirectChat");
        let room_list = vec![open_room.clone(), regular_room];

        let mut modal = ReplyModal::new_with_context(
            Some(open_room),
            room_list.clone(),
            Some("999".to_string()),
        );
        modal.selectors.thread_suggestions = vec![ThreadSummary {
            thread_id: "999".to_string(),
            origin_message: Some("hello".to_string()),
            message_count: 2,
            last_active_at: None,
        }];
        modal.selectors.thread_selector_cursor = 1;

        modal.room_list = room_list;
        modal.selectors.room_selector_cursor = 1;
        modal.ui.overlay = OverlayState::RoomSelector;
        modal.ui.field_focus = FieldFocus::Room;
        modal.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));

        assert_eq!(modal.draft.thread_mode, ThreadMode::None);
        assert!(modal.thread_id_input().is_empty());
        assert!(modal.selectors.thread_suggestions.is_empty());
        assert_eq!(modal.selectors.thread_selector_cursor, 0);
    }

    #[test]
    fn switching_between_open_chats_clears_stale_thread_state() {
        let open_room_a = make_room(10, "OM");
        let open_room_b = make_room(20, "OM");
        let room_list = vec![open_room_a.clone(), open_room_b];

        let mut modal = ReplyModal::new_with_context(
            Some(open_room_a),
            room_list.clone(),
            Some("999".to_string()),
        );
        modal.selectors.thread_suggestions = vec![ThreadSummary {
            thread_id: "999".to_string(),
            origin_message: Some("hello".to_string()),
            message_count: 2,
            last_active_at: None,
        }];
        modal.selectors.thread_selector_cursor = 1;

        modal.room_list = room_list;
        modal.selectors.room_selector_cursor = 1;
        modal.ui.overlay = OverlayState::RoomSelector;
        modal.ui.field_focus = FieldFocus::Room;
        modal.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));

        assert_eq!(modal.draft.thread_mode, ThreadMode::None);
        assert!(modal.thread_id_input().is_empty());
        assert!(modal.selectors.thread_suggestions.is_empty());
        assert_eq!(modal.selectors.thread_selector_cursor, 0);
    }

    #[test]
    fn thread_selector_does_not_fetch_threads_without_room_context() {
        let mut modal = ReplyModal::new_with_context(None, Vec::new(), None);
        modal.ui.field_focus = FieldFocus::Thread;
        modal.draft.thread_mode = ThreadMode::Specified;

        let action = modal.handle_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));

        assert!(matches!(action, ModalAction::None));
    }

    #[test]
    fn room_selector_overlay_preserves_field_focus() {
        let room = make_room(10, "OM");
        let mut modal = ReplyModal::new_with_context(Some(room.clone()), vec![room], None);
        modal.ui.field_focus = FieldFocus::Room;
        modal.ui.overlay = OverlayState::RoomSelector;

        let action = modal.handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));

        assert!(matches!(action, ModalAction::None));
        assert_eq!(modal.ui.field_focus, FieldFocus::Room);
        assert_eq!(modal.ui.overlay, OverlayState::None);
    }

    #[test]
    fn set_result_success_keeps_ui_state() {
        let room = make_room(10, "OM");
        let mut modal =
            ReplyModal::new_with_context(Some(room), Vec::new(), Some("33".to_string()));
        modal.draft.text_area.insert_str("hello");
        modal.draft.image_path.value = "/tmp/example.png".to_string();
        modal.ui.field_focus = FieldFocus::Content;
        modal.ui.sending = true;

        modal.set_result(ReplyResult::Success {
            request_id: "req-1".to_string(),
        });

        assert_eq!(modal.ui.field_focus, FieldFocus::Content);
        assert!(!modal.ui.sending);
    }
}
