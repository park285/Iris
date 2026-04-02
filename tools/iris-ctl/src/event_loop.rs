use crate::api;
use crate::app;
use crate::refresh::{apply_pending_event_history_load, apply_pending_thread_fetch};
use crate::reply_encode::send_reply_async;
use crossterm::event::Event;
use iris_common::models::SseEvent;
use std::sync::Arc;
use std::sync::atomic::AtomicI64;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::time::MissedTickBehavior;

pub(crate) async fn run_sse_loop(
    sse_api: api::TuiApi,
    sse_tx: mpsc::UnboundedSender<SseEvent>,
    sse_last_id: Arc<AtomicI64>,
) {
    loop {
        if let Err(error) =
            crate::sse::subscribe(&sse_api, sse_tx.clone(), sse_last_id.clone()).await
        {
            eprintln!("SSE error: {error}, reconnecting in 5s...");
            tokio::time::sleep(Duration::from_secs(5)).await;
        } else {
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    }
}

pub(crate) fn init_poll_tick(poll_interval: Duration) -> tokio::time::Interval {
    let mut poll_tick = tokio::time::interval(poll_interval);
    poll_tick.set_missed_tick_behavior(MissedTickBehavior::Skip);
    poll_tick
}

pub(crate) fn handle_terminal_stream_event(
    app: &mut app::App,
    terminal_event: Option<std::io::Result<Event>>,
) -> bool {
    match terminal_event {
        Some(Ok(event)) => app.handle_app_event(app::AppEvent::Terminal(event)),
        Some(Err(err)) => {
            app.status = format!("Input error: {err}");
            false
        }
        None => {
            app.status = "Input stream closed".to_string();
            true
        }
    }
}

pub(crate) fn handle_sse_event(app: &mut app::App, sse_closed: &mut bool, sse: Option<SseEvent>) {
    if let Some(event) = sse {
        app.handle_app_event(app::AppEvent::Server(event));
    } else {
        *sse_closed = true;
        app.status = "Server event stream closed".to_string();
    }
}

pub(crate) fn spawn_pending_reply(
    iris: &api::TuiApi,
    pending_reply: Option<iris_common::models::ReplyRequest>,
    reply_tx: &mpsc::UnboundedSender<app::ReplyResult>,
) {
    if let Some(req) = pending_reply {
        let api = iris.clone();
        let tx = reply_tx.clone();
        tokio::spawn(async move {
            let result = send_reply_async(&api, req).await;
            let _ = tx.send(result);
        });
    }
}

pub(crate) async fn handle_pending_actions(
    iris: &api::TuiApi,
    app: &mut app::App,
    reply_tx: &mpsc::UnboundedSender<app::ReplyResult>,
) {
    spawn_pending_reply(iris, app.pending_reply.take(), reply_tx);
    apply_pending_thread_fetch(iris, app).await;
    apply_pending_event_history_load(iris, app).await;
}
