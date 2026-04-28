use crate::errors::{NativeCoreError, NativeCoreResult};
use serde::de;
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::Value;

pub const DEFAULT_WEBHOOK_ROUTE: &str = "default";
pub const CHATBOTGO_ROUTE: &str = "chatbotgo";
pub const SETTLEMENT_ROUTE: &str = "settlement";
pub const IMAGE_MESSAGE_TYPE_PHOTO: &str = "2";
pub const EVENT_TYPE_NICKNAME_CHANGE: &str = "nickname_change";
pub const EVENT_TYPE_PROFILE_CHANGE: &str = "profile_change";

pub const SETTLEMENT_COMMAND_PREFIXES: &[&str] = &["!정산", "!정산완료"];
pub const CHATBOTGO_COMMAND_PREFIXES: &[&str] = &[
    "!질문",
    "!프로필",
    "!누구",
    "!세션",
    "!모델",
    "!온도",
    "!이미지",
    "!그림",
    "!리셋",
    "!관리자",
    "!한강",
    "!시뮬",
    "!법령",
    "!변환",
];

#[derive(Debug, Deserialize)]
struct RoutingBatchRequest {
    items: Vec<RoutingBatchItem>,
    #[serde(default, rename = "commandRoutePrefixes")]
    command_route_prefixes: RouteList,
    #[serde(default, rename = "imageMessageTypeRoutes")]
    image_message_type_routes: RouteList,
}

#[derive(Debug, Deserialize)]
struct RoutingBatchItem {
    text: String,
    #[serde(default, rename = "messageType")]
    message_type: Option<String>,
    #[serde(default, rename = "commandRoutePrefixes")]
    command_route_prefixes: Option<RouteList>,
    #[serde(default, rename = "imageMessageTypeRoutes")]
    image_message_type_routes: Option<RouteList>,
}

#[derive(Debug, Serialize)]
struct RoutingBatchResponse {
    items: Vec<RoutingBatchResult>,
}

#[derive(Debug, Serialize)]
struct RoutingBatchResult {
    ok: bool,
    kind: CommandKind,
    #[serde(rename = "normalizedText")]
    normalized_text: String,
    #[serde(rename = "webhookRoute")]
    webhook_route: Option<String>,
    #[serde(rename = "eventRoute")]
    event_route: Option<String>,
    #[serde(rename = "imageRoute")]
    image_route: Option<String>,
    #[serde(rename = "targetRoute")]
    target_route: Option<String>,
    #[serde(rename = "errorKind")]
    error_kind: Option<&'static str>,
    error: Option<String>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub(crate) enum CommandKind {
    None,
    Comment,
    Webhook,
}

#[derive(Debug, Eq, PartialEq)]
struct ParsedCommand {
    kind: CommandKind,
    normalized_text: String,
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub(crate) struct RoutingDecision {
    pub(crate) kind: CommandKind,
    pub(crate) normalized_text: String,
    pub(crate) webhook_route: Option<String>,
    pub(crate) event_route: Option<String>,
    pub(crate) image_route: Option<String>,
    pub(crate) target_route: Option<String>,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub(crate) struct RouteList {
    entries: Vec<RouteEntry>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct RouteEntry {
    route: String,
    values: Vec<String>,
}

impl<'de> Deserialize<'de> for RouteList {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let value = Value::deserialize(deserializer)?;
        Self::from_value(value).map_err(de::Error::custom)
    }
}

impl RouteList {
    fn from_value(value: Value) -> Result<Self, String> {
        match value {
            Value::Null => Ok(Self::default()),
            Value::Array(items) => items
                .into_iter()
                .map(Self::entry_from_array_item)
                .collect::<Result<Vec<_>, _>>()
                .map(|entries| Self { entries }),
            Value::Object(entries) => entries
                .into_iter()
                .map(|(route, values)| {
                    Ok(RouteEntry {
                        route,
                        values: string_array(values)?,
                    })
                })
                .collect::<Result<Vec<_>, _>>()
                .map(|entries| Self { entries }),
            other => Err(format!("route list must be array or object, got {other}")),
        }
    }

    fn entry_from_array_item(value: Value) -> Result<RouteEntry, String> {
        let mut object = match value {
            Value::Object(object) => object,
            other => return Err(format!("route entry must be object, got {other}")),
        };
        let route = match object.remove("route") {
            Some(Value::String(route)) => route,
            Some(other) => return Err(format!("route must be string, got {other}")),
            None => return Err("route entry missing route".to_owned()),
        };
        let values = ["prefixes", "messageTypes", "types", "values"]
            .into_iter()
            .find_map(|key| object.remove(key))
            .ok_or_else(|| "route entry missing prefixes/messageTypes/types/values".to_owned())
            .and_then(string_array)?;

        Ok(RouteEntry { route, values })
    }

    const fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    #[cfg(test)]
    fn default_command_prefixes() -> Self {
        Self {
            entries: vec![
                RouteEntry {
                    route: SETTLEMENT_ROUTE.to_owned(),
                    values: SETTLEMENT_COMMAND_PREFIXES
                        .iter()
                        .map(ToString::to_string)
                        .collect(),
                },
                RouteEntry {
                    route: CHATBOTGO_ROUTE.to_owned(),
                    values: CHATBOTGO_COMMAND_PREFIXES
                        .iter()
                        .map(ToString::to_string)
                        .collect(),
                },
            ],
        }
    }

    #[cfg(test)]
    fn default_image_message_types() -> Self {
        Self {
            entries: vec![RouteEntry {
                route: CHATBOTGO_ROUTE.to_owned(),
                values: vec![IMAGE_MESSAGE_TYPE_PHOTO.to_owned()],
            }],
        }
    }
}

fn string_array(value: Value) -> Result<Vec<String>, String> {
    match value {
        Value::Array(values) => values
            .into_iter()
            .map(|value| match value {
                Value::String(value) => Ok(value),
                other => Err(format!("route value must be string, got {other}")),
            })
            .collect(),
        other => Err(format!("route values must be array, got {other}")),
    }
}

pub fn routing_batch_json(request_bytes: &[u8]) -> NativeCoreResult<Vec<u8>> {
    let request: RoutingBatchRequest = serde_json::from_slice(request_bytes)
        .map_err(|error| NativeCoreError::InvalidRequest(error.to_string()))?;
    let command_routes = request.command_route_prefixes;
    let image_routes = request.image_message_type_routes;
    let response = RoutingBatchResponse {
        items: request
            .items
            .into_iter()
            .map(|item| {
                let item_command_routes = item
                    .command_route_prefixes
                    .as_ref()
                    .unwrap_or(&command_routes);
                let item_image_routes = item
                    .image_message_type_routes
                    .as_ref()
                    .unwrap_or(&image_routes);
                let decision = route_command(
                    &item.text,
                    item.message_type.as_deref(),
                    item_command_routes,
                    item_image_routes,
                );

                RoutingBatchResult {
                    ok: true,
                    kind: decision.kind,
                    normalized_text: decision.normalized_text,
                    webhook_route: decision.webhook_route,
                    event_route: decision.event_route,
                    image_route: decision.image_route,
                    target_route: decision.target_route,
                    error_kind: None,
                    error: None,
                }
            })
            .collect(),
    };
    serde_json::to_vec(&response)
        .map_err(|error| NativeCoreError::InvalidResponse(error.to_string()))
}

pub(crate) fn route_command(
    text: &str,
    message_type: Option<&str>,
    command_route_prefixes: &RouteList,
    image_message_type_routes: &RouteList,
) -> RoutingDecision {
    let parsed_command = parse_command(text);
    let webhook_route = resolve_webhook_route(&parsed_command, command_route_prefixes);
    let event_route = resolve_event_route(message_type);
    let image_route = resolve_image_route(message_type, image_message_type_routes);
    let target_route = webhook_route
        .clone()
        .or_else(|| event_route.clone())
        .or_else(|| image_route.clone());

    RoutingDecision {
        kind: parsed_command.kind,
        normalized_text: parsed_command.normalized_text,
        webhook_route,
        event_route,
        image_route,
        target_route,
    }
}

fn parse_command(message: &str) -> ParsedCommand {
    let normalized_text = message.trim_start().to_owned();
    let kind = if normalized_text.starts_with("//") {
        CommandKind::Comment
    } else if normalized_text.starts_with('!') || normalized_text.starts_with('/') {
        CommandKind::Webhook
    } else {
        CommandKind::None
    };

    ParsedCommand {
        kind,
        normalized_text,
    }
}

fn resolve_webhook_route(
    parsed_command: &ParsedCommand,
    command_route_prefixes: &RouteList,
) -> Option<String> {
    if parsed_command.kind != CommandKind::Webhook {
        return None;
    }

    if command_route_prefixes.is_empty() {
        return Some(DEFAULT_WEBHOOK_ROUTE.to_owned());
    }

    command_route_prefixes
        .entries
        .iter()
        .find(|entry| {
            entry
                .values
                .iter()
                .any(|prefix| matches_command_prefix(&parsed_command.normalized_text, prefix))
        })
        .map(|entry| entry.route.clone())
        .or_else(|| Some(DEFAULT_WEBHOOK_ROUTE.to_owned()))
}

fn resolve_event_route(message_type: Option<&str>) -> Option<String> {
    match message_type.map(str::trim).unwrap_or_default() {
        EVENT_TYPE_NICKNAME_CHANGE | EVENT_TYPE_PROFILE_CHANGE => Some(CHATBOTGO_ROUTE.to_owned()),
        _ => None,
    }
}

fn resolve_image_route(
    message_type: Option<&str>,
    image_message_type_routes: &RouteList,
) -> Option<String> {
    let normalized_type = message_type.map(str::trim).unwrap_or_default();
    if normalized_type.is_empty() || image_message_type_routes.is_empty() {
        return None;
    }

    image_message_type_routes
        .entries
        .iter()
        .find(|entry| entry.values.iter().any(|value| value == normalized_type))
        .map(|entry| entry.route.clone())
}

fn matches_command_prefix(raw: &str, command: &str) -> bool {
    if !raw.starts_with(command) {
        return false;
    }
    if raw.len() == command.len() {
        return true;
    }

    raw[command.len()..]
        .chars()
        .next()
        .is_some_and(char::is_whitespace)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    #[test]
    fn routing_batch_parses_command_kind_and_normalized_text() {
        let response = routing_batch_json(
            br#"{"items":[{"text":"   // note"},{"text":"\t/hello"},{"text":"hello"}]}"#,
        )
        .expect("routing response");
        let response: Value = serde_json::from_slice(&response).expect("json response");

        assert_eq!(response["items"][0]["kind"], "COMMENT");
        assert_eq!(response["items"][0]["normalizedText"], "// note");
        assert_eq!(response["items"][0]["webhookRoute"], Value::Null);
        assert_eq!(response["items"][1]["kind"], "WEBHOOK");
        assert_eq!(response["items"][1]["normalizedText"], "/hello");
        assert_eq!(response["items"][1]["webhookRoute"], DEFAULT_WEBHOOK_ROUTE);
        assert_eq!(response["items"][2]["kind"], "NONE");
    }

    #[test]
    fn routing_batch_matches_default_routing_constants_when_snapshot_supplied() {
        let command_route_prefixes: Vec<_> = RouteList::default_command_prefixes()
            .entries
            .iter()
            .map(|entry| serde_json::json!({"route": &entry.route, "prefixes": &entry.values}))
            .collect();
        let image_message_type_routes: Vec<_> = RouteList::default_image_message_types()
            .entries
            .iter()
            .map(|entry| serde_json::json!({"route": &entry.route, "messageTypes": &entry.values}))
            .collect();
        let request = serde_json::json!({
            "commandRoutePrefixes": command_route_prefixes,
            "imageMessageTypeRoutes": image_message_type_routes,
            "items": [
                {"text": "!정산"},
                {"text": "!질문 hello"},
                {"text": "!정산완료됨"},
                {"text": "photo", "messageType": " 2 "},
                {"text": "event", "messageType": "nickname_change"}
            ]
        });
        let response =
            routing_batch_json(request.to_string().as_bytes()).expect("routing response");
        let response: Value = serde_json::from_slice(&response).expect("json response");

        assert_eq!(response["items"][0]["webhookRoute"], SETTLEMENT_ROUTE);
        assert_eq!(response["items"][0]["targetRoute"], SETTLEMENT_ROUTE);
        assert_eq!(response["items"][1]["webhookRoute"], CHATBOTGO_ROUTE);
        assert_eq!(response["items"][2]["webhookRoute"], DEFAULT_WEBHOOK_ROUTE);
        assert_eq!(response["items"][3]["imageRoute"], CHATBOTGO_ROUTE);
        assert_eq!(response["items"][3]["targetRoute"], CHATBOTGO_ROUTE);
        assert_eq!(response["items"][4]["eventRoute"], CHATBOTGO_ROUTE);
    }

    #[test]
    fn routing_batch_accepts_object_route_snapshot_and_first_matching_route_wins() {
        let request = br#"{
            "commandRoutePrefixes": [
                {"route":"first","prefixes":["!cmd"]},
                {"route":"second","prefixes":["!cmd"]}
            ],
            "imageMessageTypeRoutes": {"img":["2"]},
            "items": [
                {"text":"!cmd arg", "messageType":"2"},
                {"text":"!cmdSuffix", "messageType":"2"}
            ]
        }"#;
        let response = routing_batch_json(request).expect("routing response");
        let response: Value = serde_json::from_slice(&response).expect("json response");

        assert_eq!(response["items"][0]["webhookRoute"], "first");
        assert_eq!(response["items"][0]["targetRoute"], "first");
        assert_eq!(response["items"][1]["webhookRoute"], DEFAULT_WEBHOOK_ROUTE);
        assert_eq!(response["items"][1]["imageRoute"], "img");
        assert_eq!(response["items"][1]["targetRoute"], DEFAULT_WEBHOOK_ROUTE);
    }

    #[test]
    fn routing_batch_rejects_malformed_json() {
        let error = routing_batch_json(b"not-json").expect_err("invalid json should fail");

        assert!(matches!(error, NativeCoreError::InvalidRequest(_)));
    }
}
