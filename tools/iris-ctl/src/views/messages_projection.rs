use crate::views::messages::{ChatMessage, VisibleRow};
use std::collections::HashMap;
use std::collections::HashSet;

type ThreadGroup<'a> = Vec<(usize, &'a ChatMessage)>;
type ThreadGroups<'a> = HashMap<i64, ThreadGroup<'a>>;

pub(crate) fn visible_rows(
    messages: &[ChatMessage],
    expanded_threads: &HashSet<i64>,
) -> Vec<VisibleRow> {
    let mut rows = Vec::new();
    let mut thread_groups: ThreadGroups<'_> = HashMap::new();

    for (index, message) in messages.iter().enumerate() {
        if let Some(thread_id) = message.thread_id {
            thread_groups
                .entry(thread_id)
                .or_default()
                .push((index, message));
        } else {
            rows.push(VisibleRow::Message {
                index,
                message_id: message.id,
            });
        }
    }

    for (thread_id, mut group) in thread_groups {
        group.sort_by_key(|(_, message)| message.created_at);
        let (root_index, root_message) = group[0];
        rows.push(VisibleRow::ThreadHeader {
            thread_id,
            root_index,
            message_id: root_message.id,
        });
        if expanded_threads.contains(&thread_id) {
            for (index, message) in group.into_iter().skip(1) {
                rows.push(VisibleRow::ThreadChild {
                    thread_id,
                    index,
                    message_id: message.id,
                });
            }
        }
    }

    rows.sort_by_key(|row| match row {
        VisibleRow::ThreadHeader { root_index, .. } => messages[*root_index].created_at,
        VisibleRow::Message { index, .. } | VisibleRow::ThreadChild { index, .. } => {
            messages[*index].created_at
        }
    });
    rows
}
