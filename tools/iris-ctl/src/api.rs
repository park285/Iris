use crate::auth::{canonical_target, signed_headers};
use crate::config::Config;
use crate::models::*;
use anyhow::Result;
use reqwest::Client;

#[derive(Clone)]
pub struct IrisApi {
    client: Client,
    sse_client: Client,
    base_url: String,
    token: String,
}

#[allow(dead_code)]
impl IrisApi {
    pub fn new(config: &Config) -> Result<Self> {
        let client = Client::builder()
            .timeout(std::time::Duration::from_secs(10))
            .build()?;
        let sse_client = Client::builder().build()?;
        Ok(Self {
            client,
            sse_client,
            base_url: config.base_url().trim_end_matches('/').to_string(),
            token: config.token().to_string(),
        })
    }
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
    pub fn sse_url(&self) -> String {
        format!("{}/events/stream", self.base_url)
    }
    pub fn client(&self) -> &Client {
        &self.client
    }
    pub fn sse_client(&self) -> &Client {
        &self.sse_client
    }

    pub fn sse_request(&self) -> Result<reqwest::RequestBuilder> {
        let target = canonical_target("/events/stream", &[]);
        Ok(self.sse_client.get(self.sse_url()).headers(signed_headers(
            &self.token,
            "GET",
            &target,
            b"",
        )?))
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
}
