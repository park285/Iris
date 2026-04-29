use crate::errors::{NativeCoreError, NativeCoreResult};
use base64::{Engine as _, engine::general_purpose};
use serde::{Deserialize, Serialize};
use serde_json::{Number, Value};

#[derive(Debug, Deserialize)]
struct QueryProjectionBatchRequest {
    items: Vec<Value>,
}

#[derive(Debug, Deserialize)]
struct QueryProjectionBatchItem {
    cells: Vec<QueryProjectionCell>,
}

#[derive(Debug, Deserialize)]
struct QueryProjectionCell {
    #[serde(rename = "sqliteType")]
    sqlite_type: SqliteType,
    #[serde(default, rename = "longValue")]
    long_value: Option<i64>,
    #[serde(default, rename = "doubleValue")]
    double_value: Option<f64>,
    #[serde(default, rename = "textValue")]
    text_value: Option<String>,
    #[serde(default)]
    blob: Option<Vec<u8>>,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize)]
enum SqliteType {
    #[serde(rename = "NULL")]
    Null,
    #[serde(rename = "INTEGER")]
    Integer,
    #[serde(rename = "FLOAT")]
    Float,
    #[serde(rename = "TEXT")]
    Text,
    #[serde(rename = "BLOB")]
    Blob,
    #[serde(rename = "UNKNOWN")]
    Unknown,
}

#[derive(Debug, Serialize)]
struct QueryProjectionBatchResponse {
    items: Vec<QueryProjectionBatchResult>,
}

#[derive(Debug, Serialize)]
struct QueryProjectionBatchResult {
    ok: bool,
    cells: Option<Vec<QueryProjectionOutputCell>>,
    #[serde(rename = "errorKind")]
    error_kind: Option<&'static str>,
    error: Option<String>,
}

#[derive(Debug, Serialize)]
struct QueryProjectionOutputCell {
    #[serde(rename = "sqliteType")]
    sqlite_type: SqliteType,
    value: Option<Value>,
}

pub fn query_projection_batch_json(request_bytes: &[u8]) -> NativeCoreResult<Vec<u8>> {
    let request: QueryProjectionBatchRequest = serde_json::from_slice(request_bytes)
        .map_err(|error| NativeCoreError::InvalidRequest(error.to_string()))?;
    let response = QueryProjectionBatchResponse {
        items: request.items.into_iter().map(project_raw_item).collect(),
    };
    serde_json::to_vec(&response)
        .map_err(|error| NativeCoreError::InvalidResponse(error.to_string()))
}

fn project_raw_item(item: Value) -> QueryProjectionBatchResult {
    match serde_json::from_value::<QueryProjectionBatchItem>(item)
        .map_err(|error| NativeCoreError::InvalidRequest(error.to_string()))
        .and_then(project_item)
    {
        Ok(cells) => QueryProjectionBatchResult {
            ok: true,
            cells: Some(cells),
            error_kind: None,
            error: None,
        },
        Err(error) => QueryProjectionBatchResult {
            ok: false,
            cells: None,
            error_kind: Some(error.kind()),
            error: Some(error.to_string()),
        },
    }
}

fn project_item(
    item: QueryProjectionBatchItem,
) -> NativeCoreResult<Vec<QueryProjectionOutputCell>> {
    item.cells.into_iter().map(project_cell).collect()
}

fn project_cell(cell: QueryProjectionCell) -> NativeCoreResult<QueryProjectionOutputCell> {
    let value = match cell.sqlite_type {
        SqliteType::Null => None,
        SqliteType::Integer => Some(Value::Number(Number::from(required_i64(
            cell.long_value,
            "longValue",
        )?))),
        SqliteType::Float => Some(Value::Number(
            Number::from_f64(required_f64(cell.double_value, "doubleValue")?).ok_or_else(|| {
                NativeCoreError::InvalidRequest("doubleValue must be finite".to_owned())
            })?,
        )),
        SqliteType::Text | SqliteType::Unknown => {
            Some(Value::String(cell.text_value.unwrap_or_default()))
        }
        SqliteType::Blob => Some(Value::String(
            general_purpose::STANDARD.encode(required_blob(cell.blob, "blob")?),
        )),
    };

    Ok(QueryProjectionOutputCell {
        sqlite_type: cell.sqlite_type,
        value,
    })
}

fn required_i64(value: Option<i64>, field_name: &str) -> NativeCoreResult<i64> {
    value.ok_or_else(|| NativeCoreError::InvalidRequest(format!("{field_name} is required")))
}

fn required_f64(value: Option<f64>, field_name: &str) -> NativeCoreResult<f64> {
    value.ok_or_else(|| NativeCoreError::InvalidRequest(format!("{field_name} is required")))
}

fn required_blob(value: Option<Vec<u8>>, field_name: &str) -> NativeCoreResult<Vec<u8>> {
    value.ok_or_else(|| NativeCoreError::InvalidRequest(format!("{field_name} is required")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn project(request: &Value) -> Value {
        let response = query_projection_batch_json(request.to_string().as_bytes())
            .expect("query projection response");
        serde_json::from_slice(&response).expect("response json")
    }

    #[test]
    fn projects_all_cell_types_with_kotlin_parity() {
        let response = project(&serde_json::json!({
            "items": [{
                "cells": [
                    {"sqliteType": "NULL"},
                    {"sqliteType": "INTEGER", "longValue": 42},
                    {"sqliteType": "FLOAT", "doubleValue": 3.25},
                    {"sqliteType": "TEXT", "textValue": "hello"},
                    {"sqliteType": "BLOB", "blob": [0, 1, 2, 250, 255]},
                    {"sqliteType": "BLOB", "blob": []},
                    {"sqliteType": "UNKNOWN", "textValue": "fallback"}
                ]
            }]
        }));

        let item = &response["items"][0];
        assert_eq!(item["ok"], true);
        assert_eq!(item["errorKind"], Value::Null);
        assert_eq!(item["error"], Value::Null);
        assert_eq!(item["cells"][0]["sqliteType"], "NULL");
        assert!(item["cells"][0]["value"].is_null());
        assert_eq!(item["cells"][1]["sqliteType"], "INTEGER");
        assert_eq!(item["cells"][1]["value"], 42);
        assert_eq!(item["cells"][2]["sqliteType"], "FLOAT");
        assert_eq!(item["cells"][2]["value"], 3.25);
        assert_eq!(item["cells"][3]["sqliteType"], "TEXT");
        assert_eq!(item["cells"][3]["value"], "hello");
        assert_eq!(item["cells"][4]["sqliteType"], "BLOB");
        assert_eq!(item["cells"][4]["value"], "AAEC+v8=");
        assert_eq!(item["cells"][5]["sqliteType"], "BLOB");
        assert_eq!(item["cells"][5]["value"], "");
        assert_eq!(item["cells"][6]["sqliteType"], "UNKNOWN");
        assert_eq!(item["cells"][6]["value"], "fallback");
    }

    #[test]
    fn preserves_multi_row_and_cell_order() {
        let response = project(&serde_json::json!({
            "items": [
                {"cells": [
                    {"sqliteType": "TEXT", "textValue": "first-row-first-cell"},
                    {"sqliteType": "INTEGER", "longValue": 1}
                ]},
                {"cells": [
                    {"sqliteType": "INTEGER", "longValue": 2},
                    {"sqliteType": "TEXT", "textValue": "second-row-second-cell"}
                ]}
            ]
        }));

        assert_eq!(
            response["items"][0]["cells"][0]["value"],
            "first-row-first-cell"
        );
        assert_eq!(response["items"][0]["cells"][1]["value"], 1);
        assert_eq!(response["items"][1]["cells"][0]["value"], 2);
        assert_eq!(
            response["items"][1]["cells"][1]["value"],
            "second-row-second-cell"
        );
    }

    #[test]
    fn base64_encodes_blob_unsigned_bytes_with_standard_alphabet() {
        let response = project(&serde_json::json!({
            "items": [{
                "cells": [{"sqliteType": "BLOB", "blob": [0, 127, 128, 255]}]
            }]
        }));

        assert_eq!(response["items"][0]["cells"][0]["value"], "AH+A/w==");
    }

    #[test]
    fn text_and_unknown_missing_values_project_to_empty_string() {
        let response = project(&serde_json::json!({
            "items": [{
                "cells": [
                    {"sqliteType": "TEXT"},
                    {"sqliteType": "UNKNOWN", "textValue": null}
                ]
            }]
        }));

        assert_eq!(response["items"][0]["cells"][0]["value"], "");
        assert_eq!(response["items"][0]["cells"][1]["value"], "");
    }

    #[test]
    fn malformed_item_returns_item_level_error_without_dropping_other_rows() {
        let response = project(&serde_json::json!({
            "items": [
                {"cells": [{"sqliteType": "TEXT", "textValue": "ok"}]},
                {},
                {"cells": [{"sqliteType": "INTEGER"}]}
            ]
        }));

        assert_eq!(response["items"][0]["ok"], true);
        assert_eq!(response["items"][0]["cells"][0]["value"], "ok");
        assert_eq!(response["items"][1]["ok"], false);
        assert!(response["items"][1]["cells"].is_null());
        assert_eq!(response["items"][1]["errorKind"], "invalidRequest");
        assert!(
            response["items"][1]["error"]
                .as_str()
                .expect("error")
                .contains("cells")
        );
        assert_eq!(response["items"][2]["ok"], false);
        assert!(response["items"][2]["cells"].is_null());
        assert_eq!(response["items"][2]["errorKind"], "invalidRequest");
        assert!(
            response["items"][2]["error"]
                .as_str()
                .expect("error")
                .contains("longValue")
        );
    }

    #[test]
    fn malformed_blob_byte_returns_item_level_error() {
        let response = project(&serde_json::json!({
            "items": [{
                "cells": [{"sqliteType": "BLOB", "blob": [0, 256]}]
            }]
        }));

        assert_eq!(response["items"][0]["ok"], false);
        assert!(response["items"][0]["cells"].is_null());
        assert_eq!(response["items"][0]["errorKind"], "invalidRequest");
    }
}
