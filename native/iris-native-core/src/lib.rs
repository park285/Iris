pub mod decrypt;
pub mod errors;
pub mod ffi;
pub mod parsers;
pub mod routing;
pub mod webhook;

pub const VERSION: &str = concat!("iris-native-core:", env!("CARGO_PKG_VERSION"));
