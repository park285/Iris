use crate::app;
use crate::views;
use anyhow::Error;
use iris_common::models::{MemberActivityResponse, RoomInfoResponse};

pub(crate) fn clear_selected_room_context(app: &mut app::App) {
    app.members_view.chat_id = None;
    app.members_view.set_members(Vec::new());
    app.stats_view.chat_id = None;
    app.stats_view.stats = None;
    app.stats_view.room_info = None;
    app.stats_view.member_activity = None;
    app.stats_view.selected_member_id = None;
    app.messages_view.clear();
    app.status = "Selected room is no longer available".to_string();
}

pub(crate) async fn selected_member_activity(
    iris: &crate::api::TuiApi,
    chat_id: i64,
    selected_member_id: Option<i64>,
    period: &str,
) -> Option<MemberActivityResponse> {
    if let Some(user_id) = selected_member_id {
        iris.member_activity(chat_id, user_id, period).await.ok()
    } else {
        None
    }
}

pub(crate) fn update_stats_status(app: &mut app::App, info: &RoomInfoResponse) {
    let notice_count = info.notices.len();
    let blinded_count = info.blinded_member_ids.len();
    if notice_count > 0 || blinded_count > 0 {
        app.status = format!("Room: {notice_count} notices, {blinded_count} blinded");
    }
}

pub(crate) fn apply_messages_refresh_result(
    app: &mut app::App,
    messages_result: anyhow::Result<Vec<views::messages::ChatMessage>>,
) {
    match messages_result {
        Ok(messages) => {
            app.messages_view.set_messages(messages);
            if app.status.starts_with("Failed to load messages:") {
                app.status = "Ready".to_string();
            }
        }
        Err(error) => {
            app.status = format!("Failed to load messages: {error}");
        }
    }
}

pub(crate) fn handle_event_history_load_error(app: &mut app::App, error: &Error) {
    let message = error.to_string();
    if message.contains("404 Not Found") {
        let chat_id = crate::refresh::event_history_target_chat_id(&app.rooms_view);
        app.events_view.mark_history_unavailable(chat_id);
        app.status = "Event history unavailable on this server".to_string();
    } else if message.contains("No room available for event history") {
        app.status = "Select a room first to load event history".to_string();
    } else {
        app.status = format!("Failed to load event history: {error}");
    }
}
