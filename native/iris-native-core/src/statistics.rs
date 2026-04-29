use crate::errors::{NativeCoreError, NativeCoreResult};
use serde::ser::SerializeMap;
use serde::{Deserialize, Serialize, Serializer};
use serde_json::Value;
use std::collections::HashMap;

const ROOM_STATS_KIND: &str = "roomStats";
const MEMBER_ACTIVITY_KIND: &str = "memberActivity";
const UNKNOWN_KIND: &str = "unknown";
const HOURS_PER_DAY: usize = 24;
const SECONDS_PER_HOUR: i64 = 3_600;
const SECONDS_PER_DAY: i64 = 86_400;
const MAX_ERROR_CHARS: usize = 240;

#[derive(Debug, Deserialize)]
struct StatisticsProjectionBatchRequest {
    items: Vec<Value>,
}

#[derive(Debug, Serialize)]
struct StatisticsProjectionBatchResponse {
    items: Vec<StatisticsProjectionBatchResult>,
}

#[derive(Debug, Serialize)]
#[serde(untagged)]
enum StatisticsProjectionBatchResult {
    RoomStats(RoomStatsProjectionResult),
    MemberActivity(MemberActivityProjectionResult),
    Unknown(UnknownProjectionResult),
}

#[derive(Debug, Deserialize)]
struct RoomStatsProjectionItem {
    #[serde(default)]
    rows: Vec<RoomStatsRow>,
    #[serde(default, rename = "messageTypeNames")]
    message_type_names: HashMap<String, String>,
    #[serde(default)]
    nicknames: HashMap<String, String>,
    #[serde(default)]
    limit: i64,
    #[serde(default, rename = "minMessages")]
    min_messages: i64,
}

#[derive(Debug, Deserialize)]
struct RoomStatsRow {
    #[serde(rename = "userId")]
    user_id: i64,
    #[serde(default, rename = "type")]
    message_type: Option<String>,
    count: i64,
    #[serde(default, rename = "lastActive")]
    last_active: Option<i64>,
}

#[derive(Debug)]
struct MemberAccumulator {
    user_id: i64,
    message_count: i64,
    last_active_at: Option<i64>,
    message_types: OrderedCounts,
}

#[derive(Debug, Serialize)]
struct RoomStatsProjectionResult {
    kind: &'static str,
    ok: bool,
    #[serde(rename = "memberStats")]
    member_stats: Vec<MemberStatsProjection>,
    #[serde(rename = "totalMessages")]
    total_messages: i64,
    #[serde(rename = "activeMembers")]
    active_members: usize,
    #[serde(rename = "errorKind")]
    error_kind: Option<&'static str>,
    error: Option<String>,
}

#[derive(Debug, Serialize)]
struct MemberStatsProjection {
    #[serde(rename = "userId")]
    user_id: i64,
    nickname: String,
    #[serde(rename = "messageCount")]
    message_count: i64,
    #[serde(rename = "lastActiveAt")]
    last_active_at: Option<i64>,
    #[serde(rename = "messageTypes")]
    message_types: OrderedCounts,
}

#[derive(Debug, Deserialize)]
struct MemberActivityProjectionItem {
    #[serde(default)]
    rows: Vec<MemberActivityRow>,
    #[serde(default, rename = "messageTypeNames")]
    message_type_names: HashMap<String, String>,
}

#[derive(Debug, Deserialize)]
struct MemberActivityRow {
    #[serde(default, rename = "type")]
    message_type: Option<String>,
    #[serde(rename = "createdAt")]
    created_at: i64,
}

#[derive(Debug, Serialize)]
struct MemberActivityProjectionResult {
    kind: &'static str,
    ok: bool,
    #[serde(rename = "messageCount")]
    message_count: i64,
    #[serde(rename = "firstMessageAt")]
    first_message_at: Option<i64>,
    #[serde(rename = "lastMessageAt")]
    last_message_at: Option<i64>,
    #[serde(rename = "activeHours")]
    active_hours: Vec<i64>,
    #[serde(rename = "messageTypes")]
    message_types: OrderedCounts,
    #[serde(rename = "errorKind")]
    error_kind: Option<&'static str>,
    error: Option<String>,
}

#[derive(Debug, Serialize)]
struct UnknownProjectionResult {
    kind: &'static str,
    ok: bool,
    #[serde(rename = "errorKind")]
    error_kind: Option<&'static str>,
    error: Option<String>,
}

#[derive(Clone, Debug, Default)]
struct OrderedCounts {
    entries: Vec<OrderedCountEntry>,
}

#[derive(Clone, Debug)]
struct OrderedCountEntry {
    key: String,
    count: i64,
}

impl OrderedCounts {
    fn add(&mut self, key: String, count: i64) -> NativeCoreResult<()> {
        if let Some(entry) = self.entries.iter_mut().find(|entry| entry.key == key) {
            entry.count = checked_add(entry.count, count, "messageTypes count overflow")?;
        } else {
            self.entries.push(OrderedCountEntry { key, count });
        }
        Ok(())
    }
}

impl Serialize for OrderedCounts {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        let mut map = serializer.serialize_map(Some(self.entries.len()))?;
        for entry in &self.entries {
            map.serialize_entry(&entry.key, &entry.count)?;
        }
        map.end()
    }
}

pub fn statistics_projection_batch_json(request_bytes: &[u8]) -> NativeCoreResult<Vec<u8>> {
    let request: StatisticsProjectionBatchRequest = serde_json::from_slice(request_bytes)
        .map_err(|error| NativeCoreError::InvalidRequest(error.to_string()))?;
    let response = StatisticsProjectionBatchResponse {
        items: request.items.into_iter().map(project_raw_item).collect(),
    };
    serde_json::to_vec(&response)
        .map_err(|error| NativeCoreError::InvalidResponse(error.to_string()))
}

fn project_raw_item(item: Value) -> StatisticsProjectionBatchResult {
    match item.get("kind").and_then(Value::as_str) {
        Some(ROOM_STATS_KIND) => match parse_room_stats_item(item).and_then(project_room_stats) {
            Ok(result) => StatisticsProjectionBatchResult::RoomStats(result),
            Err(error) => StatisticsProjectionBatchResult::RoomStats(room_stats_error(&error)),
        },
        Some(MEMBER_ACTIVITY_KIND) => {
            match parse_member_activity_item(item).and_then(project_member_activity) {
                Ok(result) => StatisticsProjectionBatchResult::MemberActivity(result),
                Err(error) => {
                    StatisticsProjectionBatchResult::MemberActivity(member_activity_error(&error))
                }
            }
        }
        _ => {
            let error = NativeCoreError::InvalidRequest(
                "kind must be roomStats or memberActivity".to_owned(),
            );
            StatisticsProjectionBatchResult::Unknown(unknown_error(&error))
        }
    }
}

fn parse_room_stats_item(item: Value) -> NativeCoreResult<RoomStatsProjectionItem> {
    serde_json::from_value(item)
        .map_err(|_| NativeCoreError::InvalidRequest("invalid roomStats item".to_owned()))
}

fn parse_member_activity_item(item: Value) -> NativeCoreResult<MemberActivityProjectionItem> {
    serde_json::from_value(item)
        .map_err(|_| NativeCoreError::InvalidRequest("invalid memberActivity item".to_owned()))
}

fn project_room_stats(
    item: RoomStatsProjectionItem,
) -> NativeCoreResult<RoomStatsProjectionResult> {
    let limit = non_negative_usize(item.limit, "limit")?;
    let mut member_index_by_user = HashMap::<i64, usize>::new();
    let mut members = Vec::<MemberAccumulator>::new();

    for row in item.rows {
        let count = non_negative_i64(row.count, "count")?;
        let type_name = message_type_name(&item.message_type_names, row.message_type.as_deref());
        let member_index = if let Some(member_index) = member_index_by_user.get(&row.user_id) {
            *member_index
        } else {
            let member_index = members.len();
            member_index_by_user.insert(row.user_id, member_index);
            members.push(MemberAccumulator {
                user_id: row.user_id,
                message_count: 0,
                last_active_at: None,
                message_types: OrderedCounts::default(),
            });
            member_index
        };

        let member = &mut members[member_index];
        member.message_count = checked_add(member.message_count, count, "messageCount overflow")?;
        member.message_types.add(type_name, count)?;
        if let Some(last_active) = row.last_active
            && member
                .last_active_at
                .is_none_or(|current_last_active| last_active > current_last_active)
        {
            member.last_active_at = Some(last_active);
        }
    }

    let mut member_stats = members
        .into_iter()
        .map(|member| {
            let user_id_key = member.user_id.to_string();
            let nickname = item
                .nicknames
                .get(&user_id_key)
                .cloned()
                .unwrap_or(user_id_key);
            MemberStatsProjection {
                user_id: member.user_id,
                nickname,
                message_count: member.message_count,
                last_active_at: member.last_active_at,
                message_types: member.message_types,
            }
        })
        .collect::<Vec<_>>();

    member_stats.sort_by_key(|member| std::cmp::Reverse(member.message_count));
    let filtered_member_stats = member_stats
        .into_iter()
        .filter(|member| member.message_count >= item.min_messages)
        .collect::<Vec<_>>();
    let total_messages = checked_sum(
        filtered_member_stats
            .iter()
            .map(|member| member.message_count),
        "totalMessages overflow",
    )?;
    let active_members = filtered_member_stats.len();
    let member_stats = filtered_member_stats.into_iter().take(limit).collect();

    Ok(RoomStatsProjectionResult {
        kind: ROOM_STATS_KIND,
        ok: true,
        member_stats,
        total_messages,
        active_members,
        error_kind: None,
        error: None,
    })
}

fn project_member_activity(
    item: MemberActivityProjectionItem,
) -> NativeCoreResult<MemberActivityProjectionResult> {
    let message_count = i64::try_from(item.rows.len())
        .map_err(|_| NativeCoreError::InvalidRequest("messageCount overflow".to_owned()))?;
    let mut active_hours = vec![0; HOURS_PER_DAY];
    let mut message_types = OrderedCounts::default();
    let mut first_message_at = None;
    let mut last_message_at = None;

    for row in item.rows {
        if row.created_at < 0 {
            return Err(NativeCoreError::InvalidRequest(
                "createdAt must be non-negative".to_owned(),
            ));
        }
        if row.created_at == 0 {
            continue;
        }

        if first_message_at.is_none() {
            first_message_at = Some(row.created_at);
        }
        last_message_at = Some(row.created_at);

        let hour = hour_of_day(row.created_at)?;
        active_hours[hour] = checked_add(active_hours[hour], 1, "activeHours count overflow")?;
        message_types.add(
            message_type_name(&item.message_type_names, row.message_type.as_deref()),
            1,
        )?;
    }

    Ok(MemberActivityProjectionResult {
        kind: MEMBER_ACTIVITY_KIND,
        ok: true,
        message_count,
        first_message_at,
        last_message_at,
        active_hours,
        message_types,
        error_kind: None,
        error: None,
    })
}

fn message_type_name(
    message_type_names: &HashMap<String, String>,
    message_type: Option<&str>,
) -> String {
    message_type
        .and_then(|message_type| message_type_names.get(message_type))
        .map_or_else(|| "other".to_owned(), Clone::clone)
}

fn non_negative_i64(value: i64, field_name: &str) -> NativeCoreResult<i64> {
    if value < 0 {
        return Err(NativeCoreError::InvalidRequest(format!(
            "{field_name} must be non-negative"
        )));
    }
    Ok(value)
}

fn non_negative_usize(value: i64, field_name: &str) -> NativeCoreResult<usize> {
    non_negative_i64(value, field_name).and_then(|non_negative| {
        usize::try_from(non_negative)
            .map_err(|_| NativeCoreError::InvalidRequest(format!("{field_name} is too large")))
    })
}

fn checked_add(left: i64, right: i64, message: &str) -> NativeCoreResult<i64> {
    left.checked_add(right)
        .ok_or_else(|| NativeCoreError::InvalidRequest(message.to_owned()))
}

fn checked_sum<I>(values: I, message: &str) -> NativeCoreResult<i64>
where
    I: IntoIterator<Item = i64>,
{
    values
        .into_iter()
        .try_fold(0, |total, value| checked_add(total, value, message))
}

fn hour_of_day(timestamp: i64) -> NativeCoreResult<usize> {
    let hour = (timestamp % SECONDS_PER_DAY) / SECONDS_PER_HOUR;
    usize::try_from(hour)
        .map_err(|_| NativeCoreError::InvalidRequest("createdAt hour out of range".to_owned()))
}

fn room_stats_error(error: &NativeCoreError) -> RoomStatsProjectionResult {
    RoomStatsProjectionResult {
        kind: ROOM_STATS_KIND,
        ok: false,
        member_stats: Vec::new(),
        total_messages: 0,
        active_members: 0,
        error_kind: Some(error.kind()),
        error: Some(redacted_error(error)),
    }
}

fn member_activity_error(error: &NativeCoreError) -> MemberActivityProjectionResult {
    MemberActivityProjectionResult {
        kind: MEMBER_ACTIVITY_KIND,
        ok: false,
        message_count: 0,
        first_message_at: None,
        last_message_at: None,
        active_hours: vec![0; HOURS_PER_DAY],
        message_types: OrderedCounts::default(),
        error_kind: Some(error.kind()),
        error: Some(redacted_error(error)),
    }
}

fn unknown_error(error: &NativeCoreError) -> UnknownProjectionResult {
    UnknownProjectionResult {
        kind: UNKNOWN_KIND,
        ok: false,
        error_kind: Some(error.kind()),
        error: Some(redacted_error(error)),
    }
}

fn redacted_error(error: &NativeCoreError) -> String {
    let message = error.to_string();
    if message.chars().count() <= MAX_ERROR_CHARS {
        return message;
    }

    let mut truncated = message.chars().take(MAX_ERROR_CHARS).collect::<String>();
    truncated.push('…');
    truncated
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn project(request: &Value) -> Value {
        let response = statistics_projection_batch_json(request.to_string().as_bytes())
            .expect("statistics projection response");
        serde_json::from_slice(&response).expect("response json")
    }

    #[test]
    fn room_stats_groups_sorts_filters_and_limits_with_kotlin_parity() {
        let response = project(&serde_json::json!({
            "items": [{
                "kind": "roomStats",
                "rows": [
                    {"userId": 2, "type": "0", "count": 2, "lastActive": 100},
                    {"userId": 1, "type": "1", "count": 3, "lastActive": 90},
                    {"userId": 2, "type": "9", "count": 1, "lastActive": 110},
                    {"userId": 3, "type": "0", "count": 1, "lastActive": 120}
                ],
                "messageTypeNames": {"0": "text"},
                "nicknames": {"2": "bob"},
                "limit": 1,
                "minMessages": 2
            }]
        }));

        let item = &response["items"][0];
        assert_eq!(item["kind"], "roomStats");
        assert_eq!(item["ok"], true);
        assert_eq!(item["totalMessages"], 6);
        assert_eq!(item["activeMembers"], 2);
        assert_eq!(
            item["memberStats"].as_array().expect("member stats").len(),
            1
        );

        let member = &item["memberStats"][0];
        assert_eq!(member["userId"], 2);
        assert_eq!(member["nickname"], "bob");
        assert_eq!(member["messageCount"], 3);
        assert_eq!(member["lastActiveAt"], 110);
        assert_eq!(member["messageTypes"]["text"], 2);
        assert_eq!(member["messageTypes"]["other"], 1);
        assert_eq!(item["errorKind"], Value::Null);
        assert_eq!(item["error"], Value::Null);
    }

    #[test]
    fn member_activity_counts_rows_but_skips_zero_timestamps_for_activity() {
        let response = project(&serde_json::json!({
            "items": [{
                "kind": "memberActivity",
                "rows": [
                    {"type": "0", "createdAt": 0},
                    {"type": "0", "createdAt": 3600},
                    {"type": "9", "createdAt": 86399},
                    {"type": "0", "createdAt": 90000}
                ],
                "messageTypeNames": {"0": "text"}
            }]
        }));

        let item = &response["items"][0];
        assert_eq!(item["kind"], "memberActivity");
        assert_eq!(item["ok"], true);
        assert_eq!(item["messageCount"], 4);
        assert_eq!(item["firstMessageAt"], 3600);
        assert_eq!(item["lastMessageAt"], 90000);
        assert_eq!(item["activeHours"].as_array().expect("hours").len(), 24);
        assert_eq!(item["activeHours"][1], 2);
        assert_eq!(item["activeHours"][23], 1);
        assert_eq!(item["messageTypes"]["text"], 2);
        assert_eq!(item["messageTypes"]["other"], 1);
        assert_eq!(item["errorKind"], Value::Null);
        assert_eq!(item["error"], Value::Null);
    }

    #[test]
    fn item_level_errors_keep_kind_and_redacted_defaults() {
        let response = project(&serde_json::json!({
            "items": [
                {
                    "kind": "roomStats",
                    "rows": [{"userId": "secret-user", "type": "0", "count": 1}],
                    "messageTypeNames": {"0": "text"},
                    "nicknames": {},
                    "limit": 1,
                    "minMessages": 0
                },
                {
                    "kind": "memberActivity",
                    "rows": [{"type": "0", "createdAt": -1}],
                    "messageTypeNames": {"0": "text"}
                }
            ]
        }));

        let room = &response["items"][0];
        assert_eq!(room["kind"], "roomStats");
        assert_eq!(room["ok"], false);
        assert_eq!(
            room["memberStats"].as_array().expect("member stats").len(),
            0
        );
        assert_eq!(room["totalMessages"], 0);
        assert_eq!(room["activeMembers"], 0);
        assert_eq!(room["errorKind"], "invalidRequest");
        assert!(
            !room["error"]
                .as_str()
                .expect("room error")
                .contains("secret-user")
        );

        let activity = &response["items"][1];
        assert_eq!(activity["kind"], "memberActivity");
        assert_eq!(activity["ok"], false);
        assert_eq!(activity["messageCount"], 0);
        assert!(activity["firstMessageAt"].is_null());
        assert!(activity["lastMessageAt"].is_null());
        assert_eq!(activity["activeHours"].as_array().expect("hours").len(), 24);
        assert!(
            activity["activeHours"]
                .as_array()
                .expect("hours")
                .iter()
                .all(|value| value == 0)
        );
        assert_eq!(activity["messageTypes"], serde_json::json!({}));
        assert_eq!(activity["errorKind"], "invalidRequest");
    }

    #[test]
    fn statistics_projection_accepts_empty_default_omitted_kotlin_fields() {
        let response = project(&serde_json::json!({
            "items": [
                {"kind": "roomStats"},
                {"kind": "memberActivity"}
            ]
        }));

        let room = &response["items"][0];
        assert_eq!(room["kind"], "roomStats");
        assert_eq!(room["ok"], true);
        assert_eq!(
            room["memberStats"].as_array().expect("member stats").len(),
            0
        );
        assert_eq!(room["totalMessages"], 0);
        assert_eq!(room["activeMembers"], 0);

        let activity = &response["items"][1];
        assert_eq!(activity["kind"], "memberActivity");
        assert_eq!(activity["ok"], true);
        assert_eq!(activity["messageCount"], 0);
        assert!(activity["firstMessageAt"].is_null());
        assert!(activity["lastMessageAt"].is_null());
        assert_eq!(activity["activeHours"].as_array().expect("hours").len(), 24);
        assert!(
            activity["activeHours"]
                .as_array()
                .expect("hours")
                .iter()
                .all(|value| value == 0)
        );
        assert_eq!(activity["messageTypes"], serde_json::json!({}));
    }

    #[test]
    fn unknown_kind_returns_batch_item_error_without_echoing_kind_value() {
        let response = project(&serde_json::json!({
            "items": [{"kind": "secret-kind", "rows": []}]
        }));

        let item = &response["items"][0];
        assert_eq!(item["kind"], "unknown");
        assert_eq!(item["ok"], false);
        assert_eq!(item["errorKind"], "invalidRequest");
        assert!(
            !item["error"]
                .as_str()
                .expect("error")
                .contains("secret-kind")
        );
    }
}
