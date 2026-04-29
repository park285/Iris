use crate::adb::Adb;
use crate::config::DaemonConfig;
use anyhow::{Context, Result};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::Read;
use std::path::Path;

const NATIVE_LIB_MODE: &str = "644";
const NATIVE_LIB_TEMP_HASH_PREFIX_LEN: usize = 16;
const PLACEHOLDER_SECRET_VALUE: &str = "change-me";
const PLACEHOLDER_WEBHOOK_HOST: &str = "example.invalid";

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
    validate_rendered_config(&rendered).with_context(|| {
        format!(
            "config 렌더링 안전성 검증 실패: {}",
            template_path.display()
        )
    })?;
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

pub async fn sync_native_lib_if_needed(
    adb: &Adb,
    cfg: &DaemonConfig,
    strict_source: bool,
) -> Result<bool> {
    let native_core_mode = native_core_mode_from_env();
    if !native_core_mode.sync_enabled() {
        tracing::debug!("native core mode off — native library sync skipped");
        return Ok(false);
    }

    let native_lib_src = Path::new(&cfg.init.native_lib_src);
    if !native_lib_source_available(native_lib_src, strict_source, native_core_mode)? {
        return Ok(false);
    }

    let local_hash = local_file_sha256(native_lib_src).with_context(|| {
        format!(
            "로컬 native library hash 계산 실패: {}",
            native_lib_src.display()
        )
    })?;
    let device_hash = device_file_sha256(adb, &cfg.init.native_lib_dest).await?;
    if !apk_needs_sync(&local_hash, device_hash.as_deref()) {
        tracing::debug!(dest = %cfg.init.native_lib_dest, "native library drift 없음");
        return Ok(false);
    }

    push_native_lib_atomically(adb, native_lib_src, &cfg.init.native_lib_dest, &local_hash).await?;

    tracing::info!(src = %native_lib_src.display(), dest = %cfg.init.native_lib_dest, "native library sync 완료");
    Ok(true)
}

async fn push_native_lib_atomically(
    adb: &Adb,
    native_lib_src: &Path,
    native_lib_dest: &str,
    local_hash: &str,
) -> Result<()> {
    ensure_native_lib_dest_dir(adb, native_lib_dest).await?;
    let temp_dest = native_lib_temp_path(native_lib_dest, local_hash);
    adb.push(native_lib_src, &temp_dest)
        .await
        .with_context(|| format!("native library temp push 실패: {temp_dest}"))?;

    let temp_hash = device_file_sha256(adb, &temp_dest)
        .await
        .with_context(|| format!("native library temp hash 확인 실패: {temp_dest}"))?;
    verify_native_lib_remote_hash(
        NativeLibHashStage::Temp,
        &temp_dest,
        local_hash,
        temp_hash.as_deref(),
    )?;

    adb.shell(&build_device_chmod_command(&temp_dest, NATIVE_LIB_MODE))
        .await
        .with_context(|| format!("native library temp chmod 실패: {temp_dest}"))?;

    adb.shell(&build_device_atomic_replace_command(
        &temp_dest,
        native_lib_dest,
    ))
    .await
    .with_context(|| {
        format!("native library atomic replace 실패: {temp_dest} -> {native_lib_dest}")
    })?;

    let final_hash = device_file_sha256(adb, native_lib_dest)
        .await
        .with_context(|| format!("native library final hash 확인 실패: {native_lib_dest}"))?;
    verify_native_lib_remote_hash(
        NativeLibHashStage::Final,
        native_lib_dest,
        local_hash,
        final_hash.as_deref(),
    )?;
    Ok(())
}

fn native_lib_source_available(
    native_lib_src: &Path,
    strict_source: bool,
    native_core_mode: NativeCoreMode,
) -> Result<bool> {
    if native_lib_src.exists() {
        return Ok(true);
    }
    if strict_source && native_core_mode.requires_source() {
        anyhow::bail!(
            "native library source missing: {}",
            native_lib_src.display()
        );
    }
    tracing::warn!(
        path = %native_lib_src.display(),
        "native library source missing, native library drift check skipped"
    );
    Ok(false)
}

async fn ensure_native_lib_dest_dir(adb: &Adb, native_lib_dest: &str) -> Result<()> {
    if let Some(dest_dir) = remote_parent_dir(native_lib_dest) {
        adb.shell(&build_device_mkdir_command(&dest_dir))
            .await
            .with_context(|| format!("native library 대상 디렉터리 생성 실패: {dest_dir}"))?;
    }
    Ok(())
}

pub async fn check_and_sync(adb: &Adb, cfg: &DaemonConfig) -> Result<()> {
    let config_changed = sync_config_if_needed(adb, cfg).await?;
    let apk_changed = sync_apk_if_needed(adb, cfg, false).await?;
    let native_changed = if native_sync_enabled_from_env() {
        sync_native_lib_if_needed(adb, cfg, false).await?
    } else {
        false
    };
    if config_changed || apk_changed || native_changed {
        tracing::info!(
            config_changed = config_changed,
            apk_changed = apk_changed,
            native_changed = native_changed,
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
    validate_rendered_config(&expected).with_context(|| {
        format!(
            "config 렌더링 안전성 검증 실패: {}",
            template_path.display()
        )
    })?;
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

fn validate_rendered_config(rendered: &str) -> Result<()> {
    let value: serde_json::Value = serde_json::from_str(rendered)?;
    let mut errors = Vec::new();
    collect_rendered_config_errors(&value, "$", &mut errors);
    if errors.is_empty() {
        return Ok(());
    }

    anyhow::bail!(
        "rendered config contains unsafe placeholder values: {}",
        errors.join(", ")
    );
}

fn collect_rendered_config_errors(value: &serde_json::Value, path: &str, errors: &mut Vec<String>) {
    match value {
        serde_json::Value::String(raw) => {
            if contains_unresolved_placeholder(raw) {
                errors.push(format!("{path}: unresolved placeholder"));
            }
            if is_runtime_secret_path(path) && is_placeholder_secret(raw) {
                errors.push(format!("{path}: placeholder secret"));
            }
            if is_webhook_endpoint_path(path) && is_placeholder_webhook_endpoint(raw) {
                errors.push(format!("{path}: placeholder webhook endpoint"));
            }
        }
        serde_json::Value::Array(items) => {
            for (index, item) in items.iter().enumerate() {
                collect_rendered_config_errors(item, &format!("{path}[{index}]"), errors);
            }
        }
        serde_json::Value::Object(entries) => {
            for (key, value) in entries {
                collect_rendered_config_errors(value, &format!("{path}.{key}"), errors);
            }
        }
        _ => {}
    }
}

fn contains_unresolved_placeholder(value: &str) -> bool {
    value.contains("${")
}

fn is_runtime_secret_path(path: &str) -> bool {
    let field_name = path.rsplit('.').next().unwrap_or_default();
    matches!(
        field_name,
        "inboundSigningSecret"
            | "outboundWebhookToken"
            | "botControlToken"
            | "bridgeToken"
            | "sharedToken"
    )
}

fn is_webhook_endpoint_path(path: &str) -> bool {
    path == "$.endpoint" || path.starts_with("$.webhooks.")
}

fn is_placeholder_secret(value: &str) -> bool {
    let trimmed = value.trim();
    trimmed.eq_ignore_ascii_case(PLACEHOLDER_SECRET_VALUE)
        || contains_unresolved_placeholder(trimmed)
}

fn is_placeholder_webhook_endpoint(value: &str) -> bool {
    let trimmed = value.trim();
    if trimmed.is_empty() || contains_unresolved_placeholder(trimmed) {
        return contains_unresolved_placeholder(trimmed);
    }

    webhook_endpoint_host(trimmed)
        .is_some_and(|host| host.eq_ignore_ascii_case(PLACEHOLDER_WEBHOOK_HOST))
}

fn webhook_endpoint_host(endpoint: &str) -> Option<&str> {
    let without_scheme = endpoint
        .strip_prefix("http://")
        .or_else(|| endpoint.strip_prefix("https://"))?;
    let authority = without_scheme
        .split(['/', '?', '#'])
        .next()
        .unwrap_or_default();
    authority.split(':').next().filter(|host| !host.is_empty())
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
    build_root_shell_command(&inner)
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

fn remote_parent_dir(remote_path: &str) -> Option<String> {
    let parent = Path::new(remote_path).parent()?;
    let parent = parent.to_string_lossy();
    if parent.is_empty() {
        None
    } else {
        Some(parent.into_owned())
    }
}

fn build_device_mkdir_command(remote_dir: &str) -> String {
    build_root_shell_command(&format!("mkdir -p {}", shell_quote(remote_dir)))
}

fn build_device_chmod_command(remote_path: &str, mode: &str) -> String {
    build_root_shell_command(&format!("chmod {mode} {}", shell_quote(remote_path)))
}

fn build_device_atomic_replace_command(temp_path: &str, final_path: &str) -> String {
    build_root_shell_command(&format!(
        "mv -f {} {}",
        shell_quote(temp_path),
        shell_quote(final_path)
    ))
}

fn build_root_shell_command(inner: &str) -> String {
    format!("su 0 sh -lc {}", shell_quote(inner))
}

fn native_lib_temp_path(native_lib_dest: &str, local_hash: &str) -> String {
    let prefix_len = local_hash.len().min(NATIVE_LIB_TEMP_HASH_PREFIX_LEN);
    let hash_prefix = &local_hash[..prefix_len];
    format!(
        "{native_lib_dest}.tmp.{}.{}",
        std::process::id(),
        hash_prefix
    )
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum NativeLibHashStage {
    Temp,
    Final,
}

impl NativeLibHashStage {
    const fn label(self) -> &'static str {
        match self {
            Self::Temp => "temp",
            Self::Final => "final",
        }
    }
}

fn verify_native_lib_remote_hash(
    stage: NativeLibHashStage,
    remote_path: &str,
    expected_hash: &str,
    actual_hash: Option<&str>,
) -> Result<()> {
    let Some(actual_hash) = actual_hash else {
        anyhow::bail!(
            "native library {} sha256 missing: {}",
            stage.label(),
            remote_path
        );
    };

    if actual_hash != expected_hash {
        anyhow::bail!(
            "native library {} sha256 mismatch for {}: expected {}, got {}",
            stage.label(),
            remote_path,
            expected_hash,
            actual_hash
        );
    }
    Ok(())
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum NativeCoreMode {
    Off,
    Shadow,
    On,
}

impl NativeCoreMode {
    fn sync_enabled(self) -> bool {
        self != Self::Off
    }

    fn requires_source(self) -> bool {
        self == Self::On
    }
}

pub fn native_sync_enabled_from_env() -> bool {
    let raw = std::env::var("IRIS_NATIVE_CORE").ok();
    native_sync_enabled_env_value(raw.as_deref())
}

pub fn native_required_from_env() -> bool {
    let raw = std::env::var("IRIS_NATIVE_CORE").ok();
    native_required_env_value(raw.as_deref())
}

fn native_core_mode_from_env() -> NativeCoreMode {
    let raw = std::env::var("IRIS_NATIVE_CORE").ok();
    native_core_mode_env_value(raw.as_deref())
}

fn native_core_mode_env_value(raw: Option<&str>) -> NativeCoreMode {
    let Some(value) = raw.map(str::trim) else {
        return NativeCoreMode::Off;
    };

    if value.eq_ignore_ascii_case("shadow") {
        NativeCoreMode::Shadow
    } else if value.eq_ignore_ascii_case("on") {
        NativeCoreMode::On
    } else {
        NativeCoreMode::Off
    }
}

pub fn native_sync_enabled_env_value(raw: Option<&str>) -> bool {
    native_core_mode_env_value(raw).sync_enabled()
}

pub fn native_required_env_value(raw: Option<&str>) -> bool {
    native_core_mode_env_value(raw).requires_source()
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
    fn validate_rendered_config_rejects_unresolved_placeholders() {
        let rendered = r#"{"webhooks":{"chatbotgo":"${IRIS_WEBHOOK_CHATBOTGO}"}}"#;

        let error = validate_rendered_config(rendered).unwrap_err();

        assert!(error.to_string().contains("unresolved placeholder"));
    }

    #[test]
    fn validate_rendered_config_rejects_placeholder_runtime_values() {
        let rendered = r#"{"inboundSigningSecret":"change-me","webhooks":{"chatbotgo":"http://example.invalid/webhook"}}"#;

        let error = validate_rendered_config(rendered).unwrap_err();
        let message = error.to_string();

        assert!(message.contains("placeholder secret"));
        assert!(message.contains("placeholder webhook endpoint"));
    }

    #[test]
    fn validate_rendered_config_allows_empty_optional_webhook() {
        let rendered = r#"{"inboundSigningSecret":"secret","webhooks":{"chatbotgo":"http://100.100.1.3:31001/webhook","twentyq":""}}"#;

        validate_rendered_config(rendered).unwrap();
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

        assert_eq!(
            command,
            format!("su 0 sh -lc {}", shell_quote(&expected_inner))
        );
    }

    #[test]
    fn build_device_sha256_command_quotes_remote_path() {
        let remote_path = "/data/local/tmp/Iris weird's.apk";
        let command = build_device_sha256_command(remote_path);
        let quoted_path = shell_quote(remote_path);
        let expected_inner = format!(
            "sha256sum {quoted_path} 2>/dev/null || toybox sha256sum {quoted_path} 2>/dev/null || true"
        );

        assert_eq!(
            command,
            format!("su 0 sh -lc {}", shell_quote(&expected_inner))
        );
    }

    #[test]
    fn remote_parent_dir_extracts_native_library_dest_dir() {
        assert_eq!(
            remote_parent_dir("/data/iris/lib/libiris_native_core.so").as_deref(),
            Some("/data/iris/lib"),
        );
        assert_eq!(remote_parent_dir("libiris_native_core.so"), None);
    }

    #[test]
    fn build_device_mkdir_command_uses_root_shell_and_quotes_dir() {
        let remote_dir = "/data/iris/lib weird's";
        let command = build_device_mkdir_command(remote_dir);
        let expected_inner = format!("mkdir -p {}", shell_quote(remote_dir));

        assert_eq!(
            command,
            format!("su 0 sh -lc {}", shell_quote(&expected_inner))
        );
    }

    #[test]
    fn build_device_chmod_command_uses_root_shell_and_quotes_path() {
        let remote_path = "/data/iris/lib/lib weird's.so";
        let command = build_device_chmod_command(remote_path, "644");
        let expected_inner = format!("chmod 644 {}", shell_quote(remote_path));

        assert_eq!(
            command,
            format!("su 0 sh -lc {}", shell_quote(&expected_inner))
        );
    }

    #[test]
    fn native_lib_temp_path_is_destination_specific_and_hash_scoped() {
        let remote_path = "/data/iris/lib/lib weird's.so";
        let digest = "543c84b34d339f923939b62b81147fd729087c280ab9963256e7af55b3cd8b5b";

        let temp_path = native_lib_temp_path(remote_path, digest);

        assert!(temp_path.starts_with("/data/iris/lib/lib weird's.so.tmp."));
        assert!(temp_path.ends_with(".543c84b34d339f92"));
    }

    #[test]
    fn build_device_atomic_replace_command_uses_root_mv_and_quotes_paths() {
        let temp_path = "/data/iris/lib/lib weird's.so.tmp.123.543c84b34d339f92";
        let final_path = "/data/iris/lib/lib weird's.so";
        let command = build_device_atomic_replace_command(temp_path, final_path);
        let expected_inner = format!(
            "mv -f {} {}",
            shell_quote(temp_path),
            shell_quote(final_path)
        );

        assert_eq!(
            command,
            format!("su 0 sh -lc {}", shell_quote(&expected_inner))
        );
    }

    #[test]
    fn verify_native_lib_remote_hash_rejects_final_hash_mismatch() {
        let expected = "543c84b34d339f923939b62b81147fd729087c280ab9963256e7af55b3cd8b5b";
        let actual = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

        let error = verify_native_lib_remote_hash(
            NativeLibHashStage::Final,
            "/data/iris/lib/libiris_native_core.so",
            expected,
            Some(actual),
        )
        .unwrap_err();

        let message = error.to_string();
        assert!(message.contains("final"));
        assert!(message.contains(expected));
        assert!(message.contains(actual));
    }

    #[test]
    fn verify_native_lib_remote_hash_rejects_missing_temp_hash() {
        let expected = "543c84b34d339f923939b62b81147fd729087c280ab9963256e7af55b3cd8b5b";

        let error = verify_native_lib_remote_hash(
            NativeLibHashStage::Temp,
            "/data/iris/lib/libiris_native_core.so.tmp.123.543c84b34d339f92",
            expected,
            None,
        )
        .unwrap_err();

        let message = error.to_string();
        assert!(message.contains("temp"));
        assert!(message.contains("missing"));
    }

    #[test]
    fn native_mode_parser_accepts_case_insensitive_modes_and_trims() {
        assert_eq!(native_core_mode_env_value(Some(" On ")), NativeCoreMode::On);
        assert_eq!(
            native_core_mode_env_value(Some("SHADOW")),
            NativeCoreMode::Shadow
        );
        assert_eq!(
            native_core_mode_env_value(Some(" off ")),
            NativeCoreMode::Off
        );
    }

    #[test]
    fn malformed_blank_and_missing_native_mode_are_off_and_do_not_sync() {
        for raw in [None, Some(""), Some("   "), Some("enabled")] {
            assert_eq!(native_core_mode_env_value(raw), NativeCoreMode::Off);
            assert!(!native_sync_enabled_env_value(raw));
        }
    }

    #[test]
    fn native_sync_enabled_for_shadow_and_on_only() {
        assert!(!native_sync_enabled_env_value(Some("off")));
        assert!(native_sync_enabled_env_value(Some("shadow")));
        assert!(native_sync_enabled_env_value(Some("ON")));
    }

    #[test]
    fn native_required_only_for_on_when_strict() {
        assert!(native_required_env_value(Some("on")));
        assert!(native_required_env_value(Some(" ON ")));
        assert!(!native_required_env_value(Some("shadow")));
        assert!(!native_required_env_value(Some("off")));
        assert!(!native_required_env_value(None));
    }

    #[test]
    fn missing_native_source_fails_only_for_on_when_strict() {
        let missing_path =
            std::env::temp_dir().join(format!("iris-daemon-missing-native-{}", std::process::id()));

        assert!(native_lib_source_available(&missing_path, true, NativeCoreMode::On).is_err());
        assert!(!native_lib_source_available(&missing_path, true, NativeCoreMode::Shadow).unwrap());
        assert!(!native_lib_source_available(&missing_path, true, NativeCoreMode::Off).unwrap());
        assert!(!native_lib_source_available(&missing_path, false, NativeCoreMode::On).unwrap());
    }

    #[test]
    fn verify_native_lib_remote_hash_accepts_match() {
        let expected = "543c84b34d339f923939b62b81147fd729087c280ab9963256e7af55b3cd8b5b";

        verify_native_lib_remote_hash(
            NativeLibHashStage::Final,
            "/data/iris/lib/libiris_native_core.so",
            expected,
            Some(expected),
        )
        .unwrap();
    }
}
