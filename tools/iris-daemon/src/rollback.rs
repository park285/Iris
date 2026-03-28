use crate::adb::Adb;
use crate::config::DaemonConfig;
use anyhow::Result;

pub async fn perform_rollback(_adb: &Adb, _cfg: &DaemonConfig) -> Result<()> {
    // Task 15에서 구현
    tracing::info!("rollback: 미구현");
    Ok(())
}
