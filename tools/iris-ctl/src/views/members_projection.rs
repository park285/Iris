use crate::views::members::{RoleFilter, SortMode};
use iris_common::models::MemberInfo;

pub(crate) fn rebuild_filtered_indices(
    members: &[MemberInfo],
    search: &str,
    role_filter: RoleFilter,
    sort_mode: SortMode,
) -> Vec<usize> {
    let lower_search = (!search.is_empty()).then(|| search.to_lowercase());
    let mut filtered_indices: Vec<usize> = members
        .iter()
        .enumerate()
        .filter(|(_, member)| {
            role_matches(role_filter, member) && search_matches(member, lower_search.as_deref())
        })
        .map(|(index, _)| index)
        .collect();

    match sort_mode {
        SortMode::Activity => filtered_indices.sort_by(|a, b| compare_by_activity(members, *a, *b)),
        SortMode::Name => filtered_indices.sort_by(|a, b| compare_by_name(members, *a, *b)),
    }
    filtered_indices
}

fn role_matches(role_filter: RoleFilter, member: &MemberInfo) -> bool {
    match role_filter {
        RoleFilter::All => true,
        RoleFilter::Owners => member.role_code == 1,
        RoleFilter::Admins => member.role_code == 4,
        RoleFilter::Bots => member.role_code == 8,
        RoleFilter::Members => {
            member.role_code != 1 && member.role_code != 4 && member.role_code != 8
        }
    }
}

fn search_matches(member: &MemberInfo, query: Option<&str>) -> bool {
    query.is_none_or(|query| {
        member
            .nickname
            .as_deref()
            .unwrap_or("")
            .to_lowercase()
            .contains(query)
    })
}

fn compare_by_activity(
    members: &[MemberInfo],
    left_index: usize,
    right_index: usize,
) -> std::cmp::Ordering {
    let left = &members[left_index];
    let right = &members[right_index];
    right
        .message_count
        .cmp(&left.message_count)
        .then_with(|| right.last_active_at.cmp(&left.last_active_at))
        .then_with(|| {
            left.nickname
                .as_deref()
                .unwrap_or("")
                .cmp(right.nickname.as_deref().unwrap_or(""))
        })
}

fn compare_by_name(
    members: &[MemberInfo],
    left_index: usize,
    right_index: usize,
) -> std::cmp::Ordering {
    let left = &members[left_index];
    let right = &members[right_index];
    left.nickname
        .as_deref()
        .unwrap_or("")
        .cmp(right.nickname.as_deref().unwrap_or(""))
        .then_with(|| left.user_id.cmp(&right.user_id))
}
