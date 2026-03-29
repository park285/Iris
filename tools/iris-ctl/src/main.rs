mod api;
mod app;
mod config;
mod sse;
mod views;

use anyhow::Result;
use crossterm::event::EventStream;
use futures_util::StreamExt;
use iris_common::models::SseEvent;
use std::sync::Arc;
use std::sync::atomic::AtomicI64;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::time::MissedTickBehavior;

const MAX_IMAGE_BYTES: u64 = 35 * 1024 * 1024;

/// 단일 이미지 경로를 base64로 인코딩하거나 크기 초과/읽기 오류를 반환한다.
async fn encode_single_image(
    req: iris_common::models::ReplyRequest,
) -> Result<iris_common::models::ReplyRequest, app::ReplyResult> {
    use base64::Engine;
    let path = req.data.as_str().unwrap_or("").to_owned();
    match tokio::fs::read(&path).await {
        Ok(bytes) => {
            if bytes.len() as u64 > MAX_IMAGE_BYTES {
                #[allow(clippy::cast_precision_loss)]
                let mb = bytes.len() as f64 / 1_048_576.0;
                return Err(app::ReplyResult::Error {
                    message: format!("파일이 너무 큽니다 ({mb:.1} MB, 상한 35 MB)"),
                });
            }
            let encoded = base64::engine::general_purpose::STANDARD.encode(&bytes);
            Ok(iris_common::models::ReplyRequest {
                data: serde_json::Value::String(encoded),
                ..req
            })
        }
        Err(e) => Err(app::ReplyResult::Error {
            message: format!("파일 읽기 실패: {e}"),
        }),
    }
}

/// 다중 이미지 경로 목록을 base64 배열로 인코딩하거나 크기 초과/읽기 오류를 반환한다.
async fn encode_multiple_images(
    req: iris_common::models::ReplyRequest,
) -> Result<iris_common::models::ReplyRequest, app::ReplyResult> {
    use base64::Engine;
    let paths: Vec<String> = req
        .data
        .as_array()
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v.as_str())
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_default();
    let mut encoded_list = Vec::new();
    let mut total_bytes: u64 = 0;
    for path in &paths {
        match tokio::fs::read(path).await {
            Ok(bytes) => {
                total_bytes += bytes.len() as u64;
                if total_bytes > MAX_IMAGE_BYTES {
                    #[allow(clippy::cast_precision_loss)]
                    let mb = total_bytes as f64 / 1_048_576.0;
                    return Err(app::ReplyResult::Error {
                        message: format!("이미지 합산 크기 초과 ({mb:.1} MB, 상한 35 MB)"),
                    });
                }
                encoded_list.push(serde_json::Value::String(
                    base64::engine::general_purpose::STANDARD.encode(&bytes),
                ));
            }
            Err(e) => {
                return Err(app::ReplyResult::Error {
                    message: format!("파일 읽기 실패 ({path}): {e}"),
                });
            }
        }
    }
    Ok(iris_common::models::ReplyRequest {
        data: serde_json::Value::Array(encoded_list),
        ..req
    })
}

async fn send_reply_async(
    iris: &api::TuiApi,
    req: iris_common::models::ReplyRequest,
) -> app::ReplyResult {
    use iris_common::models::ReplyType;

    let final_req = match &req.reply_type {
        ReplyType::Image => match encode_single_image(req).await {
            Ok(r) => r,
            Err(e) => return e,
        },
        ReplyType::ImageMultiple => match encode_multiple_images(req).await {
            Ok(r) => r,
            Err(e) => return e,
        },
        _ => req,
    };

    match iris.send_reply(&final_req).await {
        Ok(resp) => app::ReplyResult::Success {
            request_id: resp.request_id,
        },
        Err(e) => app::ReplyResult::Error {
            message: e.to_string(),
        },
    }
}

async fn refresh_rooms(iris: &api::TuiApi, app: &mut app::App) {
    if let Ok(rooms) = iris.rooms().await {
        app.rooms_view.set_rooms(rooms.rooms);
    }
}

async fn selected_member_activity(
    iris: &api::TuiApi,
    chat_id: i64,
    selected_member_id: Option<i64>,
    period: &str,
) -> Option<iris_common::models::MemberActivityResponse> {
    if let Some(user_id) = selected_member_id {
        iris.member_activity(chat_id, user_id, period).await.ok()
    } else {
        None
    }
}

fn update_stats_status(app: &mut app::App, info: &iris_common::models::RoomInfoResponse) {
    let notice_count = info.notices.len();
    let blinded_count = info.blinded_member_ids.len();
    if notice_count > 0 || blinded_count > 0 {
        app.status = format!("Room: {notice_count} notices, {blinded_count} blinded");
    }
}

async fn refresh_selected_room(iris: &api::TuiApi, app: &mut app::App, chat_id: i64) {
    let selected_member_id = app.stats_view.selected_member_id;
    let period = app.stats_view.period.clone();
    let members_fut = iris.members(chat_id);
    let stats_fut = iris.stats(chat_id, &period, 20);
    let info_fut = iris.room_info(chat_id);
    let activity_fut = selected_member_activity(iris, chat_id, selected_member_id, &period);

    let (members_result, stats_result, info_result, activity_result) =
        tokio::join!(members_fut, stats_fut, info_fut, activity_fut);

    if let Ok(members) = members_result {
        app.members_view.set_members(members.members);
    }
    if let Ok(stats) = stats_result {
        app.stats_view.chat_id = Some(chat_id);
        app.stats_view.set_stats(stats);
    }
    if let Some(activity) = activity_result {
        app.stats_view.set_member_activity(activity);
    }
    if let Ok(info) = info_result {
        update_stats_status(app, &info);
        app.stats_view.set_room_info(info);
    }
}

async fn refresh_app_data(iris: &api::TuiApi, app: &mut app::App) {
    refresh_rooms(iris, app).await;

    if let Some(chat_id) = app.members_view.chat_id {
        refresh_selected_room(iris, app, chat_id).await;
    }
}

async fn run_sse_loop(
    sse_api: api::TuiApi,
    sse_tx: mpsc::UnboundedSender<SseEvent>,
    sse_last_id: Arc<AtomicI64>,
) {
    loop {
        if let Err(error) = sse::subscribe(&sse_api, sse_tx.clone(), sse_last_id.clone()).await {
            eprintln!("SSE error: {error}, reconnecting in 5s...");
            tokio::time::sleep(Duration::from_secs(5)).await;
        } else {
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
    }
}

fn init_poll_tick(poll_interval: Duration) -> tokio::time::Interval {
    let mut poll_tick = tokio::time::interval(poll_interval);
    poll_tick.set_missed_tick_behavior(MissedTickBehavior::Skip);
    poll_tick
}

fn handle_terminal_stream_event(
    app: &mut app::App,
    terminal_event: Option<std::io::Result<crossterm::event::Event>>,
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

fn handle_sse_event(app: &mut app::App, sse_closed: &mut bool, sse: Option<SseEvent>) {
    if let Some(event) = sse {
        app.handle_app_event(app::AppEvent::Server(event));
    } else {
        *sse_closed = true;
        app.status = "Server event stream closed".to_string();
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    let cfg = config::Config::load()?;
    let iris = api::TuiApi::new(&cfg)?;
    let poll_interval = Duration::from_secs(cfg.ui.poll_interval_secs);
    let (sse_tx, sse_rx) = mpsc::unbounded_channel::<SseEvent>();
    let (reply_tx, mut reply_rx) = mpsc::unbounded_channel::<app::ReplyResult>();
    let sse_api = iris.clone();
    let last_event_id = Arc::new(AtomicI64::new(0));
    let sse_last_id = last_event_id.clone();
    tokio::spawn(run_sse_loop(sse_api, sse_tx, sse_last_id));

    let mut sse_rx = sse_rx;
    let mut app = app::App::new();
    refresh_app_data(&iris, &mut app).await;
    app.status = format!("{} rooms loaded", app.rooms_view.rooms.len());

    let mut terminal = ratatui::init();
    let mut terminal_events = EventStream::new();
    let mut poll_tick = init_poll_tick(poll_interval);
    poll_tick.tick().await;
    let mut sse_closed = false;

    loop {
        terminal.draw(|frame| app.render(frame))?;
        tokio::select! {
            terminal_event = terminal_events.next() => {
                if handle_terminal_stream_event(&mut app, terminal_event) {
                    break;
                }
            }
            sse = sse_rx.recv(), if !sse_closed => {
                handle_sse_event(&mut app, &mut sse_closed, sse);
            }
            reply_result = reply_rx.recv() => {
                if let Some(result) = reply_result {
                    if let Some(modal) = &mut app.reply_modal {
                        modal.set_result(result);
                    }
                }
            }
            _ = poll_tick.tick() => {
                refresh_app_data(&iris, &mut app).await;
            }
        }

        // 대기 중인 액션 처리
        if let Some(req) = app.pending_reply.take() {
            let api = iris.clone();
            let tx = reply_tx.clone();
            tokio::spawn(async move {
                let result = send_reply_async(&api, req).await;
                let _ = tx.send(result);
            });
        }

        if let Some(chat_id) = app.pending_thread_fetch.take() {
            if let Ok(thread_list) = iris.list_threads(chat_id).await {
                if let Some(modal) = &mut app.reply_modal {
                    modal.thread_suggestions = thread_list.threads;
                }
            }
        }
    }

    ratatui::restore();
    Ok(())
}
