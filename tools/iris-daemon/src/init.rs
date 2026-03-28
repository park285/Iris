use crate::config::DaemonConfig;
use anyhow::Result;

pub async fn run_init(_cfg: &DaemonConfig) -> Result<()> {
    tracing::info!("init: 미구현");
    Ok(())
}
