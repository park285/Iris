use crate::views::path_input::PathInput;
use crate::views::reply_modal::state::{ModalFocus, ReplyResult, ReplyValidationError, ThreadMode, ThreadScope};
use iris_common::models::ReplyType;
use tui_textarea::TextArea;

pub(crate) fn apply_validation_error(
    result: &mut Option<ReplyResult>,
    focus: &mut ModalFocus,
    error: ReplyValidationError,
) {
    let (message, next_focus) = match error {
        ReplyValidationError::MissingThreadId => ("thread id를 입력해주세요".to_string(), ModalFocus::ThreadId),
        ReplyValidationError::InvalidThreadId => ("thread id는 숫자여야 합니다".to_string(), ModalFocus::ThreadId),
        ReplyValidationError::MissingTextContent => ("내용을 입력해주세요".to_string(), ModalFocus::Content),
        ReplyValidationError::MissingImagePath => ("이미지 경로를 입력해주세요".to_string(), ModalFocus::Content),
        ReplyValidationError::MissingImagePaths => ("최소 한 개의 이미지 경로를 입력해주세요".to_string(), ModalFocus::Content),
    };
    *result = Some(ReplyResult::Error { message });
    *focus = next_focus;
}

pub(crate) fn validate_thread(
    thread_mode: ThreadMode,
    thread_id_input: &str,
    scope: ThreadScope,
) -> Result<(Option<String>, Option<u8>), ReplyValidationError> {
    match thread_mode {
        ThreadMode::None => Ok((None, None)),
        ThreadMode::Specified => {
            if thread_id_input.is_empty() {
                return Err(ReplyValidationError::MissingThreadId);
            }
            if thread_id_input.parse::<i64>().is_err() {
                return Err(ReplyValidationError::InvalidThreadId);
            }
            Ok((Some(thread_id_input.to_string()), Some(scope.value())))
        }
    }
}

pub(crate) fn build_data(
    reply_type: &ReplyType,
    text_area: &TextArea<'_>,
    image_path: &PathInput,
    image_paths: &[PathInput],
) -> Result<serde_json::Value, ReplyValidationError> {
    match reply_type {
        ReplyType::Text | ReplyType::Markdown => {
            let text = text_area.lines().join("\n");
            if text.trim().is_empty() {
                return Err(ReplyValidationError::MissingTextContent);
            }
            Ok(serde_json::Value::String(text))
        }
        ReplyType::Image => {
            if image_path.value.is_empty() {
                return Err(ReplyValidationError::MissingImagePath);
            }
            Ok(serde_json::Value::String(image_path.value.clone()))
        }
        ReplyType::ImageMultiple => {
            let paths: Vec<String> = image_paths
                .iter()
                .filter(|path| !path.value.is_empty())
                .map(|path| path.value.clone())
                .collect();
            if paths.is_empty() {
                return Err(ReplyValidationError::MissingImagePaths);
            }
            Ok(serde_json::Value::Array(
                paths.into_iter().map(serde_json::Value::String).collect(),
            ))
        }
    }
}
