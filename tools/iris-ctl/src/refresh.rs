use crate::api;
use crate::app;
use crate::query_mapping::{build_nickname_map, fetch_recent_messages};
use crate::room_context::{
    apply_messages_refresh_result, clear_selected_room_context, handle_event_history_load_error,
    selected_member_activity, update_stats_status,
};
use crate::views;
use anyhow::Result;
use iris_common::models::RoomEventRecord;

pub(crate) async fn refresh_rooms(iris: &api::TuiApi, app: &mut app::App) {
    if let Ok(rooms) = iris.rooms().await {
        app.rooms_view.set_rooms(rooms.rooms);
        if let Some(chat_id) = app.members_view.chat_id {
            let exists = app.rooms_view.rooms.iter().any(|room| room.chat_id == chat_id);
            if !exists {
                clear_selected_room_context(app);
            }
        }
    }
}

pub(crate) async fn refresh_selected_room(
    iris: &api::TuiApi,
    app: &mut app::App,
    chat_id: i64,
) {
    let selected_member_id = app.stats_view.selected_member_id;
    let period = app.stats_view.period.clone();
    let members_fut = iris.members(chat_id);
    let stats_fut = iris.stats(chat_id, &period, 20);
    let info_fut = iris.room_info(chat_id);
    let activity_fut = selected_member_activity(iris, chat_id, selected_member_id, &period);
    let messages_fut = fetch_recent_messages(iris, chat_id);

    let (members_result, stats_result, info_result, activity_result, messages_result) =
        tokio::join!(members_fut, stats_fut, info_fut, activity_fut, messages_fut);

    if let Ok(members) = members_result {
        let nickname_map = build_nickname_map(&members.members);
        app.members_view.set_members(members.members);
        app.messages_view.set_nicknames(nickname_map);
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
    apply_messages_refresh_result(app, messages_result);
}

pub(crate) async fn refresh_app_data(iris: &api::TuiApi, app: &mut app::App) {
    refresh_rooms(iris, app).await;

    if let Some(chat_id) = app.members_view.chat_id {
        refresh_selected_room(iris, app, chat_id).await;
    }
}

pub(crate) async fn load_event_history(
    iris: &api::TuiApi,
    rooms_view: &views::rooms::RoomsView,
) -> Result<Vec<RoomEventRecord>> {
    let chat_id = rooms_view
        .selected_chat_id()
        .or_else(|| rooms_view.rooms.first().map(|room| room.chat_id))
        .ok_or_else(|| anyhow::anyhow!("No room available for event history"))?;
    iris.get_room_events(chat_id, 50, 0).await
}

pub(crate) async fn apply_pending_thread_fetch(iris: &api::TuiApi, app: &mut app::App) {
    if let Some(chat_id) = app.pending_thread_fetch.take() {
        if let Ok(thread_list) = iris.list_threads(chat_id).await {
            if let Some(modal) = &mut app.reply_modal {
                modal.thread_suggestions = thread_list.threads;
            }
        }
    }
}

pub(crate) async fn apply_pending_event_history_load(iris: &api::TuiApi, app: &mut app::App) {
    if app.pending_event_history_load {
        app.pending_event_history_load = false;
        match load_event_history(iris, &app.rooms_view).await {
            Ok(records) => {
                app.handle_app_event(app::AppEvent::EventHistoryLoaded(records));
            }
            Err(error) => {
                handle_event_history_load_error(app, &error);
            }
        }
    }
}
