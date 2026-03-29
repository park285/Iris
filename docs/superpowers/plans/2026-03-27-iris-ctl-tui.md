# iris-ctl TUI Implementation Plan (Phase 6)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Iris API를 소비하는 Rust 기반 TUI 관리 도구를 구축한다. 방 목록, 멤버 조회, 활동 통계, 실시간 이벤트 스트림을 터미널에서 조작한다.

**Architecture:** ratatui immediate-mode rendering loop + tokio async runtime. API 호출은 reqwest, SSE 구독은 reqwest streaming response. 각 탭(Rooms/Members/Stats/Events)이 독립된 뷰 모듈이며, App 상태가 중앙에서 뷰 전환을 관리한다.

**Tech Stack:** Rust, ratatui (v0.30+, crossterm backend), reqwest (async, json), tokio, serde/serde_json, toml

**Spec:** `docs/superpowers/specs/2026-03-27-member-management-design.md` — Phase 6 section

**Depends on:** Plan A (Phase 1-5) — Iris API endpoints must be available

---

## File Structure

```
tools/iris-ctl/
├── Cargo.toml
├── src/
│   ├── main.rs           # entry point, terminal setup, async runtime
│   ├── config.rs          # config.toml + env var loading
│   ├── app.rs             # App state machine, view routing, key handling
│   ├── api.rs             # reqwest HTTP client wrapper (all Iris API calls)
│   ├── sse.rs             # SSE stream consumer
│   ├── models.rs          # API response types (serde deserialize)
│   └── views/
│       ├── mod.rs          # View trait + re-exports
│       ├── rooms.rs        # Rooms tab (Table widget)
│       ├── members.rs      # Members tab (Table + search/filter)
│       ├── stats.rs        # Stats tab (BarChart + summary)
│       └── events.rs       # Events tab (List + live SSE stream)
```

---

## Task 1: Project Scaffold + Config

**Files:**
- Create: `tools/iris-ctl/Cargo.toml`
- Create: `tools/iris-ctl/src/main.rs`
- Create: `tools/iris-ctl/src/config.rs`

- [ ] **Step 1: Create Cargo.toml**

```toml
[package]
name = "iris-ctl"
version = "0.1.0"
edition = "2021"

[dependencies]
ratatui = "0.30"
crossterm = "0.28"
reqwest = { version = "0.12", features = ["json", "stream"] }
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
toml = "0.8"
dirs = "6"
anyhow = "1"
```

- [ ] **Step 2: Create config.rs**

```rust
use anyhow::{Context, Result};
use serde::Deserialize;
use std::path::PathBuf;

#[derive(Deserialize, Clone)]
pub struct Config {
    pub server: ServerConfig,
    #[serde(default)]
    pub ui: UiConfig,
}

#[derive(Deserialize, Clone)]
pub struct ServerConfig {
    pub url: String,
    #[serde(default)]
    pub token: String,
}

#[derive(Deserialize, Clone)]
pub struct UiConfig {
    #[serde(default = "default_poll_interval")]
    pub poll_interval_secs: u64,
}

impl Default for UiConfig {
    fn default() -> Self {
        Self { poll_interval_secs: 5 }
    }
}

fn default_poll_interval() -> u64 { 5 }

impl Config {
    pub fn load() -> Result<Self> {
        let path = config_path();
        let content = std::fs::read_to_string(&path)
            .with_context(|| format!("Failed to read config: {}", path.display()))?;

        // Check file permissions on unix
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&path)?.permissions().mode() & 0o777;
            if mode & 0o077 != 0 {
                eprintln!("WARNING: {} has permissions {:o}, should be 600", path.display(), mode);
            }
        }

        let mut config: Config = toml::from_str(&content)?;

        // Env var override for token
        if let Ok(token) = std::env::var("IRIS_TOKEN") {
            config.server.token = token;
        }

        Ok(config)
    }

    pub fn token(&self) -> &str {
        &self.server.token
    }

    pub fn base_url(&self) -> &str {
        &self.server.url
    }
}

fn config_path() -> PathBuf {
    if let Some(config_dir) = dirs::config_dir() {
        config_dir.join("iris-ctl").join("config.toml")
    } else {
        PathBuf::from("config.toml")
    }
}
```

- [ ] **Step 3: Create minimal main.rs**

```rust
mod config;

use anyhow::Result;

fn main() -> Result<()> {
    let cfg = config::Config::load()?;
    println!("Connected to: {}", cfg.base_url());
    println!("Token configured: {}", !cfg.token().is_empty());
    Ok(())
}
```

- [ ] **Step 4: Verify build**

Run: `cd /home/kapu/gemini/Iris/tools/iris-ctl && cargo build 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add tools/iris-ctl/
git commit -m "feat(iris-ctl): scaffold Rust project with config loading"
```

---

## Task 2: API Response Models

**Files:**
- Create: `tools/iris-ctl/src/models.rs`

- [ ] **Step 1: Create models matching Iris API responses**

```rust
use serde::Deserialize;

// GET /rooms
#[derive(Deserialize, Debug, Clone)]
pub struct RoomListResponse {
    pub rooms: Vec<RoomSummary>,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RoomSummary {
    pub chat_id: i64,
    #[serde(rename = "type")]
    pub room_type: Option<String>,
    pub link_id: Option<i64>,
    pub active_members_count: Option<i32>,
    pub link_name: Option<String>,
    pub link_url: Option<String>,
    pub member_limit: Option<i32>,
    pub searchable: Option<i32>,
    pub bot_role: Option<i32>,
}

// GET /rooms/{chatId}/members
#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct MemberListResponse {
    pub chat_id: i64,
    pub link_id: Option<i64>,
    pub members: Vec<MemberInfo>,
    pub total_count: i32,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct MemberInfo {
    pub user_id: i64,
    pub nickname: Option<String>,
    pub role: String,
    pub role_code: i32,
    pub profile_image_url: Option<String>,
}

// GET /rooms/{chatId}/info
#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct RoomInfoResponse {
    pub chat_id: i64,
    #[serde(rename = "type")]
    pub room_type: Option<String>,
    pub link_id: Option<i64>,
    pub notices: Vec<NoticeInfo>,
    pub blinded_member_ids: Vec<i64>,
    pub bot_commands: Vec<BotCommandInfo>,
    pub open_link: Option<OpenLinkInfo>,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct NoticeInfo {
    pub content: String,
    pub author_id: i64,
    pub updated_at: i64,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct BotCommandInfo {
    pub name: String,
    pub bot_id: i64,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct OpenLinkInfo {
    pub name: Option<String>,
    pub url: Option<String>,
    pub member_limit: Option<i32>,
    pub description: Option<String>,
    pub searchable: Option<i32>,
}

// GET /rooms/{chatId}/stats
#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct StatsResponse {
    pub chat_id: i64,
    pub period: PeriodRange,
    pub total_messages: i32,
    pub active_members: i32,
    pub top_members: Vec<MemberStats>,
}

#[derive(Deserialize, Debug, Clone)]
pub struct PeriodRange {
    pub from: i64,
    pub to: i64,
}

#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct MemberStats {
    pub user_id: i64,
    pub nickname: Option<String>,
    pub message_count: i32,
    pub last_active_at: Option<i64>,
    pub message_types: std::collections::HashMap<String, i32>,
}

// GET /rooms/{chatId}/members/{userId}/activity
#[derive(Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct MemberActivityResponse {
    pub user_id: i64,
    pub nickname: Option<String>,
    pub message_count: i32,
    pub first_message_at: Option<i64>,
    pub last_message_at: Option<i64>,
    pub active_hours: Vec<i32>,
    pub message_types: std::collections::HashMap<String, i32>,
}

// SSE events
#[derive(Deserialize, Debug, Clone)]
pub struct SseEvent {
    #[serde(rename = "type")]
    pub event_type: String,
    #[serde(default)]
    pub event: Option<String>,
    #[serde(rename = "chatId", default)]
    pub chat_id: Option<i64>,
    #[serde(rename = "userId", default)]
    pub user_id: Option<i64>,
    #[serde(default)]
    pub nickname: Option<String>,
    #[serde(rename = "oldNickname", default)]
    pub old_nickname: Option<String>,
    #[serde(rename = "newNickname", default)]
    pub new_nickname: Option<String>,
    #[serde(rename = "oldRole", default)]
    pub old_role: Option<String>,
    #[serde(rename = "newRole", default)]
    pub new_role: Option<String>,
    #[serde(default)]
    pub estimated: Option<bool>,
    #[serde(default)]
    pub timestamp: Option<i64>,
}

impl RoomSummary {
    pub fn role_name(&self) -> &str {
        match self.bot_role {
            Some(1) => "owner",
            Some(4) => "admin",
            Some(8) => "bot",
            _ => "member",
        }
    }
}
```

- [ ] **Step 2: Register module in main.rs**

Add `mod models;` to `main.rs`.

- [ ] **Step 3: Verify build**

Run: `cd /home/kapu/gemini/Iris/tools/iris-ctl && cargo build 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add tools/iris-ctl/src/models.rs tools/iris-ctl/src/main.rs
git commit -m "feat(iris-ctl): add API response model types"
```

---

## Task 3: API Client

**Files:**
- Create: `tools/iris-ctl/src/api.rs`

- [ ] **Step 1: Implement API client**

```rust
use anyhow::Result;
use reqwest::{Client, header};
use crate::config::Config;
use crate::models::*;

#[derive(Clone)]
pub struct IrisApi {
    client: Client,
    base_url: String,
}

impl IrisApi {
    pub fn new(config: &Config) -> Result<Self> {
        let mut headers = header::HeaderMap::new();
        headers.insert("X-Bot-Token", header::HeaderValue::from_str(config.token())?);

        let client = Client::builder()
            .default_headers(headers)
            .timeout(std::time::Duration::from_secs(10))
            .build()?;

        Ok(Self {
            client,
            base_url: config.base_url().trim_end_matches('/').to_string(),
        })
    }

    pub async fn rooms(&self) -> Result<RoomListResponse> {
        Ok(self.client.get(format!("{}/rooms", self.base_url))
            .send().await?.json().await?)
    }

    pub async fn members(&self, chat_id: i64) -> Result<MemberListResponse> {
        Ok(self.client.get(format!("{}/rooms/{}/members", self.base_url, chat_id))
            .send().await?.json().await?)
    }

    pub async fn room_info(&self, chat_id: i64) -> Result<RoomInfoResponse> {
        Ok(self.client.get(format!("{}/rooms/{}/info", self.base_url, chat_id))
            .send().await?.json().await?)
    }

    pub async fn stats(&self, chat_id: i64, period: &str, limit: i32) -> Result<StatsResponse> {
        Ok(self.client.get(format!("{}/rooms/{}/stats", self.base_url, chat_id))
            .query(&[("period", period), ("limit", &limit.to_string())])
            .send().await?.json().await?)
    }

    pub async fn member_activity(&self, chat_id: i64, user_id: i64, period: &str) -> Result<MemberActivityResponse> {
        Ok(self.client.get(format!("{}/rooms/{}/members/{}/activity", self.base_url, chat_id, user_id))
            .query(&[("period", period)])
            .send().await?.json().await?)
    }

    pub fn sse_url(&self) -> String {
        format!("{}/events/stream", self.base_url)
    }

    pub fn client(&self) -> &Client {
        &self.client
    }
}
```

- [ ] **Step 2: Register module, verify build**

Add `mod api;` to `main.rs`.

Run: `cd /home/kapu/gemini/Iris/tools/iris-ctl && cargo build 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add tools/iris-ctl/src/api.rs tools/iris-ctl/src/main.rs
git commit -m "feat(iris-ctl): add Iris API client with all endpoints"
```

---

## Task 4: SSE Stream Consumer

**Files:**
- Create: `tools/iris-ctl/src/sse.rs`

- [ ] **Step 1: Implement SSE consumer**

```rust
use anyhow::Result;
use tokio::sync::mpsc;
use crate::api::IrisApi;
use crate::models::SseEvent;

pub async fn subscribe(api: &IrisApi, tx: mpsc::UnboundedSender<SseEvent>) -> Result<()> {
    let response = api.client()
        .get(api.sse_url())
        .header("Accept", "text/event-stream")
        .send()
        .await?;

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
```

- [ ] **Step 2: Add `futures-util` dependency to Cargo.toml**

```toml
futures-util = "0.3"
```

- [ ] **Step 3: Register module, verify build**

Add `mod sse;` to `main.rs`.

Run: `cd /home/kapu/gemini/Iris/tools/iris-ctl && cargo build 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add tools/iris-ctl/
git commit -m "feat(iris-ctl): add SSE stream consumer"
```

---

## Task 5: App State Machine + View Trait

**Files:**
- Create: `tools/iris-ctl/src/app.rs`
- Create: `tools/iris-ctl/src/views/mod.rs`

- [ ] **Step 1: Create View trait**

```rust
// src/views/mod.rs
pub mod rooms;
pub mod members;
pub mod stats;
pub mod events;

use ratatui::Frame;
use crossterm::event::KeyEvent;

pub trait View {
    fn render(&self, frame: &mut Frame, area: ratatui::layout::Rect);
    fn handle_key(&mut self, key: KeyEvent) -> ViewAction;
    fn title(&self) -> &str;
}

pub enum ViewAction {
    None,
    SwitchTo(TabId),
    SelectRoom(i64),   // chatId
    SelectMember(i64, i64), // chatId, userId
    Quit,
    Back,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum TabId {
    Rooms,
    Members,
    Stats,
    Events,
}

impl TabId {
    pub fn all() -> &'static [TabId] {
        &[TabId::Rooms, TabId::Members, TabId::Stats, TabId::Events]
    }

    pub fn label(&self) -> &str {
        match self {
            TabId::Rooms => "Rooms",
            TabId::Members => "Members",
            TabId::Stats => "Stats",
            TabId::Events => "Events",
        }
    }

    pub fn index(&self) -> usize {
        match self {
            TabId::Rooms => 0,
            TabId::Members => 1,
            TabId::Stats => 2,
            TabId::Events => 3,
        }
    }
}
```

- [ ] **Step 2: Create App state machine**

```rust
// src/app.rs
use crossterm::event::{self, Event, KeyCode, KeyEvent, KeyEventKind};
use ratatui::Frame;
use ratatui::layout::{Constraint, Layout, Direction};
use ratatui::style::Stylize;
use ratatui::widgets::{Block, Tabs};
use std::time::Duration;
use tokio::sync::mpsc;

use crate::api::IrisApi;
use crate::models::*;
use crate::views::*;

pub struct App {
    pub api: IrisApi,
    pub active_tab: TabId,
    pub rooms_view: rooms::RoomsView,
    pub members_view: members::MembersView,
    pub stats_view: stats::StatsView,
    pub events_view: events::EventsView,
    pub should_quit: bool,
    pub event_rx: mpsc::UnboundedReceiver<SseEvent>,
    pub status: String,
}

impl App {
    pub fn new(api: IrisApi, event_rx: mpsc::UnboundedReceiver<SseEvent>) -> Self {
        Self {
            api,
            active_tab: TabId::Rooms,
            rooms_view: rooms::RoomsView::new(),
            members_view: members::MembersView::new(),
            stats_view: stats::StatsView::new(),
            events_view: events::EventsView::new(),
            should_quit: false,
            event_rx,
            status: "Ready".to_string(),
        }
    }

    pub fn render(&self, frame: &mut Frame) {
        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([
                Constraint::Length(3),  // tabs
                Constraint::Min(0),    // content
                Constraint::Length(1), // status
            ])
            .split(frame.area());

        // Tab bar
        let tabs = Tabs::new(TabId::all().iter().map(|t| t.label()))
            .block(Block::bordered().title(" Iris Control "))
            .select(self.active_tab.index())
            .highlight_style(ratatui::style::Style::default().yellow().bold());
        frame.render_widget(tabs, chunks[0]);

        // Active view
        match self.active_tab {
            TabId::Rooms => self.rooms_view.render(frame, chunks[1]),
            TabId::Members => self.members_view.render(frame, chunks[1]),
            TabId::Stats => self.stats_view.render(frame, chunks[1]),
            TabId::Events => self.events_view.render(frame, chunks[1]),
        }

        // Status bar
        let status = ratatui::widgets::Paragraph::new(self.status.as_str())
            .style(ratatui::style::Style::default().dim());
        frame.render_widget(status, chunks[2]);
    }

    pub fn handle_event(&mut self) -> std::io::Result<bool> {
        // Drain SSE events
        while let Ok(sse) = self.event_rx.try_recv() {
            self.events_view.push_event(sse);
        }

        if event::poll(Duration::from_millis(100))? {
            if let Event::Key(key) = event::read()? {
                if key.kind != KeyEventKind::Press { return Ok(false); }
                // Global keys
                match key.code {
                    KeyCode::Char('q') => return Ok(true),
                    KeyCode::Tab => {
                        let tabs = TabId::all();
                        let idx = (self.active_tab.index() + 1) % tabs.len();
                        self.active_tab = tabs[idx];
                        return Ok(false);
                    }
                    KeyCode::BackTab => {
                        let tabs = TabId::all();
                        let idx = if self.active_tab.index() == 0 { tabs.len() - 1 } else { self.active_tab.index() - 1 };
                        self.active_tab = tabs[idx];
                        return Ok(false);
                    }
                    _ => {}
                }
                // Delegate to active view
                let action = match self.active_tab {
                    TabId::Rooms => self.rooms_view.handle_key(key),
                    TabId::Members => self.members_view.handle_key(key),
                    TabId::Stats => self.stats_view.handle_key(key),
                    TabId::Events => self.events_view.handle_key(key),
                };
                match action {
                    ViewAction::Quit => return Ok(true),
                    ViewAction::SelectRoom(chat_id) => {
                        self.members_view.set_chat_id(chat_id);
                        self.active_tab = TabId::Members;
                    }
                    ViewAction::SwitchTo(tab) => self.active_tab = tab,
                    ViewAction::Back => self.active_tab = TabId::Rooms,
                    _ => {}
                }
            }
        }
        Ok(false)
    }
}
```

- [ ] **Step 3: Verify build (will fail on missing view modules — expected, created in next tasks)**

- [ ] **Step 4: Commit**

```bash
git add tools/iris-ctl/src/app.rs tools/iris-ctl/src/views/mod.rs
git commit -m "feat(iris-ctl): add App state machine and View trait"
```

---

## Task 6: View Implementations (Rooms, Members, Stats, Events)

**Files:**
- Create: `tools/iris-ctl/src/views/rooms.rs`
- Create: `tools/iris-ctl/src/views/members.rs`
- Create: `tools/iris-ctl/src/views/stats.rs`
- Create: `tools/iris-ctl/src/views/events.rs`

Due to the size of 4 view files, each is implemented as a sub-step. All views follow the same pattern: hold data state + `TableState`/`ListState`, implement `View` trait.

- [ ] **Step 1: Implement RoomsView**

```rust
// src/views/rooms.rs
use crossterm::event::{KeyCode, KeyEvent};
use ratatui::Frame;
use ratatui::layout::Rect;
use ratatui::style::Stylize;
use ratatui::widgets::{Block, Row, Table, TableState, Cell};
use ratatui::layout::Constraint;
use crate::models::RoomSummary;
use super::{View, ViewAction};

pub struct RoomsView {
    pub rooms: Vec<RoomSummary>,
    state: TableState,
}

impl RoomsView {
    pub fn new() -> Self {
        Self { rooms: vec![], state: TableState::default() }
    }

    pub fn set_rooms(&mut self, rooms: Vec<RoomSummary>) {
        self.rooms = rooms;
        if !self.rooms.is_empty() && self.state.selected().is_none() {
            self.state.select(Some(0));
        }
    }

    pub fn selected_chat_id(&self) -> Option<i64> {
        self.state.selected().and_then(|i| self.rooms.get(i)).map(|r| r.chat_id)
    }
}

impl View for RoomsView {
    fn render(&self, frame: &mut Frame, area: Rect) {
        let header = Row::new(["#", "Room", "Type", "Members", "Bot Role", "Link ID"])
            .bold().bottom_margin(1);
        let rows: Vec<Row> = self.rooms.iter().enumerate().map(|(i, r)| {
            Row::new([
                Cell::from(format!("{}", i + 1)),
                Cell::from(r.link_name.as_deref().unwrap_or("?")),
                Cell::from(r.room_type.as_deref().unwrap_or("?")),
                Cell::from(r.active_members_count.map(|c| c.to_string()).unwrap_or_default()),
                Cell::from(r.role_name()),
                Cell::from(r.link_id.map(|l| l.to_string()).unwrap_or_default()),
            ])
        }).collect();

        let table = Table::new(rows, [
            Constraint::Length(3), Constraint::Min(20), Constraint::Length(6),
            Constraint::Length(8), Constraint::Length(10), Constraint::Length(12),
        ])
        .header(header)
        .block(Block::bordered().title(" Rooms "))
        .row_highlight_style(ratatui::style::Style::default().reversed());

        frame.render_stateful_widget(table, area, &mut self.state.clone());
    }

    fn handle_key(&mut self, key: KeyEvent) -> ViewAction {
        match key.code {
            KeyCode::Up | KeyCode::Char('k') => {
                let i = self.state.selected().unwrap_or(0);
                self.state.select(Some(if i == 0 { self.rooms.len().saturating_sub(1) } else { i - 1 }));
                ViewAction::None
            }
            KeyCode::Down | KeyCode::Char('j') => {
                let i = self.state.selected().unwrap_or(0);
                self.state.select(Some(if i >= self.rooms.len().saturating_sub(1) { 0 } else { i + 1 }));
                ViewAction::None
            }
            KeyCode::Enter => {
                if let Some(chat_id) = self.selected_chat_id() {
                    ViewAction::SelectRoom(chat_id)
                } else {
                    ViewAction::None
                }
            }
            _ => ViewAction::None,
        }
    }

    fn title(&self) -> &str { "Rooms" }
}
```

- [ ] **Step 2: Implement MembersView**

```rust
// src/views/members.rs
use crossterm::event::{KeyCode, KeyEvent};
use ratatui::Frame;
use ratatui::layout::Rect;
use ratatui::style::Stylize;
use ratatui::widgets::{Block, Row, Table, TableState, Cell};
use ratatui::layout::Constraint;
use crate::models::MemberInfo;
use super::{View, ViewAction};

pub struct MembersView {
    pub chat_id: Option<i64>,
    pub members: Vec<MemberInfo>,
    state: TableState,
    search: String,
}

impl MembersView {
    pub fn new() -> Self {
        Self { chat_id: None, members: vec![], state: TableState::default(), search: String::new() }
    }

    pub fn set_chat_id(&mut self, chat_id: i64) {
        self.chat_id = Some(chat_id);
        self.members.clear();
        self.state.select(None);
    }

    pub fn set_members(&mut self, members: Vec<MemberInfo>) {
        self.members = members;
        if !self.members.is_empty() && self.state.selected().is_none() {
            self.state.select(Some(0));
        }
    }

    fn filtered(&self) -> Vec<&MemberInfo> {
        if self.search.is_empty() {
            self.members.iter().collect()
        } else {
            let q = self.search.to_lowercase();
            self.members.iter().filter(|m| {
                m.nickname.as_deref().unwrap_or("").to_lowercase().contains(&q)
            }).collect()
        }
    }
}

impl View for MembersView {
    fn render(&self, frame: &mut Frame, area: Rect) {
        let filtered = self.filtered();
        let title = format!(" Members ({}) ", filtered.len());
        let header = Row::new(["#", "Nickname", "Role", "User ID"]).bold().bottom_margin(1);
        let rows: Vec<Row> = filtered.iter().enumerate().map(|(i, m)| {
            let role_display = match m.role_code {
                1 => "★ owner",
                4 => "◆ admin",
                8 => "● bot",
                _ => "  member",
            };
            Row::new([
                Cell::from(format!("{}", i + 1)),
                Cell::from(m.nickname.as_deref().unwrap_or("?")),
                Cell::from(role_display),
                Cell::from(m.user_id.to_string()),
            ])
        }).collect();

        let table = Table::new(rows, [
            Constraint::Length(4), Constraint::Min(20),
            Constraint::Length(12), Constraint::Length(22),
        ])
        .header(header)
        .block(Block::bordered().title(title))
        .row_highlight_style(ratatui::style::Style::default().reversed());

        frame.render_stateful_widget(table, area, &mut self.state.clone());
    }

    fn handle_key(&mut self, key: KeyEvent) -> ViewAction {
        match key.code {
            KeyCode::Up | KeyCode::Char('k') => {
                let len = self.filtered().len();
                let i = self.state.selected().unwrap_or(0);
                self.state.select(Some(if i == 0 { len.saturating_sub(1) } else { i - 1 }));
                ViewAction::None
            }
            KeyCode::Down | KeyCode::Char('j') => {
                let len = self.filtered().len();
                let i = self.state.selected().unwrap_or(0);
                self.state.select(Some(if i >= len.saturating_sub(1) { 0 } else { i + 1 }));
                ViewAction::None
            }
            KeyCode::Esc => ViewAction::Back,
            _ => ViewAction::None,
        }
    }

    fn title(&self) -> &str { "Members" }
}
```

- [ ] **Step 3: Implement StatsView**

```rust
// src/views/stats.rs
use crossterm::event::{KeyCode, KeyEvent};
use ratatui::Frame;
use ratatui::layout::{Constraint, Direction, Layout, Rect};
use ratatui::style::Stylize;
use ratatui::widgets::{BarChart, Block, Paragraph};
use crate::models::StatsResponse;
use super::{View, ViewAction};

pub struct StatsView {
    pub chat_id: Option<i64>,
    pub stats: Option<StatsResponse>,
    pub period: String,
}

impl StatsView {
    pub fn new() -> Self {
        Self { chat_id: None, stats: None, period: "7d".to_string() }
    }

    pub fn set_stats(&mut self, stats: StatsResponse) {
        self.stats = Some(stats);
    }
}

impl View for StatsView {
    fn render(&self, frame: &mut Frame, area: Rect) {
        let block = Block::bordered().title(format!(" Stats ({}) ", self.period));
        let inner = block.inner(area);
        frame.render_widget(block, area);

        if let Some(stats) = &self.stats {
            let chunks = Layout::default()
                .direction(Direction::Vertical)
                .constraints([Constraint::Length(3), Constraint::Min(0)])
                .split(inner);

            let summary = format!(
                "Total: {} msgs | Active: {}/{} members",
                stats.total_messages, stats.active_members, stats.top_members.len()
            );
            frame.render_widget(Paragraph::new(summary).bold(), chunks[0]);

            // Top members as bar chart
            let data: Vec<(&str, u64)> = stats.top_members.iter().take(15).map(|m| {
                (m.nickname.as_deref().unwrap_or("?"), m.message_count as u64)
            }).collect();
            let chart = BarChart::default()
                .data(&data)
                .bar_width(5)
                .bar_gap(1);
            frame.render_widget(chart, chunks[1]);
        } else {
            frame.render_widget(Paragraph::new("No data. Press 'r' to refresh."), inner);
        }
    }

    fn handle_key(&mut self, key: KeyEvent) -> ViewAction {
        match key.code {
            KeyCode::Char('7') => { self.period = "7d".to_string(); ViewAction::None }
            KeyCode::Char('3') => { self.period = "30d".to_string(); ViewAction::None }
            KeyCode::Char('a') => { self.period = "all".to_string(); ViewAction::None }
            KeyCode::Esc => ViewAction::Back,
            _ => ViewAction::None,
        }
    }

    fn title(&self) -> &str { "Stats" }
}
```

- [ ] **Step 4: Implement EventsView**

```rust
// src/views/events.rs
use crossterm::event::{KeyCode, KeyEvent};
use ratatui::Frame;
use ratatui::layout::Rect;
use ratatui::widgets::{Block, List, ListItem, ListState};
use ratatui::style::Stylize;
use crate::models::SseEvent;
use super::{View, ViewAction};

pub struct EventsView {
    events: Vec<String>,
    state: ListState,
    paused: bool,
}

impl EventsView {
    pub fn new() -> Self {
        Self { events: vec![], state: ListState::default(), paused: false }
    }

    pub fn push_event(&mut self, event: SseEvent) {
        if self.paused { return; }
        let ts = event.timestamp.map(|t| {
            let secs = t % 86400;
            format!("{:02}:{:02}:{:02}", secs / 3600, (secs % 3600) / 60, secs % 60)
        }).unwrap_or_default();

        let line = match (event.event_type.as_str(), event.event.as_deref()) {
            ("member_event", Some("join")) =>
                format!("{} JOIN   {} entered", ts, event.nickname.as_deref().unwrap_or("?")),
            ("member_event", Some("leave")) =>
                format!("{} LEAVE  {} left{}", ts, event.nickname.as_deref().unwrap_or("?"),
                    if event.estimated == Some(true) { " (estimated)" } else { "" }),
            ("member_event", Some("kick")) =>
                format!("{} KICK   {} kicked", ts, event.nickname.as_deref().unwrap_or("?")),
            ("nickname_change", _) =>
                format!("{} NICK   {} → {}", ts,
                    event.old_nickname.as_deref().unwrap_or("?"),
                    event.new_nickname.as_deref().unwrap_or("?")),
            ("role_change", _) =>
                format!("{} ROLE   {} → {}", ts,
                    event.old_role.as_deref().unwrap_or("?"),
                    event.new_role.as_deref().unwrap_or("?")),
            ("profile_change", _) =>
                format!("{} PROF   user {} changed profile", ts, event.user_id.unwrap_or(0)),
            _ => format!("{} ???    {:?}", ts, event.event_type),
        };

        self.events.push(line);
        if self.events.len() > 500 { self.events.remove(0); }
        self.state.select(Some(self.events.len().saturating_sub(1)));
    }
}

impl View for EventsView {
    fn render(&self, frame: &mut Frame, area: Rect) {
        let title = if self.paused { " Events (PAUSED) " } else { " Events (LIVE) " };
        let items: Vec<ListItem> = self.events.iter().map(|e| ListItem::new(e.as_str())).collect();
        let list = List::new(items)
            .block(Block::bordered().title(title))
            .highlight_style(ratatui::style::Style::default().reversed());
        frame.render_stateful_widget(list, area, &mut self.state.clone());
    }

    fn handle_key(&mut self, key: KeyEvent) -> ViewAction {
        match key.code {
            KeyCode::Char('p') => { self.paused = !self.paused; ViewAction::None }
            KeyCode::Char('c') => { self.events.clear(); ViewAction::None }
            KeyCode::Up | KeyCode::Char('k') => {
                let i = self.state.selected().unwrap_or(0);
                self.state.select(Some(i.saturating_sub(1)));
                ViewAction::None
            }
            KeyCode::Down | KeyCode::Char('j') => {
                let i = self.state.selected().unwrap_or(0);
                self.state.select(Some((i + 1).min(self.events.len().saturating_sub(1))));
                ViewAction::None
            }
            KeyCode::Esc => ViewAction::Back,
            _ => ViewAction::None,
        }
    }

    fn title(&self) -> &str { "Events" }
}
```

- [ ] **Step 5: Verify build**

Run: `cd /home/kapu/gemini/Iris/tools/iris-ctl && cargo build 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add tools/iris-ctl/src/views/
git commit -m "feat(iris-ctl): implement Rooms, Members, Stats, Events views"
```

---

## Task 7: Main Entry Point + Async Loop

**Files:**
- Modify: `tools/iris-ctl/src/main.rs`

- [ ] **Step 1: Implement full main with terminal setup and data fetching**

```rust
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

    // SSE channel
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

    let mut app = app::App::new(iris.clone(), sse_rx);

    // Initial data fetch
    if let Ok(rooms) = iris.rooms().await {
        app.rooms_view.set_rooms(rooms.rooms);
        app.status = format!("{} rooms loaded", app.rooms_view.rooms.len());
    }

    let mut terminal = ratatui::init();
    let mut last_poll = Instant::now();

    loop {
        terminal.draw(|frame| app.render(frame))?;

        if app.handle_event()? {
            break;
        }

        // Periodic data refresh
        if last_poll.elapsed() >= poll_interval {
            last_poll = Instant::now();
            if let Ok(rooms) = iris.rooms().await {
                app.rooms_view.set_rooms(rooms.rooms);
            }
            if let Some(chat_id) = app.members_view.chat_id {
                if let Ok(members) = iris.members(chat_id).await {
                    app.members_view.set_members(members.members);
                }
            }
        }
    }

    ratatui::restore();
    Ok(())
}
```

- [ ] **Step 2: Verify build and run smoke test**

Run: `cd /home/kapu/gemini/Iris/tools/iris-ctl && cargo build --release 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add tools/iris-ctl/src/main.rs
git commit -m "feat(iris-ctl): wire main loop with terminal, data fetching, and SSE"
```

---

## Task 8: Final Build Verification

- [ ] **Step 1: Full cargo check**

```bash
cd /home/kapu/gemini/Iris/tools/iris-ctl && cargo clippy 2>&1 | tail -20
```

Expected: No errors. Address warnings.

- [ ] **Step 2: Cross-compile check for aarch64 (optional)**

```bash
cd /home/kapu/gemini/Iris/tools/iris-ctl && cross build --release --target aarch64-unknown-linux-gnu 2>&1 | tail -5
```

If `cross` is not installed, skip — native build is sufficient for development.

- [ ] **Step 3: Final commit**

```bash
git add tools/iris-ctl/
git commit -m "chore(iris-ctl): address clippy warnings and finalize build"
```
