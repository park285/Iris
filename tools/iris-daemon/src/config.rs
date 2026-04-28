use anyhow::{Context, Result};
use iris_common::config::IrisConnection;
use serde::Deserialize;
use std::path::{Path, PathBuf};

#[derive(Deserialize, Clone, Debug, Default)]
pub struct DaemonConfig {
    #[serde(default)]
    pub iris: IrisConfig,
    #[serde(default)]
    pub adb: AdbConfig,
    #[serde(default)]
    pub watch: WatchConfig,
    #[serde(default)]
    pub alert: AlertConfig,
    #[serde(default)]
    pub rollback: RollbackConfig,
    #[serde(default)]
    pub init: InitConfig,
}

#[derive(Deserialize, Clone, Debug)]
pub struct IrisConfig {
    #[serde(default = "default_health_url")]
    pub health_url: String,
    #[serde(default)]
    pub shared_token: String,
}

impl Default for IrisConfig {
    fn default() -> Self {
        Self {
            health_url: default_health_url(),
            shared_token: String::new(),
        }
    }
}

fn default_health_url() -> String {
    "http://localhost:3000".to_string()
}

#[derive(Deserialize, Clone, Debug)]
pub struct AdbConfig {
    #[serde(default = "default_device")]
    pub device: String,
}

impl Default for AdbConfig {
    fn default() -> Self {
        Self {
            device: default_device(),
        }
    }
}

fn default_device() -> String {
    "192.168.219.201:5555".to_string()
}

#[derive(Deserialize, Clone, Debug)]
pub struct WatchConfig {
    #[serde(default = "default_check_interval")]
    pub check_interval_secs: u64,
    #[serde(default = "default_health_fail_threshold")]
    pub health_fail_threshold: u32,
    #[serde(default = "default_readiness_fail_threshold")]
    pub readiness_fail_threshold: u32,
    #[serde(default = "default_curl_timeout")]
    pub curl_timeout_secs: u64,
    #[serde(default = "default_config_check_every")]
    pub config_check_every: u32,
}

impl Default for WatchConfig {
    fn default() -> Self {
        Self {
            check_interval_secs: default_check_interval(),
            health_fail_threshold: default_health_fail_threshold(),
            readiness_fail_threshold: default_readiness_fail_threshold(),
            curl_timeout_secs: default_curl_timeout(),
            config_check_every: default_config_check_every(),
        }
    }
}

const fn default_check_interval() -> u64 {
    30
}

const fn default_health_fail_threshold() -> u32 {
    2
}

const fn default_readiness_fail_threshold() -> u32 {
    4
}

const fn default_curl_timeout() -> u64 {
    3
}

const fn default_config_check_every() -> u32 {
    10
}

#[derive(Deserialize, Clone, Debug, Default)]
pub struct AlertConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub webhook_url: String,
}

#[derive(Deserialize, Clone, Debug)]
pub struct RollbackConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_max_consecutive_failures")]
    pub max_consecutive_failures: u32,
    #[serde(default = "default_backup_dir")]
    pub backup_dir: String,
}

impl Default for RollbackConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            max_consecutive_failures: default_max_consecutive_failures(),
            backup_dir: default_backup_dir(),
        }
    }
}

const fn default_max_consecutive_failures() -> u32 {
    5
}

fn default_backup_dir() -> String {
    "/root/work/Iris/.backup-latest".to_string()
}

#[derive(Deserialize, Clone, Debug)]
pub struct InitConfig {
    #[serde(default = "default_phantom_killer_disable")]
    pub phantom_killer_disable: bool,
    #[serde(default = "default_boot_timeout")]
    pub boot_timeout_secs: u64,
    #[serde(default = "default_config_template")]
    pub config_template: String,
    #[serde(default = "default_config_dest")]
    pub config_dest: String,
    #[serde(default = "default_apk_src")]
    pub apk_src: String,
    #[serde(default = "default_apk_dest")]
    pub apk_dest: String,
    #[serde(default = "default_native_lib_src")]
    pub native_lib_src: String,
    #[serde(default = "default_native_lib_dest")]
    pub native_lib_dest: String,
}

impl Default for InitConfig {
    fn default() -> Self {
        Self {
            phantom_killer_disable: default_phantom_killer_disable(),
            boot_timeout_secs: default_boot_timeout(),
            config_template: default_config_template(),
            config_dest: default_config_dest(),
            apk_src: default_apk_src(),
            apk_dest: default_apk_dest(),
            native_lib_src: default_native_lib_src(),
            native_lib_dest: default_native_lib_dest(),
        }
    }
}

const fn default_phantom_killer_disable() -> bool {
    true
}

const fn default_boot_timeout() -> u64 {
    120
}

fn default_config_template() -> String {
    "/root/work/arm-iris-runtime/configs/iris/config.json".to_string()
}

fn default_config_dest() -> String {
    "/data/iris/config.json".to_string()
}

fn default_apk_src() -> String {
    "/root/work/Iris/Iris.apk".to_string()
}

fn default_apk_dest() -> String {
    "/data/local/tmp/Iris.apk".to_string()
}

fn default_native_lib_src() -> String {
    "/root/work/Iris/output/libiris_native_core.so".to_string()
}

fn default_native_lib_dest() -> String {
    "/data/iris/lib/libiris_native_core.so".to_string()
}

impl IrisConnection for DaemonConfig {
    fn base_url(&self) -> &str {
        &self.iris.health_url
    }

    fn token(&self) -> &str {
        &self.iris.shared_token
    }
}

impl DaemonConfig {
    pub fn load(path: Option<&Path>) -> Result<Self> {
        let config_path = path.map_or_else(default_config_path, PathBuf::from);
        let mut config: Self = if config_path.exists() {
            let content = std::fs::read_to_string(&config_path)
                .with_context(|| format!("설정 파일 읽기 실패: {}", config_path.display()))?;
            toml::from_str(&content)
                .with_context(|| format!("설정 파일 파싱 실패: {}", config_path.display()))?
        } else {
            Self::default()
        };
        apply_env_overrides(&mut config);
        Ok(config)
    }
}

fn apply_env_overrides(config: &mut DaemonConfig) {
    let env = std::env::vars().collect::<std::collections::HashMap<_, _>>();
    apply_env_overrides_from(config, &env);
}

fn apply_env_overrides_from(
    config: &mut DaemonConfig,
    env: &std::collections::HashMap<String, String>,
) {
    if let Some(url) = env.get("IRIS_HEALTH_URL") {
        config.iris.health_url.clone_from(url);
    }
    if let Some(token) = env.get("IRIS_SHARED_TOKEN") {
        config.iris.shared_token.clone_from(token);
    }
    if let Some(device) = env.get("IRIS_DEVICE") {
        config.adb.device.clone_from(device);
    }
    if let Some(apk_src) = env.get("IRIS_APK_SRC") {
        config.init.apk_src.clone_from(apk_src);
    }
    if let Some(native_lib_src) = env.get("IRIS_NATIVE_LIB_SRC") {
        config.init.native_lib_src.clone_from(native_lib_src);
    }
    if let Some(native_lib_dest) = env.get("IRIS_NATIVE_LIB_PATH") {
        config.init.native_lib_dest.clone_from(native_lib_dest);
    }
}

fn default_config_path() -> PathBuf {
    PathBuf::from("/etc/iris-daemon/config.toml")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn full_toml_fixture() -> &'static str {
        r#"
[iris]
health_url = "http://10.0.0.1:3000"
shared_token = "tok"

[adb]
device = "10.0.0.2:5555"

[watch]
check_interval_secs = 15
health_fail_threshold = 3
readiness_fail_threshold = 5
curl_timeout_secs = 5
config_check_every = 5

[alert]
enabled = true
webhook_url = "http://hooks.example.com/iris"

[rollback]
enabled = true
max_consecutive_failures = 3
backup_dir = "/opt/backup"

[init]
phantom_killer_disable = false
boot_timeout_secs = 60
config_template = "/etc/iris/template.json"
config_dest = "/data/config.json"
apk_src = "/root/work/Iris/Iris.apk"
apk_dest = "/data/Iris.apk"
"#
    }

    fn assert_watch_config(config: &DaemonConfig) {
        assert_eq!(config.watch.check_interval_secs, 15);
        assert_eq!(config.watch.health_fail_threshold, 3);
        assert_eq!(config.watch.readiness_fail_threshold, 5);
    }

    fn assert_alert_config(config: &DaemonConfig) {
        assert!(config.alert.enabled);
        assert_eq!(config.alert.webhook_url, "http://hooks.example.com/iris");
    }

    fn assert_rollback_and_init_config(config: &DaemonConfig) {
        assert!(config.rollback.enabled);
        assert_eq!(config.rollback.max_consecutive_failures, 3);
        assert!(!config.init.phantom_killer_disable);
        assert_eq!(config.init.boot_timeout_secs, 60);
        assert_eq!(config.init.apk_src, "/root/work/Iris/Iris.apk");
    }

    #[test]
    fn default_config_has_expected_watch_values() {
        let config = DaemonConfig::default();

        assert_eq!(config.iris.health_url, "http://localhost:3000");
        assert_eq!(config.watch.check_interval_secs, 30);
        assert_eq!(config.watch.health_fail_threshold, 2);
        assert_eq!(config.watch.readiness_fail_threshold, 4);
    }

    #[test]
    fn default_config_has_expected_runtime_paths() {
        let config = DaemonConfig::default();

        assert_eq!(config.init.apk_src, "/root/work/Iris/Iris.apk");
        assert_eq!(
            config.init.native_lib_src,
            "/root/work/Iris/output/libiris_native_core.so"
        );
        assert_eq!(
            config.init.native_lib_dest,
            "/data/iris/lib/libiris_native_core.so"
        );
        assert_eq!(config.rollback.max_consecutive_failures, 5);
    }

    #[test]
    fn default_config_disables_optional_features() {
        let config = DaemonConfig::default();

        assert!(!config.alert.enabled);
        assert!(!config.rollback.enabled);
    }

    #[test]
    fn parse_minimal_toml() {
        let toml_str = r#"
[iris]
health_url = "http://10.0.0.1:3000"
shared_token = "test-token"

[adb]
device = "10.0.0.2:5555"
"#;
        let config: DaemonConfig = toml::from_str(toml_str).unwrap();
        assert_eq!(config.iris.health_url, "http://10.0.0.1:3000");
        assert_eq!(config.iris.shared_token, "test-token");
        assert_eq!(config.adb.device, "10.0.0.2:5555");
        assert_eq!(config.watch.check_interval_secs, 30);
        assert_eq!(config.watch.readiness_fail_threshold, 4);
    }

    #[test]
    fn parse_full_toml() {
        let config: DaemonConfig = toml::from_str(full_toml_fixture()).unwrap();

        assert_watch_config(&config);
        assert_alert_config(&config);
        assert_rollback_and_init_config(&config);
    }

    #[test]
    fn implements_iris_connection() {
        let config = DaemonConfig {
            iris: IrisConfig {
                health_url: "http://test:3000".to_string(),
                shared_token: "secret".to_string(),
            },
            ..DaemonConfig::default()
        };
        assert_eq!(config.base_url(), "http://test:3000");
        assert_eq!(config.token(), "secret");
    }

    #[test]
    fn env_override_updates_apk_source() {
        let mut config = DaemonConfig::default();
        let env = std::collections::HashMap::from([(
            String::from("IRIS_APK_SRC"),
            String::from("/tmp/Iris.apk"),
        )]);
        apply_env_overrides_from(&mut config, &env);

        assert_eq!(config.init.apk_src, "/tmp/Iris.apk");
    }

    #[test]
    fn env_overrides_native_library_paths() {
        let mut config = DaemonConfig::default();
        let env = std::collections::HashMap::from([
            (
                String::from("IRIS_NATIVE_LIB_SRC"),
                String::from("/tmp/libiris_native_core.so"),
            ),
            (
                String::from("IRIS_NATIVE_LIB_PATH"),
                String::from("/data/local/tmp/libiris_native_core.so"),
            ),
        ]);
        apply_env_overrides_from(&mut config, &env);

        assert_eq!(config.init.native_lib_src, "/tmp/libiris_native_core.so");
        assert_eq!(
            config.init.native_lib_dest,
            "/data/local/tmp/libiris_native_core.so"
        );
    }
}
