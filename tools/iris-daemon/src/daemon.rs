use crate::config::DaemonConfig;
use anyhow::Result;

pub async fn run_watch(_cfg: DaemonConfig) -> Result<()> {
    tracing::info!("watch: 미구현");
    Ok(())
}
