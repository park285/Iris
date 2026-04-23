use crate::adb::Adb;
use crate::config::DaemonConfig;
use anyhow::{Context, Result};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::Read;
use std::path::Path;

pub fn render_template_json(template: &str, vars: &HashMap<String, String>) -> Result<String> {
    let mut value: serde_json::Value = serde_json::from_str(template)?;
    replace_placeholders(&mut value, vars);
    Ok(serde_json::to_string_pretty(&value)?)
}

pub fn collect_iris_env_vars() -> HashMap<String, String> {
    std::env::vars()
        .filter(|(key, _)| key.starts_with("IRIS_") || key.starts_with("WEBHOOK_"))
        .collect()
}

pub async fn render_and_push(adb: &Adb, cfg: &DaemonConfig) -> Result<()> {
    let template_path = Path::new(&cfg.init.config_template);
    if !template_path.exists() {
        tracing::warn!(path = %template_path.display(), "config 템플릿 파일 없음, config push 건너뜀");
        return Ok(());
    }
    let template = std::fs::read_to_string(template_path)
        .with_context(|| format!("config 템플릿 읽기 실패: {}", template_path.display()))?;
    let vars = collect_iris_env_vars();
    let rendered = render_template_json(&template, &vars)
        .with_context(|| format!("config 템플릿 렌더링 실패: {}", template_path.display()))?;
    let tmp_dir = std::env::temp_dir();
    let tmp_file = tmp_dir.join("iris-daemon-config-rendered.json");
    std::fs::write(&tmp_file, &rendered)
        .with_context(|| format!("렌더링 결과 저장 실패: {}", tmp_file.display()))?;
    adb.push(&tmp_file, &cfg.init.config_dest).await?;
    tracing::info!(dest = %cfg.init.config_dest, vars_count = vars.len(), "config 렌더링 + push 완료");
    let _ = std::fs::remove_file(&tmp_file);
    Ok(())
}

pub async fn sync_apk_if_needed(
    adb: &Adb,
    cfg: &DaemonConfig,
    strict_source: bool,
) -> Result<bool> {
    let apk_src = Path::new(&cfg.init.apk_src);
    if !apk_src.exists() {
        if strict_source {
            anyhow::bail!("APK source missing: {}", apk_src.display());
        }
        tracing::warn!(path = %apk_src.display(), "APK source missing, APK drift check skipped");
        return Ok(false);
    }

    let local_hash = local_file_sha256(apk_src)
        .with_context(|| format!("로컬 APK hash 계산 실패: {}", apk_src.display()))?;
    let device_hash = device_file_sha256(adb, &cfg.init.apk_dest).await?;
    if !apk_needs_sync(&local_hash, device_hash.as_deref()) {
        tracing::debug!(dest = %cfg.init.apk_dest, "APK drift 없음");
        return Ok(false);
    }

    adb.push(apk_src, &cfg.init.apk_dest).await?;
    let quoted_apk_dest = shell_quote(&cfg.init.apk_dest);
    let chmod_command = format!(
        "su 0 sh -lc {}",
        shell_quote(&format!("chmod 644 {quoted_apk_dest}")),
    );
    let _ = adb.shell(&chmod_command).await;
    tracing::info!(src = %apk_src.display(), dest = %cfg.init.apk_dest, "APK sync 완료");
    Ok(true)
}

pub async fn check_and_sync(adb: &Adb, cfg: &DaemonConfig) -> Result<()> {
    let config_changed = sync_config_if_needed(adb, cfg).await?;
    let apk_changed = sync_apk_if_needed(adb, cfg, false).await?;
    if config_changed || apk_changed {
        tracing::info!(
            config_changed = config_changed,
            apk_changed = apk_changed,
            "runtime drift 감지 — Iris 재시작",
        );
        crate::process::restart_iris(adb, cfg).await?;
    }
    Ok(())
}

async fn sync_config_if_needed(adb: &Adb, cfg: &DaemonConfig) -> Result<bool> {
    let template_path = Path::new(&cfg.init.config_template);
    if !template_path.exists() {
        return Ok(false);
    }
    let config_path = shell_quote(&cfg.init.config_dest);
    let device_config = match adb.shell(&format!("cat {config_path}")).await {
        Ok(content) => content,
        Err(error) => {
            tracing::warn!(error = %error, "디바이스 config 읽기 실패 — config drift check 건너뜀");
            return Ok(false);
        }
    };
    let template = std::fs::read_to_string(template_path)
        .with_context(|| format!("config 템플릿 읽기 실패: {}", template_path.display()))?;
    let vars = collect_iris_env_vars();
    let expected = render_template_json(&template, &vars)
        .with_context(|| format!("config 템플릿 렌더링 실패: {}", template_path.display()))?;
    let device_normalized = normalize_json(&device_config);
    let expected_normalized = normalize_json(&expected);
    if device_normalized == expected_normalized {
        tracing::debug!("config drift 없음");
        return Ok(false);
    }
    tracing::warn!("config drift 감지 — 재렌더링 + push 수행");
    render_and_push(adb, cfg).await?;
    Ok(true)
}

fn normalize_json(input: &str) -> String {
    serde_json::from_str::<serde_json::Value>(input)
        .and_then(|v| serde_json::to_string(&v))
        .unwrap_or_else(|_| input.trim().to_string())
}

fn replace_placeholders(value: &mut serde_json::Value, vars: &HashMap<String, String>) {
    match value {
        serde_json::Value::String(raw) => {
            let mut rendered = raw.clone();
            for (key, value) in vars {
                rendered = rendered.replace(&format!("${{{key}}}"), value);
            }
            *raw = rendered;
        }
        serde_json::Value::Array(items) => {
            for item in items {
                replace_placeholders(item, vars);
            }
        }
        serde_json::Value::Object(entries) => {
            for value in entries.values_mut() {
                replace_placeholders(value, vars);
            }
        }
        _ => {}
    }
}

fn local_file_sha256(path: &Path) -> Result<String> {
    let mut file = std::fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 8192];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}

async fn device_file_sha256(adb: &Adb, remote_path: &str) -> Result<Option<String>> {
    let output = adb.shell(&build_device_sha256_command(remote_path)).await?;
    Ok(parse_sha256_output(&output))
}

fn build_device_sha256_command(remote_path: &str) -> String {
    let quoted_path = shell_quote(remote_path);
    let inner = format!(
        "sha256sum {quoted_path} 2>/dev/null || toybox sha256sum {quoted_path} 2>/dev/null || true"
    );
    format!("su 0 sh -lc {}", shell_quote(&inner))
}

fn parse_sha256_output(output: &str) -> Option<String> {
    let digest = output
        .split_whitespace()
        .next()?
        .trim()
        .to_ascii_lowercase();
    if digest.len() == 64 && digest.chars().all(|ch| ch.is_ascii_hexdigit()) {
        Some(digest)
    } else {
        None
    }
}

fn apk_needs_sync(local_hash: &str, device_hash: Option<&str>) -> bool {
    device_hash != Some(local_hash)
}

fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\"'\"'"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn render_template_json_replaces_dollar_brace_vars() {
        let template = r#"{"url": "${IRIS_WEBHOOK_URL}", "token": "${IRIS_SHARED_TOKEN}"}"#;
        let mut vars = HashMap::new();
        vars.insert(
            "IRIS_WEBHOOK_URL".to_string(),
            "http://example.com".to_string(),
        );
        vars.insert("IRIS_SHARED_TOKEN".to_string(), "secret123".to_string());
        let result = render_template_json(template, &vars).unwrap();
        assert_eq!(
            normalize_json(&result),
            r#"{"token":"secret123","url":"http://example.com"}"#
        );
    }

    #[test]
    fn render_template_json_leaves_unknown_vars_intact() {
        let template = r#"{"url": "${UNKNOWN_VAR}"}"#;
        let vars = HashMap::new();
        let result = render_template_json(template, &vars).unwrap();
        assert_eq!(normalize_json(&result), r#"{"url":"${UNKNOWN_VAR}"}"#);
    }

    #[test]
    fn render_template_json_rejects_non_json_template() {
        let vars = HashMap::new();
        assert!(render_template_json("", &vars).is_err());
    }

    #[test]
    fn render_template_json_handles_multiple_occurrences() {
        let template = r#"{"message":"${A} and ${A} again"}"#;
        let mut vars = HashMap::new();
        vars.insert("A".to_string(), "x".to_string());
        let result = render_template_json(template, &vars).unwrap();
        assert_eq!(normalize_json(&result), r#"{"message":"x and x again"}"#);
    }

    #[test]
    fn render_template_json_escapes_embedded_quotes() {
        let template = r#"{"token":"${TOKEN}"}"#;
        let mut vars = HashMap::new();
        vars.insert("TOKEN".to_string(), "\"quoted\"\nvalue".to_string());

        let result = render_template_json(template, &vars).unwrap();

        assert_eq!(normalize_json(&result), r#"{"token":"\"quoted\"\nvalue"}"#);
    }

    #[test]
    fn normalize_json_compacts_whitespace() {
        let input = r#"{  "a":  1,  "b": "hello"  }"#;
        let normalized = normalize_json(input);
        assert_eq!(normalized, r#"{"a":1,"b":"hello"}"#);
    }

    #[test]
    fn normalize_json_returns_original_on_invalid_json() {
        let input = "not json at all";
        let normalized = normalize_json(input);
        assert_eq!(normalized, "not json at all");
    }

    #[test]
    fn normalize_json_handles_nested_objects() {
        let a = r#"{"outer": {"inner": 42}}"#;
        let b = r#"{  "outer" :  { "inner" : 42 }  }"#;
        assert_eq!(normalize_json(a), normalize_json(b));
    }

    #[test]
    fn parse_sha256_output_extracts_digest() {
        let digest = parse_sha256_output(
            "543c84b34d339f923939b62b81147fd729087c280ab9963256e7af55b3cd8b5b  /data/local/tmp/Iris.apk",
        );
        assert_eq!(
            digest.as_deref(),
            Some("543c84b34d339f923939b62b81147fd729087c280ab9963256e7af55b3cd8b5b"),
        );
    }

    #[test]
    fn parse_sha256_output_returns_none_for_missing_or_invalid_output() {
        assert_eq!(parse_sha256_output(""), None);
        assert_eq!(parse_sha256_output("No such file"), None);
        assert_eq!(parse_sha256_output("abc  /data/local/tmp/Iris.apk"), None);
    }

    #[test]
    fn apk_needs_sync_when_device_hash_is_missing_or_different() {
        let digest = "543c84b34d339f923939b62b81147fd729087c280ab9963256e7af55b3cd8b5b";
        assert!(apk_needs_sync(digest, None));
        assert!(apk_needs_sync(
            digest,
            Some("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        ));
        assert!(!apk_needs_sync(digest, Some(digest)));
    }

    #[test]
    fn build_device_sha256_command_uses_root_shell_and_fallback() {
        let command = build_device_sha256_command("/data/local/tmp/Iris.apk");
        let quoted_path = shell_quote("/data/local/tmp/Iris.apk");
        let expected_inner = format!(
            "sha256sum {quoted_path} 2>/dev/null || toybox sha256sum {quoted_path} 2>/dev/null || true"
        );

        assert_eq!(command, format!("su 0 sh -lc {}", shell_quote(&expected_inner)));
    }

    #[test]
    fn build_device_sha256_command_quotes_remote_path() {
        let remote_path = "/data/local/tmp/Iris weird's.apk";
        let command = build_device_sha256_command(remote_path);
        let quoted_path = shell_quote(remote_path);
        let expected_inner = format!(
            "sha256sum {quoted_path} 2>/dev/null || toybox sha256sum {quoted_path} 2>/dev/null || true"
        );

        assert_eq!(command, format!("su 0 sh -lc {}", shell_quote(&expected_inner)));
    }
}
