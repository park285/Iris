use crate::VERSION;
use crate::errors::{NativeCoreError, NativeCoreResult};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jstring};
use std::panic::{AssertUnwindSafe, catch_unwind};

fn recover<T>(operation: impl FnOnce() -> NativeCoreResult<T>) -> NativeCoreResult<T> {
    catch_unwind(AssertUnwindSafe(operation)).map_err(|_| NativeCoreError::Panic)?
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_party_qwer_iris_nativecore_NativeCoreJni_nativeSelfTest(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    let value =
        recover(|| Ok(VERSION.to_string())).unwrap_or_else(|error| format!("error:{error}"));
    env.new_string(value)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_party_qwer_iris_nativecore_NativeCoreJni_decryptBatch(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    request: JByteArray<'_>,
) -> jbyteArray {
    let response = recover(|| decrypt_batch_response(&env, &request))
        .unwrap_or_else(|error| fallback_error_response(&error));
    env.byte_array_from_slice(&response)
        .map_or(std::ptr::null_mut(), JByteArray::into_raw)
}

fn decrypt_batch_response(env: &JNIEnv<'_>, request: &JByteArray<'_>) -> NativeCoreResult<Vec<u8>> {
    let request_bytes = env
        .convert_byte_array(request)
        .map_err(|error| NativeCoreError::Jni(error.to_string()))?;
    crate::decrypt::decrypt_batch_json(&request_bytes)
}

fn fallback_error_response(error: &NativeCoreError) -> Vec<u8> {
    serde_json::json!({
        "items": [
            {
                "ok": false,
                "error": error.to_string(),
            },
        ],
    })
    .to_string()
    .into_bytes()
}
