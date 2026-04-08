use super::{View, ViewAction};
use crossterm::event::{KeyCode, KeyEvent};
use iris_common::models::{RoomEventRecord, SseEvent};
use ratatui::Frame;
use ratatui::layout::Rect;
use ratatui::widgets::{Block, List, ListItem, ListState};
use serde_json::Value;
use std::collections::VecDeque;

const MAX_EVENTS: usize = 500;
const LEGACY_HISTORY_KEYS: &[&str] = &[
    "type",
    "eventType",
    "event",
    "chatId",
    "chat_id",
    "userId",
    "user_id",
    "nickname",
    "oldNickname",
    "old_nickname",
    "newNickname",
    "new_nickname",
    "oldRole",
    "old_role",
    "newRole",
    "new_role",
    "oldProfileImageUrl",
    "old_profile_image_url",
    "newProfileImageUrl",
    "new_profile_image_url",
    "estimated",
    "timestamp",
    "createdAt",
    "created_at",
];

pub struct HistoryLoadResult {
    pub loaded: usize,
    pub parsed: usize,
    pub fallback: usize,
}

pub struct EventsView {
    events: VecDeque<String>,
    state: ListState,
    paused: bool,
    filter_active: bool,
    history_loaded_for_chat_id: Option<i64>,
}

impl EventsView {
    pub fn new() -> Self {
        Self {
            events: VecDeque::new(),
            state: ListState::default(),
            paused: false,
            filter_active: false,
            history_loaded_for_chat_id: None,
        }
    }

    pub fn push_event(&mut self, event: &SseEvent) {
        if self.paused {
            return;
        }
        self.events.push_back(format_event_line(event));
        self.trim_to_limit();
        self.select_last_visible();
    }

    pub fn push_history(&mut self, chat_id: i64, records: &[RoomEventRecord]) -> HistoryLoadResult {
        let mut lines = Vec::new();
        let mut parsed = 0;
        let mut fallback = 0;
        for record in records {
            if let Some(event) = decode_history_event(record) {
                lines.push(format_event_line(&event));
                parsed += 1;
            } else {
                lines.push(format_history_fallback(record));
                fallback += 1;
            }
        }

        for line in lines.into_iter().rev() {
            self.events.push_front(line);
        }
        self.trim_to_limit();
        self.history_loaded_for_chat_id = Some(chat_id);
        if self.state.selected().is_none() {
            let len = self.visible_events().len();
            self.state.select(if len == 0 { None } else { Some(0) });
        }

        HistoryLoadResult {
            loaded: records.len(),
            parsed,
            fallback,
        }
    }

    pub fn should_auto_load_history_for(&self, chat_id: Option<i64>) -> bool {
        matches!(chat_id, Some(id) if self.history_loaded_for_chat_id != Some(id))
    }

    pub fn mark_history_unavailable(&mut self, chat_id: Option<i64>) {
        self.history_loaded_for_chat_id = chat_id;
    }

    fn trim_to_limit(&mut self) {
        while self.events.len() > MAX_EVENTS {
            self.events.pop_front();
        }
    }

    fn select_last_visible(&mut self) {
        self.state
            .select(Some(self.visible_events().len().saturating_sub(1)));
    }

    fn visible_events(&self) -> Vec<&str> {
        self.events
            .iter()
            .filter(|e| {
                !self.filter_active
                    || e.contains("JOIN")
                    || e.contains("LEAVE")
                    || e.contains("KICK")
                    || e.contains("NICK")
            })
            .map(String::as_str)
            .collect()
    }

    #[cfg(test)]
    pub(crate) fn event_count(&self) -> usize {
        self.events.len()
    }
}

fn format_timestamp(timestamp: Option<i64>) -> String {
    timestamp
        .map(|t| {
            let s = t % 86400;
            format!("{:02}:{:02}:{:02}", s / 3600, (s % 3600) / 60, s % 60)
        })
        .unwrap_or_default()
}

fn format_event_line(event: &SseEvent) -> String {
    let ts = format_timestamp(event.timestamp);
    match (event.event_type.as_str(), event.event.as_deref()) {
        ("member_event", Some("join")) => format!(
            "{} JOIN   {} entered",
            ts,
            event.nickname.as_deref().unwrap_or("?")
        ),
        ("member_event", Some("leave")) => format!(
            "{} LEAVE  {} left{}",
            ts,
            event.nickname.as_deref().unwrap_or("?"),
            if event.estimated == Some(true) {
                " (est)"
            } else {
                ""
            }
        ),
        ("member_event", Some("kick")) => format!(
            "{} KICK   {} kicked",
            ts,
            event.nickname.as_deref().unwrap_or("?")
        ),
        ("nickname_change", _) => format!(
            "{} NICK   {} -> {}",
            ts,
            event.old_nickname.as_deref().unwrap_or("?"),
            event.new_nickname.as_deref().unwrap_or("?")
        ),
        ("role_change", _) => format!(
            "{} ROLE   {} -> {}",
            ts,
            event.old_role.as_deref().unwrap_or("?"),
            event.new_role.as_deref().unwrap_or("?")
        ),
        ("profile_change", _) => format!(
            "{} PROF   {} {} -> {}",
            ts,
            profile_actor_label(event),
            compact_profile_value(event.old_profile_image_url.as_deref()),
            compact_profile_value(event.new_profile_image_url.as_deref())
        ),
        _ => format!("{} ???    {:?}", ts, event.event_type),
    }
}

fn profile_actor_label(event: &SseEvent) -> String {
    event
        .nickname
        .clone()
        .unwrap_or_else(|| format!("user {}", event.user_id.unwrap_or(0)))
}

fn compact_profile_value(value: Option<&str>) -> String {
    let Some(text) = value.map(str::trim).filter(|text| !text.is_empty()) else {
        return "(none)".to_string();
    };

    compact_text(text, 24)
}

fn compact_text(text: &str, max_chars: usize) -> String {
    let chars: Vec<char> = text.chars().collect();
    if chars.len() <= max_chars {
        return text.to_string();
    }

    let head: String = chars.iter().take(10).collect();
    let tail: String = chars[chars.len().saturating_sub(10)..].iter().collect();
    format!("{head}...{tail}")
}

fn format_history_fallback(record: &RoomEventRecord) -> String {
    format!(
        "{} HIST   {} user {} (payload parse failed)",
        format_timestamp(Some(record.created_at / 1000)),
        record.event_type,
        record.user_id
    )
}

fn decode_history_event(record: &RoomEventRecord) -> Option<SseEvent> {
    if let Ok(event) = serde_json::from_str::<SseEvent>(&record.payload) {
        return Some(enrich_history_event(event, record));
    }

    if let Ok(value) = serde_json::from_str::<Value>(&record.payload) {
        return decode_history_event_nested(record, value.get("payload"))
            .or_else(|| decode_history_event_nested(record, value.get("data")))
            .or_else(|| decode_history_event_value(record, &value));
    }

    decode_legacy_history_event(record, &record.payload)
}

fn decode_history_event_nested(
    record: &RoomEventRecord,
    value: Option<&Value>,
) -> Option<SseEvent> {
    let nested = value?;
    decode_history_event_value(record, nested).or_else(|| match nested {
        Value::String(raw) => {
            let value = serde_json::from_str::<Value>(raw).ok()?;
            decode_history_event_value(record, &value)
        }
        _ => None,
    })
}

fn decode_history_event_value(record: &RoomEventRecord, value: &Value) -> Option<SseEvent> {
    let Value::Object(_) = value else {
        return None;
    };

    Some(enrich_history_event(
        SseEvent {
            event_type: string_field(value, &["type", "eventType"])
                .unwrap_or_else(|| record.event_type.clone()),
            event: string_field(value, &["event"]),
            chat_id: int_field(value, &["chatId", "chat_id"]),
            user_id: int_field(value, &["userId", "user_id"]),
            nickname: string_field(value, &["nickname"]),
            old_nickname: string_field(value, &["oldNickname", "old_nickname"]),
            new_nickname: string_field(value, &["newNickname", "new_nickname"]),
            old_role: string_field(value, &["oldRole", "old_role"]),
            new_role: string_field(value, &["newRole", "new_role"]),
            old_profile_image_url: string_field(
                value,
                &["oldProfileImageUrl", "old_profile_image_url"],
            ),
            new_profile_image_url: string_field(
                value,
                &["newProfileImageUrl", "new_profile_image_url"],
            ),
            estimated: bool_field(value, &["estimated"]),
            timestamp: int_field(value, &["timestamp", "createdAt", "created_at"]),
        },
        record,
    ))
}

fn enrich_history_event(mut event: SseEvent, record: &RoomEventRecord) -> SseEvent {
    if event.event_type.is_empty() {
        event.event_type.clone_from(&record.event_type);
    }
    if event.chat_id.is_none() {
        event.chat_id = Some(record.chat_id);
    }
    if event.user_id.is_none() {
        event.user_id = Some(record.user_id);
    }
    event.timestamp = Some(match event.timestamp {
        Some(timestamp) => normalize_history_timestamp(timestamp),
        None => record.created_at / 1000,
    });
    event
}

fn string_field(value: &Value, keys: &[&str]) -> Option<String> {
    keys.iter().find_map(|key| {
        value.get(*key).and_then(|field| match field {
            Value::String(text) => Some(text.clone()),
            Value::Number(number) => Some(number.to_string()),
            Value::Bool(flag) => Some(flag.to_string()),
            _ => None,
        })
    })
}

fn int_field(value: &Value, keys: &[&str]) -> Option<i64> {
    keys.iter().find_map(|key| {
        value.get(*key).and_then(|field| match field {
            Value::Number(number) => number.as_i64(),
            Value::String(text) => text.parse().ok(),
            _ => None,
        })
    })
}

fn bool_field(value: &Value, keys: &[&str]) -> Option<bool> {
    keys.iter().find_map(|key| {
        value.get(*key).and_then(|field| match field {
            Value::Bool(flag) => Some(*flag),
            Value::Number(number) => match number.as_i64() {
                Some(0) => Some(false),
                Some(1) => Some(true),
                _ => None,
            },
            Value::String(text) => match text.as_str() {
                "true" | "TRUE" | "True" | "1" => Some(true),
                "false" | "FALSE" | "False" | "0" => Some(false),
                _ => None,
            },
            _ => None,
        })
    })
}

fn decode_legacy_history_event(record: &RoomEventRecord, raw: &str) -> Option<SseEvent> {
    let body = legacy_payload_body(raw)?;

    Some(enrich_history_event(
        SseEvent {
            event_type: legacy_string_field(body, &["type", "eventType"])
                .or_else(|| legacy_type_from_prefix(raw))
                .unwrap_or_else(|| record.event_type.clone()),
            event: legacy_string_field(body, &["event"]),
            chat_id: legacy_int_field(body, &["chatId", "chat_id"]),
            user_id: legacy_int_field(body, &["userId", "user_id"]),
            nickname: legacy_string_field(body, &["nickname"]),
            old_nickname: legacy_string_field(body, &["oldNickname", "old_nickname"]),
            new_nickname: legacy_string_field(body, &["newNickname", "new_nickname"]),
            old_role: legacy_string_field(body, &["oldRole", "old_role"]),
            new_role: legacy_string_field(body, &["newRole", "new_role"]),
            old_profile_image_url: legacy_string_field(
                body,
                &["oldProfileImageUrl", "old_profile_image_url"],
            ),
            new_profile_image_url: legacy_string_field(
                body,
                &["newProfileImageUrl", "new_profile_image_url"],
            ),
            estimated: legacy_bool_field(body, &["estimated"]),
            timestamp: legacy_int_field(body, &["timestamp", "createdAt", "created_at"]),
        },
        record,
    ))
}

fn legacy_payload_body(raw: &str) -> Option<&str> {
    let trimmed = raw.trim();
    if let Some((_, rest)) = trimmed.split_once('(') {
        return rest.strip_suffix(')');
    }
    if let Some(body) = trimmed
        .strip_prefix('{')
        .and_then(|body| body.strip_suffix('}'))
    {
        return Some(body);
    }
    trimmed.contains('=').then_some(trimmed)
}

fn legacy_type_from_prefix(raw: &str) -> Option<String> {
    match raw.trim().split_once('(')?.0.trim() {
        "MemberEvent" => Some("member_event".to_string()),
        "NicknameChangeEvent" => Some("nickname_change".to_string()),
        "RoleChangeEvent" => Some("role_change".to_string()),
        "ProfileChangeEvent" => Some("profile_change".to_string()),
        _ => None,
    }
}

fn legacy_string_field(body: &str, keys: &[&str]) -> Option<String> {
    keys.iter()
        .find_map(|key| legacy_field(body, key))
        .and_then(|value| match value.as_str() {
            "null" | "<null>" | "" => None,
            _ => Some(value),
        })
}

fn legacy_int_field(body: &str, keys: &[&str]) -> Option<i64> {
    keys.iter().find_map(|key| {
        let value = legacy_field(body, key)?;
        value.trim_end_matches('L').parse::<i64>().ok()
    })
}

fn legacy_bool_field(body: &str, keys: &[&str]) -> Option<bool> {
    keys.iter().find_map(|key| {
        let value = legacy_field(body, key)?;
        match value.as_str() {
            "true" | "TRUE" | "True" | "1" => Some(true),
            "false" | "FALSE" | "False" | "0" => Some(false),
            _ => None,
        }
    })
}

fn legacy_field(body: &str, key: &str) -> Option<String> {
    let pattern = format!("{key}=");
    let mut search_from = 0;

    while let Some(relative) = body[search_from..].find(&pattern) {
        let start = search_from + relative;
        if start == 0 || body[..start].ends_with(", ") {
            let value_start = start + pattern.len();
            let value_end = legacy_field_end(body, value_start);
            return Some(
                body[value_start..value_end]
                    .trim()
                    .trim_matches('"')
                    .to_string(),
            );
        }
        search_from = start + pattern.len();
    }

    None
}

fn legacy_field_end(body: &str, value_start: usize) -> usize {
    LEGACY_HISTORY_KEYS
        .iter()
        .filter_map(|key| body[value_start..].find(&format!(", {key}=")))
        .map(|relative| value_start + relative)
        .min()
        .unwrap_or(body.len())
}

fn normalize_history_timestamp(timestamp: i64) -> i64 {
    if timestamp > 10_000_000_000 {
        timestamp / 1000
    } else {
        timestamp
    }
}

impl View for EventsView {
    fn render(&self, frame: &mut Frame<'_>, area: Rect) {
        let title = if self.paused {
            if self.filter_active {
                " Events (PAUSED, FILTERED) "
            } else {
                " Events (PAUSED) "
            }
        } else if self.filter_active {
            " Events (LIVE, FILTERED) "
        } else {
            " Events (LIVE) "
        };
        let items: Vec<ListItem<'_>> = self
            .visible_events()
            .into_iter()
            .map(ListItem::new)
            .collect();
        let list = List::new(items)
            .block(Block::bordered().title(title))
            .highlight_style(ratatui::style::Style::default().reversed());
        frame.render_stateful_widget(list, area, &mut self.state.clone());
    }
    fn handle_key(&mut self, key: KeyEvent) -> ViewAction {
        match key.code {
            KeyCode::Char('p') => {
                self.paused = !self.paused;
                ViewAction::None
            }
            KeyCode::Char('c') => {
                self.events.clear();
                self.state.select(None);
                ViewAction::None
            }
            KeyCode::Char('f') => {
                self.filter_active = !self.filter_active;
                let len = self.visible_events().len();
                self.state.select(if len == 0 { None } else { Some(0) });
                ViewAction::None
            }
            KeyCode::Char('h') => ViewAction::LoadEventHistory,
            KeyCode::Up | KeyCode::Char('k') => {
                let i = self.state.selected().unwrap_or(0);
                self.state.select(Some(i.saturating_sub(1)));
                ViewAction::None
            }
            KeyCode::Down | KeyCode::Char('j') => {
                let i = self.state.selected().unwrap_or(0);
                self.state.select(Some(
                    (i + 1).min(self.visible_events().len().saturating_sub(1)),
                ));
                ViewAction::None
            }
            KeyCode::Esc => ViewAction::Back,
            _ => ViewAction::None,
        }
    }
    fn title(&self) -> &'static str {
        "Events"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crossterm::event::KeyModifiers;

    fn key(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn member_event(event: &str) -> SseEvent {
        SseEvent {
            event_type: "member_event".to_string(),
            event: Some(event.to_string()),
            chat_id: Some(1),
            user_id: Some(2),
            nickname: Some("alice".to_string()),
            old_nickname: None,
            new_nickname: None,
            old_role: None,
            new_role: None,
            old_profile_image_url: None,
            new_profile_image_url: None,
            estimated: None,
            timestamp: Some(0),
        }
    }

    #[test]
    fn filter_toggle_limits_visible_events_to_member_changes() {
        let mut view = EventsView::new();
        view.push_event(&member_event("join"));
        view.push_event(&SseEvent {
            event_type: "profile_change".to_string(),
            event: None,
            chat_id: Some(1),
            user_id: Some(2),
            nickname: None,
            old_nickname: None,
            new_nickname: None,
            old_role: None,
            new_role: None,
            old_profile_image_url: None,
            new_profile_image_url: Some("profile-b".to_string()),
            estimated: None,
            timestamp: Some(0),
        });

        assert_eq!(view.visible_events().len(), 2);
        view.handle_key(key(KeyCode::Char('f')));
        assert!(view.filter_active);
        assert_eq!(view.visible_events().len(), 1);
        assert!(view.visible_events()[0].contains("JOIN"));
    }

    #[test]
    fn live_nickname_change_event_is_rendered() {
        let mut view = EventsView::new();
        view.push_event(&SseEvent {
            event_type: "nickname_change".to_string(),
            event: None,
            chat_id: Some(1),
            user_id: Some(2),
            nickname: None,
            old_nickname: Some("old".to_string()),
            new_nickname: Some("new".to_string()),
            old_role: None,
            new_role: None,
            old_profile_image_url: None,
            new_profile_image_url: None,
            estimated: None,
            timestamp: Some(0),
        });

        assert_eq!(view.visible_events().len(), 1);
        assert!(view.visible_events()[0].contains("NICK"));
        assert!(view.visible_events()[0].contains("old"));
        assert!(view.visible_events()[0].contains("new"));
    }

    #[test]
    fn live_profile_change_event_shows_old_and_new_values() {
        let mut view = EventsView::new();
        view.push_event(&SseEvent {
            event_type: "profile_change".to_string(),
            event: None,
            chat_id: Some(1),
            user_id: Some(2),
            nickname: Some("alice".to_string()),
            old_nickname: None,
            new_nickname: None,
            old_role: None,
            new_role: None,
            old_profile_image_url: Some("profile-a".to_string()),
            new_profile_image_url: Some("profile-b".to_string()),
            estimated: None,
            timestamp: Some(0),
        });

        assert_eq!(view.visible_events().len(), 1);
        assert!(view.visible_events()[0].contains("PROF"));
        assert!(view.visible_events()[0].contains("alice"));
        assert!(view.visible_events()[0].contains("profile-a"));
        assert!(view.visible_events()[0].contains("profile-b"));
    }

    #[test]
    fn event_buffer_keeps_latest_500_entries() {
        let mut view = EventsView::new();

        for idx in 0..505 {
            let mut event = member_event("join");
            event.timestamp = Some(idx);
            event.nickname = Some(format!("user-{idx}"));
            view.push_event(&event);
        }

        assert_eq!(view.visible_events().len(), 500);
        assert!(
            view.visible_events()
                .first()
                .is_some_and(|line| line.contains("user-5"))
        );
        assert!(
            view.visible_events()
                .last()
                .is_some_and(|line| line.contains("user-504"))
        );
    }

    #[test]
    fn history_records_are_prepended_in_time_order() {
        let mut view = EventsView::new();
        view.push_event(&member_event("join"));

        let result = view.push_history(
            1,
            &[
                RoomEventRecord {
                    id: 1,
                    chat_id: 1,
                    event_type: "member_event".to_string(),
                    user_id: 2,
                    payload: r#"{"type":"member_event","event":"join","nickname":"alice"}"#
                        .to_string(),
                    created_at: 1_000,
                },
                RoomEventRecord {
                    id: 2,
                    chat_id: 1,
                    event_type: "member_event".to_string(),
                    user_id: 3,
                    payload: r#"{"type":"member_event","event":"leave","nickname":"bob"}"#
                        .to_string(),
                    created_at: 2_000,
                },
            ],
        );

        assert!(!view.should_auto_load_history_for(Some(1)));
        assert_eq!(result.loaded, 2);
        assert_eq!(result.parsed, 2);
        assert_eq!(result.fallback, 0);
        assert_eq!(view.visible_events().len(), 3);
        assert!(view.visible_events()[0].contains("alice"));
        assert!(view.visible_events()[1].contains("bob"));
    }

    #[test]
    fn switching_history_target_requires_another_auto_load() {
        let mut view = EventsView::new();

        let result = view.push_history(
            1,
            &[RoomEventRecord {
                id: 1,
                chat_id: 1,
                event_type: "member_event".to_string(),
                user_id: 2,
                payload: r#"{"type":"member_event","event":"join","nickname":"alice"}"#.to_string(),
                created_at: 1_000,
            }],
        );

        assert_eq!(result.loaded, 1);
        assert_eq!(result.parsed, 1);
        assert_eq!(result.fallback, 0);
        assert!(!view.should_auto_load_history_for(Some(1)));
        assert!(view.should_auto_load_history_for(Some(2)));
    }

    #[test]
    fn invalid_history_payload_uses_fallback_line() {
        let mut view = EventsView::new();

        let result = view.push_history(
            1,
            &[RoomEventRecord {
                id: 1,
                chat_id: 1,
                event_type: "member_event".to_string(),
                user_id: 9,
                payload: r#"{"type":"member_event""#.to_string(),
                created_at: 1_000,
            }],
        );

        assert_eq!(result.loaded, 1);
        assert_eq!(result.parsed, 0);
        assert_eq!(result.fallback, 1);
        assert_eq!(view.visible_events().len(), 1);
        assert!(view.visible_events()[0].contains("member_event"));
        assert!(view.visible_events()[0].contains("payload parse failed"));
    }

    #[test]
    fn mixed_history_payloads_keep_valid_and_fallback_rows() {
        let mut view = EventsView::new();

        let result = view.push_history(
            1,
            &[
                RoomEventRecord {
                    id: 1,
                    chat_id: 1,
                    event_type: "member_event".to_string(),
                    user_id: 2,
                    payload: r#"{"type":"member_event","event":"join","nickname":"alice"}"#
                        .to_string(),
                    created_at: 1_000,
                },
                RoomEventRecord {
                    id: 2,
                    chat_id: 1,
                    event_type: "member_event".to_string(),
                    user_id: 9,
                    payload: r#"{"type":"member_event""#.to_string(),
                    created_at: 2_000,
                },
            ],
        );

        assert_eq!(result.loaded, 2);
        assert_eq!(result.parsed, 1);
        assert_eq!(result.fallback, 1);
        assert_eq!(view.visible_events().len(), 2);
        assert!(view.visible_events()[0].contains("JOIN"));
        assert!(view.visible_events()[1].contains("payload parse failed"));
    }

    #[test]
    fn unknown_but_valid_history_payload_counts_as_parsed() {
        let mut view = EventsView::new();

        let result = view.push_history(
            1,
            &[RoomEventRecord {
                id: 1,
                chat_id: 1,
                event_type: "mystery_event".to_string(),
                user_id: 5,
                payload: r#"{"type":"mystery_event","userId":5}"#.to_string(),
                created_at: 1_000,
            }],
        );

        assert_eq!(result.loaded, 1);
        assert_eq!(result.parsed, 1);
        assert_eq!(result.fallback, 0);
        assert_eq!(view.visible_events().len(), 1);
        assert!(view.visible_events()[0].contains("???"));
        assert!(view.visible_events()[0].contains("mystery_event"));
    }

    #[test]
    fn history_payload_without_type_uses_record_event_type() {
        let mut view = EventsView::new();

        let result = view.push_history(
            1,
            &[RoomEventRecord {
                id: 1,
                chat_id: 1,
                event_type: "member_event".to_string(),
                user_id: 2,
                payload: r#"{"event":"join","nickname":"alice","timestamp":1}"#.to_string(),
                created_at: 1_000,
            }],
        );

        assert_eq!(result.loaded, 1);
        assert_eq!(result.parsed, 1);
        assert_eq!(result.fallback, 0);
        assert_eq!(view.visible_events().len(), 1);
        assert!(view.visible_events()[0].contains("JOIN"));
        assert!(view.visible_events()[0].contains("alice"));
    }

    #[test]
    fn history_payload_with_snake_case_fields_is_normalized() {
        let mut view = EventsView::new();

        let result = view.push_history(
            1,
            &[RoomEventRecord {
                id: 1,
                chat_id: 1,
                event_type: "nickname_change".to_string(),
                user_id: 2,
                payload: r#"{"old_nickname":"old","new_nickname":"new","created_at":"1000"}"#
                    .to_string(),
                created_at: 1_000,
            }],
        );

        assert_eq!(result.loaded, 1);
        assert_eq!(result.parsed, 1);
        assert_eq!(result.fallback, 0);
        assert_eq!(view.visible_events().len(), 1);
        assert!(view.visible_events()[0].contains("NICK"));
        assert!(view.visible_events()[0].contains("old"));
        assert!(view.visible_events()[0].contains("new"));
    }

    #[test]
    fn history_payload_wrapper_uses_nested_payload_body() {
        let mut view = EventsView::new();

        let result = view.push_history(
            1,
            &[RoomEventRecord {
                id: 1,
                chat_id: 1,
                event_type: "member_event".to_string(),
                user_id: 2,
                payload: r#"{"eventType":"member_event","payload":"{\"event\":\"leave\",\"nickname\":\"bob\",\"estimated\":true}"}"#
                    .to_string(),
                created_at: 1_000,
            }],
        );

        assert_eq!(result.loaded, 1);
        assert_eq!(result.parsed, 1);
        assert_eq!(result.fallback, 0);
        assert_eq!(view.visible_events().len(), 1);
        assert!(view.visible_events()[0].contains("LEAVE"));
        assert!(view.visible_events()[0].contains("bob"));
    }

    #[test]
    fn legacy_member_event_string_payload_is_normalized() {
        let mut view = EventsView::new();

        let result = view.push_history(
            1,
            &[RoomEventRecord {
                id: 1,
                chat_id: 1,
                event_type: "member_event".to_string(),
                user_id: 2,
                payload: "MemberEvent(event=join, chatId=1, userId=2, nickname=alice, estimated=false, timestamp=1000)".to_string(),
                created_at: 1_000,
            }],
        );

        assert_eq!(result.loaded, 1);
        assert_eq!(result.parsed, 1);
        assert_eq!(result.fallback, 0);
        assert_eq!(view.visible_events().len(), 1);
        assert!(view.visible_events()[0].contains("JOIN"));
        assert!(view.visible_events()[0].contains("alice"));
    }

    #[test]
    fn history_payload_with_millisecond_timestamp_is_normalized() {
        let mut view = EventsView::new();

        let result = view.push_history(
            1,
            &[RoomEventRecord {
                id: 1,
                chat_id: 1,
                event_type: "member_event".to_string(),
                user_id: 2,
                payload: r#"{"event":"join","nickname":"alice","createdAt":86403600000}"#
                    .to_string(),
                created_at: 1_000,
            }],
        );

        assert_eq!(result.loaded, 1);
        assert_eq!(result.parsed, 1);
        assert_eq!(result.fallback, 0);
        assert_eq!(view.visible_events().len(), 1);
        assert!(view.visible_events()[0].starts_with("01:00:00"));
        assert!(view.visible_events()[0].contains("JOIN"));
    }

    #[test]
    fn history_key_requests_load() {
        let mut view = EventsView::new();

        assert!(matches!(
            view.handle_key(key(KeyCode::Char('h'))),
            ViewAction::LoadEventHistory
        ));
    }
}
