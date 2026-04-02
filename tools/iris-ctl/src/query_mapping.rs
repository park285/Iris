use crate::{api, views};
use anyhow::Result;
use iris_common::models::{MemberInfo, RecentMessagesResponse};
use std::collections::HashMap;

pub(crate) const RECENT_MESSAGES_LIMIT: i32 = 50;

pub(crate) fn map_recent_messages_response(
    response: &RecentMessagesResponse,
) -> Vec<views::messages::ChatMessage> {
    let mut messages = Vec::with_capacity(response.messages.len());
    for row in &response.messages {
        messages.push(views::messages::ChatMessage {
            id: row.id,
            chat_id: row.chat_id,
            user_id: row.user_id,
            message: row.message.clone(),
            msg_type: row.msg_type,
            created_at: row.created_at,
            thread_id: row.thread_id,
        });
    }
    messages.sort_by_key(|message| message.created_at);
    messages
}

pub(crate) fn build_nickname_map(members: &[MemberInfo]) -> HashMap<i64, String> {
    members
        .iter()
        .filter_map(|member| {
            member
                .nickname
                .clone()
                .map(|nickname| (member.user_id, nickname))
        })
        .collect()
}

pub(crate) async fn fetch_recent_messages(
    iris: &api::TuiApi,
    chat_id: i64,
) -> Result<Vec<views::messages::ChatMessage>> {
    let response = iris.recent_messages(chat_id, RECENT_MESSAGES_LIMIT).await?;
    Ok(map_recent_messages_response(&response))
}
