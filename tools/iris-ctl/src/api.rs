use crate::config::Config;
use anyhow::Result;
use iris_common::api::IrisApi;
use reqwest::Client;

/// SSE는 timeout 없는 별도 클라이언트가 필요해서 `IrisApi`를 감싼다.
#[derive(Clone)]
pub struct TuiApi {
    inner: IrisApi,
    sse_client: Client,
}

impl TuiApi {
    pub fn new(config: &Config) -> Result<Self> {
        let transport = Some(config.server.transport.as_str());
        let inner = IrisApi::new_with_transport(config, transport)?;
        let sse_client =
            iris_common::api::build_http_client(config.server.url.as_str(), transport, None)?;
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
