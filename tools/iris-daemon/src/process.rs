use crate::adb::Adb;
use crate::config::DaemonConfig;
use anyhow::{Result, bail};
use std::path::Path;
use std::time::Duration;

const IRIS_PROCESS_NAME: &str = "party.qwer.iris.Main";
const KAKAOTALK_PACKAGE: &str = "com.kakao.talk";
const SIGTERM_WAIT_SECS: u64 = 5;
const STARTUP_RETRY_COUNT: usize = 10;
const KILL_RETRY_WAIT_SECS: u64 = 1;
const DEFAULT_IRIS_BIND_HOST: &str = "127.0.0.1";
const DEFAULT_IRIS_LOG_LEVEL: &str = "INFO";
const DEFAULT_IRIS_LOG_DEST: &str = "/data/iris/logs/iris.log";

pub async fn iris_pid(adb: &Adb) -> Option<u32> {
    let output = adb
        .shell(&format!(
            "ps -A -o PID,ARGS | grep 'app_process / {IRIS_PROCESS_NAME}' | grep -v grep"
        ))
        .await
        .ok()?;
    parse_iris_pid(&output)
}

fn parse_iris_pid(output: &str) -> Option<u32> {
    for line in output.lines() {
        if line.contains("grep") {
            continue;
        }

        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() < 4 {
            continue;
        }

        if parts[1] == "app_process" && parts[2] == "/" && parts[3] == IRIS_PROCESS_NAME {
            return parts[0].parse().ok();
        }
    }
    None
}

async fn wait_for_process_exit(wait_secs: u64) {
    tokio::time::sleep(Duration::from_secs(wait_secs)).await;
}

async fn send_kill(adb: &Adb, pid: u32, signal: Option<&str>) {
    let kill_cmd = signal.map_or_else(
        || format!("kill {pid}"),
        |signal| format!("kill -{signal} {pid}"),
    );
    let command = format!("su root sh -c {}", shell_quote(&kill_cmd));
    let _ = adb.shell(&command).await;
}

async fn ensure_process_exited(adb: &Adb, pid: u32) -> Result<()> {
    if iris_pid(adb).await.is_some() {
        bail!("Iris 프로세스 종료 실패 (PID: {pid})");
    }
    Ok(())
}

async fn force_kill_if_needed(adb: &Adb, pid: u32) -> Result<()> {
    if iris_pid(adb).await.is_none() {
        return Ok(());
    }

    tracing::warn!(pid = pid, "SIGTERM 무응답, SIGKILL 전송");
    send_kill(adb, pid, Some("9")).await;
    wait_for_process_exit(KILL_RETRY_WAIT_SECS).await;
    ensure_process_exited(adb, pid).await
}

pub async fn stop_iris(adb: &Adb) -> Result<()> {
    let Some(pid) = iris_pid(adb).await else {
        tracing::info!("Iris 프로세스 미실행, 종료 불필요");
        return Ok(());
    };

    tracing::info!(pid = pid, "Iris 프로세스 종료 시도 (SIGTERM)");
    send_kill(adb, pid, None).await;
    wait_for_process_exit(SIGTERM_WAIT_SECS).await;
    force_kill_if_needed(adb, pid).await?;
    tracing::info!(pid = pid, "Iris 프로세스 종료 완료");
    Ok(())
}

fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\"'\"'"))
}

fn env_or_default(key: &str, fallback: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| fallback.to_string())
}

fn start_command(cfg: &DaemonConfig) -> String {
    let bind_host = env_or_default("IRIS_BIND_HOST", DEFAULT_IRIS_BIND_HOST);
    let log_level = env_or_default("IRIS_LOG_LEVEL", DEFAULT_IRIS_LOG_LEVEL);
    let log_dest = env_or_default("IRIS_LOG_PATH", DEFAULT_IRIS_LOG_DEST);
    let log_dir = Path::new(&log_dest)
        .parent()
        .and_then(|path| path.to_str())
        .unwrap_or("/data/iris/logs");
    let launch_cmd = format!(
        "mkdir -p {log_dir}; KAKAOTALK_APP_UID=0 \
         IRIS_CONFIG_PATH={config} \
         IRIS_BIND_HOST={bind_host} \
         IRIS_LOG_LEVEL={log_level} \
         CLASSPATH={apk} \
         app_process / {main} > {log_dest} 2>&1 &",
        log_dir = shell_quote(log_dir),
        config = shell_quote(&cfg.init.config_dest),
        bind_host = shell_quote(&bind_host),
        log_level = shell_quote(&log_level),
        apk = shell_quote(&cfg.init.apk_dest),
        main = IRIS_PROCESS_NAME,
        log_dest = shell_quote(&log_dest),
    );
    format!(
        "su root sh -c {}",
        shell_quote(&launch_cmd),
    )
}

async fn wait_for_process_start(adb: &Adb) -> Result<()> {
    for _ in 0..STARTUP_RETRY_COUNT {
        tokio::time::sleep(Duration::from_secs(1)).await;
        if iris_pid(adb).await.is_some() {
            tracing::info!("Iris 프로세스 시작 확인");
            return Ok(());
        }
    }
    bail!("Iris 프로세스 시작 실패 (10초 timeout)");
}

pub async fn start_iris(adb: &Adb, cfg: &DaemonConfig) -> Result<()> {
    if iris_pid(adb).await.is_some() {
        tracing::warn!("Iris 프로세스 이미 실행 중, 시작 건너뜀");
        return Ok(());
    }

    adb.shell(&start_command(cfg)).await?;
    tracing::info!("Iris 프로세스 시작 명령 전송");
    wait_for_process_start(adb).await
}

pub async fn restart_iris(adb: &Adb, cfg: &DaemonConfig) -> Result<()> {
    tracing::info!("Iris 프로세스 재시작 시작");
    stop_iris(adb).await?;
    start_iris(adb, cfg).await?;
    tracing::info!("Iris 프로세스 재시작 완료");
    Ok(())
}

pub async fn kakaotalk_alive(adb: &Adb) -> bool {
    adb.shell(&format!("pidof {KAKAOTALK_PACKAGE}"))
        .await
        .map(|output| !output.is_empty())
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constants_are_correct() {
        assert_eq!(IRIS_PROCESS_NAME, "party.qwer.iris.Main");
        assert_eq!(KAKAOTALK_PACKAGE, "com.kakao.talk");
        assert_eq!(SIGTERM_WAIT_SECS, 5);
    }

    #[test]
    fn start_command_includes_runtime_arguments() {
        let cfg = DaemonConfig::default();
        let command = start_command(&cfg);

        assert!(command.contains(IRIS_PROCESS_NAME));
        assert!(command.contains("su root sh -c"));
        assert!(command.contains("mkdir -p "));
        assert!(command.contains("/data/iris/logs"));
        assert!(command.contains("KAKAOTALK_APP_UID=0"));
        assert!(command.contains("IRIS_CONFIG_PATH="));
        assert!(command.contains("config.json"));
        assert!(command.contains("IRIS_BIND_HOST="));
        assert!(command.contains("127.0.0.1"));
        assert!(command.contains("IRIS_LOG_LEVEL="));
        assert!(command.contains("INFO"));
        assert!(command.contains("CLASSPATH="));
        assert!(command.contains("Iris.apk"));
        assert!(command.contains("iris.log"));
        assert!(command.contains("2>&1 &"));
    }

    #[test]
    fn start_command_excludes_token_env_vars() {
        let mut cfg = DaemonConfig::default();
        cfg.iris.shared_token = "shared-token".to_string();
        let command = start_command(&cfg);

        // 토큰은 config.json에서만 공급됨, env var 주입 제거됨
        assert!(!command.contains("IRIS_WEBHOOK_TOKEN"));
        assert!(!command.contains("IRIS_BOT_TOKEN"));
    }

    #[test]
    fn parse_iris_pid_ignores_shell_wrapper_lines() {
        let output = "27346 sh -c mkdir -p '/data/iris/logs' && KAKAOTALK_APP_UID=0 app_process / party.qwer.iris.Main\n27348 app_process / party.qwer.iris.Main";
        assert_eq!(parse_iris_pid(output), Some(27348));
    }
}
