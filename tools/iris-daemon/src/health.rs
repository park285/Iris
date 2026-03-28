use iris_common::api::IrisApi;

pub async fn probe_all(_api: &IrisApi) -> String {
    "status: unknown (not implemented)".to_string()
}
