use crate::errors::{NativeCoreError, NativeCoreResult};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashSet;

const DEFAULT_PERIOD_DAYS: i64 = 7;
const SECONDS_PER_DAY: i64 = 86_400;

#[derive(Debug, Deserialize)]
struct ParserBatchRequest {
    items: Vec<ParserBatchItem>,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "kind")]
enum ParserBatchItem {
    #[serde(rename = "roomTitle")]
    RoomTitle {
        #[serde(default)]
        meta: Option<String>,
    },
    #[serde(rename = "notices")]
    Notices {
        #[serde(default)]
        meta: Option<String>,
    },
    #[serde(rename = "idArray")]
    IdArray {
        #[serde(default)]
        raw: Option<String>,
    },
    #[serde(rename = "periodSpec")]
    PeriodSpec {
        #[serde(default)]
        period: Option<String>,
        #[serde(default, rename = "defaultDays")]
        default_days: Option<i64>,
    },
}

#[derive(Debug, Serialize)]
struct ParserBatchResponse {
    items: Vec<ParserBatchResult>,
}

#[derive(Debug, Serialize)]
#[serde(tag = "kind")]
enum ParserBatchResult {
    #[serde(rename = "roomTitle")]
    RoomTitle {
        ok: bool,
        fallback: bool,
        #[serde(rename = "usedDefault")]
        used_default: bool,
        #[serde(rename = "roomTitle")]
        room_title: Option<String>,
        error: Option<String>,
    },
    #[serde(rename = "notices")]
    Notices {
        ok: bool,
        fallback: bool,
        #[serde(rename = "usedDefault")]
        used_default: bool,
        notices: Vec<NoticeInfo>,
        error: Option<String>,
    },
    #[serde(rename = "idArray")]
    IdArray {
        ok: bool,
        fallback: bool,
        #[serde(rename = "usedDefault")]
        used_default: bool,
        ids: Vec<i64>,
        error: Option<String>,
    },
    #[serde(rename = "periodSpec")]
    PeriodSpec {
        ok: bool,
        fallback: bool,
        #[serde(rename = "usedDefault")]
        used_default: bool,
        #[serde(rename = "periodSpec")]
        period_spec: PeriodSpecInfo,
        error: Option<String>,
    },
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
struct NoticeInfo {
    content: String,
    #[serde(rename = "authorId")]
    author_id: i64,
    #[serde(rename = "updatedAt")]
    updated_at: i64,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
struct PeriodSpecInfo {
    kind: PeriodSpecKind,
    days: Option<i64>,
    seconds: Option<i64>,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
enum PeriodSpecKind {
    All,
    Days,
}

pub fn parser_batch_json(request_bytes: &[u8]) -> NativeCoreResult<Vec<u8>> {
    let request: ParserBatchRequest = serde_json::from_slice(request_bytes)
        .map_err(|error| NativeCoreError::InvalidRequest(error.to_string()))?;
    let response = ParserBatchResponse {
        items: request.items.into_iter().map(parse_item).collect(),
    };
    serde_json::to_vec(&response)
        .map_err(|error| NativeCoreError::InvalidResponse(error.to_string()))
}

fn parse_item(item: ParserBatchItem) -> ParserBatchResult {
    match item {
        ParserBatchItem::RoomTitle { meta } => {
            let (room_title, fallback) = parse_room_title(meta.as_deref());
            ParserBatchResult::RoomTitle {
                ok: true,
                fallback,
                used_default: false,
                room_title,
                error: None,
            }
        }
        ParserBatchItem::Notices { meta } => {
            let (notices, fallback) = parse_notices(meta.as_deref());
            ParserBatchResult::Notices {
                ok: true,
                fallback,
                used_default: false,
                notices,
                error: None,
            }
        }
        ParserBatchItem::IdArray { raw } => {
            let (ids, fallback) = parse_id_array(raw.as_deref());
            ParserBatchResult::IdArray {
                ok: true,
                fallback,
                used_default: false,
                ids,
                error: None,
            }
        }
        ParserBatchItem::PeriodSpec {
            period,
            default_days,
        } => {
            let default_days = default_days
                .filter(|days| *days > 0)
                .unwrap_or(DEFAULT_PERIOD_DAYS);
            let (period_spec, used_default) = parse_period_spec(period.as_deref(), default_days);
            ParserBatchResult::PeriodSpec {
                ok: true,
                fallback: false,
                used_default,
                period_spec,
                error: None,
            }
        }
    }
}

fn parse_room_title(meta: Option<&str>) -> (Option<String>, bool) {
    let Some(meta) = meta else {
        return (None, false);
    };
    if meta.trim().is_empty() {
        return (None, false);
    }

    match try_parse_room_title(meta) {
        Ok(room_title) => (room_title, false),
        Err(()) => (None, true),
    }
}

fn try_parse_room_title(meta: &str) -> Result<Option<String>, ()> {
    let element: Value = serde_json::from_str(meta).map_err(|_| ())?;
    let candidates = match &element {
        Value::Array(candidates) => candidates.as_slice(),
        Value::Object(object) => match object.get("noticeActivityContents") {
            Some(Value::Array(candidates)) => candidates.as_slice(),
            Some(_) => return Err(()),
            None => return Ok(None),
        },
        _ => return Ok(None),
    };

    for candidate in candidates {
        let object = candidate.as_object().ok_or(())?;
        let type_value = match object.get("type") {
            Some(value) => primitive_content(value)?.parse::<i32>().ok(),
            None => None,
        };
        let content = match object.get("content") {
            Some(value) => Some(primitive_content(value)?.trim().to_owned()),
            None => None,
        };

        match (type_value, content) {
            (Some(3), Some(content)) if !content.is_empty() => return Ok(Some(content)),
            _ => {}
        }
    }

    Ok(None)
}

fn parse_notices(meta: Option<&str>) -> (Vec<NoticeInfo>, bool) {
    let Some(meta) = meta else {
        return (Vec::new(), false);
    };
    if meta.trim().is_empty() {
        return (Vec::new(), false);
    }

    match try_parse_notices(meta) {
        Ok((notices, skipped_malformed_item)) => (notices, skipped_malformed_item),
        Err(()) => (Vec::new(), true),
    }
}

fn try_parse_notices(meta: &str) -> Result<(Vec<NoticeInfo>, bool), ()> {
    let element: Value = serde_json::from_str(meta).map_err(|_| ())?;
    let object = element.as_object().ok_or(())?;
    let notices_array = match object.get("noticeActivityContents") {
        Some(Value::Array(notices_array)) => notices_array,
        Some(_) => return Err(()),
        None => return Ok((Vec::new(), false)),
    };

    let mut notices = Vec::new();
    let mut skipped_malformed_item = false;
    for elem in notices_array {
        match parse_notice(elem) {
            Ok(Some(notice)) => notices.push(notice),
            Ok(None) | Err(()) => skipped_malformed_item = true,
        }
    }

    Ok((notices, skipped_malformed_item))
}

fn parse_notice(value: &Value) -> Result<Option<NoticeInfo>, ()> {
    let object = value.as_object().ok_or(())?;
    let Some(message_value) = object.get("message") else {
        return Ok(None);
    };
    let content = primitive_content(message_value)?;
    let author_id = match object.get("authorId") {
        Some(value) => primitive_long(value)?,
        None => 0,
    };
    let updated_at = match object.get("createdAt") {
        Some(value) => primitive_long(value)?,
        None => 0,
    };

    Ok(Some(NoticeInfo {
        content,
        author_id,
        updated_at,
    }))
}

fn parse_id_array(raw: Option<&str>) -> (Vec<i64>, bool) {
    let Some(raw) = raw else {
        return (Vec::new(), false);
    };
    if raw.trim().is_empty() || raw == "[]" {
        return (Vec::new(), false);
    }

    match try_parse_id_array(raw) {
        Ok(ids) => (ids, false),
        Err(()) => (Vec::new(), true),
    }
}

fn try_parse_id_array(raw: &str) -> Result<Vec<i64>, ()> {
    let element: Value = serde_json::from_str(raw).map_err(|_| ())?;
    let values = element.as_array().ok_or(())?;
    let mut seen = HashSet::new();
    let mut ids = Vec::new();

    for value in values {
        let id = primitive_long(value)?;
        if seen.insert(id) {
            ids.push(id);
        }
    }

    Ok(ids)
}

fn parse_period_spec(period: Option<&str>, default_days: i64) -> (PeriodSpecInfo, bool) {
    match period {
        Some("all") => (
            PeriodSpecInfo {
                kind: PeriodSpecKind::All,
                days: None,
                seconds: None,
            },
            false,
        ),
        Some(value) if value.ends_with('d') => {
            let days = value
                .strip_suffix('d')
                .and_then(|days| days.parse::<i64>().ok())
                .filter(|days| *days > 0);
            days.map_or_else(
                || (period_days(default_days), true),
                |days| (period_days(days), false),
            )
        }
        _ => (period_days(default_days), true),
    }
}

const fn period_days(days: i64) -> PeriodSpecInfo {
    PeriodSpecInfo {
        kind: PeriodSpecKind::Days,
        days: Some(days),
        seconds: Some(days.wrapping_mul(SECONDS_PER_DAY)),
    }
}

fn primitive_content(value: &Value) -> Result<String, ()> {
    match value {
        Value::String(value) => Ok(value.clone()),
        Value::Number(value) => Ok(value.to_string()),
        Value::Bool(value) => Ok(value.to_string()),
        Value::Null | Value::Array(_) | Value::Object(_) => Err(()),
    }
}

fn primitive_long(value: &Value) -> Result<i64, ()> {
    match value {
        Value::Number(number) => number.as_i64().ok_or(()),
        Value::String(value) => value.parse::<i64>().map_err(|_| ()),
        Value::Bool(_) | Value::Null | Value::Array(_) | Value::Object(_) => Err(()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    #[test]
    fn parsers_batch_extracts_room_title_from_array_or_object_meta() {
        let array_meta = serde_json::json!([
            {"type": 1, "content": "ignore"},
            {"type": 3, "content": "  공지방  "}
        ])
        .to_string();
        let object_meta = serde_json::json!({
            "noticeActivityContents": [
                {"type": "3", "content": "오픈채팅"}
            ]
        })
        .to_string();
        let request = serde_json::json!({
            "items": [
                {"kind": "roomTitle", "meta": array_meta},
                {"kind": "roomTitle", "meta": object_meta}
            ]
        });

        let response = parser_batch_json(request.to_string().as_bytes()).expect("parser response");
        let response: Value = serde_json::from_slice(&response).expect("json response");

        assert_eq!(response["items"][0]["roomTitle"], "공지방");
        assert_eq!(response["items"][0]["fallback"], false);
        assert_eq!(response["items"][1]["roomTitle"], "오픈채팅");
    }

    #[test]
    fn parsers_batch_returns_room_title_fallback_for_malformed_meta() {
        let response = parser_batch_json(
            br#"{"items":[{"kind":"roomTitle","meta":"not-json"},{"kind":"roomTitle","meta":"   "}]}"#,
        )
        .expect("parser response");
        let response: Value = serde_json::from_slice(&response).expect("json response");

        assert_eq!(response["items"][0]["roomTitle"], Value::Null);
        assert_eq!(response["items"][0]["fallback"], true);
        assert_eq!(response["items"][1]["roomTitle"], Value::Null);
        assert_eq!(response["items"][1]["fallback"], false);
    }

    #[test]
    fn parsers_batch_extracts_notices_and_skips_malformed_notice_items() {
        let meta = serde_json::json!({
            "noticeActivityContents": [
                {"message": "첫 공지", "authorId": 42, "createdAt": "1000"},
                {"authorId": 11, "createdAt": 12},
                {"message": "둘째 공지"}
            ]
        })
        .to_string();
        let request = serde_json::json!({
            "items": [{"kind": "notices", "meta": meta}]
        });

        let response = parser_batch_json(request.to_string().as_bytes()).expect("parser response");
        let response: Value = serde_json::from_slice(&response).expect("json response");

        assert_eq!(response["items"][0]["notices"][0]["content"], "첫 공지");
        assert_eq!(response["items"][0]["notices"][0]["authorId"], 42);
        assert_eq!(response["items"][0]["notices"][0]["updatedAt"], 1000);
        assert_eq!(response["items"][0]["notices"][1]["content"], "둘째 공지");
        assert_eq!(response["items"][0]["notices"][1]["authorId"], 0);
        assert_eq!(response["items"][0]["fallback"], true);
    }

    #[test]
    fn parsers_batch_returns_empty_notices_for_malformed_top_level_meta() {
        let response = parser_batch_json(br#"{"items":[{"kind":"notices","meta":"[]"}]}"#)
            .expect("parser response");
        let response: Value = serde_json::from_slice(&response).expect("json response");

        assert_eq!(response["items"][0]["notices"].as_array().unwrap().len(), 0);
        assert_eq!(response["items"][0]["fallback"], true);
    }

    #[test]
    fn parsers_batch_parses_id_array_with_deduplication_and_malformed_fallback() {
        let request = br#"{
            "items": [
                {"kind":"idArray","raw":"[1,\"2\",1]"},
                {"kind":"idArray","raw":"[1,\"bad\"]"},
                {"kind":"idArray","raw":"[]"}
            ]
        }"#;
        let response = parser_batch_json(request).expect("parser response");
        let response: Value = serde_json::from_slice(&response).expect("json response");

        assert_eq!(response["items"][0]["ids"], serde_json::json!([1, 2]));
        assert_eq!(response["items"][0]["fallback"], false);
        assert_eq!(response["items"][1]["ids"], serde_json::json!([]));
        assert_eq!(response["items"][1]["fallback"], true);
        assert_eq!(response["items"][2]["fallback"], false);
    }

    #[test]
    fn parsers_batch_parses_period_spec_and_defaults_without_fallback_required() {
        let request = br#"{
            "items": [
                {"kind":"periodSpec","period":"all"},
                {"kind":"periodSpec","period":"14d"},
                {"kind":"periodSpec","period":"0d","defaultDays":3},
                {"kind":"periodSpec","period":" all "}
            ]
        }"#;
        let response = parser_batch_json(request).expect("parser response");
        let response: Value = serde_json::from_slice(&response).expect("json response");

        assert_eq!(response["items"][0]["periodSpec"]["kind"], "all");
        assert_eq!(response["items"][0]["periodSpec"]["seconds"], Value::Null);
        assert_eq!(response["items"][1]["periodSpec"]["kind"], "days");
        assert_eq!(response["items"][1]["periodSpec"]["days"], 14);
        assert_eq!(
            response["items"][1]["periodSpec"]["seconds"],
            14 * SECONDS_PER_DAY
        );
        assert_eq!(response["items"][1]["fallback"], false);
        assert_eq!(response["items"][1]["usedDefault"], false);
        assert_eq!(response["items"][2]["periodSpec"]["days"], 3);
        assert_eq!(response["items"][2]["fallback"], false);
        assert_eq!(response["items"][2]["usedDefault"], true);
        assert_eq!(
            response["items"][3]["periodSpec"]["days"],
            DEFAULT_PERIOD_DAYS
        );
        assert_eq!(response["items"][3]["fallback"], false);
        assert_eq!(response["items"][3]["usedDefault"], true);
    }

    #[test]
    fn parsers_batch_rejects_unknown_variant() {
        let error = parser_batch_json(br#"{"items":[{"kind":"unknown"}]}"#)
            .expect_err("unknown kind should fail envelope deserialization");

        assert!(matches!(error, NativeCoreError::InvalidRequest(_)));
    }
}
