use crate::errors::{NativeCoreError, NativeCoreResult};
use serde::{Deserialize, Serialize};

#[derive(Debug, Deserialize)]
struct DecryptBatchRequest {
    items: Vec<DecryptBatchItem>,
}

#[derive(Debug, Deserialize)]
struct DecryptBatchItem {
    #[serde(rename = "encType")]
    enc_type: i32,
    ciphertext: String,
    #[serde(rename = "userId")]
    user_id: i64,
}

#[derive(Debug, Serialize)]
struct DecryptBatchResponse {
    items: Vec<DecryptBatchResult>,
}

#[derive(Debug, Serialize)]
struct DecryptBatchResult {
    ok: bool,
    plaintext: Option<String>,
    error: Option<String>,
}

pub fn decrypt_batch_json(request_bytes: &[u8]) -> NativeCoreResult<Vec<u8>> {
    let request: DecryptBatchRequest = serde_json::from_slice(request_bytes)
        .map_err(|error| NativeCoreError::InvalidRequest(error.to_string()))?;
    let response = DecryptBatchResponse {
        items: request
            .items
            .into_iter()
            .map(|item| DecryptBatchResult {
                ok: false,
                plaintext: None,
                error: Some(format!(
                    "decrypt not implemented for encType={} userId={} ciphertextLen={}",
                    item.enc_type,
                    item.user_id,
                    item.ciphertext.len()
                )),
            })
            .collect(),
    };
    serde_json::to_vec(&response)
        .map_err(|error| NativeCoreError::InvalidResponse(error.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    #[test]
    fn returns_placeholder_error_for_each_valid_item() {
        let response =
            decrypt_batch_json(br#"{"items":[{"encType":1,"ciphertext":"abc123","userId":42}]}"#)
                .expect("valid request should serialize a placeholder response");

        let response: Value =
            serde_json::from_slice(&response).expect("response should be valid json");
        let item = &response["items"][0];

        assert_eq!(item["ok"], false);
        assert_eq!(item["plaintext"], Value::Null);
        assert_eq!(
            item["error"],
            "decrypt not implemented for encType=1 userId=42 ciphertextLen=6"
        );
    }

    #[test]
    fn rejects_invalid_request_json() {
        let error = decrypt_batch_json(b"not-json").expect_err("invalid json should fail");

        assert!(matches!(error, NativeCoreError::InvalidRequest(_)));
    }
}
