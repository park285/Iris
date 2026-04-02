use iris_common::models::{ReplyRequest, ReplyType, RoomSummary};

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum ModalFocus {
    Type,
    Room,
    RoomSelector,
    Thread,
    ThreadId,
    ThreadSelector,
    Scope,
    Content,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ThreadMode {
    None,
    Specified,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum ThreadScope {
    Thread,
    Both,
    Room,
}

impl ThreadScope {
    pub(crate) const fn value(self) -> u8 {
        match self {
            Self::Thread => 2,
            Self::Both => 3,
            Self::Room => 1,
        }
    }

    pub(crate) const fn label(self) -> &'static str {
        match self {
            Self::Thread => "thread",
            Self::Both => "both",
            Self::Room => "room",
        }
    }

    pub(crate) const fn next(self) -> Self {
        match self {
            Self::Thread => Self::Both,
            Self::Both => Self::Room,
            Self::Room => Self::Thread,
        }
    }

    pub(crate) const fn prev(self) -> Self {
        match self {
            Self::Thread => Self::Room,
            Self::Room => Self::Both,
            Self::Both => Self::Thread,
        }
    }
}

#[derive(Clone)]
pub enum ReplyResult {
    Success { request_id: String },
    Error { message: String },
}

pub enum ModalAction {
    None,
    Close,
    Send(ReplyRequest),
    FetchThreads(i64),
}

pub(crate) enum ReplyValidationError {
    MissingThreadId,
    InvalidThreadId,
    MissingTextContent,
    MissingImagePath,
    MissingImagePaths,
}

pub(crate) fn is_open_chat(room: Option<&RoomSummary>) -> bool {
    room.and_then(|selected| selected.room_type.as_deref())
        .is_some_and(|room_type| room_type.starts_with('O'))
}

pub(crate) fn cycle_reply_type(current: ReplyType, step_right: bool) -> ReplyType {
    let types = [
        ReplyType::Text,
        ReplyType::Image,
        ReplyType::ImageMultiple,
        ReplyType::Markdown,
    ];
    let cur = types.iter().position(|t| *t == current).unwrap_or(0);
    let next = if step_right {
        (cur + 1) % types.len()
    } else {
        (cur + types.len() - 1) % types.len()
    };
    types[next]
}
