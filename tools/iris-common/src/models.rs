use serde::Deserialize;

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
}
