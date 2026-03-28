use crate::adb::Adb;
use crate::config::DaemonConfig;
use crate::config_sync;
use crate::process;
use anyhow::{Result, bail};
use std::time::Duration;

pub async fn run_init(cfg: &DaemonConfig) -> Result<()> {
    let adb = Adb::new(&cfg.adb.device);
    tracing::info!(device = %cfg.adb.device, "init 시작");
    let connect_result = adb.connect().await?;
    tracing::info!(result = %connect_result, "ADB 연결");
    wait_for_boot(&adb, cfg.init.boot_timeout_secs).await?;
    if cfg.init.phantom_killer_disable {
        disable_phantom_killer(&adb).await;
    }
    config_sync::render_and_push(&adb, cfg).await?;
    if !process::kakaotalk_alive(&adb).await {
        tracing::warn!("KakaoTalk 미실행 — Iris 시작 전에 KakaoTalk이 실행 중이어야 합니다");
    }
    process::stop_iris(&adb).await?;
    process::start_iris(&adb, cfg).await?;
    tracing::info!("init 완료");
    Ok(())
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
            bail!(
                "부팅 대기 timeout ({}초): sys.boot_completed=1 미확인",
                timeout_secs
            );
        }
        tokio::time::sleep(Duration::from_secs(5)).await;
    }
}

async fn disable_phantom_killer(adb: &Adb) {
    let commands = [
        "settings put global settings_enable_monitor_phantom_procs false",
        "setprop persist.sys.fflag.override.settings_enable_monitor_phantom_procs false",
    ];
    for cmd in &commands {
        match adb.shell(cmd).await {
            Ok(_) => tracing::debug!(cmd = cmd, "phantom killer 비활성화 명령 성공"),
            Err(e) => {
                tracing::warn!(cmd = cmd, error = %e, "phantom killer 비활성화 명령 실패 (무시)")
            }
        }
    }
    tracing::info!("phantom process killer 비활성화 완료");
}

#[cfg(test)]
mod tests {
    #[test]
    fn phantom_killer_commands_are_correct() {
        let cmd1 = "settings put global settings_enable_monitor_phantom_procs false";
        let cmd2 = "setprop persist.sys.fflag.override.settings_enable_monitor_phantom_procs false";
        assert!(cmd1.contains("settings_enable_monitor_phantom_procs"));
        assert!(cmd2.contains("persist.sys.fflag.override"));
    }
}
