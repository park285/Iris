use anyhow::{Context, Result};
use iris_common::config::IrisConnection;
use serde::Deserialize;
use std::path::PathBuf;

#[derive(Deserialize, Clone)]
pub struct Config {
    pub server: ServerConfig,
    #[serde(default)]
    pub ui: UiConfig,
}

#[derive(Deserialize, Clone)]
pub struct ServerConfig {
    pub url: String,
    #[serde(default)]
    pub token: String,
}

#[derive(Deserialize, Clone)]
pub struct UiConfig {
    #[serde(default = "default_poll_interval")]
    pub poll_interval_secs: u64,
}

impl Default for UiConfig {
    fn default() -> Self {
        Self {
            poll_interval_secs: 5,
        }
    }
}

fn default_poll_interval() -> u64 {
    5
}

impl Config {
    pub fn load() -> Result<Self> {
        let path = config_path();
        let content = std::fs::read_to_string(&path)
            .with_context(|| format!("Failed to read config: {}", path.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&path)?.permissions().mode() & 0o777;
            if mode & 0o077 != 0 {
                eprintln!(
                    "WARNING: {} has permissions {:o}, should be 600",
                    path.display(),
                    mode
                );
            }
        }
        let mut config: Config = toml::from_str(&content)?;
        if let Ok(token) = std::env::var("IRIS_TOKEN") {
            config.server.token = token;
        }
        Ok(config)
    }
}

impl IrisConnection for Config {
    fn base_url(&self) -> &str {
        &self.server.url
    }
    fn token(&self) -> &str {
        &self.server.token
    }
}

fn config_path() -> PathBuf {
    if let Some(config_dir) = dirs::config_dir() {
        config_dir.join("iris-ctl").join("config.toml")
    } else {
        PathBuf::from("config.toml")
    }
}
