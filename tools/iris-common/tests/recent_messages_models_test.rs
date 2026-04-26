use iris_common::models::{RecentMessagesCursorRequest, RecentMessagesRequest};

#[test]
fn recent_messages_request_public_shape_stays_chat_id_and_limit() {
    let req = RecentMessagesRequest {
        chat_id: 123,
        limit: 25,
    };

    let json = serde_json::to_value(&req).unwrap();

    assert_eq!(json["chatId"], 123);
    assert_eq!(json["limit"], 25);
    assert!(json.get("afterId").is_none());
    assert!(json.get("beforeId").is_none());
    assert!(json.get("threadId").is_none());
}

#[test]
fn recent_messages_cursor_request_is_public_and_omits_none_fields() {
    let req = RecentMessagesCursorRequest {
        chat_id: 123,
        limit: 25,
        after_id: Some(10),
        before_id: None,
        thread_id: Some(9001),
    };

    let json = serde_json::to_value(&req).unwrap();

    assert_eq!(json["chatId"], 123);
    assert_eq!(json["limit"], 25);
    assert_eq!(json["afterId"], 10);
    assert!(json.get("beforeId").is_none());
    assert_eq!(json["threadId"], 9001);
}
