use crate::adb::Adb;
use crate::config::DaemonConfig;
use anyhow::{Context, Result};
use std::collections::HashMap;
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

pub async fn check_and_sync(adb: &Adb, cfg: &DaemonConfig) -> Result<()> {
    let template_path = Path::new(&cfg.init.config_template);
    if !template_path.exists() {
        return Ok(());
    }
    let device_config = match adb.shell(&format!("cat {}", cfg.init.config_dest)).await {
        Ok(content) => content,
        Err(e) => {
            tracing::warn!(error = %e, "디바이스 config 읽기 실패 — drift check 건너뜀");
            return Ok(());
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
        return Ok(());
    }
    tracing::warn!("config drift 감지 — 재렌더링 + push 수행");
    render_and_push(adb, cfg).await?;
    tracing::info!("config 변경으로 인한 Iris 재시작");
    crate::process::restart_iris(adb, cfg).await?;
    Ok(())
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
}
