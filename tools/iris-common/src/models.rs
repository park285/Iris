use serde::{Deserialize, Serialize};

#[derive(Deserialize, Debug, Clone)]
pub struct RoomListResponse {
    pub rooms: Vec<RoomSummary>,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RoomSummary {
    pub chat_id: i64,
    #[serde(rename = "type")]
    pub room_type: Option<String>,
    pub link_id: Option<i64>,
    pub active_members_count: Option<i32>,
    pub link_name: Option<String>,
    pub link_url: Option<String>,
    pub member_limit: Option<i32>,
    pub searchable: Option<i32>,
    pub bot_role: Option<i32>,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct MemberListResponse {
    pub chat_id: i64,
    pub link_id: Option<i64>,
    pub members: Vec<MemberInfo>,
    pub total_count: i32,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct MemberInfo {
    pub user_id: i64,
    pub nickname: Option<String>,
    pub role: String,
    pub role_code: i32,
    pub profile_image_url: Option<String>,
    #[serde(default)]
    pub message_count: i32,
    #[serde(default)]
    pub last_active_at: Option<i64>,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RoomInfoResponse {
    pub chat_id: i64,
    #[serde(rename = "type")]
    pub room_type: Option<String>,
    pub link_id: Option<i64>,
    pub notices: Vec<NoticeInfo>,
    pub blinded_member_ids: Vec<i64>,
    pub bot_commands: Vec<BotCommandInfo>,
    pub open_link: Option<OpenLinkInfo>,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct NoticeInfo {
    pub content: String,
    pub author_id: i64,
    pub updated_at: i64,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct BotCommandInfo {
    pub name: String,
    pub bot_id: i64,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct OpenLinkInfo {
    pub name: Option<String>,
    pub url: Option<String>,
    pub member_limit: Option<i32>,
    pub description: Option<String>,
    pub searchable: Option<i32>,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct StatsResponse {
    pub chat_id: i64,
    pub period: PeriodRange,
    pub total_messages: i32,
    pub active_members: i32,
    pub top_members: Vec<MemberStats>,
}

#[derive(Deserialize, Debug, Clone)]
pub struct PeriodRange {
    pub from: i64,
    pub to: i64,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct MemberStats {
    pub user_id: i64,
    pub nickname: Option<String>,
    pub message_count: i32,
    pub last_active_at: Option<i64>,
    pub message_types: std::collections::HashMap<String, i32>,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct MemberActivityResponse {
    pub user_id: i64,
    pub nickname: Option<String>,
    pub message_count: i32,
    pub first_message_at: Option<i64>,
    pub last_message_at: Option<i64>,
    pub active_hours: Vec<i32>,
    pub message_types: std::collections::HashMap<String, i32>,
}

#[derive(Deserialize, Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RoomEventRecord {
    pub id: i64,
    pub chat_id: i64,
    pub event_type: String,
    pub user_id: i64,
    pub payload: String,
    pub created_at: i64,
}

#[derive(Deserialize, Debug, Clone)]
pub struct SseEvent {
    #[serde(rename = "type")]
    pub event_type: String,
    #[serde(default)]
    pub event: Option<String>,
    #[serde(rename = "chatId", default)]
    pub chat_id: Option<i64>,
    #[serde(rename = "userId", default)]
    pub user_id: Option<i64>,
    #[serde(default)]
    pub nickname: Option<String>,
    #[serde(rename = "oldNickname", default)]
    pub old_nickname: Option<String>,
    #[serde(rename = "newNickname", default)]
    pub new_nickname: Option<String>,
    #[serde(rename = "oldRole", default)]
    pub old_role: Option<String>,
    #[serde(rename = "newRole", default)]
    pub new_role: Option<String>,
    #[serde(default)]
    pub estimated: Option<bool>,
    #[serde(default)]
    pub timestamp: Option<i64>,
}

impl RoomSummary {
    pub const fn role_name(&self) -> &str {
        match self.bot_role {
            Some(1) => "owner",
            Some(4) => "admin",
            Some(8) => "bot",
            _ => "member",
        }
    }
}

/// daemon health probe용 응답 모델
#[derive(Deserialize, Debug, Clone)]
pub struct HealthResponse {
    pub status: String,
}

/// daemon bridge diagnostics 응답 모델
#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
#[allow(clippy::struct_excessive_bools)]
pub struct BridgeDiagnosticsResponse {
    pub reachable: bool,
    pub running: bool,
    pub spec_ready: bool,
    #[serde(default)]
    pub checked_at_epoch_ms: Option<i64>,
    pub restart_count: i32,
    #[serde(default)]
    pub last_crash_message: Option<String>,
    #[serde(default)]
    pub checks: Vec<BridgeDiagnosticsCheck>,
    #[serde(default)]
    pub discovery_install_attempted: bool,
    #[serde(default)]
    pub discovery_hooks: Vec<BridgeDiagnosticsHook>,
    #[serde(default)]
    pub error: Option<String>,
}

#[derive(Deserialize, Debug, Clone)]
pub struct BridgeDiagnosticsCheck {
    pub name: String,
    pub ok: bool,
    #[serde(default)]
    pub detail: Option<String>,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct BridgeDiagnosticsHook {
    pub name: String,
    pub installed: bool,
    #[serde(default)]
    pub install_error: Option<String>,
    pub invocation_count: i32,
    #[serde(default)]
    pub last_seen_epoch_ms: Option<i64>,
    #[serde(default)]
    pub last_summary: Option<String>,
}

// --- Reply models ---

#[derive(Serialize, Debug, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ReplyType {
    Text,
    Image,
    ImageMultiple,
    Markdown,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ReplyRequest {
    #[serde(rename = "type")]
    pub reply_type: ReplyType,
    pub room: String,
    pub data: serde_json::Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub thread_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub thread_scope: Option<u8>,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ReplyAcceptedResponse {
    #[serde(default)]
    pub success: bool,
    #[serde(default)]
    pub delivery: Option<String>,
    pub request_id: String,
    pub room: String,
    #[serde(rename = "type")]
    pub reply_type: String,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ErrorResponse {
    pub status: bool,
    pub message: String,
}

#[derive(Serialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RecentMessagesRequest {
    pub chat_id: i64,
    pub limit: i32,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RecentMessagesResponse {
    pub chat_id: i64,
    pub messages: Vec<RecentMessage>,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RecentMessage {
    pub id: i64,
    pub chat_id: i64,
    pub user_id: i64,
    pub message: String,
    #[serde(rename = "type")]
    pub msg_type: i32,
    pub created_at: i64,
    pub thread_id: Option<i64>,
}

// --- Thread models ---

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ThreadListResponse {
    pub chat_id: i64,
    pub threads: Vec<ThreadSummary>,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ThreadSummary {
    pub thread_id: String,
    pub origin_message: Option<String>,
    pub message_count: i32,
    pub last_active_at: Option<i64>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bridge_diagnostics_response_parses_runtime_payload() {
        let payload = r#"{
            "reachable": true,
            "running": true,
            "specReady": true,
            "checkedAtEpochMs": 1774616292238,
            "restartCount": 0,
            "checks": [
                {"name": "class ChatMediaSender", "ok": true, "detail": "bh.c"}
            ],
            "discoveryInstallAttempted": true,
            "discoveryHooks": [
                {
                    "name": "ChatMediaSender#sendMultiple",
                    "installed": true,
                    "invocationCount": 3,
                    "lastSeenEpochMs": 1774617677760,
                    "lastSummary": "uris=1 type=Photo"
                }
            ]
        }"#;

        let parsed = serde_json::from_str::<BridgeDiagnosticsResponse>(payload);

        assert!(
            parsed.is_ok(),
            "bridge diagnostics should parse runtime payload: {parsed:?}"
        );
    }

    #[test]
    fn reply_request_serializes_correctly() {
        let req = ReplyRequest {
            reply_type: ReplyType::Text,
            room: "12345".to_string(),
            data: serde_json::Value::String("hello".to_string()),
            thread_id: Some("999".to_string()),
            thread_scope: Some(2),
        };
        let json = serde_json::to_value(&req).unwrap();
        assert_eq!(json["type"], "text");
        assert_eq!(json["room"], "12345");
        assert_eq!(json["data"], "hello");
        assert_eq!(json["threadId"], "999");
        assert_eq!(json["threadScope"], 2);
    }

    #[test]
    fn reply_request_omits_none_thread_fields() {
        let req = ReplyRequest {
            reply_type: ReplyType::Markdown,
            room: "12345".to_string(),
            data: serde_json::Value::String("# title".to_string()),
            thread_id: None,
            thread_scope: None,
        };
        let json = serde_json::to_value(&req).unwrap();
        assert_eq!(json["type"], "markdown");
        assert!(json.get("threadId").is_none());
        assert!(json.get("threadScope").is_none());
    }

    #[test]
    fn reply_accepted_response_parses_full() {
        let payload = r#"{"success":true,"delivery":"queued","requestId":"reply-abc","room":"123","type":"text"}"#;
        let parsed: ReplyAcceptedResponse = serde_json::from_str(payload).unwrap();
        assert_eq!(parsed.request_id, "reply-abc");
        assert!(parsed.success);
    }

    #[test]
    fn reply_accepted_response_parses_minimal() {
        // 서버 실제 응답: success/delivery 기본값 생략
        let payload = r#"{"requestId":"reply-abc","room":"123","type":"text"}"#;
        let parsed: ReplyAcceptedResponse = serde_json::from_str(payload).unwrap();
        assert_eq!(parsed.request_id, "reply-abc");
        assert!(!parsed.success); // default false
        assert!(parsed.delivery.is_none());
    }

    #[test]
    fn thread_list_response_parses() {
        let payload = r#"{"chatId":123,"threads":[{"threadId":"456","originMessage":"원본","messageCount":5,"lastActiveAt":1774787702}]}"#;
        let parsed: ThreadListResponse = serde_json::from_str(payload).unwrap();
        assert_eq!(parsed.threads.len(), 1);
        assert_eq!(parsed.threads[0].origin_message.as_deref(), Some("원본"));
    }

    #[test]
    fn thread_list_response_parses_null_last_active() {
        let payload =
            r#"{"chatId":123,"threads":[{"threadId":"456","messageCount":5,"lastActiveAt":null}]}"#;
        let parsed: ThreadListResponse = serde_json::from_str(payload).unwrap();
        assert_eq!(parsed.threads[0].last_active_at, None);
    }

    #[test]
    fn recent_messages_request_serializes_chat_id_and_limit() {
        let req = RecentMessagesRequest {
            chat_id: 123,
            limit: 25,
        };

        let json = serde_json::to_value(&req).unwrap();

        assert_eq!(json["chatId"], 123);
        assert_eq!(json["limit"], 25);
    }

    #[test]
    fn recent_messages_response_parses_messages() {
        let payload = r#"{
            "chatId": 123,
            "messages": [
                {
                    "id": 1,
                    "chatId": 123,
                    "userId": 7,
                    "message": "hello",
                    "type": 1,
                    "createdAt": 1000,
                    "threadId": 99
                }
            ]
        }"#;

        let parsed: RecentMessagesResponse = serde_json::from_str(payload).unwrap();

        assert_eq!(parsed.chat_id, 123);
        assert_eq!(parsed.messages.len(), 1);
        assert_eq!(parsed.messages[0].msg_type, 1);
        assert_eq!(parsed.messages[0].thread_id, Some(99));
    }
}
