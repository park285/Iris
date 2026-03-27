mod config;
mod models;
mod api;
mod sse;
mod app;
mod views;

use anyhow::Result;
use tokio::sync::mpsc;
use std::time::{Duration, Instant};

#[tokio::main]
async fn main() -> Result<()> {
    let cfg = config::Config::load()?;
    let iris = api::IrisApi::new(&cfg)?;
    let poll_interval = Duration::from_secs(cfg.ui.poll_interval_secs);
    let (sse_tx, sse_rx) = mpsc::unbounded_channel();
    let sse_api = iris.clone();
    tokio::spawn(async move {
        loop {
            if let Err(e) = sse::subscribe(&sse_api, sse_tx.clone()).await {
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
            }
        }
    }
    ratatui::restore();
    Ok(())
}
