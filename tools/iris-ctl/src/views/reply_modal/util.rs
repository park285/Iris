use ratatui::layout::{Constraint, Direction, Layout, Rect};

pub fn centered_rect(area: Rect, percent_x: u16, percent_y: u16) -> Rect {
    let vertical = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Percentage((100 - percent_y) / 2),
            Constraint::Percentage(percent_y),
            Constraint::Percentage((100 - percent_y) / 2),
        ])
        .split(area);
    Layout::default()
        .direction(Direction::Horizontal)
        .constraints([
            Constraint::Percentage((100 - percent_x) / 2),
            Constraint::Percentage(percent_x),
            Constraint::Percentage((100 - percent_x) / 2),
        ])
        .split(vertical[1])[1]
}

pub fn truncate_thread_origin(origin: &str) -> String {
    if origin.chars().count() > 40 {
        let cut: String = origin.chars().take(40).collect();
        format!("{cut}...")
    } else {
        origin.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::truncate_thread_origin;

    #[test]
    fn truncate_thread_origin_is_char_safe() {
        let truncated = truncate_thread_origin(&"가".repeat(45));

        assert_eq!(truncated.chars().count(), 43);
        assert!(truncated.ends_with("가..."));
    }
}
