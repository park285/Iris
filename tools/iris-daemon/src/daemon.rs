use crate::adb::Adb;
use crate::alert;
use crate::config::DaemonConfig;
use crate::config_sync;
use crate::health;
use crate::process;
use crate::rollback;
use crate::state::{State, StateMachine, Transition};
use anyhow::Result;
use std::time::Duration;

pub async fn run_watch(cfg: DaemonConfig) -> Result<()> {
    let api = iris_common::api::IrisApi::with_timeout(
        &cfg,
        Duration::from_secs(cfg.watch.curl_timeout_secs),
    )?;
    let adb = Adb::new(&cfg.adb.device);
    let mut sm = StateMachine::new(
        cfg.watch.health_fail_threshold,
        cfg.rollback.max_consecutive_failures,
    );
    let mut interval = tokio::time::interval(Duration::from_secs(cfg.watch.check_interval_secs));
    let mut cycle_count: u32 = 0;

    let _ = sd_notify::notify(&[sd_notify::NotifyState::Ready]);
    tracing::info!(
        check_interval = cfg.watch.check_interval_secs,
        health_fail_threshold = cfg.watch.health_fail_threshold,
        "watch 모드 시작"
    );

    loop {
        tokio::select! {
            _ = interval.tick() => {
                cycle_count = cycle_count.wrapping_add(1);

                let report = health::probe_all(&api).await;
                tracing::debug!(%report, "health probe 완료");

                if !report.is_alive() {
                    tracing::warn!(
                        fail_count = sm.fail_count + 1,
                        state = %sm.state,
                        "liveness 실패"
                    );
                }
                if report.readiness != health::ProbeResult::Ok {
                    tracing::warn!("readiness probe 실패");
                }
                if report.bridge != health::ProbeResult::Ok {
                    tracing::warn!("bridge probe 실패");
                }

                let transition = if report.is_alive() {
                    sm.on_health_ok()
                } else {
                    sm.on_health_fail()
                };

                if let Some(ref t) = transition {
                    handle_transition(t, &cfg, &adb, &mut sm).await;
                }

                if cfg.watch.config_check_every > 0
                    && cycle_count % cfg.watch.config_check_every == 0
                    && sm.state == State::Healthy
                {
                    if let Err(e) = config_sync::check_and_sync(&adb, &cfg).await {
                        tracing::warn!(error = %e, "config drift check 실패");
                    }
                }

                let _ = sd_notify::notify(&[sd_notify::NotifyState::Watchdog]);
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

async fn handle_transition(
    transition: &Transition,
    cfg: &DaemonConfig,
    adb: &Adb,
    sm: &mut StateMachine,
) {
    tracing::info!(
        from = %transition.from,
        to = %transition.to,
        reason = %transition.reason,
        "상태 전이"
    );

    let _ = sd_notify::notify(&[sd_notify::NotifyState::Status(&format!(
        "state={}",
        transition.to
    ))]);

    if cfg.alert.enabled {
        alert::send_transition_alert(cfg, transition).await;
    }

    match transition.to {
        State::Recovering => {
            tracing::info!("recovery 시작: Iris 프로세스 재시작");
            if let Err(e) = process::restart_iris(adb, cfg).await {
                tracing::error!(error = %e, "recovery 실패: 프로세스 재시작 불가");
            }
        }
        State::RollbackNeeded => {
            if cfg.rollback.enabled {
                tracing::warn!("자동 롤백 시작");
                match rollback::perform_rollback(adb, cfg).await {
                    Ok(()) => {
                        let t = sm.on_rollback_done();
                        tracing::info!(from = %t.from, to = %t.to, "롤백 완료, recovery 재시도");
                        if cfg.alert.enabled {
                            alert::send_transition_alert(cfg, &t).await;
                        }
                    }
                    Err(e) => {
                        tracing::error!(error = %e, "자동 롤백 실패");
                    }
                }
            } else {
                tracing::error!("롤백이 필요하지만 rollback.enabled=false — 수동 개입 필요");
            }
        }
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use crate::state::{State, StateMachine};

    #[test]
    fn state_machine_integrates_with_daemon_flow() {
        let mut sm = StateMachine::new(2, 3);
        assert!(sm.on_health_ok().is_some());
        assert_eq!(sm.state, State::Healthy);
        assert!(sm.on_health_fail().is_none());
        let t = sm.on_health_fail().unwrap();
        assert_eq!(t.to, State::Degraded);
        let t = sm.on_health_fail().unwrap();
        assert_eq!(t.to, State::Recovering);
        let t = sm.on_health_ok().unwrap();
        assert_eq!(t.to, State::Healthy);
    }
}
