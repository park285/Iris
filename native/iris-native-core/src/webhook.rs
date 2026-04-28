use crate::errors::{NativeCoreError, NativeCoreResult};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Number, Value};

#[derive(Debug, Deserialize)]
struct WebhookPayloadBatchRequest {
    items: Vec<WebhookPayloadBatchItem>,
}

#[derive(Debug, Deserialize)]
struct WebhookPayloadBatchItem {
    command: Value,
    route: String,
    #[serde(rename = "messageId")]
    message_id: String,
}

#[derive(Debug, Serialize)]
struct WebhookPayloadBatchResponse {
    items: Vec<WebhookPayloadBatchResult>,
}

#[derive(Debug, Serialize)]
struct WebhookPayloadBatchResult {
    ok: bool,
    #[serde(rename = "payloadJson")]
    payload_json: Option<String>,
    #[serde(rename = "errorKind")]
    error_kind: Option<&'static str>,
    error: Option<String>,
}

pub fn webhook_payload_batch_json(request_bytes: &[u8]) -> NativeCoreResult<Vec<u8>> {
    let request: WebhookPayloadBatchRequest = serde_json::from_slice(request_bytes)
        .map_err(|error| NativeCoreError::InvalidRequest(error.to_string()))?;
    let response = WebhookPayloadBatchResponse {
        items: request
            .items
            .into_iter()
            .map(
                |item| match build_webhook_payload(&item.command, &item.route, &item.message_id) {
                    Ok(payload_json) => WebhookPayloadBatchResult {
                        ok: true,
                        payload_json: Some(payload_json),
                        error_kind: None,
                        error: None,
                    },
                    Err(error) => WebhookPayloadBatchResult {
                        ok: false,
                        payload_json: None,
                        error_kind: Some(error.kind()),
                        error: Some(error.to_string()),
                    },
                },
            )
            .collect(),
    };
    serde_json::to_vec(&response)
        .map_err(|error| NativeCoreError::InvalidResponse(error.to_string()))
}

pub(crate) fn build_webhook_payload(
    command: &Value,
    route: &str,
    message_id: &str,
) -> NativeCoreResult<String> {
    let command = command
        .as_object()
        .ok_or_else(|| NativeCoreError::InvalidRequest("command must be object".to_owned()))?;
    let mut payload = Map::new();

    payload.insert("route".to_owned(), Value::String(route.to_owned()));
    payload.insert("messageId".to_owned(), Value::String(message_id.to_owned()));
    payload.insert(
        "sourceLogId".to_owned(),
        Value::Number(Number::from(required_i64(command, "sourceLogId")?)),
    );
    payload.insert(
        "text".to_owned(),
        Value::String(required_string(command, "text")?.to_owned()),
    );
    payload.insert(
        "room".to_owned(),
        Value::String(required_string(command, "room")?.to_owned()),
    );
    payload.insert(
        "sender".to_owned(),
        Value::String(required_string(command, "sender")?.to_owned()),
    );
    payload.insert(
        "userId".to_owned(),
        Value::String(required_string(command, "userId")?.to_owned()),
    );

    insert_optional_string(command, &mut payload, "chatLogId", "chatLogId")?;
    insert_optional_string(command, &mut payload, "roomType", "roomType")?;
    insert_optional_string(command, &mut payload, "roomLinkId", "roomLinkId")?;
    insert_optional_string(command, &mut payload, "threadId", "threadId")?;
    if let Some(thread_scope) = optional_i64(command, "threadScope")? {
        payload.insert(
            "threadScope".to_owned(),
            Value::Number(Number::from(thread_scope)),
        );
    }
    insert_optional_string(command, &mut payload, "messageType", "type")?;
    if let Some(event_payload) = command.get("eventPayload") {
        payload.insert("eventPayload".to_owned(), event_payload.clone());
    }
    insert_optional_string(command, &mut payload, "attachment", "attachment")?;

    serde_json::to_string(&Value::Object(payload))
        .map_err(|error| NativeCoreError::InvalidResponse(error.to_string()))
}

fn insert_optional_string(
    source: &Map<String, Value>,
    target: &mut Map<String, Value>,
    source_key: &str,
    target_key: &str,
) -> NativeCoreResult<()> {
    if let Some(value) = optional_string(source, source_key)?
        && !value.trim().is_empty()
    {
        target.insert(target_key.to_owned(), Value::String(value.to_owned()));
    }
    Ok(())
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

fn required_i64(object: &Map<String, Value>, key: &str) -> NativeCoreResult<i64> {
    optional_i64(object, key)?.map_or_else(
        || {
            Err(NativeCoreError::InvalidRequest(format!(
                "{key} is required"
            )))
        },
        Ok,
    )
}

fn optional_i64(object: &Map<String, Value>, key: &str) -> NativeCoreResult<Option<i64>> {
    match object.get(key) {
        Some(Value::Number(value)) => value.as_i64().map(Some).ok_or_else(|| {
            NativeCoreError::InvalidRequest(format!("{key} must fit signed 64-bit integer"))
        }),
        Some(Value::String(value)) => value.parse::<i64>().map(Some).map_err(|_| {
            NativeCoreError::InvalidRequest(format!("{key} must be signed 64-bit integer"))
        }),
        Some(Value::Null) | None => Ok(None),
        Some(other) => Err(NativeCoreError::InvalidRequest(format!(
            "{key} must be integer or null, got {other}"
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    #[test]
    fn webhook_payload_batch_builds_required_fields_only() {
        let request = serde_json::json!({
            "items": [{
                "route": "default",
                "messageId": "msg-001",
                "command": {
                    "text": "!test",
                    "room": "TestRoom",
                    "sender": "TestUser",
                    "userId": "12345",
                    "sourceLogId": 100
                }
            }]
        });
        let response =
            webhook_payload_batch_json(request.to_string().as_bytes()).expect("webhook response");
        let response: Value = serde_json::from_slice(&response).expect("json response");
        let payload: Value = serde_json::from_str(
            response["items"][0]["payloadJson"]
                .as_str()
                .expect("payload json"),
        )
        .expect("payload object");

        assert_eq!(response["items"][0]["ok"], true);
        assert_eq!(payload["route"], "default");
        assert_eq!(payload["messageId"], "msg-001");
        assert_eq!(payload["sourceLogId"], 100);
        assert_eq!(payload["text"], "!test");
        assert_eq!(payload["room"], "TestRoom");
        assert_eq!(payload["sender"], "TestUser");
        assert_eq!(payload["userId"], "12345");
        assert!(payload.get("chatLogId").is_none());
        assert!(payload.get("roomType").is_none());
        assert!(payload.get("roomLinkId").is_none());
        assert!(payload.get("threadId").is_none());
        assert!(payload.get("threadScope").is_none());
        assert!(payload.get("type").is_none());
        assert!(payload.get("eventPayload").is_none());
        assert!(payload.get("attachment").is_none());
    }

    #[test]
    fn webhook_payload_batch_includes_present_optional_fields_and_event_payload() {
        let request = serde_json::json!({
            "items": [{
                "route": "settlement",
                "messageId": "msg-002",
                "command": {
                    "text": "!cmd",
                    "room": "Room",
                    "sender": "Sender",
                    "userId": "999",
                    "sourceLogId": 200,
                    "chatLogId": "log-123",
                    "roomType": "MultiChat",
                    "roomLinkId": "link-456",
                    "threadId": "thread-789",
                    "threadScope": 2,
                    "messageType": "nickname_change",
                    "eventPayload": {
                        "type": "nickname_change",
                        "chatId": 18_219_201_472_247_343_i64,
                        "userId": 1_234_567_890,
                        "oldNickname": "이전닉",
                        "newNickname": "변경닉"
                    },
                    "attachment": "{\"url\":\"http://example.com\"}",
                    "senderRole": 1
                }
            }]
        });
        let response =
            webhook_payload_batch_json(request.to_string().as_bytes()).expect("webhook response");
        let response: Value = serde_json::from_slice(&response).expect("json response");
        let payload: Value = serde_json::from_str(
            response["items"][0]["payloadJson"]
                .as_str()
                .expect("payload json"),
        )
        .expect("payload object");

        assert_eq!(payload["route"], "settlement");
        assert_eq!(payload["messageId"], "msg-002");
        assert_eq!(payload["sourceLogId"], 200);
        assert_eq!(payload["chatLogId"], "log-123");
        assert_eq!(payload["roomType"], "MultiChat");
        assert_eq!(payload["roomLinkId"], "link-456");
        assert_eq!(payload["threadId"], "thread-789");
        assert_eq!(payload["threadScope"], 2);
        assert_eq!(payload["type"], "nickname_change");
        assert_eq!(payload["eventPayload"]["newNickname"], "변경닉");
        assert_eq!(payload["attachment"], "{\"url\":\"http://example.com\"}");
        assert!(payload.get("senderRole").is_none());
    }

    #[test]
    fn webhook_payload_batch_omits_blank_optional_fields_but_preserves_non_blank_spacing() {
        let request = serde_json::json!({
            "items": [{
                "route": "default",
                "messageId": "msg-003",
                "command": {
                    "text": "!cmd",
                    "room": "Room",
                    "sender": "Sender",
                    "userId": "999",
                    "sourceLogId": 201,
                    "chatLogId": "",
                    "roomType": "   ",
                    "roomLinkId": null,
                    "threadId": " thread ",
                    "threadScope": null,
                    "messageType": "",
                    "attachment": "   "
                }
            }]
        });
        let response =
            webhook_payload_batch_json(request.to_string().as_bytes()).expect("webhook response");
        let response: Value = serde_json::from_slice(&response).expect("json response");
        let payload: Value = serde_json::from_str(
            response["items"][0]["payloadJson"]
                .as_str()
                .expect("payload json"),
        )
        .expect("payload object");

        assert!(payload.get("chatLogId").is_none());
        assert!(payload.get("roomType").is_none());
        assert!(payload.get("roomLinkId").is_none());
        assert_eq!(payload["threadId"], " thread ");
        assert!(payload.get("threadScope").is_none());
        assert!(payload.get("type").is_none());
        assert!(payload.get("eventPayload").is_none());
        assert!(payload.get("attachment").is_none());
    }

    #[test]
    fn webhook_payload_batch_preserves_json_null_event_payload_when_present() {
        let request = br#"{
            "items": [{
                "route": "default",
                "messageId": "msg-004",
                "command": {
                    "text": "event",
                    "room": "Room",
                    "sender": "Sender",
                    "userId": "0",
                    "sourceLogId": -1,
                    "eventPayload": null
                }
            }]
        }"#;
        let response = webhook_payload_batch_json(request).expect("webhook response");
        let response: Value = serde_json::from_slice(&response).expect("json response");
        let payload: Value = serde_json::from_str(
            response["items"][0]["payloadJson"]
                .as_str()
                .expect("payload json"),
        )
        .expect("payload object");

        assert!(payload.get("eventPayload").is_some());
        assert_eq!(payload["eventPayload"], Value::Null);
    }

    #[test]
    fn webhook_payload_batch_reports_item_error_for_missing_required_field() {
        let response = webhook_payload_batch_json(
            br#"{"items":[{"route":"default","messageId":"msg","command":{}}]}"#,
        )
        .expect("webhook response");
        let response: Value = serde_json::from_slice(&response).expect("json response");

        assert_eq!(response["items"][0]["ok"], false);
        assert!(
            response["items"][0]["error"]
                .as_str()
                .expect("error")
                .contains("sourceLogId")
        );
    }

    #[test]
    fn webhook_payload_batch_rejects_malformed_envelope_json() {
        let error = webhook_payload_batch_json(b"not-json").expect_err("invalid json should fail");

        assert!(matches!(error, NativeCoreError::InvalidRequest(_)));
    }
}
