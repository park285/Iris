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

pub type NativeCoreResult<T> = Result<T, NativeCoreError>;
