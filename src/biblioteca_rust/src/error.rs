use thiserror::Error;

#[derive(Debug, Error)]
pub enum CoreError {
    #[error("network error: {0}")]
    Network(String),

    #[error("session expired: {0}")]
    SessionExpired(String),

    #[error("parse error: {0}")]
    Parse(String),

    #[error("io error: {0}")]
    Io(String),

    #[error("invalid input")]
    InvalidInput,
}

impl From<reqwest::Error> for CoreError {
    fn from(e: reqwest::Error) -> Self {
        CoreError::Network(e.to_string())
    }
}

impl From<std::io::Error> for CoreError {
    fn from(e: std::io::Error) -> Self {
        CoreError::Io(e.to_string())
    }
}

impl From<serde_json::Error> for CoreError {
    fn from(e: serde_json::Error) -> Self {
        CoreError::Parse(e.to_string())
    }
}
