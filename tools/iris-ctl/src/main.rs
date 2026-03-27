mod config;
#[allow(dead_code)]
mod models;
mod api;
mod sse;
mod app;
mod views;

use anyhow::Result;
use std::sync::Arc;
use std::sync::atomic::AtomicI64;
use tokio::sync::mpsc;
use std::time::{Duration, Instant};

#[tokio::main]
async fn main() -> Result<()> {
    let cfg = config::Config::load()?;
    let iris = api::IrisApi::new(&cfg)?;
    let poll_interval = Duration::from_secs(cfg.ui.poll_interval_secs);
    let (sse_tx, sse_rx) = mpsc::unbounded_channel();
    let sse_api = iris.clone();
    let last_event_id = Arc::new(AtomicI64::new(0));
    let sse_last_id = last_event_id.clone();
    tokio::spawn(async move {
        loop {
            if let Err(e) = sse::subscribe(&sse_api, sse_tx.clone(), sse_last_id.clone()).await {
                eprintln!("SSE error: {}, reconnecting in 5s...", e);
                tokio::time::sleep(Duration::from_secs(5)).await;
            }
        }
    });
    let mut app = app::App::new(sse_rx);
    if let Ok(rooms) = iris.rooms().await {
        app.rooms_view.set_rooms(rooms.rooms);
        app.status = format!("{} rooms loaded", app.rooms_view.rooms.len());
    }
    let mut terminal = ratatui::init();
    let mut last_poll = Instant::now();
    loop {
        terminal.draw(|frame| app.render(frame))?;
        if app.handle_event()? { break; }
        if last_poll.elapsed() >= poll_interval {
            last_poll = Instant::now();
            if let Ok(rooms) = iris.rooms().await { app.rooms_view.set_rooms(rooms.rooms); }
            if let Some(chat_id) = app.members_view.chat_id {
                if let Ok(members) = iris.members(chat_id).await { app.members_view.set_members(members.members); }
                if let Ok(stats) = iris.stats(chat_id, &app.stats_view.period, 20).await {
                    app.stats_view.chat_id = Some(chat_id);
                    app.stats_view.set_stats(stats);
                }
                if let Ok(info) = iris.room_info(chat_id).await {
                    let notice_count = info.notices.len();
                    let blinded_count = info.blinded_member_ids.len();
                    app.stats_view.set_room_info(info);
                    if notice_count > 0 || blinded_count > 0 {
                        app.status = format!("Room: {} notices, {} blinded", notice_count, blinded_count);
                    }
                }
            }
        }
    }
    ratatui::restore();
    Ok(())
}
