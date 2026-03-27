use anyhow::Result;
use reqwest::{Client, header};
use crate::config::Config;
use crate::models::*;

#[derive(Clone)]
pub struct IrisApi { client: Client, base_url: String }

impl IrisApi {
    pub fn new(config: &Config) -> Result<Self> {
        let mut headers = header::HeaderMap::new();
        headers.insert("X-Bot-Token", header::HeaderValue::from_str(config.token())?);
        let client = Client::builder().default_headers(headers)
            .timeout(std::time::Duration::from_secs(10)).build()?;
        Ok(Self { client, base_url: config.base_url().trim_end_matches('/').to_string() })
    }
    pub async fn rooms(&self) -> Result<RoomListResponse> {
        Ok(self.client.get(format!("{}/rooms", self.base_url)).send().await?.json().await?)
    }
    pub async fn members(&self, chat_id: i64) -> Result<MemberListResponse> {
        Ok(self.client.get(format!("{}/rooms/{}/members", self.base_url, chat_id)).send().await?.json().await?)
    }
    pub async fn room_info(&self, chat_id: i64) -> Result<RoomInfoResponse> {
        Ok(self.client.get(format!("{}/rooms/{}/info", self.base_url, chat_id)).send().await?.json().await?)
    }
    pub async fn stats(&self, chat_id: i64, period: &str, limit: i32) -> Result<StatsResponse> {
        Ok(self.client.get(format!("{}/rooms/{}/stats", self.base_url, chat_id))
            .query(&[("period", period), ("limit", &limit.to_string())])
            .send().await?.json().await?)
    }
    pub async fn member_activity(&self, chat_id: i64, user_id: i64, period: &str) -> Result<MemberActivityResponse> {
        Ok(self.client.get(format!("{}/rooms/{}/members/{}/activity", self.base_url, chat_id, user_id))
            .query(&[("period", period)]).send().await?.json().await?)
    }
    pub fn sse_url(&self) -> String { format!("{}/events/stream", self.base_url) }
    pub fn client(&self) -> &Client { &self.client }
}
