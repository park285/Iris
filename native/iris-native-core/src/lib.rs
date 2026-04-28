pub mod decrypt;
pub mod errors;
pub mod ffi;

pub const VERSION: &str = concat!("iris-native-core:", env!("CARGO_PKG_VERSION"));
