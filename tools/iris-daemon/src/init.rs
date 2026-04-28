use crate::adb::Adb;
use crate::config::DaemonConfig;
use crate::config_sync;
use crate::process;
use anyhow::{Result, bail};
use std::time::Duration;

const PHANTOM_KILLER_COMMANDS: [&str; 2] = [
    "settings put global settings_enable_monitor_phantom_procs false",
    "setprop persist.sys.fflag.override.settings_enable_monitor_phantom_procs false",
];

pub async fn run_init(cfg: &DaemonConfig) -> Result<()> {
    let adb = Adb::new(&cfg.adb.device);
    tracing::info!(device = %cfg.adb.device, "init 시작");

    prepare_device(&adb, cfg).await?;
    config_sync::render_and_push(&adb, cfg).await?;
    config_sync::sync_apk_if_needed(&adb, cfg, true).await?;
    config_sync::sync_native_lib_if_needed(&adb, cfg, false).await?;
    ensure_runtime_ready(&adb, cfg).await?;

    tracing::info!("init 완료");
    Ok(())
}

async fn prepare_device(adb: &Adb, cfg: &DaemonConfig) -> Result<()> {
    connect_adb(adb).await?;
    wait_for_boot(adb, cfg.init.boot_timeout_secs).await?;
    maybe_disable_phantom_killer(adb, cfg).await;
    Ok(())
}

async fn ensure_runtime_ready(adb: &Adb, cfg: &DaemonConfig) -> Result<()> {
    warn_if_kakaotalk_not_running(adb).await;
    restart_iris_process(adb, cfg).await
}

async fn connect_adb(adb: &Adb) -> Result<()> {
    let connect_result = adb.connect().await?;
    tracing::info!(result = %connect_result, "ADB 연결");
    Ok(())
}

async fn maybe_disable_phantom_killer(adb: &Adb, cfg: &DaemonConfig) {
    if cfg.init.phantom_killer_disable {
        disable_phantom_killer(adb).await;
    }
}

async fn warn_if_kakaotalk_not_running(adb: &Adb) {
    if !process::kakaotalk_alive(adb).await {
        tracing::warn!("KakaoTalk 미실행 — Iris 시작 전에 KakaoTalk이 실행 중이어야 합니다");
    }
}

async fn restart_iris_process(adb: &Adb, cfg: &DaemonConfig) -> Result<()> {
    process::stop_iris(adb).await?;
    process::start_iris(adb, cfg).await
}

async fn wait_for_boot(adb: &Adb, timeout_secs: u64) -> Result<()> {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(timeout_secs);
    tracing::info!(timeout_secs = timeout_secs, "부팅 대기 시작");
    loop {
        if adb.is_boot_completed().await {
            tracing::info!("부팅 완료 확인");
            return Ok(());
        }
        if tokio::time::Instant::now() >= deadline {
            bail!("부팅 대기 timeout ({timeout_secs}초): sys.boot_completed=1 미확인");
        }
        tokio::time::sleep(Duration::from_secs(5)).await;
    }
}

async fn disable_phantom_killer(adb: &Adb) {
    for command in PHANTOM_KILLER_COMMANDS {
        run_phantom_killer_command(adb, command).await;
    }
    tracing::info!("phantom process killer 비활성화 완료");
}

async fn run_phantom_killer_command(adb: &Adb, command: &str) {
    match adb.shell(command).await {
        Ok(_) => tracing::debug!(cmd = command, "phantom killer 비활성화 명령 성공"),
        Err(error) => {
            tracing::warn!(cmd = command, error = %error, "phantom killer 비활성화 명령 실패 (무시)");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn phantom_killer_commands_are_correct() {
        assert!(PHANTOM_KILLER_COMMANDS[0].contains("settings_enable_monitor_phantom_procs"));
        assert!(PHANTOM_KILLER_COMMANDS[1].contains("persist.sys.fflag.override"));
    }
}
