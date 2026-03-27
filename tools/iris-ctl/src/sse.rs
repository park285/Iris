use anyhow::Result;
use tokio::sync::mpsc;
use crate::api::IrisApi;
use crate::models::SseEvent;

pub async fn subscribe(api: &IrisApi, tx: mpsc::UnboundedSender<SseEvent>) -> Result<()> {
    let response = api.client().get(api.sse_url())
        .header("Accept", "text/event-stream").send().await?;
    let mut bytes = response.bytes_stream();
    let mut buffer = String::new();
    use futures_util::StreamExt;
    while let Some(chunk) = bytes.next().await {
        let chunk = chunk?;
        buffer.push_str(&String::from_utf8_lossy(&chunk));
        while let Some(pos) = buffer.find("\n\n") {
            let message = buffer[..pos].to_string();
            buffer = buffer[pos + 2..].to_string();
            if let Some(data) = message.strip_prefix("data: ").or_else(|| {
                message.lines().find_map(|l| l.strip_prefix("data: "))
            }) {
                if let Ok(event) = serde_json::from_str::<SseEvent>(data) {
                    let _ = tx.send(event);
                }
            }
        }
    }
    Ok(())
}
