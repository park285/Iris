use anyhow::{Context, Result, bail};
use std::path::Path;
use tokio::process::Command;

pub struct Adb {
    device: String,
}

impl Adb {
    pub fn new(device: &str) -> Self {
        Self {
            device: device.to_string(),
        }
    }

    pub fn device(&self) -> &str {
        &self.device
    }

    pub async fn shell(&self, cmd: &str) -> Result<String> {
        let output = Command::new("adb")
            .args(["-s", &self.device, "shell", cmd])
            .output()
            .await
            .context("adb shell 실행 실패")?;
        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            bail!("adb shell 실패 (exit {}): {}", output.status, stderr.trim());
        }
        Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
    }

    pub async fn push(&self, local: &Path, remote: &str) -> Result<()> {
        let output = Command::new("adb")
            .args(["-s", &self.device, "push", &local.to_string_lossy(), remote])
            .output()
            .await
            .context("adb push 실행 실패")?;
        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            bail!("adb push 실패 (exit {}): {}", output.status, stderr.trim());
        }
        tracing::debug!(local = %local.display(), remote = remote, "adb push 완료");
        Ok(())
    }

    pub async fn pull(&self, remote: &str, local: &Path) -> Result<()> {
        let output = Command::new("adb")
            .args(["-s", &self.device, "pull", remote, &local.to_string_lossy()])
            .output()
            .await
            .context("adb pull 실행 실패")?;
        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            bail!("adb pull 실패 (exit {}): {}", output.status, stderr.trim());
        }
        tracing::debug!(remote = remote, local = %local.display(), "adb pull 완료");
        Ok(())
    }

    pub async fn install(&self, apk_path: &Path) -> Result<()> {
        let output = Command::new("adb")
            .args([
                "-s",
                &self.device,
                "install",
                "-r",
                &apk_path.to_string_lossy(),
            ])
            .output()
            .await
            .context("adb install 실행 실패")?;
        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            bail!(
                "adb install 실패 (exit {}): {}",
                output.status,
                stderr.trim()
            );
        }
        tracing::info!(apk = %apk_path.display(), "APK 설치 완료");
        Ok(())
    }

    pub async fn is_boot_completed(&self) -> bool {
        self.shell("getprop sys.boot_completed")
            .await
            .is_ok_and(|output| output.trim() == "1")
    }

    pub async fn connect(&self) -> Result<String> {
        let output = Command::new("adb")
            .args(["connect", &self.device])
            .output()
            .await
            .context("adb connect 실행 실패")?;
        Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adb_stores_device() {
        let adb = Adb::new("192.168.1.100:5555");
        assert_eq!(adb.device(), "192.168.1.100:5555");
    }
}
