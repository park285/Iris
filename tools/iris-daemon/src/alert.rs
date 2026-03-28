use crate::config::DaemonConfig;
use crate::state::Transition;
use serde::Serialize;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Serialize, Debug)]
struct AlertPayload {
    event: String,
    from_state: String,
    to_state: String,
    timestamp: String,
    details: String,
}

pub async fn send_transition_alert(cfg: &DaemonConfig, transition: &Transition) {
    if !cfg.alert.enabled || cfg.alert.webhook_url.is_empty() {
        return;
    }

    let payload = AlertPayload {
        event: "iris_recovery".to_string(),
        from_state: transition.from.to_string(),
        to_state: transition.to.to_string(),
        timestamp: current_timestamp_iso(),
        details: transition.reason.clone(),
    };

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(5))
        .build();

    let client = match client {
        Ok(c) => c,
        Err(e) => {
            tracing::warn!(error = %e, "알림 HTTP 클라이언트 생성 실패");
            return;
        }
    };

    match client
        .post(&cfg.alert.webhook_url)
        .json(&payload)
        .send()
        .await
    {
        Ok(response) => {
            if response.status().is_success() {
                tracing::info!(url = %cfg.alert.webhook_url, from = %transition.from, to = %transition.to, "알림 전송 성공");
            } else {
                tracing::warn!(url = %cfg.alert.webhook_url, status = %response.status(), "알림 전송 실패 (HTTP 에러)");
            }
        }
        Err(e) => {
            tracing::warn!(url = %cfg.alert.webhook_url, error = %e, "알림 전송 실패 (네트워크 에러)");
        }
    }
}

fn current_timestamp_iso() -> String {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let secs_per_day = 86400u64;
    let days_since_epoch = now / secs_per_day;
    let time_of_day = now % secs_per_day;
    let hours = time_of_day / 3600;
    let minutes = (time_of_day % 3600) / 60;
    let seconds = time_of_day % 60;

    let mut year = 1970i32;
    let mut remaining_days = days_since_epoch as i64;
    loop {
        let days_in_year = if is_leap_year(year) { 366 } else { 365 };
        if remaining_days < days_in_year {
            break;
        }
        remaining_days -= days_in_year;
        year += 1;
    }
    let month_days = if is_leap_year(year) {
        [31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    } else {
        [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    };
    let mut month = 1u32;
    for &days in &month_days {
        if remaining_days < days {
            break;
        }
        remaining_days -= days;
        month += 1;
    }
    let day = remaining_days + 1;
    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}Z",
        year, month, day, hours, minutes, seconds
    )
}

fn is_leap_year(year: i32) -> bool {
    (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::state::State;

    #[test]
    fn alert_payload_serializes_correctly() {
        let payload = AlertPayload {
            event: "iris_recovery".to_string(),
            from_state: "Degraded".to_string(),
            to_state: "Recovering".to_string(),
            timestamp: "2026-03-28T18:10:03Z".to_string(),
            details: "health check 2회 연속 실패".to_string(),
        };
        let json = serde_json::to_string(&payload).unwrap();
        assert!(json.contains("\"event\":\"iris_recovery\""));
        assert!(json.contains("\"from_state\":\"Degraded\""));
        assert!(json.contains("\"to_state\":\"Recovering\""));
    }

    #[test]
    fn current_timestamp_iso_has_valid_format() {
        let ts = current_timestamp_iso();
        assert_eq!(ts.len(), 20);
        assert!(ts.ends_with('Z'));
        assert_eq!(&ts[4..5], "-");
        assert_eq!(&ts[7..8], "-");
        assert_eq!(&ts[10..11], "T");
    }

    #[test]
    fn is_leap_year_correct() {
        assert!(is_leap_year(2000));
        assert!(is_leap_year(2024));
        assert!(!is_leap_year(1900));
        assert!(!is_leap_year(2023));
    }

    #[tokio::test]
    async fn send_alert_with_disabled_config_does_nothing() {
        let cfg = DaemonConfig {
            alert: crate::config::AlertConfig {
                enabled: false,
                webhook_url: "http://example.com/webhook".to_string(),
            },
            ..DaemonConfig::default()
        };
        let transition = Transition {
            from: State::Degraded,
            to: State::Recovering,
            reason: "test".to_string(),
        };
        send_transition_alert(&cfg, &transition).await;
    }

    #[tokio::test]
    async fn send_alert_with_empty_url_does_nothing() {
        let cfg = DaemonConfig {
            alert: crate::config::AlertConfig {
                enabled: true,
                webhook_url: String::new(),
            },
            ..DaemonConfig::default()
        };
        let transition = Transition {
            from: State::Healthy,
            to: State::Degraded,
            reason: "test".to_string(),
        };
        send_transition_alert(&cfg, &transition).await;
    }
}
