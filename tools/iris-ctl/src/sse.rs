use crate::api::TuiApi;
use anyhow::Result;
use futures_util::StreamExt;
use iris_common::models::SseEvent;
use std::sync::Arc;
use std::sync::atomic::{AtomicI64, Ordering};
use tokio::sync::mpsc;

pub async fn subscribe(
    api: &TuiApi,
    tx: mpsc::UnboundedSender<SseEvent>,
    last_id: Arc<AtomicI64>,
) -> Result<()> {
    let mut req = api.sse_request()?.header("Accept", "text/event-stream");

    let id = last_id.load(Ordering::Relaxed);
    if id > 0 {
        req = req.header("Last-Event-ID", id.to_string());
    }

    let response = req.send().await?.error_for_status()?;
    let mut bytes = response.bytes_stream();
    let mut buffer = String::new();

    while let Some(chunk) = bytes.next().await {
        let chunk = chunk?;
        buffer.push_str(&String::from_utf8_lossy(&chunk));

        while let Some(pos) = buffer.find("\n\n") {
            let message = buffer[..pos].to_string();
            buffer = buffer[pos + 2..].to_string();

            if let Some(event) = parse_message(&message, &last_id) {
                let _ = tx.send(event);
            }
        }
    }
    Ok(())
}

fn parse_message(message: &str, last_id: &Arc<AtomicI64>) -> Option<SseEvent> {
    let mut event_id: Option<i64> = None;
    let mut data_line: Option<&str> = None;
    for line in message.lines() {
        if let Some(id_str) = line.strip_prefix("id: ") {
            event_id = id_str.trim().parse().ok();
        }
        if let Some(d) = line.strip_prefix("data: ") {
            data_line = Some(d);
        }
    }

    if let Some(id) = event_id {
        last_id.store(id, Ordering::Relaxed);
    }

    data_line.and_then(|data| serde_json::from_str::<SseEvent>(data).ok())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
    use tokio::sync::mpsc;

    use crate::config::{Config, ServerConfig, UiConfig};

    #[test]
    fn parse_message_updates_last_event_id_and_decodes_event() {
        let last_id = Arc::new(AtomicI64::new(0));
        let message =
            "id: 15\ndata: {\"type\":\"member_event\",\"event\":\"join\",\"timestamp\":1}";

        let event = parse_message(message, &last_id).expect("event should parse");

        assert_eq!(last_id.load(Ordering::Relaxed), 15);
        assert_eq!(event.event_type, "member_event");
        assert_eq!(event.event.as_deref(), Some("join"));
    }

    fn test_api(base_url: String) -> TuiApi {
        TuiApi::new(&Config {
            server: ServerConfig {
                url: base_url,
                token: "token".to_string(),
            },
            ui: UiConfig {
                poll_interval_secs: 1,
            },
        })
        .expect("test config should build")
    }

    #[tokio::test]
    #[cfg_attr(miri, ignore)]
    async fn subscribe_returns_error_for_non_success_status() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("listener should bind");
        let addr = listener.local_addr().expect("addr should resolve");
        std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("request should arrive");
            let mut buffer = [0_u8; 1024];
            let _ = stream.read(&mut buffer);
            let response = "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n";
            stream
                .write_all(response.as_bytes())
                .expect("response should write");
        });

        let (tx, _rx) = mpsc::unbounded_channel();
        let last_id = Arc::new(AtomicI64::new(0));

        let result = subscribe(&test_api(format!("http://{addr}")), tx, last_id).await;

        assert!(result.is_err());
    }

    #[tokio::test]
    #[cfg_attr(miri, ignore)]
    async fn subscribe_sends_last_event_id_and_replays_event() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("listener should bind");
        let addr = listener.local_addr().expect("addr should resolve");
        let saw_last_event_id = Arc::new(AtomicBool::new(false));
        let saw_last_event_id_server = saw_last_event_id.clone();
        std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("request should arrive");
            let mut buffer = [0_u8; 2048];
            let read = stream.read(&mut buffer).expect("request should read");
            let request = String::from_utf8_lossy(&buffer[..read]);
            saw_last_event_id_server.store(
                request.to_lowercase().contains("last-event-id: 22"),
                Ordering::Relaxed,
            );
            let body =
                "id: 23\ndata: {\"type\":\"member_event\",\"event\":\"join\",\"timestamp\":1}\n\n";
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: {}\r\n\r\n{}",
                body.len(),
                body,
            );
            stream
                .write_all(response.as_bytes())
                .expect("response should write");
        });

        let (tx, mut rx) = mpsc::unbounded_channel();
        let last_id = Arc::new(AtomicI64::new(22));

        subscribe(&test_api(format!("http://{addr}")), tx, last_id.clone())
            .await
            .expect("subscribe should succeed");

        let event = rx.try_recv().expect("event should be replayed");
        assert_eq!(event.event_type, "member_event");
        assert_eq!(event.event.as_deref(), Some("join"));
        assert_eq!(last_id.load(Ordering::Relaxed), 23);
        assert!(saw_last_event_id.load(Ordering::Relaxed));
    }
}
