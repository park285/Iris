use anyhow::Result;
use hmac::{Hmac, Mac};
use reqwest::header::{HeaderMap, HeaderValue};
use sha2::{Digest, Sha256};
use std::fmt::Write as _;
use std::time::{SystemTime, UNIX_EPOCH};

type HmacSha256 = Hmac<Sha256>;

/// 쿼리 파라미터를 정렬하여 canonical target 문자열을 생성한다.
pub fn canonical_target(path: &str, query: &[(String, String)]) -> String {
    if query.is_empty() {
        return path.to_string();
    }
    let mut normalized = query.to_vec();
    normalized.sort_by(|left, right| left.0.cmp(&right.0).then(left.1.cmp(&right.1)));
    let encoded = normalized
        .into_iter()
        .map(|(key, value)| format!("{key}={value}"))
        .collect::<Vec<_>>()
        .join("&");
    format!("{path}?{encoded}")
}

/// HMAC 서명에 사용하는 canonical request 문자열을 생성한다.
#[allow(clippy::too_many_arguments)] // 서명 프로토콜 필드 순서를 그대로 받는 경계 함수
pub fn canonical_request(
    method: &str,
    target: &str,
    timestamp_ms: &str,
    nonce: &str,
    body_sha256_hex: &str,
) -> String {
    format!(
        "{}\n{}\n{}\n{}\n{}",
        method.to_uppercase(),
        target,
        timestamp_ms,
        nonce,
        body_sha256_hex
    )
}

/// HMAC-SHA256 서명 헤더를 생성한다.
pub fn signed_headers(token: &str, method: &str, target: &str, body: &[u8]) -> Result<HeaderMap> {
    let now_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)?
        .as_millis()
        .to_string();
    let nonce = format!(
        "iris-{}",
        SystemTime::now().duration_since(UNIX_EPOCH)?.as_nanos()
    );
    signed_headers_with(token, method, target, body, &now_ms, &nonce)
}

/// 테스트용: timestamp와 nonce를 직접 지정하여 서명 헤더를 생성한다.
#[allow(clippy::too_many_arguments)] // HMAC 프로토콜 필드가 6개 — 구조체화하면 호출측이 더 복잡
pub fn signed_headers_with(
    token: &str,
    method: &str,
    target: &str,
    body: &[u8],
    timestamp_ms: &str,
    nonce: &str,
) -> Result<HeaderMap> {
    let body_hash = hex_sha256(body);
    let canonical = canonical_request(method, target, timestamp_ms, nonce, &body_hash);
    let mut mac = HmacSha256::new_from_slice(token.as_bytes())?;
    mac.update(canonical.as_bytes());
    let signature = hex_bytes(&mac.finalize().into_bytes());

    let mut headers = HeaderMap::new();
    headers.insert("X-Iris-Timestamp", HeaderValue::from_str(timestamp_ms)?);
    headers.insert("X-Iris-Nonce", HeaderValue::from_str(nonce)?);
    headers.insert("X-Iris-Signature", HeaderValue::from_str(&signature)?);
    headers.insert("X-Iris-Body-Sha256", HeaderValue::from_str(&body_hash)?);
    Ok(headers)
}

fn hex_sha256(body: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(body);
    hex_bytes(&hasher.finalize())
}

fn hex_bytes(bytes: &[u8]) -> String {
    bytes
        .iter()
        .fold(String::with_capacity(bytes.len() * 2), |mut out, byte| {
            let _ = write!(&mut out, "{byte:02x}");
            out
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_target_sorts_query_pairs() {
        let target = canonical_target(
            "/rooms/1/stats",
            &[
                ("limit".into(), "20".into()),
                ("period".into(), "7d".into()),
            ],
        );
        assert_eq!(target, "/rooms/1/stats?limit=20&period=7d");
    }

    #[test]
    fn canonical_target_returns_path_when_no_query() {
        assert_eq!(canonical_target("/health", &[]), "/health");
    }

    #[test]
    fn canonical_request_serializes_protocol_fields_in_signing_order() {
        assert_eq!(
            canonical_request(
                "post",
                "/reply?room=1",
                "1700000000000",
                "nonce-1",
                "abc123"
            ),
            "POST\n/reply?room=1\n1700000000000\nnonce-1\nabc123"
        );
    }

    #[test]
    fn signed_headers_include_only_hmac_fields() {
        let headers = signed_headers_with("secret", "GET", "/rooms", b"", "1000", "nonce-1")
            .expect("headers should build");
        assert_eq!(headers["X-Iris-Timestamp"], "1000");
        assert_eq!(headers["X-Iris-Nonce"], "nonce-1");
        assert!(headers.contains_key("X-Iris-Signature"));
        assert_eq!(
            headers["X-Iris-Body-Sha256"],
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert!(!headers.contains_key("X-Bot-Token"));
    }

    #[test]
    fn signed_headers_deterministic_for_same_inputs() {
        let h1 = signed_headers_with("tok", "POST", "/reply", b"{}", "500", "n1").unwrap();
        let h2 = signed_headers_with("tok", "POST", "/reply", b"{}", "500", "n1").unwrap();
        assert_eq!(h1["X-Iris-Signature"], h2["X-Iris-Signature"]);
    }

    #[test]
    fn signed_headers_differ_for_different_body() {
        let h1 = signed_headers_with("tok", "POST", "/reply", b"a", "500", "n1").unwrap();
        let h2 = signed_headers_with("tok", "POST", "/reply", b"b", "500", "n1").unwrap();
        assert_ne!(h1["X-Iris-Signature"], h2["X-Iris-Signature"]);
    }
}
