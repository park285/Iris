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
            _ = poll_tick.tick() => {
                refresh_app_data(&iris, &mut app).await;
            }
        }
    }

    ratatui::restore();
    Ok(())
}
