use crate::views::reply_modal::state::{FieldFocus, ReplyValidationError, ThreadMode, ThreadScope};
use iris_common::models::ReplyType;

pub(crate) fn validation_message_and_focus(error: ReplyValidationError) -> (String, FieldFocus) {
    match error {
        ReplyValidationError::MissingThreadId => ("thread id를 입력해주세요".to_string(), FieldFocus::ThreadId),
        ReplyValidationError::InvalidThreadId => ("thread id는 숫자여야 합니다".to_string(), FieldFocus::ThreadId),
        ReplyValidationError::MissingTextContent => ("내용을 입력해주세요".to_string(), FieldFocus::Content),
        ReplyValidationError::MissingImagePath => ("이미지 경로를 입력해주세요".to_string(), FieldFocus::Content),
        ReplyValidationError::MissingImagePaths => ("최소 한 개의 이미지 경로를 입력해주세요".to_string(), FieldFocus::Content),
    }
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
    reply_type: ReplyType,
    text: &str,
    image_path: &str,
    image_paths: &[String],
) -> Result<serde_json::Value, ReplyValidationError> {
    match reply_type {
        ReplyType::Text | ReplyType::Markdown => {
            if text.trim().is_empty() {
                return Err(ReplyValidationError::MissingTextContent);
            }
            Ok(serde_json::Value::String(text.to_string()))
        }
        ReplyType::Image => {
            if image_path.is_empty() {
                return Err(ReplyValidationError::MissingImagePath);
            }
            Ok(serde_json::Value::String(image_path.to_string()))
        }
        ReplyType::ImageMultiple => {
            let paths: Vec<String> = image_paths
                .iter()
                .filter(|path| !path.is_empty())
                .cloned()
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_data_uses_plain_values_for_text() {
        let data = build_data(ReplyType::Text, "hello", "", &[]).expect("text should build");
        assert_eq!(data, serde_json::Value::String("hello".to_string()));
    }

    #[test]
    fn validation_message_and_focus_points_thread_errors_to_thread_id() {
        let (message, focus) = validation_message_and_focus(ReplyValidationError::MissingThreadId);
        assert_eq!(message, "thread id를 입력해주세요");
        assert_eq!(focus, FieldFocus::ThreadId);
    }
}
