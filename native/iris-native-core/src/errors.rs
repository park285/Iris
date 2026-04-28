use thiserror::Error;

#[derive(Debug, Error)]
pub enum NativeCoreError {
    #[error("invalid request json: {0}")]
    InvalidRequest(String),
    #[error("invalid response json: {0}")]
    InvalidResponse(String),
    #[error("decrypt failed: {0}")]
    Decrypt(String),
    #[error("jni error: {0}")]
    Jni(String),
    #[error("panic in native core")]
    Panic,
}

impl NativeCoreError {
    #[must_use]
    pub const fn kind(&self) -> &'static str {
        match self {
            Self::InvalidRequest(_) => "invalidRequest",
            Self::InvalidResponse(_) => "invalidResponse",
            Self::Decrypt(_) => "decrypt",
            Self::Jni(_) => "jniError",
            Self::Panic => "panic",
        }
    }
}

pub type NativeCoreResult<T> = Result<T, NativeCoreError>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn error_kind_is_low_cardinality_and_stable() {
        let cases = [
            (
                NativeCoreError::InvalidRequest("bad input".to_owned()),
                "invalidRequest",
            ),
            (
                NativeCoreError::InvalidResponse("bad output".to_owned()),
                "invalidResponse",
            ),
            (NativeCoreError::Decrypt("bad cipher".to_owned()), "decrypt"),
            (NativeCoreError::Jni("bad env".to_owned()), "jniError"),
            (NativeCoreError::Panic, "panic"),
        ];

        for (error, expected_kind) in cases {
            assert_eq!(error.kind(), expected_kind);
        }
    }
}
