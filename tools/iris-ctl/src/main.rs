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

async fn refresh_app_data(iris: &api::TuiApi, app: &mut app::App) {
    if let Ok(rooms) = iris.rooms().await {
        app.rooms_view.set_rooms(rooms.rooms);
    }

    if let Some(chat_id) = app.members_view.chat_id {
        let selected_member_id = app.stats_view.selected_member_id;
        let period = app.stats_view.period.clone();
        let members_fut = iris.members(chat_id);
        let stats_fut = iris.stats(chat_id, &period, 20);
        let info_fut = iris.room_info(chat_id);
        let activity_fut = async {
            if let Some(user_id) = selected_member_id {
                iris.member_activity(chat_id, user_id, &period).await.ok()
            } else {
                None
            }
        };

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
            let notice_count = info.notices.len();
            let blinded_count = info.blinded_member_ids.len();
            app.stats_view.set_room_info(info);
            if notice_count > 0 || blinded_count > 0 {
                app.status = format!("Room: {} notices, {} blinded", notice_count, blinded_count);
            }
        }
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
    tokio::spawn(async move {
        loop {
            if let Err(e) = sse::subscribe(&sse_api, sse_tx.clone(), sse_last_id.clone()).await {
                eprintln!("SSE error: {}, reconnecting in 5s...", e);
                tokio::time::sleep(Duration::from_secs(5)).await;
            } else {
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
        }
    });
    let mut sse_rx = sse_rx;
    let mut app = app::App::new();
    refresh_app_data(&iris, &mut app).await;
    app.status = format!("{} rooms loaded", app.rooms_view.rooms.len());
    let mut terminal = ratatui::init();
    let mut terminal_events = EventStream::new();
    let mut poll_tick = tokio::time::interval(poll_interval);
    poll_tick.set_missed_tick_behavior(MissedTickBehavior::Skip);
    poll_tick.tick().await;
    let mut sse_closed = false;

    loop {
        terminal.draw(|frame| app.render(frame))?;
        tokio::select! {
            terminal_event = terminal_events.next() => {
                match terminal_event {
                    Some(Ok(event)) => {
                        if app.handle_app_event(app::AppEvent::Terminal(event)) {
                            break;
                        }
                    }
                    Some(Err(err)) => {
                        app.status = format!("Input error: {err}");
                    }
                    None => {
                        app.status = "Input stream closed".to_string();
                        break;
                    }
                }
            }
            sse = sse_rx.recv(), if !sse_closed => {
                match sse {
                    Some(event) => {
                        app.handle_app_event(app::AppEvent::Server(event));
                    }
                    None => {
                        sse_closed = true;
                        app.status = "Server event stream closed".to_string();
                    }
                }
            }
            _ = poll_tick.tick() => {
                refresh_app_data(&iris, &mut app).await;
            }
        }
    }
    ratatui::restore();
    Ok(())
}
