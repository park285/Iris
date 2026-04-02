use iris_common::auth::{canonical_request, signed_headers_with};
use serde::Deserialize;
use std::fs;
use std::path::PathBuf;

#[derive(Debug, Deserialize)]
struct AuthContractVector {
    name: String,
    secret: String,
    method: String,
    target: String,
    #[serde(rename = "timestampMs")]
    timestamp_ms: String,
    nonce: String,
    body: String,
    #[serde(rename = "bodySha256Hex")]
    body_sha256_hex: String,
    #[serde(rename = "canonicalRequest")]
    canonical_request: String,
    signature: String,
}

#[test]
fn shared_auth_vectors_match_rust_signer() {
    let vectors: Vec<AuthContractVector> =
        serde_json::from_str(&fs::read_to_string(resolve_fixture_path()).expect("fixture should load"))
            .expect("fixture should parse");
    assert!(
        vectors.len() >= 7,
        "shared auth contract should keep at least 7 vectors"
    );

    for vector in vectors {
        let headers = signed_headers_with(
            &vector.secret,
            &vector.method,
            &vector.target,
            vector.body.as_bytes(),
            &vector.timestamp_ms,
            &vector.nonce,
        )
        .expect("headers should build");

        assert_eq!(
            canonical_request(
                &vector.method,
                &vector.target,
                &vector.timestamp_ms,
                &vector.nonce,
                &vector.body_sha256_hex
            ),
            vector.canonical_request,
            "{}",
            vector.name
        );
        assert_eq!(
            headers["X-Iris-Body-Sha256"].to_str().unwrap(),
            vector.body_sha256_hex,
            "{}",
            vector.name
        );
        assert_eq!(
            headers["X-Iris-Signature"].to_str().unwrap(),
            vector.signature,
            "{}",
            vector.name
        );
    }
}

fn resolve_fixture_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("..")
        .join("tests")
        .join("contracts")
        .join("iris_auth_vectors.json")
}
