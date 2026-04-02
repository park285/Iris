use crate::{api, app};
use anyhow::Result;
use iris_common::models::{ReplyRequest, ReplyType};

pub(crate) const MAX_IMAGE_BYTES: u64 = 35 * 1024 * 1024;

/// 파일을 읽어 base64로 변환. 35 MB 초과 시 에러.
pub(crate) async fn encode_single_image(
    req: ReplyRequest,
) -> Result<ReplyRequest, app::ReplyResult> {
    use base64::Engine;
    let path = req.data.as_str().unwrap_or("").to_owned();
    match tokio::fs::read(&path).await {
        Ok(bytes) => {
            if bytes.len() as u64 > MAX_IMAGE_BYTES {
                #[allow(clippy::cast_precision_loss)]
                let mb = bytes.len() as f64 / 1_048_576.0;
                return Err(app::ReplyResult::Error {
                    message: format!("파일이 너무 큽니다 ({mb:.1} MB, 상한 35 MB)"),
                });
            }
            let encoded = base64::engine::general_purpose::STANDARD.encode(&bytes);
            Ok(ReplyRequest {
                data: serde_json::Value::String(encoded),
                ..req
            })
        }
        Err(e) => Err(app::ReplyResult::Error {
            message: format!("파일 읽기 실패: {e}"),
        }),
    }
}

/// 여러 이미지를 base64 배열로 변환. 합산 35 MB 초과 시 에러.
pub(crate) async fn encode_multiple_images(
    req: ReplyRequest,
) -> Result<ReplyRequest, app::ReplyResult> {
    use base64::Engine;
    let paths: Vec<String> = req
        .data
        .as_array()
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v.as_str())
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_default();
    let mut encoded_list = Vec::new();
    let mut total_bytes: u64 = 0;
    for path in &paths {
        match tokio::fs::read(path).await {
            Ok(bytes) => {
                total_bytes += bytes.len() as u64;
                if total_bytes > MAX_IMAGE_BYTES {
                    #[allow(clippy::cast_precision_loss)]
                    let mb = total_bytes as f64 / 1_048_576.0;
                    return Err(app::ReplyResult::Error {
                        message: format!("이미지 합산 크기 초과 ({mb:.1} MB, 상한 35 MB)"),
                    });
                }
                encoded_list.push(serde_json::Value::String(
                    base64::engine::general_purpose::STANDARD.encode(&bytes),
                ));
            }
            Err(e) => {
                return Err(app::ReplyResult::Error {
                    message: format!("파일 읽기 실패 ({path}): {e}"),
                });
            }
        }
    }
    Ok(ReplyRequest {
        data: serde_json::Value::Array(encoded_list),
        ..req
    })
}

pub(crate) async fn send_reply_async(iris: &api::TuiApi, req: ReplyRequest) -> app::ReplyResult {
    let final_req = match &req.reply_type {
        ReplyType::Image => match encode_single_image(req).await {
            Ok(r) => r,
            Err(e) => return e,
        },
        ReplyType::ImageMultiple => match encode_multiple_images(req).await {
            Ok(r) => r,
            Err(e) => return e,
        },
        _ => req,
    };

    match iris.send_reply(&final_req).await {
        Ok(resp) => app::ReplyResult::Success {
            request_id: resp.request_id,
        },
        Err(e) => app::ReplyResult::Error {
            message: e.to_string(),
        },
    }
}
