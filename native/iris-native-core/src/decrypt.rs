use crate::errors::{NativeCoreError, NativeCoreResult};
use aes::Aes256;
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use cbc::cipher::block_padding::NoPadding;
use cbc::cipher::{BlockDecryptMut, KeyIvInit};
use serde::{Deserialize, Serialize};
use sha1::{Digest, Sha1};

type Aes256CbcDec = cbc::Decryptor<Aes256>;

const KEY_BYTES: [u8; 16] = [
    0x16, 0x08, 0x09, 0x6f, 0x02, 0x17, 0x2b, 0x08, 0x21, 0x21, 0x0a, 0x10, 0x03, 0x03, 0x07, 0x06,
];
const IV_BYTES: [u8; 16] = [
    0x0f, 0x08, 0x01, 0x00, 0x19, 0x47, 0x25, 0xdc, 0x15, 0xf5, 0x17, 0xe0, 0xe1, 0x15, 0x0c, 0x35,
];
const AES_BLOCK_SIZE: usize = 16;
const KDF_BLOCK_SIZE: usize = 64;
const SHA1_DIGEST_SIZE: usize = 20;
const INCEPT_DICT1: &[&str] = &[
    "adrp.ldrsh.ldnp",
    "ldpsw",
    "umax",
    "stnp.rsubhn",
    "sqdmlsl",
    "uqrshl.csel",
    "sqshlu",
    "umin.usubl.umlsl",
    "cbnz.adds",
    "tbnz",
    "usubl2",
    "stxr",
    "sbfx",
    "strh",
    "stxrb.adcs",
    "stxrh",
    "ands.urhadd",
    "subs",
    "sbcs",
    "fnmadd.ldxrb.saddl",
    "stur",
    "ldrsb",
    "strb",
    "prfm",
    "ubfiz",
    "ldrsw.madd.msub.sturb.ldursb",
    "ldrb",
    "b.eq",
    "ldur.sbfiz",
    "extr",
    "fmadd",
    "uqadd",
    "sshr.uzp1.sttrb",
    "umlsl2",
    "rsubhn2.ldrh.uqsub",
    "uqshl",
    "uabd",
    "ursra",
    "usubw",
    "uaddl2",
    "b.gt",
    "b.lt",
    "sqshl",
    "bics",
    "smin.ubfx",
    "smlsl2",
    "uabdl2",
    "zip2.ssubw2",
    "ccmp",
    "sqdmlal",
    "b.al",
    "smax.ldurh.uhsub",
    "fcvtxn2",
    "b.pl",
];
const INCEPT_DICT2: &[&str] = &[
    "saddl",
    "urhadd",
    "ubfiz.sqdmlsl.tbnz.stnp",
    "smin",
    "strh",
    "ccmp",
    "usubl",
    "umlsl",
    "uzp1",
    "sbfx",
    "b.eq",
    "zip2.prfm.strb",
    "msub",
    "b.pl",
    "csel",
    "stxrh.ldxrb",
    "uqrshl.ldrh",
    "cbnz",
    "ursra",
    "sshr.ubfx.ldur.ldnp",
    "fcvtxn2",
    "usubl2",
    "uaddl2",
    "b.al",
    "ssubw2",
    "umax",
    "b.lt",
    "adrp.sturb",
    "extr",
    "uqshl",
    "smax",
    "uqsub.sqshlu",
    "ands",
    "madd",
    "umin",
    "b.gt",
    "uabdl2",
    "ldrsb.ldpsw.rsubhn",
    "uqadd",
    "sttrb",
    "stxr",
    "adds",
    "rsubhn2.umlsl2",
    "sbcs.fmadd",
    "usubw",
    "sqshl",
    "stur.ldrsh.smlsl2",
    "ldrsw",
    "fnmadd",
    "stxrb.sbfiz",
    "adcs",
    "bics.ldrb",
    "l1ursb",
    "subs.uhsub",
    "ldurh",
    "uabd",
    "sqdmlal",
];

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
            .map(
                |item| match decrypt(item.enc_type, &item.ciphertext, item.user_id) {
                    Ok(plaintext) => DecryptBatchResult {
                        ok: true,
                        plaintext: Some(plaintext),
                        error: None,
                    },
                    Err(error) => DecryptBatchResult {
                        ok: false,
                        plaintext: None,
                        error: Some(error.to_string()),
                    },
                },
            )
            .collect(),
    };
    serde_json::to_vec(&response)
        .map_err(|error| NativeCoreError::InvalidResponse(error.to_string()))
}

pub fn decrypt(enc_type: i32, ciphertext_b64: &str, user_id: i64) -> NativeCoreResult<String> {
    let ciphertext = STANDARD
        .decode(ciphertext_b64.as_bytes())
        .map_err(|error| NativeCoreError::Decrypt(format!("base64 decode failed: {error}")))?;
    if ciphertext.is_empty() {
        return Ok(ciphertext_b64.to_owned());
    }

    let salt = gen_salt(user_id, enc_type)?;
    let key = derive_key(&KEY_BYTES, &salt, 2, 32);
    let padded = Aes256CbcDec::new((&key[..]).into(), (&IV_BYTES).into())
        .decrypt_padded_vec_mut::<NoPadding>(&ciphertext)
        .map_err(|_| NativeCoreError::Decrypt("aes decrypt failed".to_owned()))?;
    let plaintext = strip_pkcs7_padding(&padded)?;

    String::from_utf8(plaintext.to_vec())
        .map_err(|error| NativeCoreError::Decrypt(format!("utf-8 decode failed: {error}")))
}

fn strip_pkcs7_padding(padded: &[u8]) -> NativeCoreResult<&[u8]> {
    let padding_length = usize::from(
        *padded
            .last()
            .ok_or_else(|| NativeCoreError::Decrypt("empty decrypted payload".to_owned()))?,
    );
    if padding_length == 0 || padding_length > AES_BLOCK_SIZE || padding_length > padded.len() {
        return Err(NativeCoreError::Decrypt(format!(
            "invalid padding length: {padding_length}"
        )));
    }

    let plaintext_length = padded.len() - padding_length;
    Ok(&padded[..plaintext_length])
}

fn gen_salt(user_id: i64, enc_type: i32) -> NativeCoreResult<Vec<u8>> {
    if user_id <= 0 {
        return Ok(vec![0; AES_BLOCK_SIZE]);
    }

    let prefix = match enc_type {
        0 | 1 => String::new(),
        2 | 7 => "12".to_owned(),
        3 => "24".to_owned(),
        4 => "18".to_owned(),
        5 => "30".to_owned(),
        6 => "36".to_owned(),
        8 => "48".to_owned(),
        9 => "7".to_owned(),
        10 => "35".to_owned(),
        11 => "40".to_owned(),
        12 => "17".to_owned(),
        13 => "23".to_owned(),
        14 => "29".to_owned(),
        15 => "isabel".to_owned(),
        16 => "kale".to_owned(),
        17 => "sulli".to_owned(),
        18 => "van".to_owned(),
        19 => "merry".to_owned(),
        20 => "kyle".to_owned(),
        21 => "james".to_owned(),
        22 => "maddux".to_owned(),
        23 => "tony".to_owned(),
        24 => "hayden".to_owned(),
        25 => "paul".to_owned(),
        26 => "elijah".to_owned(),
        27 => "dorothy".to_owned(),
        28 => "sally".to_owned(),
        29 => "bran".to_owned(),
        30 => incept(830_819),
        31 => "veil".to_owned(),
        _ => {
            return Err(NativeCoreError::Decrypt(format!(
                "Unsupported encoding type {enc_type}"
            )));
        }
    };

    let mut salt = format!("{prefix}{user_id}").into_bytes();
    salt.truncate(AES_BLOCK_SIZE);
    salt.resize(AES_BLOCK_SIZE, 0);
    Ok(salt)
}

fn incept(seed: usize) -> String {
    format!(
        "{}.{}",
        INCEPT_DICT1[seed % INCEPT_DICT1.len()],
        INCEPT_DICT2[(seed + 31) % INCEPT_DICT2.len()]
    )
}

fn derive_key(
    password_bytes: &[u8],
    salt_bytes: &[u8],
    iterations: usize,
    derived_key_size: usize,
) -> Vec<u8> {
    let mut password_utf16be = Vec::with_capacity((password_bytes.len() + 1) * 2);
    for byte in password_bytes {
        password_utf16be.push(0);
        password_utf16be.push(*byte);
    }
    password_utf16be.extend_from_slice(&[0, 0]);

    let diversifier = vec![1; KDF_BLOCK_SIZE];
    let salt_block = repeat_to_block(salt_bytes, KDF_BLOCK_SIZE);
    let password_block = repeat_to_block(&password_utf16be, KDF_BLOCK_SIZE);
    let mut input_block = Vec::with_capacity(salt_block.len() + password_block.len());
    input_block.extend_from_slice(&salt_block);
    input_block.extend_from_slice(&password_block);

    let block_count = derived_key_size.div_ceil(SHA1_DIGEST_SIZE);
    let mut derived_key = vec![0; derived_key_size];
    let mut adjustment = vec![0; KDF_BLOCK_SIZE];

    for block_index in 0..block_count {
        let mut digest = sha1_digest(&diversifier, &input_block);
        for _ in 1..iterations {
            digest = sha1_digest(&digest, &[]);
        }

        for (index, byte) in adjustment.iter_mut().enumerate() {
            *byte = digest[index % digest.len()];
        }

        for chunk_index in 0..(input_block.len() / KDF_BLOCK_SIZE) {
            pkcs16adjust(&mut input_block, chunk_index * KDF_BLOCK_SIZE, &adjustment);
        }

        let start = block_index * SHA1_DIGEST_SIZE;
        let copied = (derived_key_size - start).min(digest.len());
        derived_key[start..start + copied].copy_from_slice(&digest[..copied]);
    }

    derived_key
}

fn sha1_digest(first: &[u8], second: &[u8]) -> Vec<u8> {
    let mut hasher = Sha1::new();
    hasher.update(first);
    hasher.update(second);
    hasher.finalize().to_vec()
}

fn repeat_to_block(input: &[u8], block_size: usize) -> Vec<u8> {
    if input.is_empty() || block_size == 0 {
        return Vec::new();
    }

    let output_len = block_size * input.len().div_ceil(block_size);
    (0..output_len)
        .map(|index| input[index % input.len()])
        .collect()
}

fn pkcs16adjust(value: &mut [u8], offset: usize, adjustment: &[u8]) {
    if adjustment.is_empty() {
        return;
    }

    let last_index = adjustment.len() - 1;
    let mut carry = u16::from(adjustment[last_index]) + u16::from(value[offset + last_index]) + 1;
    value[offset + last_index] = carry.to_le_bytes()[0];
    carry >>= 8;

    for index in (0..last_index).rev() {
        carry += u16::from(adjustment[index]) + u16::from(value[offset + index]);
        value[offset + index] = carry.to_le_bytes()[0];
        carry >>= 8;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aes::Aes256;
    use base64::Engine;
    use base64::engine::general_purpose::STANDARD;
    use cbc::cipher::block_padding::Pkcs7;
    use cbc::cipher::{BlockEncryptMut, KeyIvInit};
    use serde_json::Value;
    type Aes256CbcEnc = cbc::Encryptor<Aes256>;

    const KEY_BYTES: [u8; 16] = [
        0x16, 0x08, 0x09, 0x6f, 0x02, 0x17, 0x2b, 0x08, 0x21, 0x21, 0x0a, 0x10, 0x03, 0x03, 0x07,
        0x06,
    ];
    const IV_BYTES: [u8; 16] = [
        0x0f, 0x08, 0x01, 0x00, 0x19, 0x47, 0x25, 0xdc, 0x15, 0xf5, 0x17, 0xe0, 0xe1, 0x15, 0x0c,
        0x35,
    ];

    #[test]
    fn decrypt_round_trips_short_plaintext() {
        let ciphertext = encrypt(0, "hello", 987_654_321);

        let plaintext = decrypt(0, &ciphertext, 987_654_321).expect("decrypt should succeed");

        assert_eq!("hello", plaintext);
    }

    #[test]
    fn decrypt_empty_ciphertext_returns_original_empty_string() {
        let plaintext = decrypt(0, "", 1).expect("empty base64 should decode");

        assert_eq!("", plaintext);
    }

    #[test]
    fn decrypt_batch_json_returns_plaintext() {
        let ciphertext = encrypt(3, "batch-payload", 777);
        let request =
            format!(r#"{{"items":[{{"encType":3,"ciphertext":"{ciphertext}","userId":777}}]}}"#);

        let raw = decrypt_batch_json(request.as_bytes()).expect("batch should succeed");
        let response: serde_json::Value = serde_json::from_slice(&raw).expect("json response");

        assert_eq!(response["items"][0]["ok"], true);
        assert_eq!(response["items"][0]["plaintext"], "batch-payload");
    }

    #[test]
    fn decrypt_batch_json_reports_malformed_base64_per_item() {
        let request = br#"{"items":[{"encType":0,"ciphertext":"not_base64!!!","userId":1}]}"#;

        let raw = decrypt_batch_json(request).expect("batch envelope should be valid");
        let response: serde_json::Value = serde_json::from_slice(&raw).expect("json response");

        assert_eq!(response["items"][0]["ok"], false);
        assert!(
            response["items"][0]["error"]
                .as_str()
                .unwrap()
                .contains("base64")
        );
    }

    #[test]
    fn returns_empty_items_for_empty_batch() {
        let response =
            decrypt_batch_json(br#"{"items":[]}"#).expect("empty batch should serialize response");

        let response: Value =
            serde_json::from_slice(&response).expect("response should be valid json");

        assert_eq!(
            response["items"]
                .as_array()
                .expect("items should be an array")
                .len(),
            0
        );
    }

    #[test]
    fn rejects_invalid_request_json() {
        let error = decrypt_batch_json(b"not-json").expect_err("invalid json should fail");

        assert!(matches!(error, NativeCoreError::InvalidRequest(_)));
    }

    fn encrypt(enc_type: i32, plaintext: &str, user_id: i64) -> String {
        let salt = gen_salt(user_id, enc_type).expect("salt");
        let key = derive_key(&KEY_BYTES, &salt, 2, 32);
        let encrypted = Aes256CbcEnc::new((&key[..]).into(), (&IV_BYTES).into())
            .encrypt_padded_vec_mut::<Pkcs7>(plaintext.as_bytes());
        STANDARD.encode(encrypted)
    }
}
