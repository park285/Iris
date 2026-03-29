use crate::auth::{canonical_target, signed_headers};
use crate::config::IrisConnection;
use crate::models::{
    BridgeDiagnosticsResponse, HealthResponse, MemberActivityResponse, MemberListResponse,
    ReplyAcceptedResponse, ReplyRequest, RoomInfoResponse, RoomListResponse, StatsResponse,
    ThreadListResponse,
};
use anyhow::Result;
use reqwest::Client;
use std::time::Duration;

#[derive(Clone)]
pub struct IrisApi {
    client: Client,
    base_url: String,
    token: String,
}

impl IrisApi {
    pub fn new(conn: &dyn IrisConnection) -> Result<Self> {
        let client = Client::builder().timeout(Duration::from_secs(10)).build()?;
        Ok(Self {
            client,
            base_url: conn.base_url().trim_end_matches('/').to_string(),
            token: conn.token().to_string(),
        })
    }

    /// 커스텀 timeout으로 생성. daemon의 짧은 health check timeout에 사용.
    pub fn with_timeout(conn: &dyn IrisConnection, timeout: Duration) -> Result<Self> {
        let client = Client::builder().timeout(timeout).build()?;
        Ok(Self {
            client,
            base_url: conn.base_url().trim_end_matches('/').to_string(),
            token: conn.token().to_string(),
        })
    }

    // --- health probes (daemon용) ---

    pub async fn health(&self) -> Result<HealthResponse> {
        Ok(self
            .signed_get("/health", &[])?
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }

    pub async fn ready(&self) -> Result<HealthResponse> {
        Ok(self
            .signed_get("/ready", &[])?
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }

    pub async fn bridge_diagnostics(&self) -> Result<BridgeDiagnosticsResponse> {
        Ok(self
            .signed_get("/diagnostics/bridge", &[])?
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }

    // --- rooms/members/stats (iris-ctl용, daemon에서도 사용 가능) ---

    pub async fn rooms(&self) -> Result<RoomListResponse> {
        Ok(self
            .signed_get("/rooms", &[])?
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }

    pub async fn members(&self, chat_id: i64) -> Result<MemberListResponse> {
        Ok(self
            .signed_get(&format!("/rooms/{chat_id}/members"), &[])?
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }

    pub async fn room_info(&self, chat_id: i64) -> Result<RoomInfoResponse> {
        Ok(self
            .signed_get(&format!("/rooms/{chat_id}/info"), &[])?
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }

    pub async fn stats(&self, chat_id: i64, period: &str, limit: i32) -> Result<StatsResponse> {
        let query = vec![
            ("period".to_string(), period.to_string()),
            ("limit".to_string(), limit.to_string()),
        ];
        Ok(self
            .signed_get(&format!("/rooms/{chat_id}/stats"), &query)?
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }

    pub async fn member_activity(
        &self,
        chat_id: i64,
        user_id: i64,
        period: &str,
    ) -> Result<MemberActivityResponse> {
        let query = vec![("period".to_string(), period.to_string())];
        Ok(self
            .signed_get(
                &format!("/rooms/{chat_id}/members/{user_id}/activity"),
                &query,
            )?
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }

    pub async fn send_reply(&self, req: &ReplyRequest) -> Result<ReplyAcceptedResponse> {
        let resp = self.signed_post_json("/reply", req)?.send().await?;
        if resp.status().is_success() {
            Ok(resp.json().await?)
        } else {
            let status = resp.status();
            let error: crate::models::ErrorResponse = resp.json().await.unwrap_or_else(|_| {
                crate::models::ErrorResponse {
                    status: false,
                    message: format!("HTTP {status}"),
                }
            });
            anyhow::bail!("[{status}] {}", error.message)
        }
    }

    pub async fn list_threads(&self, chat_id: i64) -> Result<ThreadListResponse> {
        Ok(self
            .signed_get(&format!("/rooms/{chat_id}/threads"), &[])?
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }

    // --- SSE 지원 (iris-ctl용) ---

    pub fn sse_url(&self) -> String {
        format!("{}/events/stream", self.base_url)
    }

    pub fn sse_request(&self) -> Result<reqwest::RequestBuilder> {
        let target = canonical_target("/events/stream", &[]);
        Ok(self.client.get(self.sse_url()).headers(signed_headers(
            &self.token,
            "GET",
            &target,
            b"",
        )?))
    }

    /// SSE 전용: timeout 없는 클라이언트로 SSE request를 생성한다.
    pub fn sse_request_with_client(&self, sse_client: &Client) -> Result<reqwest::RequestBuilder> {
        let target = canonical_target("/events/stream", &[]);
        Ok(sse_client.get(self.sse_url()).headers(signed_headers(
            &self.token,
            "GET",
            &target,
            b"",
        )?))
    }

    pub fn base_url(&self) -> &str {
        &self.base_url
    }

    pub fn token(&self) -> &str {
        &self.token
    }

    fn signed_get(
        &self,
        path: &str,
        query: &[(String, String)],
    ) -> Result<reqwest::RequestBuilder> {
        let target = canonical_target(path, query);
        Ok(self
            .client
            .get(format!("{}{}", self.base_url, target))
            .headers(signed_headers(&self.token, "GET", &target, b"")?))
    }

    fn signed_post_json<T: serde::Serialize>(
        &self,
        path: &str,
        body: &T,
    ) -> Result<reqwest::RequestBuilder> {
        let body_bytes = serde_json::to_vec(body)?;
        let target = canonical_target(path, &[]);
        Ok(self
            .client
            .post(format!("{}{}", self.base_url, target))
            .headers(signed_headers(&self.token, "POST", &target, &body_bytes)?)
            .header("Content-Type", "application/json")
            .body(body_bytes))
    }
}
