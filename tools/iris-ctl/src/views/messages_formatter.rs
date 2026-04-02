use crate::views::messages::{ChatMessage, VisibleRow};
use std::collections::HashMap;
use std::collections::HashSet;

pub(crate) fn render_line(
    messages: &[ChatMessage],
    row: &VisibleRow,
    nicknames: &HashMap<i64, String>,
    expanded_threads: &HashSet<i64>,
) -> String {
    let message = match row {
        VisibleRow::ThreadHeader { root_index, .. } => &messages[*root_index],
        VisibleRow::Message { index, .. } | VisibleRow::ThreadChild { index, .. } => {
            &messages[*index]
        }
    };
    let nickname = nicknames
        .get(&message.user_id)
        .cloned()
        .unwrap_or_else(|| message.user_id.to_string());
    let content = match message.msg_type {
        1 => message.message.clone(),
        2 | 27 => "[image]".to_string(),
        3 => "[file]".to_string(),
        20 => "[emoticon]".to_string(),
        26 => "[reply]".to_string(),
        _ => "[unsupported]".to_string(),
    };
    match row {
        VisibleRow::ThreadChild { .. } => format!("    {nickname}: {content}"),
        VisibleRow::ThreadHeader { thread_id, .. } => {
            let marker = if expanded_threads.contains(thread_id) {
                "▾"
            } else {
                "▸"
            };
            format!("{marker} {nickname}: {content}")
        }
        VisibleRow::Message { .. } => format!("{nickname}: {content}"),
    }
}
