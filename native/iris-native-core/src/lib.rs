pub mod decrypt;
pub mod errors;
pub mod ffi;
pub mod ingress;
pub mod parsers;
pub mod routing;
pub mod webhook;

pub const VERSION: &str = concat!("iris-native-core:", env!("CARGO_PKG_VERSION"));
pub const NATIVE_CORE_SCHEMA_VERSION: u32 = 1;
pub const BUILD_PROFILE: &str = if cfg!(debug_assertions) {
    "debug"
} else {
    "release"
};

#[must_use]
pub fn native_core_identity() -> String {
    format!(
        "{VERSION};schema={NATIVE_CORE_SCHEMA_VERSION};profile={BUILD_PROFILE};target={}",
        native_core_target()
    )
}

fn native_core_target() -> String {
    option_env!("TARGET").map_or_else(
        || {
            format!(
                "{arch}-{os}",
                arch = std::env::consts::ARCH,
                os = std::env::consts::OS
            )
        },
        str::to_owned,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn native_core_identity_preserves_prefix_and_exposes_stable_build_fields() {
        let identity = native_core_identity();

        assert!(identity.starts_with(VERSION));
        assert!(identity.starts_with("iris-native-core:"));
        assert!(identity.contains(&format!(";schema={NATIVE_CORE_SCHEMA_VERSION};")));
        assert!(identity.contains(";profile="));
        assert!(identity.contains(";target="));
    }
}
