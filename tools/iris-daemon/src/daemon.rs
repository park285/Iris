use crate::adb::Adb;
use crate::alert;
use crate::config::DaemonConfig;
use crate::config_sync;
use crate::health;
use crate::process;
use crate::rollback;
use crate::state::{FailureKind, State, StateMachine, Transition};
use anyhow::Result;
use std::time::Duration;

struct WatchContext<'a> {
    cfg: &'a DaemonConfig,
    api: &'a iris_common::api::IrisApi,
    adb: &'a Adb,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ProbeState {
    Healthy,
    LivenessFailed,
    ReadinessFailed,
}

pub async fn run_watch(cfg: DaemonConfig) -> Result<()> {
    let api = iris_common::api::IrisApi::with_timeout(
        &cfg,
        Duration::from_secs(cfg.watch.curl_timeout_secs),
    )?;
    let adb = Adb::new(&cfg.adb.device);
    let context = WatchContext {
        cfg: &cfg,
        api: &api,
        adb: &adb,
    };
    let mut sm = StateMachine::new(
        cfg.watch.health_fail_threshold,
        cfg.watch.readiness_fail_threshold,
        cfg.rollback.max_consecutive_failures,
    );
    let mut interval = tokio::time::interval(Duration::from_secs(cfg.watch.check_interval_secs));
    let mut cycle_count: u32 = 0;

    notify_ready(&cfg);

    loop {
        tokio::select! {
            _ = interval.tick() => {
                cycle_count = cycle_count.wrapping_add(1);
                run_watch_cycle(&context, &mut sm, cycle_count).await;
            }
            _ = tokio::signal::ctrl_c() => {
                tracing::info!("SIGINT 수신, graceful shutdown");
                break;
            }
        }
    }

    tracing::info!("watch 모드 종료");
    Ok(())
}

fn notify_ready(cfg: &DaemonConfig) {
    let _ = sd_notify::notify(&[sd_notify::NotifyState::Ready]);
    tracing::info!(
        check_interval = cfg.watch.check_interval_secs,
        liveness_fail_threshold = cfg.watch.health_fail_threshold,
        readiness_fail_threshold = cfg.watch.readiness_fail_threshold,
        "watch 모드 시작"
    );
}

async fn run_watch_cycle(context: &WatchContext<'_>, sm: &mut StateMachine, cycle_count: u32) {
    let report = health::probe_all(context.api).await;
    tracing::debug!(%report, "health probe 완료");
    log_probe_failures(&report, sm);

    if let Some(transition) = next_transition(sm, &report) {
        handle_transition(&transition, context.cfg, context.adb, sm).await;
    }

    maybe_sync_config(context.cfg, context.adb, sm, cycle_count).await;
    let _ = sd_notify::notify(&[sd_notify::NotifyState::Watchdog]);
}

fn log_probe_failures(report: &health::HealthReport, sm: &StateMachine) {
    match probe_state(report) {
        ProbeState::Healthy => {}
        ProbeState::LivenessFailed => {
            tracing::warn!(
                fail_count = sm.liveness_fail_count + 1,
                state = %sm.state,
                "liveness 실패"
            );
        }
        ProbeState::ReadinessFailed => {
            tracing::warn!(
                fail_count = sm.readiness_fail_count + 1,
                state = %sm.state,
                "readiness probe 실패"
            );
        }
    }
    if let health::ProbeResult::Fail(reason) = &report.bridge {
        tracing::warn!(reason = %reason, "bridge probe 실패");
    }
}

fn next_transition(sm: &mut StateMachine, report: &health::HealthReport) -> Option<Transition> {
    match probe_state(report) {
        ProbeState::Healthy => sm.on_probe_ok(),
        ProbeState::LivenessFailed => sm.on_probe_fail(FailureKind::Liveness),
        ProbeState::ReadinessFailed => sm.on_probe_fail(FailureKind::Readiness),
    }
}

fn probe_state(report: &health::HealthReport) -> ProbeState {
    if !report.is_alive() {
        ProbeState::LivenessFailed
    } else if report.bridge != health::ProbeResult::Ok
        || report.readiness != health::ProbeResult::Ok
    {
        ProbeState::ReadinessFailed
    } else {
        ProbeState::Healthy
    }
}

async fn maybe_sync_config(cfg: &DaemonConfig, adb: &Adb, sm: &StateMachine, cycle_count: u32) {
    let should_sync = cfg.watch.config_check_every > 0
        && cycle_count.is_multiple_of(cfg.watch.config_check_every)
        && sm.state == State::Healthy;
    if should_sync && let Err(error) = config_sync::check_and_sync(adb, cfg).await {
        tracing::warn!(error = %error, "config drift check 실패");
    }
}

async fn handle_transition(
    transition: &Transition,
    cfg: &DaemonConfig,
    adb: &Adb,
    sm: &mut StateMachine,
) {
    log_transition(transition);
    notify_transition_status(transition);
    maybe_send_alert(cfg, transition).await;

    match transition.to {
        State::Recovering => recover_process(adb, cfg).await,
        State::RollbackNeeded => handle_rollback_transition(cfg, adb, sm).await,
        _ => {}
    }
}

fn log_transition(transition: &Transition) {
    tracing::info!(
        from = %transition.from,
        to = %transition.to,
        reason = %transition.reason,
        "상태 전이"
    );
}

fn notify_transition_status(transition: &Transition) {
    let _ = sd_notify::notify(&[sd_notify::NotifyState::Status(&format!(
        "state={}",
        transition.to
    ))]);
}

async fn maybe_send_alert(cfg: &DaemonConfig, transition: &Transition) {
    if cfg.alert.enabled {
        alert::send_transition_alert(cfg, transition).await;
    }
}

async fn recover_process(adb: &Adb, cfg: &DaemonConfig) {
    tracing::info!("recovery 시작: Iris 프로세스 재시작");
    if let Err(error) = process::restart_iris(adb, cfg).await {
        tracing::error!(error = %error, "recovery 실패: 프로세스 재시작 불가");
    }
}

async fn handle_rollback_transition(cfg: &DaemonConfig, adb: &Adb, sm: &mut StateMachine) {
    if cfg.rollback.enabled {
        run_automatic_rollback(cfg, adb, sm).await;
    } else {
        tracing::error!("롤백이 필요하지만 rollback.enabled=false — 수동 개입 필요");
    }
}

async fn run_automatic_rollback(cfg: &DaemonConfig, adb: &Adb, sm: &mut StateMachine) {
    tracing::warn!("자동 롤백 시작");
    match rollback::perform_rollback(adb, cfg).await {
        Ok(()) => handle_rollback_success(cfg, sm).await,
        Err(error) => {
            tracing::error!(error = %error, "자동 롤백 실패");
        }
    }
}

async fn handle_rollback_success(cfg: &DaemonConfig, sm: &mut StateMachine) {
    let transition = sm.on_rollback_done();
    tracing::info!(from = %transition.from, to = %transition.to, "롤백 완료, recovery 재시도");
    maybe_send_alert(cfg, &transition).await;
}

#[cfg(test)]
mod tests {
    use super::next_transition;
    use crate::health::{HealthReport, ProbeResult};
    use crate::state::{FailureKind, State, StateMachine};

    #[test]
    fn state_machine_integrates_with_daemon_flow() {
        let mut sm = StateMachine::new(2, 4, 3);
        assert!(sm.on_probe_ok().is_some());
        assert_eq!(sm.state, State::Healthy);
        assert!(sm.on_probe_fail(FailureKind::Liveness).is_none());
        let t = sm.on_probe_fail(FailureKind::Liveness).unwrap();
        assert_eq!(t.to, State::Degraded);
        let t = sm.on_probe_fail(FailureKind::Liveness).unwrap();
        assert_eq!(t.to, State::Recovering);
        let t = sm.on_probe_ok().unwrap();
        assert_eq!(t.to, State::Healthy);
    }

    #[test]
    fn next_transition_treats_readiness_failure_as_failure_signal() {
        let mut sm = StateMachine::new(2, 2, 3);
        let ready = HealthReport {
            liveness: ProbeResult::Ok,
            readiness: ProbeResult::Ok,
            bridge: ProbeResult::Ok,
        };
        assert!(next_transition(&mut sm, &ready).is_some());
        let degraded = HealthReport {
            liveness: ProbeResult::Ok,
            readiness: ProbeResult::Fail("bridge not ready".to_string()),
            bridge: ProbeResult::Ok,
        };
        assert!(next_transition(&mut sm, &degraded).is_none());
        let t = next_transition(&mut sm, &degraded).expect("should degrade");
        assert_eq!(t.to, State::Degraded);
        assert!(t.reason.contains("readiness"));
    }

    #[test]
    fn bridge_failure_is_treated_as_readiness_failure() {
        let mut sm = StateMachine::new(2, 2, 3);
        let ready = HealthReport {
            liveness: ProbeResult::Ok,
            readiness: ProbeResult::Ok,
            bridge: ProbeResult::Ok,
        };
        assert!(next_transition(&mut sm, &ready).is_some());

        let degraded = HealthReport {
            liveness: ProbeResult::Ok,
            readiness: ProbeResult::Ok,
            bridge: ProbeResult::Fail("bridge not running".to_string()),
        };
        assert!(next_transition(&mut sm, &degraded).is_none());
        let t = next_transition(&mut sm, &degraded).expect("should degrade from bridge failure");
        assert_eq!(t.to, State::Degraded);
        assert!(t.reason.contains("readiness"));
    }
}
