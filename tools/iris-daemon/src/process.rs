use crate::adb::Adb;
use crate::config::DaemonConfig;
use anyhow::{Result, bail};
use std::time::Duration;

const IRIS_PROCESS_NAME: &str = "party.qwer.iris.Main";
const KAKAOTALK_PACKAGE: &str = "com.kakao.talk";
const SIGTERM_WAIT_SECS: u64 = 5;

pub async fn iris_pid(adb: &Adb) -> Option<u32> {
    let output = adb
        .shell(&format!(
            "ps -ef | grep '{IRIS_PROCESS_NAME}' | grep -v grep"
        ))
        .await
        .ok()?;
    for line in output.lines() {
        if line.contains(IRIS_PROCESS_NAME) && !line.contains("grep") {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 2 {
                return parts[1].parse().ok();
            }
        }
    }
    None
}

pub async fn stop_iris(adb: &Adb) -> Result<()> {
    let pid = match iris_pid(adb).await {
        Some(pid) => pid,
        None => {
            tracing::info!("Iris 프로세스 미실행, 종료 불필요");
            return Ok(());
        }
    };
    tracing::info!(pid = pid, "Iris 프로세스 종료 시도 (SIGTERM)");
    let _ = adb.shell(&format!("kill {pid}")).await;
    tokio::time::sleep(Duration::from_secs(SIGTERM_WAIT_SECS)).await;
    if iris_pid(adb).await.is_some() {
        tracing::warn!(pid = pid, "SIGTERM 무응답, SIGKILL 전송");
        let _ = adb.shell(&format!("kill -9 {pid}")).await;
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    if iris_pid(adb).await.is_some() {
        bail!("Iris 프로세스 종료 실패 (PID: {pid})");
    }
    tracing::info!(pid = pid, "Iris 프로세스 종료 완료");
    Ok(())
}

pub async fn start_iris(adb: &Adb, cfg: &DaemonConfig) -> Result<()> {
    if iris_pid(adb).await.is_some() {
        tracing::warn!("Iris 프로세스 이미 실행 중, 시작 건너뜀");
        return Ok(());
    }
    let health_url = &cfg.iris.health_url;
    let token = &cfg.iris.shared_token;
    let cmd = format!(
        "nohup app_process -Djava.class.path={apk} / {main} \
         --health-url={url} --shared-token={tok} \
         > /dev/null 2>&1 &",
        apk = cfg.init.apk_dest,
        main = IRIS_PROCESS_NAME,
        url = health_url,
        tok = token,
    );
    adb.shell(&cmd).await?;
    tracing::info!("Iris 프로세스 시작 명령 전송");
    for _ in 0..10 {
        tokio::time::sleep(Duration::from_secs(1)).await;
        if iris_pid(adb).await.is_some() {
            tracing::info!("Iris 프로세스 시작 확인");
            return Ok(());
        }
    }
    bail!("Iris 프로세스 시작 실패 (10초 timeout)");
}

pub async fn restart_iris(adb: &Adb, cfg: &DaemonConfig) -> Result<()> {
    tracing::info!("Iris 프로세스 재시작 시작");
    stop_iris(adb).await?;
    start_iris(adb, cfg).await?;
    tracing::info!("Iris 프로세스 재시작 완료");
    Ok(())
}

pub async fn kakaotalk_alive(adb: &Adb) -> bool {
    adb.shell(&format!("pidof {KAKAOTALK_PACKAGE}"))
        .await
        .map(|output| !output.is_empty())
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constants_are_correct() {
        assert_eq!(IRIS_PROCESS_NAME, "party.qwer.iris.Main");
        assert_eq!(KAKAOTALK_PACKAGE, "com.kakao.talk");
        assert_eq!(SIGTERM_WAIT_SECS, 5);
    }
}
