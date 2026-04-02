use std::process::Command;

const DEFAULT_IRIS_CONTROL_PATH: &str = "/root/work/Iris/iris_control";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LaunchSpec {
    pub program: String,
    pub args: Vec<String>,
}

impl LaunchSpec {
    pub fn iris_control(subcommand: &str) -> Self {
        Self {
            program: iris_control_path(),
            args: vec![subcommand.to_string()],
        }
    }

    pub fn to_command(&self) -> Command {
        let mut command = Command::new(&self.program);
        command.args(&self.args);
        command
    }
}

pub fn iris_control_path() -> String {
    std::env::var("IRIS_CONTROL_PATH").unwrap_or_else(|_| DEFAULT_IRIS_CONTROL_PATH.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn iris_control_path_defaults_to_expected_binary() {
        assert_eq!(iris_control_path(), DEFAULT_IRIS_CONTROL_PATH);
    }

    #[test]
    fn launch_spec_captures_iris_control_subcommand() {
        let spec = LaunchSpec::iris_control("start");

        assert_eq!(spec.program, DEFAULT_IRIS_CONTROL_PATH);
        assert_eq!(spec.args, vec!["start".to_string()]);
    }
}
