use crate::views::members::RoleFilter;
use crate::views::members::SortMode;
use iris_common::models::MemberInfo;
use ratatui::widgets::{Cell, Row};
use std::time::{SystemTime, UNIX_EPOCH};

pub(crate) fn render_title(
    filtered_count: usize,
    role_filter: RoleFilter,
    sort_mode: SortMode,
) -> String {
    let filter_label = match role_filter {
        RoleFilter::All => "all",
        RoleFilter::Owners => "owners",
        RoleFilter::Admins => "admins",
        RoleFilter::Bots => "bots",
        RoleFilter::Members => "members",
    };
    let sort_label = match sort_mode {
        SortMode::Activity => "activity",
        SortMode::Name => "name",
    };
    format!(" Members ({filtered_count}) [{filter_label}|{sort_label}] ")
}

pub(crate) const fn member_role_label(role_code: i32) -> &'static str {
    match role_code {
        1 => "* owner",
        4 => "# admin",
        8 => "@ bot",
        _ => "  member",
    }
}

pub(crate) fn render_rows<'a>(filtered: &[&'a MemberInfo]) -> Vec<Row<'a>> {
    filtered
        .iter()
        .enumerate()
        .map(|(index, member)| {
            Row::new([
                Cell::from((index + 1).to_string()),
                Cell::from(member.nickname.as_deref().unwrap_or("?")),
                Cell::from(member_role_label(member.role_code)),
                Cell::from(member.message_count.to_string()),
                Cell::from(last_active_label(member.last_active_at)),
                Cell::from(member.user_id.to_string()),
            ])
        })
        .collect()
}

pub(crate) fn format_last_active(ts: Option<i64>, now_epoch_secs: i64) -> String {
    let Some(ts) = ts else {
        return "-".to_string();
    };
    let delta = now_epoch_secs.saturating_sub(ts);
    if delta < 60 {
        "just now".to_string()
    } else if delta < 3600 {
        format!("{}m ago", delta / 60)
    } else if delta < 86_400 {
        format!("{}h ago", delta / 3600)
    } else {
        format!("{}d ago", delta / 86_400)
    }
}

pub(crate) fn last_active_label(ts: Option<i64>) -> String {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64;
    format_last_active(ts, now)
}
