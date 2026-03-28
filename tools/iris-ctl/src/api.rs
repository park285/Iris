use crate::config::Config;
use anyhow::Result;
use iris_common::api::IrisApi;
use reqwest::Client;

/// TUI 전용 API 래퍼. SSE용 timeout-free 클라이언트를 추가로 관리한다.
#[derive(Clone)]
pub struct TuiApi {
    inner: IrisApi,
    sse_client: Client,
}

impl TuiApi {
    pub fn new(config: &Config) -> Result<Self> {
        let inner = IrisApi::new(config)?;
        let sse_client = Client::builder().build()?;
        Ok(Self { inner, sse_client })
    }

    pub fn sse_request(&self) -> Result<reqwest::RequestBuilder> {
        self.inner.sse_request_with_client(&self.sse_client)
    }
}

impl std::ops::Deref for TuiApi {
    type Target = IrisApi;
    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}
