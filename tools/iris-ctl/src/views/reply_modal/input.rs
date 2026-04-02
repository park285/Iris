use super::ReplyModal;
use super::state::{
    FieldFocus, OverlayState, ReplyValidationError, ThreadMode, cycle_reply_type, is_open_chat,
};
use crate::views::path_input::PathInput;
use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use iris_common::models::ReplyType;

impl ReplyModal {
    pub fn handle_key(&mut self, key: KeyEvent) -> super::ModalAction {
        if key.modifiers.contains(KeyModifiers::CONTROL) && matches!(key.code, KeyCode::Char('s')) {
            if !self.ui.sending {
                return self.try_send();
            }
            return super::ModalAction::None;
        }

        if self.overlay() == OverlayState::RoomSelector {
            return self.handle_room_selector_key(key);
        }
        if self.overlay() == OverlayState::ThreadSelector {
            return self.handle_thread_selector_key(key);
        }

        if self.field_focus() == FieldFocus::Content {
            return self.handle_content_key(key);
        }

        if self.field_focus() == FieldFocus::ThreadId {
            return self.handle_thread_id_key(key);
        }

        match key.code {
            KeyCode::Tab => {
                self.set_field_focus(self.next_focus());
                super::ModalAction::None
            }
            KeyCode::BackTab => {
                self.set_field_focus(self.prev_focus());
                super::ModalAction::None
            }
            KeyCode::Left | KeyCode::Right => {
                self.handle_option_switch(key.code);
                super::ModalAction::None
            }
            KeyCode::Enter => self.handle_enter(),
            KeyCode::Esc => super::ModalAction::Close,
            _ => super::ModalAction::None,
        }
    }

    fn next_focus(&self) -> FieldFocus {
        match self.field_focus() {
            FieldFocus::Type => FieldFocus::Room,
            FieldFocus::Room => {
                if is_open_chat(self.draft.room.as_ref()) {
                    FieldFocus::Thread
                } else {
                    FieldFocus::Content
                }
            }
            FieldFocus::Thread => {
                if self.draft.thread_mode == ThreadMode::Specified {
                    FieldFocus::ThreadId
                } else {
                    FieldFocus::Content
                }
            }
            FieldFocus::ThreadId => FieldFocus::Scope,
            FieldFocus::Scope | FieldFocus::Content => FieldFocus::Content,
        }
    }

    fn prev_focus(&self) -> FieldFocus {
        match self.field_focus() {
            FieldFocus::Type => FieldFocus::Type,
            FieldFocus::Room => FieldFocus::Type,
            FieldFocus::Thread => FieldFocus::Room,
            FieldFocus::ThreadId => FieldFocus::Thread,
            FieldFocus::Scope => FieldFocus::ThreadId,
            FieldFocus::Content => FieldFocus::Content,
        }
    }

    fn handle_option_switch(&mut self, code: KeyCode) {
        match self.field_focus() {
            FieldFocus::Type => self.cycle_reply_type(code),
            FieldFocus::Scope => {
                self.draft.scope = match code {
                    KeyCode::Right => self.draft.scope.next(),
                    KeyCode::Left => self.draft.scope.prev(),
                    _ => self.draft.scope,
                };
            }
            FieldFocus::Thread => {
                self.draft.thread_mode = match code {
                    KeyCode::Right | KeyCode::Left => match self.draft.thread_mode {
                        ThreadMode::None => ThreadMode::Specified,
                        ThreadMode::Specified => ThreadMode::None,
                    },
                    _ => self.draft.thread_mode,
                };
            }
            _ => {}
        }
    }

    fn cycle_reply_type(&mut self, code: KeyCode) {
        self.draft.reply_type = match code {
            KeyCode::Right => cycle_reply_type(self.draft.reply_type, true),
            KeyCode::Left => cycle_reply_type(self.draft.reply_type, false),
            _ => self.draft.reply_type,
        };
    }

    fn handle_enter(&mut self) -> super::ModalAction {
        match self.field_focus() {
            FieldFocus::Room => {
                self.set_overlay(OverlayState::RoomSelector);
                super::ModalAction::None
            }
            FieldFocus::Thread => {
                if self.draft.thread_mode == ThreadMode::Specified {
                    if let Some(chat_id) = self.draft.room.as_ref().map(|room| room.chat_id) {
                        self.set_overlay(OverlayState::ThreadSelector);
                        super::ModalAction::FetchThreads(chat_id)
                    } else {
                        super::ModalAction::None
                    }
                } else {
                    self.draft.thread_mode = ThreadMode::Specified;
                    self.set_field_focus(FieldFocus::ThreadId);
                    super::ModalAction::None
                }
            }
            _ => super::ModalAction::None,
        }
    }

    fn handle_content_key(&mut self, key: KeyEvent) -> super::ModalAction {
        if key.code == KeyCode::Esc {
            self.set_field_focus(FieldFocus::Type);
            return super::ModalAction::None;
        }
        match self.draft.reply_type {
            ReplyType::Text | ReplyType::Markdown => {
                self.draft.text_area.input(key);
            }
            ReplyType::Image => {
                self.draft.image_path.handle_key(key);
            }
            ReplyType::ImageMultiple => {
                self.handle_image_multiple_key(key);
            }
        }
        super::ModalAction::None
    }

    fn handle_image_multiple_key(&mut self, key: KeyEvent) {
        if self.draft.image_editing {
            self.handle_image_editing_key(key);
            return;
        }
        self.handle_image_nav_key(key);
    }

    fn handle_image_editing_key(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Esc | KeyCode::Enter => {
                self.draft.image_editing = false;
                if let Some(p) = self
                    .draft
                    .image_paths
                    .get_mut(self.draft.image_paths_cursor)
                {
                    p.validate_file();
                }
            }
            _ => {
                if let Some(p) = self
                    .draft
                    .image_paths
                    .get_mut(self.draft.image_paths_cursor)
                {
                    p.handle_key(key);
                }
            }
        }
    }

    fn handle_image_nav_key(&mut self, key: KeyEvent) {
        if key.modifiers.contains(KeyModifiers::CONTROL) {
            match key.code {
                KeyCode::Char('a') => {
                    self.draft.image_paths.push(PathInput::new());
                    self.draft.image_paths_cursor = self.draft.image_paths.len() - 1;
                    self.draft.image_editing = true;
                }
                KeyCode::Char('d') => {
                    if self.draft.image_paths.len() > 1 {
                        self.draft.image_paths.remove(self.draft.image_paths_cursor);
                        if self.draft.image_paths_cursor >= self.draft.image_paths.len() {
                            self.draft.image_paths_cursor = self.draft.image_paths.len() - 1;
                        }
                    }
                }
                _ => {}
            }
            return;
        }

        match key.code {
            KeyCode::Up => {
                if self.draft.image_paths_cursor > 0 {
                    self.draft.image_paths_cursor -= 1;
                }
            }
            KeyCode::Down => {
                if self.draft.image_paths_cursor + 1 < self.draft.image_paths.len() {
                    self.draft.image_paths_cursor += 1;
                }
            }
            KeyCode::Enter => {
                self.draft.image_editing = true;
            }
            _ => {}
        }
    }

    fn handle_room_selector_key(&mut self, key: KeyEvent) -> super::ModalAction {
        match key.code {
            KeyCode::Up => {
                if self.selectors.room_selector_cursor > 0 {
                    self.selectors.room_selector_cursor -= 1;
                }
            }
            KeyCode::Down => {
                if self.selectors.room_selector_cursor + 1 < self.room_list.len() {
                    self.selectors.room_selector_cursor += 1;
                }
            }
            KeyCode::Enter => {
                if let Some(selected) = self
                    .room_list
                    .get(self.selectors.room_selector_cursor)
                    .cloned()
                {
                    let previous_chat_id = self.draft.room.as_ref().map(|room| room.chat_id);
                    let selected_is_open_chat = selected
                        .room_type
                        .as_deref()
                        .is_some_and(|t| t.starts_with('O'));
                    let room_changed = previous_chat_id != Some(selected.chat_id);

                    self.draft.room = Some(selected);

                    if room_changed || !selected_is_open_chat {
                        self.draft.clear_thread_state();
                        self.selectors.thread_suggestions.clear();
                        self.selectors.thread_selector_cursor = 0;
                    }
                }
                self.set_overlay(OverlayState::None);
                self.set_field_focus(FieldFocus::Room);
            }
            KeyCode::Esc => {
                self.set_overlay(OverlayState::None);
                self.set_field_focus(FieldFocus::Room);
            }
            _ => {}
        }
        super::ModalAction::None
    }

    fn handle_thread_selector_key(&mut self, key: KeyEvent) -> super::ModalAction {
        match key.code {
            KeyCode::Up => {
                if self.selectors.thread_selector_cursor > 0 {
                    self.selectors.thread_selector_cursor -= 1;
                }
            }
            KeyCode::Down => {
                if self.selectors.thread_selector_cursor + 1
                    < self.selectors.thread_suggestions.len()
                {
                    self.selectors.thread_selector_cursor += 1;
                }
            }
            KeyCode::Enter => {
                if let Some(selected) = self
                    .selectors
                    .thread_suggestions
                    .get(self.selectors.thread_selector_cursor)
                {
                    self.draft.thread_id_input = selected.thread_id.clone();
                }
                self.set_overlay(OverlayState::None);
                self.set_field_focus(FieldFocus::ThreadId);
            }
            KeyCode::Esc => {
                self.set_overlay(OverlayState::None);
                self.set_field_focus(FieldFocus::Thread);
            }
            _ => {}
        }
        super::ModalAction::None
    }

    fn handle_thread_id_key(&mut self, key: KeyEvent) -> super::ModalAction {
        match key.code {
            KeyCode::Char(ch) if ch.is_ascii_digit() => {
                self.draft.thread_id_input.push(ch);
            }
            KeyCode::Backspace => {
                self.draft.thread_id_input.pop();
            }
            KeyCode::Tab => {
                self.set_field_focus(self.next_focus());
            }
            KeyCode::Esc => {
                self.set_field_focus(FieldFocus::Thread);
            }
            _ => {}
        }
        super::ModalAction::None
    }

    pub(crate) fn apply_validation_error(&mut self, error: ReplyValidationError) {
        let (message, next_focus) = super::validation_message_and_focus(error);
        self.ui.result = Some(super::ReplyResult::Error { message });
        self.set_overlay(OverlayState::None);
        self.set_field_focus(next_focus);
    }
}
