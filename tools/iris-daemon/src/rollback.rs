use crate::adb::Adb;
use crate::config::DaemonConfig;
use crate::process;
use anyhow::{Result, bail};
use std::path::{Path, PathBuf};

pub fn find_latest_backup_apk(backup_dir: &Path) -> Result<PathBuf> {
    if !backup_dir.exists() {
        bail!("백업 디렉토리 없음: {}", backup_dir.display());
    }
    let mut apk_files: Vec<PathBuf> = std::fs::read_dir(backup_dir)?
        .filter_map(|entry| entry.ok())
        .map(|entry| entry.path())
        .filter(|path| {
            path.extension()
                .map(|ext| ext.eq_ignore_ascii_case("apk"))
                .unwrap_or(false)
        })
        .collect();
    if apk_files.is_empty() {
        bail!("백업 디렉토리에 APK 파일 없음: {}", backup_dir.display());
    }
    apk_files.sort_by(|a, b| {
        let a_time = std::fs::metadata(a)
            .and_then(|m| m.modified())
            .unwrap_or(std::time::SystemTime::UNIX_EPOCH);
        let b_time = std::fs::metadata(b)
            .and_then(|m| m.modified())
            .unwrap_or(std::time::SystemTime::UNIX_EPOCH);
        b_time.cmp(&a_time)
    });
    Ok(apk_files.into_iter().next().unwrap())
}

pub async fn perform_rollback(adb: &Adb, cfg: &DaemonConfig) -> Result<()> {
    let backup_dir = Path::new(&cfg.rollback.backup_dir);
    let apk_path = find_latest_backup_apk(backup_dir)?;
    tracing::info!(apk = %apk_path.display(), "롤백 APK 선택");
    adb.push(&apk_path, &cfg.init.apk_dest).await?;
    tracing::info!(dest = %cfg.init.apk_dest, "롤백 APK push 완료");
    process::restart_iris(adb, cfg).await?;
    tracing::info!("자동 롤백 완료");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn find_latest_backup_apk_returns_error_for_missing_dir() {
        let result = find_latest_backup_apk(Path::new("/nonexistent/dir"));
        assert!(result.is_err());
        assert!(
            result
                .unwrap_err()
                .to_string()
                .contains("백업 디렉토리 없음")
        );
    }

    #[test]
    fn find_latest_backup_apk_returns_error_for_empty_dir() {
        let tmp = std::env::temp_dir().join("iris-daemon-test-empty-backup");
        let _ = fs::create_dir_all(&tmp);
        for entry in fs::read_dir(&tmp).unwrap() {
            let path = entry.unwrap().path();
            if path.extension().map(|e| e == "apk").unwrap_or(false) {
                let _ = fs::remove_file(path);
            }
        }
        let result = find_latest_backup_apk(&tmp);
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("APK 파일 없음"));
        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn find_latest_backup_apk_selects_apk_file() {
        let tmp = std::env::temp_dir().join("iris-daemon-test-backup");
        let _ = fs::create_dir_all(&tmp);
        let apk_file = tmp.join("Iris-v1.0.apk");
        fs::write(&apk_file, b"fake apk").unwrap();
        fs::write(tmp.join("readme.txt"), b"not an apk").unwrap();
        let result = find_latest_backup_apk(&tmp).unwrap();
        assert_eq!(result.extension().unwrap(), "apk");
        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn find_latest_backup_apk_selects_most_recent() {
        let tmp = std::env::temp_dir().join("iris-daemon-test-backup-multi");
        let _ = fs::remove_dir_all(&tmp);
        let _ = fs::create_dir_all(&tmp);
        let old_apk = tmp.join("Iris-old.apk");
        fs::write(&old_apk, b"old").unwrap();
        let new_apk = tmp.join("Iris-new.apk");
        fs::write(&new_apk, b"newer apk content").unwrap();
        let result = find_latest_backup_apk(&tmp).unwrap();
        assert!(result.extension().unwrap() == "apk");
        let _ = fs::remove_dir_all(&tmp);
    }
}
