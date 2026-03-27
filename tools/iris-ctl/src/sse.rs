use anyhow::Result;
use std::sync::Arc;
use std::sync::atomic::{AtomicI64, Ordering};
use tokio::sync::mpsc;
use crate::api::IrisApi;
use crate::models::SseEvent;

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicI64, Ordering};

    #[test]
    fn parse_message_updates_last_event_id_and_decodes_event() {
        let last_id = Arc::new(AtomicI64::new(0));
        let message = "id: 15\ndata: {\"type\":\"member_event\",\"event\":\"join\",\"timestamp\":1}";

        let event = parse_message(message, &last_id).expect("event should parse");

        assert_eq!(last_id.load(Ordering::Relaxed), 15);
        assert_eq!(event.event_type, "member_event");
        assert_eq!(event.event.as_deref(), Some("join"));
    }
}

pub async fn subscribe(api: &IrisApi, tx: mpsc::UnboundedSender<SseEvent>, last_id: Arc<AtomicI64>) -> Result<()> {
    let mut req = api.client().get(api.sse_url())
        .header("Accept", "text/event-stream");

    let id = last_id.load(Ordering::Relaxed);
    if id > 0 {
        req = req.header("Last-Event-ID", id.to_string());
    }

    let response = req.send().await?;
    let mut bytes = response.bytes_stream();
    let mut buffer = String::new();

    use futures_util::StreamExt;
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
