#![allow(
    clippy::cast_possible_wrap,
    clippy::clone_on_copy,
    clippy::match_same_arms,
    clippy::missing_const_for_fn,
    clippy::needless_pass_by_value,
    clippy::redundant_pub_crate,
    clippy::type_complexity
)]

mod api;
mod app;
mod config;
mod event_loop;
mod query_mapping;
mod refresh;
mod reply_encode;
mod room_context;
mod sse;
mod views;

use anyhow::Result;
use crossterm::event::EventStream;
use event_loop::{
    handle_pending_actions, handle_sse_event, handle_terminal_stream_event, init_poll_tick,
    run_sse_loop,
};
use futures_util::StreamExt;
use iris_common::models::SseEvent;
use refresh::refresh_app_data;
use std::sync::Arc;
use std::sync::atomic::AtomicI64;
use std::time::Duration;
use tokio::sync::mpsc;

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
                if let Some(result) = reply_result
                    && let Some(modal) = &mut app.reply_modal
                {
                    modal.set_result(result);
                }
            }
            _ = poll_tick.tick() => {
                refresh_app_data(&iris, &mut app).await;
            }
        }

        handle_pending_actions(&iris, &mut app, &reply_tx).await;
    }

    ratatui::restore();
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::query_mapping::{
        RECENT_MESSAGES_LIMIT, build_nickname_map, map_recent_messages_response,
    };
    use crate::room_context::{
        apply_messages_refresh_result, clear_selected_room_context, handle_event_history_load_error,
    };
    use iris_common::models::{
        MemberActivityResponse, MemberInfo, PeriodRange, RecentMessage, RecentMessagesResponse,
        RoomInfoResponse, StatsResponse,
    };
    use std::collections::HashMap as StdHashMap;

    #[test]
    fn recent_messages_are_reversed_for_display_order() {
        let response = RecentMessagesResponse {
            chat_id: 1,
            messages: vec![
                RecentMessage {
                    id: 20,
                    chat_id: 1,
                    user_id: 200,
                    message: "new".to_string(),
                    msg_type: 1,
                    created_at: 2000,
                    thread_id: None,
                },
                RecentMessage {
                    id: 10,
                    chat_id: 1,
                    user_id: 100,
                    message: "old".to_string(),
                    msg_type: 1,
                    created_at: 1000,
                    thread_id: None,
                },
            ],
        };

        let messages = map_recent_messages_response(&response);

        assert_eq!(messages[0].id, 10);
        assert_eq!(messages[1].id, 20);
    }

    #[test]
    fn recent_messages_fetch_uses_allowlisted_limit_constant() {
        assert_eq!(RECENT_MESSAGES_LIMIT, 50);
    }

    #[test]
    fn nickname_map_uses_only_members_with_names() {
        let members = vec![
            MemberInfo {
                user_id: 1,
                nickname: Some("alice".to_string()),
                role: "MEMBER".to_string(),
                role_code: 0,
                profile_image_url: None,
                message_count: 0,
                last_active_at: None,
            },
            MemberInfo {
                user_id: 2,
                nickname: None,
                role: "MEMBER".to_string(),
                role_code: 0,
                profile_image_url: None,
                message_count: 0,
                last_active_at: None,
            },
        ];

        let map = build_nickname_map(&members);

        assert_eq!(map.get(&1).map(String::as_str), Some("alice"));
        assert!(!map.contains_key(&2));
    }

    #[test]
    fn successful_message_refresh_clears_stale_message_error() {
        let mut app = app::App::new();
        app.status = "Failed to load messages: old error".to_string();

        apply_messages_refresh_result(
            &mut app,
            Ok(vec![views::messages::ChatMessage {
                id: 1,
                chat_id: 42,
                user_id: 7,
                message: "hello".to_string(),
                msg_type: 1,
                created_at: 100,
                thread_id: None,
            }]),
        );

        assert_eq!(app.status, "Ready");
        assert_eq!(app.messages_view.visible_rows().len(), 1);
    }

    #[test]
    fn successful_message_refresh_preserves_non_error_status() {
        let mut app = app::App::new();
        app.status = "Room: 1 notices, 2 blinded".to_string();

        apply_messages_refresh_result(&mut app, Ok(Vec::new()));

        assert_eq!(app.status, "Room: 1 notices, 2 blinded");
    }

    #[test]
    fn not_found_event_history_error_is_treated_as_unavailable() {
        let mut app = app::App::new();
        app.rooms_view
            .set_rooms(vec![iris_common::models::RoomSummary {
                chat_id: 1,
                room_type: Some("open".to_string()),
                link_id: None,
                active_members_count: Some(3),
                link_name: Some("room-1".to_string()),
                link_url: None,
                member_limit: None,
                searchable: None,
                bot_role: None,
            }]);

        handle_event_history_load_error(
            &mut app,
            &anyhow::anyhow!(
                "HTTP status client error (404 Not Found) for url (http://100.100.1.4:3000/rooms/1/events)"
            ),
        );

        assert_eq!(app.status, "Event history unavailable on this server");
        assert!(!app.events_view.should_auto_load_history_for(Some(1)));
    }

    #[test]
    fn missing_room_event_history_error_is_shown_as_guidance() {
        let mut app = app::App::new();

        handle_event_history_load_error(
            &mut app,
            &anyhow::anyhow!("No room available for event history"),
        );

        assert_eq!(app.status, "Select a room first to load event history");
        assert!(app.events_view.should_auto_load_history_for(Some(1)));
    }

    fn sample_member() -> MemberInfo {
        MemberInfo {
            user_id: 7,
            nickname: Some("alice".to_string()),
            role: "MEMBER".to_string(),
            role_code: 0,
            profile_image_url: None,
            message_count: 3,
            last_active_at: Some(100),
        }
    }

    fn seed_selected_room_context(app: &mut app::App) {
        app.members_view.chat_id = Some(42);
        app.members_view.set_members(vec![sample_member()]);
        app.stats_view.chat_id = Some(42);
        app.stats_view.stats = Some(StatsResponse {
            chat_id: 42,
            period: PeriodRange { from: 1, to: 2 },
            total_messages: 10,
            active_members: 2,
            top_members: Vec::new(),
        });
        app.stats_view.room_info = Some(RoomInfoResponse {
            chat_id: 42,
            room_type: Some("OM".to_string()),
            link_id: None,
            notices: Vec::new(),
            blinded_member_ids: Vec::new(),
            bot_commands: Vec::new(),
            open_link: None,
        });
        app.stats_view.member_activity = Some(MemberActivityResponse {
            user_id: 7,
            nickname: Some("alice".to_string()),
            message_count: 3,
            first_message_at: Some(1),
            last_message_at: Some(2),
            active_hours: Vec::new(),
            message_types: StdHashMap::new(),
        });
        app.stats_view.selected_member_id = Some(7);
        app.messages_view.set_chat_id(42);
        app.messages_view
            .set_messages(vec![views::messages::ChatMessage {
                id: 1,
                chat_id: 42,
                user_id: 7,
                message: "hello".to_string(),
                msg_type: 1,
                created_at: 100,
                thread_id: None,
            }]);
    }

    fn assert_members_context_cleared(app: &app::App) {
        assert_eq!(app.members_view.chat_id, None);
        assert!(app.members_view.members.is_empty());
    }

    fn assert_stats_context_cleared(app: &app::App) {
        assert_eq!(app.stats_view.chat_id, None);
        assert!(app.stats_view.stats.is_none());
        assert!(app.stats_view.room_info.is_none());
        assert!(app.stats_view.member_activity.is_none());
        assert_eq!(app.stats_view.selected_member_id, None);
    }

    fn assert_messages_context_cleared(app: &app::App) {
        assert_eq!(app.messages_view.chat_id, None);
        assert_eq!(app.status, "Selected room is no longer available");
    }

    #[test]
    fn clear_selected_room_context_removes_stale_stats_and_messages() {
        let mut app = app::App::new();
        seed_selected_room_context(&mut app);

        clear_selected_room_context(&mut app);

        assert_members_context_cleared(&app);
        assert_stats_context_cleared(&app);
        assert_messages_context_cleared(&app);
    }
}
