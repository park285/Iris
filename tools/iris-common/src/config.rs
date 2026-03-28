/// Iris 서버 연결에 필요한 최소 정보.
/// iris-ctl Config, iris-daemon DaemonConfig 모두 이 trait을 구현한다.
pub trait IrisConnection {
    fn base_url(&self) -> &str;
    fn token(&self) -> &str;
}

/// 단순 연결 정보 구조체. 테스트나 독립 사용 시 편의용.
#[derive(Clone, Debug)]
pub struct SimpleConnection {
    pub url: String,
    pub token: String,
}

impl IrisConnection for SimpleConnection {
    fn base_url(&self) -> &str {
        &self.url
    }

    fn token(&self) -> &str {
        &self.token
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn simple_connection_implements_trait() {
        let conn = SimpleConnection {
            url: "http://localhost:3000".to_string(),
            token: "secret".to_string(),
        };
        assert_eq!(conn.base_url(), "http://localhost:3000");
        assert_eq!(conn.token(), "secret");
    }
}
