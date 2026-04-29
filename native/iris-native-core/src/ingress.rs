use crate::errors::{NativeCoreError, NativeCoreResult};
use crate::routing::{self, RouteList, RoutingDecision};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};

#[derive(Debug, Deserialize)]
struct IngressBatchRequest {
    items: Vec<IngressBatchItem>,
    #[serde(default, rename = "commandRoutePrefixes")]
    command_route_prefixes: RouteList,
    #[serde(default, rename = "imageMessageTypeRoutes")]
    image_message_type_routes: RouteList,
    #[serde(default, rename = "eventTypeRoutes")]
    event_type_routes: RouteList,
}

#[derive(Debug, Deserialize)]
struct IngressBatchItem {
    command: Value,
    #[serde(rename = "messageId")]
    message_id: Option<String>,
    #[serde(default, rename = "commandRoutePrefixes")]
    command_route_prefixes: Option<RouteList>,
    #[serde(default, rename = "imageMessageTypeRoutes")]
    image_message_type_routes: Option<RouteList>,
    #[serde(default, rename = "eventTypeRoutes")]
    event_type_routes: Option<RouteList>,
}

#[derive(Debug, Serialize)]
struct IngressBatchResponse {
    items: Vec<IngressBatchResult>,
}

#[derive(Debug, Serialize)]
struct IngressBatchResult {
    ok: bool,
    kind: routing::CommandKind,
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
    #[serde(rename = "payloadJson")]
    payload_json: Option<String>,
    #[serde(rename = "messageId")]
    message_id: Option<String>,
    #[serde(rename = "errorKind")]
    error_kind: Option<&'static str>,
    error: Option<String>,
}

pub fn ingress_batch_json(request_bytes: &[u8]) -> NativeCoreResult<Vec<u8>> {
    let request: IngressBatchRequest = serde_json::from_slice(request_bytes)
        .map_err(|error| NativeCoreError::InvalidRequest(error.to_string()))?;
    let command_routes = request.command_route_prefixes;
    let image_routes = request.image_message_type_routes;
    let event_routes = request.event_type_routes;
    let response = IngressBatchResponse {
        items: request
            .items
            .into_iter()
            .map(|item| {
                let IngressBatchItem {
                    command,
                    message_id,
                    command_route_prefixes,
                    image_message_type_routes,
                    event_type_routes,
                } = item;
                let item_command_routes =
                    command_route_prefixes.as_ref().unwrap_or(&command_routes);
                let item_image_routes = image_message_type_routes.as_ref().unwrap_or(&image_routes);
                let item_event_routes = event_type_routes.as_ref().unwrap_or(&event_routes);

                plan_ingress_item(
                    command,
                    message_id,
                    item_command_routes,
                    item_image_routes,
                    item_event_routes,
                )
            })
            .collect(),
    };
    serde_json::to_vec(&response)
        .map_err(|error| NativeCoreError::InvalidResponse(error.to_string()))
}

fn plan_ingress_item(
    mut command: Value,
    message_id: Option<String>,
    command_route_prefixes: &RouteList,
    image_message_type_routes: &RouteList,
    event_type_routes: &RouteList,
) -> IngressBatchResult {
    let Some(command_object) = command.as_object() else {
        return IngressBatchResult::without_decision_error(&NativeCoreError::InvalidRequest(
            "command must be object".to_owned(),
        ));
    };
    let text = match required_string(command_object, "text") {
        Ok(text) => text.to_owned(),
        Err(error) => return IngressBatchResult::without_decision_error(&error),
    };
    let message_type = match optional_string(command_object, "messageType") {
        Ok(message_type) => message_type.map(str::to_owned),
        Err(error) => return IngressBatchResult::without_decision_error(&error),
    };

    let decision = routing::route_command(
        &text,
        message_type.as_deref(),
        command_route_prefixes,
        image_message_type_routes,
        event_type_routes,
    );
    let Some(target_route) = decision.target_route.as_deref() else {
        return IngressBatchResult::success(decision, None, None);
    };

    if let Some(command_object) = command.as_object_mut() {
        command_object.insert(
            "text".to_owned(),
            Value::String(decision.normalized_text.clone()),
        );
    }
    let Some(resolved_message_id) =
        message_id.or_else(|| build_routing_message_id(&command, target_route))
    else {
        return IngressBatchResult::with_decision_error(
            decision,
            &NativeCoreError::InvalidRequest(
                "messageId or positive sourceLogId is required".to_owned(),
            ),
        );
    };

    match crate::webhook::build_webhook_payload(&command, target_route, &resolved_message_id) {
        Ok(payload_json) => {
            IngressBatchResult::success(decision, Some(payload_json), Some(resolved_message_id))
        }
        Err(error) => IngressBatchResult::with_decision_error(decision, &error),
    }
}

impl IngressBatchResult {
    fn success(
        decision: RoutingDecision,
        payload_json: Option<String>,
        message_id: Option<String>,
    ) -> Self {
        Self::from_decision(decision, true, payload_json, message_id, None, None)
    }

    fn with_decision_error(decision: RoutingDecision, error: &NativeCoreError) -> Self {
        Self::from_decision(
            decision,
            false,
            None,
            None,
            Some(error.kind()),
            Some(error.to_string()),
        )
    }

    fn without_decision_error(error: &NativeCoreError) -> Self {
        Self {
            ok: false,
            kind: routing::CommandKind::None,
            normalized_text: String::new(),
            webhook_route: None,
            event_route: None,
            image_route: None,
            target_route: None,
            payload_json: None,
            message_id: None,
            error_kind: Some(error.kind()),
            error: Some(error.to_string()),
        }
    }

    fn from_decision(
        decision: RoutingDecision,
        ok: bool,
        payload_json: Option<String>,
        message_id: Option<String>,
        error_kind: Option<&'static str>,
        error: Option<String>,
    ) -> Self {
        let RoutingDecision {
            kind,
            normalized_text,
            webhook_route,
            event_route,
            image_route,
            target_route,
        } = decision;

        Self {
            ok,
            kind,
            normalized_text,
            webhook_route,
            event_route,
            image_route,
            target_route,
            payload_json,
            message_id,
            error_kind,
            error,
        }
    }
}

fn build_routing_message_id(command: &Value, route: &str) -> Option<String> {
    let source_log_id = command
        .as_object()
        .and_then(|object| object.get("sourceLogId"))
        .and_then(|value| match value {
            Value::Number(number) => number.as_i64(),
            Value::String(value) => value.parse::<i64>().ok(),
            _ => None,
        });
    if let Some(source_log_id) = source_log_id
        && source_log_id > 0
    {
        return Some(format!("kakao-log-{source_log_id}-{route}"));
    }

    let object = command.as_object()?;
    let fingerprint_source = [
        route.to_owned(),
        optional_fingerprint_string(object, "room"),
        optional_fingerprint_string(object, "userId"),
        optional_fingerprint_string(object, "messageType"),
        optional_fingerprint_string(object, "text"),
        optional_fingerprint_string(object, "chatLogId"),
        optional_fingerprint_string(object, "attachment"),
        optional_fingerprint_json(object, "eventPayload"),
    ]
    .join("|");
    Some(format!(
        "kakao-system-{}-{route}",
        sha256_hex(fingerprint_source.as_bytes())
    ))
}

fn optional_fingerprint_string(object: &Map<String, Value>, key: &str) -> String {
    match object.get(key) {
        Some(Value::String(value)) => value.clone(),
        Some(Value::Null) | None => String::new(),
        Some(value) => value.to_string(),
    }
}

fn optional_fingerprint_json(object: &Map<String, Value>, key: &str) -> String {
    object.get(key).map_or_else(String::new, Value::to_string)
}

fn sha256_hex(input: &[u8]) -> String {
    hex::encode(Sha256::digest(input))
}

fn required_string<'a>(object: &'a Map<String, Value>, key: &str) -> NativeCoreResult<&'a str> {
    match object.get(key) {
        Some(Value::String(value)) => Ok(value),
        Some(other) => Err(NativeCoreError::InvalidRequest(format!(
            "{key} must be string, got {other}"
        ))),
        None => Err(NativeCoreError::InvalidRequest(format!(
            "{key} is required"
        ))),
    }
}

fn optional_string<'a>(
    object: &'a Map<String, Value>,
    key: &str,
) -> NativeCoreResult<Option<&'a str>> {
    match object.get(key) {
        Some(Value::String(value)) => Ok(Some(value)),
        Some(Value::Null) | None => Ok(None),
        Some(other) => Err(NativeCoreError::InvalidRequest(format!(
            "{key} must be string or null, got {other}"
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn assert_routing_message_id(command: &Value, route: &str, expected: &str) {
        assert_eq!(
            build_routing_message_id(command, route).as_deref(),
            Some(expected)
        );
    }

    #[test]
    fn routing_message_id_uses_positive_numeric_source_log_id() {
        assert_routing_message_id(
            &serde_json::json!({
                "sourceLogId": 123,
                "text": "ignored"
            }),
            "default",
            "kakao-log-123-default",
        );
    }

    #[test]
    fn routing_message_id_accepts_positive_string_source_log_id() {
        assert_routing_message_id(
            &serde_json::json!({
                "sourceLogId": "456",
                "text": "ignored"
            }),
            "events",
            "kakao-log-456-events",
        );
    }

    #[test]
    fn routing_message_id_hashes_system_fingerprint_in_kotlin_field_order() {
        assert_routing_message_id(
            &serde_json::json!({
                "sourceLogId": 0,
                "room": "room-1",
                "userId": "user-1",
                "text": "system ping"
            }),
            "default",
            "kakao-system-0a099065e559a91452c7b69e753371e4967ce62aab2f22d9f88ce1f8f7230c3d-default",
        );
    }

    #[test]
    fn routing_message_id_hashes_null_missing_and_blank_optional_fields() {
        assert_routing_message_id(
            &serde_json::json!({
                "sourceLogId": -1,
                "room": "room-1",
                "userId": "user-1",
                "messageType": null,
                "text": "blank ping",
                "attachment": ""
            }),
            "default",
            "kakao-system-6ba7332b3d89638d46807477584e2aa6ece918ee17bf65e2c13ba2af386675c2-default",
        );
    }

    #[test]
    fn routing_message_id_hashes_unicode_route_room_user_and_text() {
        assert_routing_message_id(
            &serde_json::json!({
                "sourceLogId": 0,
                "room": "방🌙",
                "userId": "사용자-1",
                "messageType": "notice",
                "text": "안녕 👋",
                "chatLogId": "로그-0"
            }),
            "챗봇",
            "kakao-system-c450221084d82663c98961e375d00fcd7c522166d10f5fb80fff8c2f286d197f-챗봇",
        );
    }

    #[test]
    fn routing_message_id_hashes_attachment_json_string() {
        assert_routing_message_id(
            &serde_json::json!({
                "sourceLogId": -1,
                "room": "room",
                "userId": "u",
                "messageType": "2",
                "text": "",
                "attachment": "{\"image\":true,\"name\":\"a.png\"}"
            }),
            "image",
            "kakao-system-9bdca7d08bff39db95a9c4303cda82c159db5609335a0e80dbc5e6e6fdd686da-image",
        );
    }

    #[test]
    fn routing_message_id_hashes_structured_event_payload_json_order() {
        let event_payload = serde_json::json!({
            "type": "nickname_change",
            "userId": 123,
            "newNickname": "새별"
        });

        assert_routing_message_id(
            &serde_json::json!({
                "sourceLogId": 0,
                "room": "room",
                "userId": "0",
                "messageType": "nickname_change",
                "text": event_payload.to_string(),
                "eventPayload": event_payload
            }),
            "events",
            "kakao-system-ca7dda7a51909dcac3bffd81327684d8fa63f0291c56d9bf7a742755061c1398-events",
        );
    }

    #[test]
    fn ingress_batch_plans_routing_and_payloads_for_enriched_commands() {
        let request = serde_json::json!({
            "commandRoutePrefixes": [
                {"route": "settlement", "values": ["!정산"]},
                {"route": "chatbotgo", "values": ["!질문"]}
            ],
            "imageMessageTypeRoutes": [
                {"route": "chatbotgo", "values": ["2"]}
            ],
            "items": [
                {
                    "messageId": "msg-001",
                    "command": {
                        "sourceLogId": 100,
                        "text": "   !정산 lunch",
                        "room": "Room",
                        "sender": "Sender",
                        "userId": "123",
                        "messageType": "1"
                    }
                },
                {
                    "messageId": "msg-002",
                    "command": {
                        "sourceLogId": 101,
                        "text": "photo",
                        "room": "Room",
                        "sender": "Sender",
                        "userId": "456",
                        "messageType": "2",
                        "attachment": "{\"image\":true}"
                    }
                }
            ]
        });

        let response =
            ingress_batch_json(request.to_string().as_bytes()).expect("ingress response");
        let response: Value = serde_json::from_slice(&response).expect("json response");

        assert_eq!(response["items"][0]["ok"], true);
        assert_eq!(response["items"][0]["kind"], "WEBHOOK");
        assert_eq!(response["items"][0]["normalizedText"], "!정산 lunch");
        assert_eq!(response["items"][0]["webhookRoute"], "settlement");
        assert_eq!(response["items"][0]["targetRoute"], "settlement");
        let first_payload: Value = serde_json::from_str(
            response["items"][0]["payloadJson"]
                .as_str()
                .expect("first payload"),
        )
        .expect("first payload json");
        assert_eq!(first_payload["route"], "settlement");
        assert_eq!(first_payload["messageId"], "msg-001");
        assert_eq!(first_payload["text"], "!정산 lunch");
        assert_eq!(first_payload["type"], "1");

        assert_eq!(response["items"][1]["ok"], true);
        assert_eq!(response["items"][1]["kind"], "NONE");
        assert_eq!(response["items"][1]["imageRoute"], "chatbotgo");
        assert_eq!(response["items"][1]["targetRoute"], "chatbotgo");
        let second_payload: Value = serde_json::from_str(
            response["items"][1]["payloadJson"]
                .as_str()
                .expect("second payload"),
        )
        .expect("second payload json");
        assert_eq!(second_payload["route"], "chatbotgo");
        assert_eq!(second_payload["attachment"], "{\"image\":true}");
    }

    #[test]
    fn ingress_batch_generates_log_message_id_when_missing() {
        let request = serde_json::json!({
            "commandRoutePrefixes": [
                {"route": "settlement", "values": ["!정산"]}
            ],
            "items": [{
                "command": {
                    "sourceLogId": 100,
                    "text": "!정산 lunch",
                    "room": "Room",
                    "sender": "Sender",
                    "userId": "123"
                }
            }]
        });

        let response =
            ingress_batch_json(request.to_string().as_bytes()).expect("ingress response");
        let response: Value = serde_json::from_slice(&response).expect("json response");
        let payload: Value = serde_json::from_str(
            response["items"][0]["payloadJson"]
                .as_str()
                .expect("payload"),
        )
        .expect("payload json");

        assert_eq!(
            response["items"][0]["messageId"],
            "kakao-log-100-settlement"
        );
        assert_eq!(payload["messageId"], "kakao-log-100-settlement");
    }

    #[test]
    fn ingress_batch_generates_system_message_id_for_non_log_events() {
        let event_payload = serde_json::json!({
            "type": "nickname_change",
            "chatId": 1234,
            "linkId": null,
            "userId": 55,
            "oldNickname": "A",
            "newNickname": "B",
            "timestamp": 1_700_000_000
        });
        let request = serde_json::json!({
            "eventTypeRoutes": {"chatbotgo": ["nickname_change"]},
            "items": [{
                "command": {
                    "sourceLogId": -1,
                    "text": event_payload.to_string(),
                    "room": "1234",
                    "sender": "iris-system",
                    "userId": "0",
                    "messageType": "nickname_change",
                    "eventPayload": event_payload
                }
            }]
        });

        let response =
            ingress_batch_json(request.to_string().as_bytes()).expect("ingress response");
        let response: Value = serde_json::from_slice(&response).expect("json response");
        let payload: Value = serde_json::from_str(
            response["items"][0]["payloadJson"]
                .as_str()
                .expect("payload"),
        )
        .expect("payload json");

        assert_eq!(response["items"][0]["ok"], true);
        assert_eq!(response["items"][0]["targetRoute"], "chatbotgo");
        assert_eq!(
            response["items"][0]["messageId"],
            "kakao-system-70a588e355358228ed7ad1417f0bd6b9072b07e15970e3e3d0e89b997989ec77-chatbotgo"
        );
        assert_eq!(
            payload["messageId"],
            "kakao-system-70a588e355358228ed7ad1417f0bd6b9072b07e15970e3e3d0e89b997989ec77-chatbotgo"
        );
        assert_eq!(payload["eventPayload"], event_payload);
    }

    #[test]
    fn ingress_batch_generates_system_message_id_after_text_normalization() {
        let request = serde_json::json!({
            "commandRoutePrefixes": [
                {"route": "default", "values": ["!"]}
            ],
            "items": [{
                "command": {
                    "sourceLogId": 0,
                    "text": "   !ping",
                    "room": "room",
                    "sender": "sender",
                    "userId": "42"
                }
            }]
        });

        let response =
            ingress_batch_json(request.to_string().as_bytes()).expect("ingress response");
        let response: Value = serde_json::from_slice(&response).expect("json response");
        let payload: Value = serde_json::from_str(
            response["items"][0]["payloadJson"]
                .as_str()
                .expect("payload"),
        )
        .expect("payload json");

        assert_eq!(response["items"][0]["ok"], true);
        assert_eq!(response["items"][0]["normalizedText"], "!ping");
        assert_eq!(
            response["items"][0]["messageId"],
            "kakao-system-36411067df138404d82de5f4948a184866a25c4730f59b7f4d651d36f42d335f-default"
        );
        assert_eq!(
            payload["messageId"],
            "kakao-system-36411067df138404d82de5f4948a184866a25c4730f59b7f4d651d36f42d335f-default"
        );
        assert_eq!(payload["text"], "!ping");
    }

    #[test]
    fn ingress_batch_returns_item_error_when_payload_for_target_route_is_invalid() {
        let request = serde_json::json!({
            "items": [{
                "messageId": "msg-err",
                "command": {
                    "text": "!missing",
                    "room": "Room",
                    "sender": "Sender",
                    "userId": "123"
                }
            }]
        });

        let response =
            ingress_batch_json(request.to_string().as_bytes()).expect("ingress response");
        let response: Value = serde_json::from_slice(&response).expect("json response");

        assert_eq!(response["items"][0]["ok"], false);
        assert_eq!(response["items"][0]["kind"], "WEBHOOK");
        assert_eq!(response["items"][0]["targetRoute"], "default");
        assert!(response["items"][0]["payloadJson"].is_null());
        assert!(
            response["items"][0]["error"]
                .as_str()
                .expect("error")
                .contains("sourceLogId")
        );
    }

    #[test]
    fn ingress_batch_does_not_require_payload_fields_for_unrouted_items() {
        let request = br#"{
            "items": [{
                "messageId": "msg-none",
                "command": {"text": "ordinary chat"}
            }]
        }"#;

        let response = ingress_batch_json(request).expect("ingress response");
        let response: Value = serde_json::from_slice(&response).expect("json response");

        assert_eq!(response["items"][0]["ok"], true);
        assert_eq!(response["items"][0]["kind"], "NONE");
        assert_eq!(response["items"][0]["normalizedText"], "ordinary chat");
        assert!(response["items"][0]["targetRoute"].is_null());
        assert!(response["items"][0]["payloadJson"].is_null());
    }
}
