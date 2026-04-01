use crate::adb::Adb;
use crate::config::DaemonConfig;
use anyhow::{Result, bail};
use std::time::Duration;
use tokio::process::Command;

const IRIS_PROCESS_NAME: &str = "party.qwer.iris.Main";
const KAKAOTALK_PACKAGE: &str = "com.kakao.talk";
const SIGTERM_WAIT_SECS: u64 = 5;
const STARTUP_RETRY_COUNT: usize = 10;
const KILL_RETRY_WAIT_SECS: u64 = 1;
const DEFAULT_IRIS_CONTROL_PATH: &str = "/root/work/Iris/iris_control";

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

    tracing::info!(pid = pid, control = iris_control_path(), "iris_control stop 호출");
    run_iris_control("stop").await?;
    wait_for_process_exit(SIGTERM_WAIT_SECS).await;
    if iris_pid(adb).await.is_some() {
        force_kill_if_needed(adb, pid).await?;
    }
    tracing::info!(pid = pid, "Iris 프로세스 종료 완료");
    Ok(())
}

fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\"'\"'"))
}

fn env_or_default(key: &str, fallback: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| fallback.to_string())
}

fn iris_control_path() -> String {
    env_or_default("IRIS_CONTROL_PATH", DEFAULT_IRIS_CONTROL_PATH)
}

async fn run_iris_control(subcommand: &str) -> Result<()> {
    let output = Command::new(iris_control_path())
        .arg(subcommand)
        .output()
        .await?;

    if output.status.success() {
        return Ok(());
    }

    bail!(
        "iris_control {} failed (status={}): stdout={} stderr={}",
        subcommand,
        output.status,
        String::from_utf8_lossy(&output.stdout).trim(),
        String::from_utf8_lossy(&output.stderr).trim(),
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

    let _ = cfg;
    tracing::info!(control = iris_control_path(), "iris_control start 호출");
    run_iris_control("start").await?;
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
        assert_eq!(iris_control_path(), DEFAULT_IRIS_CONTROL_PATH);
    }

    #[test]
    fn start_command_excludes_token_env_vars() {
        let path = iris_control_path();
        assert!(!path.contains("IRIS_WEBHOOK_TOKEN"));
        assert!(!path.contains("IRIS_BOT_TOKEN"));
    }

    #[test]
    fn parse_iris_pid_ignores_shell_wrapper_lines() {
        let output = "27346 sh -c mkdir -p '/data/iris/logs' && KAKAOTALK_APP_UID=0 app_process / party.qwer.iris.Main\n27348 app_process / party.qwer.iris.Main";
        assert_eq!(parse_iris_pid(output), Some(27348));
    }
}
