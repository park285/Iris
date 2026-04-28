use crate::errors::{NativeCoreError, NativeCoreResult};
use crate::native_core_identity;
use jni::JNIEnv;
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jbyteArray, jstring};
use std::panic::{AssertUnwindSafe, catch_unwind};

fn recover<T>(operation: impl FnOnce() -> NativeCoreResult<T>) -> NativeCoreResult<T> {
    catch_unwind(AssertUnwindSafe(operation)).map_err(|_| NativeCoreError::Panic)?
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_party_qwer_iris_nativecore_NativeCoreJni_nativeSelfTest(
    env: JNIEnv<'_>,
    _receiver: JObject<'_>,
) -> jstring {
    let value =
        recover(|| Ok(native_core_identity())).unwrap_or_else(|error| format!("error:{error}"));
    env.new_string(value)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_party_qwer_iris_nativecore_NativeCoreJni_decryptBatch(
    env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    request: JByteArray<'_>,
) -> jbyteArray {
    json_batch_response(&env, &request, crate::decrypt::decrypt_batch_json)
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_party_qwer_iris_nativecore_NativeCoreJni_routingBatch(
    env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    request: JByteArray<'_>,
) -> jbyteArray {
    json_batch_response(&env, &request, crate::routing::routing_batch_json)
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_party_qwer_iris_nativecore_NativeCoreJni_parserBatch(
    env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    request: JByteArray<'_>,
) -> jbyteArray {
    json_batch_response(&env, &request, crate::parsers::parser_batch_json)
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_party_qwer_iris_nativecore_NativeCoreJni_webhookPayloadBatch(
    env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    request: JByteArray<'_>,
) -> jbyteArray {
    json_batch_response(&env, &request, crate::webhook::webhook_payload_batch_json)
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_party_qwer_iris_nativecore_NativeCoreJni_ingressBatch(
    env: JNIEnv<'_>,
    _receiver: JObject<'_>,
    request: JByteArray<'_>,
) -> jbyteArray {
    json_batch_response(&env, &request, crate::ingress::ingress_batch_json)
}

fn json_batch_response(
    env: &JNIEnv<'_>,
    request: &JByteArray<'_>,
    handler: fn(&[u8]) -> NativeCoreResult<Vec<u8>>,
) -> jbyteArray {
    let response = recover(|| json_response(env, request, handler))
        .unwrap_or_else(|error| fallback_error_response(&error));
    env.byte_array_from_slice(&response)
        .map_or(std::ptr::null_mut(), JByteArray::into_raw)
}

fn json_response(
    env: &JNIEnv<'_>,
    request: &JByteArray<'_>,
    handler: fn(&[u8]) -> NativeCoreResult<Vec<u8>>,
) -> NativeCoreResult<Vec<u8>> {
    let request_bytes = env
        .convert_byte_array(request)
        .map_err(|error| NativeCoreError::Jni(error.to_string()))?;
    handler(&request_bytes)
}

fn fallback_error_response(error: &NativeCoreError) -> Vec<u8> {
    serde_json::json!({
        "items": [
            {
                "ok": false,
                "errorKind": error.kind(),
                "error": error.to_string(),
            },
        ],
    })
    .to_string()
    .into_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    #[test]
    fn fallback_error_response_escapes_error_message_as_json() {
        let response = fallback_error_response(&NativeCoreError::Jni(
            "bad \"quote\" \\ slash\nline".to_owned(),
        ));

        let response: Value = serde_json::from_slice(&response).expect("fallback should be json");
        assert_eq!(
            response["items"][0]["error"],
            "jni error: bad \"quote\" \\ slash\nline"
        );
        assert_eq!(response["items"][0]["errorKind"], "jniError");
    }

    #[test]
    fn fallback_error_response_uses_batch_item_shape_for_new_handlers() {
        let response = fallback_error_response(&NativeCoreError::Panic);

        let response: Value = serde_json::from_slice(&response).expect("fallback should be json");
        assert_eq!(response["items"][0]["ok"], false);
        assert_eq!(response["items"][0]["errorKind"], "panic");
        assert_eq!(response["items"][0]["error"], "panic in native core");
    }
}
