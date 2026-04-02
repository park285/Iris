/// iris-ctl과 iris-daemon이 각자의 Config로 구현하는 공통 연결 계약.
pub trait IrisConnection {
    fn base_url(&self) -> &str;
    fn token(&self) -> &str;
}

/// 테스트·독립 실행용 최소 구현체.
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
