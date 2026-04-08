use iris_common::api::IrisApi;
use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProbeResult {
    Ok,
    Fail(String),
}

#[derive(Debug, Clone)]
pub struct HealthReport {
    pub liveness: ProbeResult,
    pub readiness: ProbeResult,
    pub bridge: ProbeResult,
}

impl HealthReport {
    pub fn is_alive(&self) -> bool {
        self.liveness == ProbeResult::Ok
    }
}

impl fmt::Display for HealthReport {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "liveness: {}, readiness: {}, bridge: {}",
            probe_display(&self.liveness),
            probe_display(&self.readiness),
            probe_display(&self.bridge),
        )
    }
}

const fn probe_display(result: &ProbeResult) -> &str {
    match result {
        ProbeResult::Ok => "ok",
        ProbeResult::Fail(_) => "fail",
    }
}

pub async fn probe_all(api: &IrisApi) -> HealthReport {
    let (liveness, readiness, bridge) =
        tokio::join!(probe_liveness(api), probe_readiness(api), probe_bridge(api));
    HealthReport {
        liveness,
        readiness,
        bridge,
    }
}

async fn probe_liveness(api: &IrisApi) -> ProbeResult {
    match api.health().await {
        Ok(_) => ProbeResult::Ok,
        Err(e) => ProbeResult::Fail(e.to_string()),
    }
}

async fn probe_readiness(api: &IrisApi) -> ProbeResult {
    match api.ready().await {
        Ok(_) => ProbeResult::Ok,
        Err(e) => ProbeResult::Fail(e.to_string()),
    }
}

async fn probe_bridge(api: &IrisApi) -> ProbeResult {
    match api.bridge_diagnostics().await {
        Ok(response) => match bridge_capability_failure(&response) {
            Some(reason) => ProbeResult::Fail(reason),
            None => ProbeResult::Ok,
        },
        Err(e) => ProbeResult::Fail(e.to_string()),
    }
}

fn bridge_capability_failure(
    response: &iris_common::models::BridgeDiagnosticsResponse,
) -> Option<String> {
    let capability = &response.capabilities.snapshot_chat_room_members;
    if capability.ready {
        return None;
    }
    Some(
        capability
            .reason
            .clone()
            .unwrap_or_else(|| "snapshot chatroom members capability not ready".to_string()),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use iris_common::models::{
        BridgeDiagnosticsCapabilities, BridgeDiagnosticsCapability, BridgeDiagnosticsResponse,
    };

    #[test]
    fn health_report_is_alive_when_liveness_ok() {
        let report = HealthReport {
            liveness: ProbeResult::Ok,
            readiness: ProbeResult::Fail("not ready".to_string()),
            bridge: ProbeResult::Fail("no bridge".to_string()),
        };
        assert!(report.is_alive());
    }

    #[test]
    fn health_report_not_alive_when_liveness_fails() {
        let report = HealthReport {
            liveness: ProbeResult::Fail("connection refused".to_string()),
            readiness: ProbeResult::Ok,
            bridge: ProbeResult::Ok,
        };
        assert!(!report.is_alive());
    }

    #[test]
    fn health_report_display_shows_all_probes() {
        let report = HealthReport {
            liveness: ProbeResult::Ok,
            readiness: ProbeResult::Ok,
            bridge: ProbeResult::Fail("timeout".to_string()),
        };
        let display = report.to_string();
        assert!(display.contains("liveness: ok"));
        assert!(display.contains("readiness: ok"));
        assert!(display.contains("bridge: fail"));
    }

    #[test]
    fn probe_result_equality() {
        assert_eq!(ProbeResult::Ok, ProbeResult::Ok);
        assert_ne!(ProbeResult::Ok, ProbeResult::Fail("x".to_string()));
    }

    #[test]
    fn bridge_capability_failure_uses_snapshot_capability_reason() {
        let response = BridgeDiagnosticsResponse {
            reachable: true,
            running: true,
            spec_ready: true,
            checked_at_epoch_ms: None,
            restart_count: 0,
            last_crash_message: None,
            checks: vec![],
            discovery_install_attempted: true,
            discovery_hooks: vec![],
            capabilities: BridgeDiagnosticsCapabilities {
                inspect_chat_room: BridgeDiagnosticsCapability {
                    supported: true,
                    ready: true,
                    reason: None,
                },
                snapshot_chat_room_members: BridgeDiagnosticsCapability {
                    supported: true,
                    ready: false,
                    reason: Some("chatroom resolver unavailable".to_string()),
                },
            },
            error: None,
        };

        assert_eq!(
            bridge_capability_failure(&response),
            Some("chatroom resolver unavailable".to_string())
        );
    }
}
