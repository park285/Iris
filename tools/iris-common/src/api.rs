use crate::auth::{canonical_target, signed_headers};
use crate::config::IrisConnection;
use crate::models::{
    BridgeDiagnosticsResponse, HealthResponse, MemberActivityResponse, MemberListResponse,
    RecentMessagesCursorRequest, RecentMessagesRequest, RecentMessagesResponse,
    ReplyAcceptedResponse, ReplyRequest, RoomEventRecord, RoomInfoResponse, RoomListResponse,
    StatsResponse, ThreadListResponse,
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
        Self::new_with_transport(conn, None)
    }

    pub fn new_with_transport(conn: &dyn IrisConnection, transport: Option<&str>) -> Result<Self> {
        let client = build_http_client(conn.base_url(), transport, Some(Duration::from_secs(10)))?;
        Ok(Self {
            client,
            base_url: conn.base_url().trim_end_matches('/').to_string(),
            token: conn.token().to_string(),
        })
    }

    /// daemon health check처럼 짧은 timeout이 필요할 때.
    pub fn with_timeout(conn: &dyn IrisConnection, timeout: Duration) -> Result<Self> {
        let client = build_http_client(conn.base_url(), None, Some(timeout))?;
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

    pub async fn recent_messages(
        &self,
        chat_id: i64,
        limit: i32,
    ) -> Result<RecentMessagesResponse> {
        Ok(self
            .signed_post_json(
                "/query/recent-messages",
                &RecentMessagesRequest { chat_id, limit },
            )?
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }

    pub async fn recent_messages_with_cursor(
        &self,
        chat_id: i64,
        limit: i32,
        after_id: Option<i64>,
        before_id: Option<i64>,
        thread_id: Option<i64>,
    ) -> Result<RecentMessagesResponse> {
        Ok(self
            .signed_post_json(
                "/query/recent-messages",
                &RecentMessagesCursorRequest {
                    chat_id,
                    limit,
                    after_id,
                    before_id,
                    thread_id,
                },
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
            let error: crate::models::ErrorResponse =
                resp.json()
                    .await
                    .unwrap_or_else(|_| crate::models::ErrorResponse {
                        status: false,
                        message: format!("HTTP {status}"),
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

    pub async fn get_room_events(
        &self,
        chat_id: i64,
        limit: u32,
        after: i64,
    ) -> Result<Vec<RoomEventRecord>> {
        let query = vec![
            ("limit".to_string(), limit.to_string()),
            ("after".to_string(), after.to_string()),
        ];
        Ok(self
            .signed_get(&format!("/rooms/{chat_id}/events"), &query)?
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

pub fn build_http_client(
    base_url: &str,
    transport: Option<&str>,
    timeout: Option<Duration>,
) -> Result<Client> {
    let mut builder = Client::builder();
    if let Some(value) = timeout {
        builder = builder.timeout(value);
    }
    if should_use_h2c(base_url, transport) {
        builder = builder.http2_prior_knowledge();
    }
    Ok(builder.build()?)
}

fn should_use_h2c(base_url: &str, transport: Option<&str>) -> bool {
    let normalized = transport.unwrap_or_default().trim().to_ascii_lowercase();
    match normalized.as_str() {
        "http1" | "http" | "http/1.1" => false,
        _ => base_url.starts_with("http://"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::SimpleConnection;
    use tokio::io::AsyncReadExt;
    use tokio::net::TcpListener;

    #[tokio::test]
    #[cfg_attr(miri, ignore)]
    async fn http_base_url_defaults_to_h2c_prior_knowledge() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let capture = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut buf = [0_u8; 24];
            let n = socket.read(&mut buf).await.unwrap();
            buf[..n].to_vec()
        });
        let conn = SimpleConnection {
            url: format!("http://{addr}"),
            token: "secret".to_string(),
        };

        let api = IrisApi::new(&conn).unwrap();
        let _ = api.health().await;

        let captured = capture.await.unwrap();
        assert!(String::from_utf8_lossy(&captured).starts_with("PRI * HTTP/2.0"));
    }

    #[tokio::test]
    #[cfg_attr(miri, ignore)]
    async fn explicit_http1_transport_uses_http1_request_line() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let capture = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut buf = [0_u8; 32];
            let n = socket.read(&mut buf).await.unwrap();
            buf[..n].to_vec()
        });
        let conn = SimpleConnection {
            url: format!("http://{addr}"),
            token: "secret".to_string(),
        };

        let api = IrisApi::new_with_transport(&conn, Some("http1")).unwrap();
        let _ = api.health().await;

        let captured = capture.await.unwrap();
        assert!(String::from_utf8_lossy(&captured).starts_with("GET /health HTTP/1.1"));
    }
}
