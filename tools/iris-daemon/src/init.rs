use crate::adb::Adb;
use crate::config::DaemonConfig;
use crate::config_sync;
use crate::process;
use anyhow::{Result, bail};
use clap::ValueEnum;
use std::time::Duration;

const PHANTOM_KILLER_COMMANDS: [&str; 2] = [
    "settings put global settings_enable_monitor_phantom_procs false",
    "setprop persist.sys.fflag.override.settings_enable_monitor_phantom_procs false",
];

#[derive(Clone, Copy, Debug, Eq, PartialEq, ValueEnum)]
#[value(rename_all = "kebab-case")]
pub enum InitMode {
    ForceRestart,
    IfMissing,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum RuntimeAction {
    Restart,
    Start,
    LeaveRunning,
}

pub async fn run_init(cfg: &DaemonConfig, mode: InitMode) -> Result<()> {
    let adb = Adb::new(&cfg.adb.device);
    tracing::info!(device = %cfg.adb.device, mode = ?mode, "init 시작");

    prepare_device(&adb, cfg).await?;
    let iris_running = process::iris_pid(&adb).await.is_some();
    sync_runtime_inputs(&adb, cfg, strict_source_for(mode, iris_running)).await?;
    let iris_running = refresh_iris_running_for_action(&adb, mode, iris_running).await;
    ensure_runtime_ready(&adb, cfg, runtime_action_for(mode, iris_running)).await?;

    tracing::info!("init 완료");
    Ok(())
}

async fn sync_runtime_inputs(adb: &Adb, cfg: &DaemonConfig, strict_source: bool) -> Result<()> {
    config_sync::render_and_push(adb, cfg).await?;
    config_sync::sync_apk_if_needed(adb, cfg, strict_source).await?;
    maybe_sync_native_lib(adb, cfg, strict_source).await
}

async fn maybe_sync_native_lib(adb: &Adb, cfg: &DaemonConfig, strict_source: bool) -> Result<()> {
    if let Some(native_strict_source) = native_init_sync_decision_from_env() {
        let strict_source = strict_source && native_strict_source;
        config_sync::sync_native_lib_if_needed(adb, cfg, strict_source).await?;
    }
    Ok(())
}

fn native_init_sync_decision_from_env() -> Option<bool> {
    if config_sync::native_sync_enabled_from_env() {
        Some(config_sync::native_required_from_env())
    } else {
        None
    }
}

#[cfg(test)]
fn native_init_sync_decision_from_env_value(raw: Option<&str>) -> Option<bool> {
    if config_sync::native_sync_enabled_env_value(raw) {
        Some(config_sync::native_required_env_value(raw))
    } else {
        None
    }
}

async fn prepare_device(adb: &Adb, cfg: &DaemonConfig) -> Result<()> {
    connect_adb(adb).await?;
    wait_for_boot(adb, cfg.init.boot_timeout_secs).await?;
    maybe_disable_phantom_killer(adb, cfg).await;
    Ok(())
}

const fn runtime_action_for(mode: InitMode, iris_running: bool) -> RuntimeAction {
    match (mode, iris_running) {
        (InitMode::ForceRestart, _) => RuntimeAction::Restart,
        (InitMode::IfMissing, true) => RuntimeAction::LeaveRunning,
        (InitMode::IfMissing, false) => RuntimeAction::Start,
    }
}

const fn strict_source_for(mode: InitMode, iris_running: bool) -> bool {
    match mode {
        InitMode::ForceRestart => true,
        InitMode::IfMissing => !iris_running,
    }
}

async fn refresh_iris_running_for_action(
    adb: &Adb,
    mode: InitMode,
    iris_running_before_sync: bool,
) -> bool {
    match mode {
        InitMode::ForceRestart => iris_running_before_sync,
        InitMode::IfMissing => process::iris_pid(adb).await.is_some(),
    }
}

async fn ensure_runtime_ready(adb: &Adb, cfg: &DaemonConfig, action: RuntimeAction) -> Result<()> {
    warn_if_kakaotalk_not_running(adb).await;
    match action {
        RuntimeAction::Restart => restart_iris_process(adb, cfg).await,
        RuntimeAction::Start => start_iris_process(adb, cfg).await,
        RuntimeAction::LeaveRunning => {
            tracing::info!(
                "Iris 프로세스 실행 중 — if-missing init은 재시작 없이 준비 단계만 수행"
            );
            Ok(())
        }
    }
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

async fn start_iris_process(adb: &Adb, cfg: &DaemonConfig) -> Result<()> {
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

    #[test]
    fn native_init_sync_decision_respects_mode_without_adb() {
        let cases = [
            (None, None),
            (Some(""), None),
            (Some("   "), None),
            (Some("off"), None),
            (Some("enabled"), None),
            (Some("shadow"), Some(false)),
            (Some(" SHADOW "), Some(false)),
            (Some("on"), Some(true)),
            (Some(" ON "), Some(true)),
        ];

        for (raw, expected) in cases {
            assert_eq!(native_init_sync_decision_from_env_value(raw), expected);
        }
    }

    #[test]
    fn init_mode_force_restart_preserves_restart_and_strict_source_behavior() {
        assert_eq!(
            runtime_action_for(InitMode::ForceRestart, true),
            RuntimeAction::Restart
        );
        assert_eq!(
            runtime_action_for(InitMode::ForceRestart, false),
            RuntimeAction::Restart
        );
        assert!(strict_source_for(InitMode::ForceRestart, true));
        assert!(strict_source_for(InitMode::ForceRestart, false));
    }

    #[test]
    fn init_mode_if_missing_leaves_running_process_alive_but_starts_when_absent() {
        assert_eq!(
            runtime_action_for(InitMode::IfMissing, true),
            RuntimeAction::LeaveRunning
        );
        assert_eq!(
            runtime_action_for(InitMode::IfMissing, false),
            RuntimeAction::Start
        );
        assert!(!strict_source_for(InitMode::IfMissing, true));
        assert!(strict_source_for(InitMode::IfMissing, false));
    }
}
