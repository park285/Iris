#![allow(dead_code)]

mod adb;
mod alert;
mod config;
mod config_sync;
mod daemon;
mod health;
mod init;
mod launch_spec;
mod process;
mod rollback;
mod state;

use anyhow::Result;
use clap::{Parser, Subcommand};
use std::path::PathBuf;
use tracing_subscriber::{EnvFilter, layer::SubscriberExt, util::SubscriberInitExt};

#[derive(Parser)]
#[command(name = "iris-daemon", about = "Iris 프로세스 관리 데몬")]
struct Cli {
    #[arg(long, short = 'c', global = true)]
    config: Option<PathBuf>,
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    Init {
        #[arg(long, value_enum, default_value = "force-restart")]
        mode: init::InitMode,
    },
    Watch,
    Status,
}

fn init_tracing() {
    let fmt_layer = tracing_subscriber::fmt::layer()
        .with_target(true)
        .with_thread_ids(false);
    let journald_layer = tracing_journald::layer().ok();
    tracing_subscriber::registry()
        .with(
            EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| EnvFilter::new("iris_daemon=info")),
        )
        .with(fmt_layer)
        .with(journald_layer)
        .init();
}

#[tokio::main]
async fn main() -> Result<()> {
    init_tracing();
    let cli = Cli::parse();
    let cfg = config::DaemonConfig::load(cli.config.as_deref())?;
    tracing::info!(health_url = %cfg.iris.health_url, device = %cfg.adb.device, "iris-daemon 시작");
    match cli.command {
        Command::Init { mode } => init::run_init(&cfg, mode).await,
        Command::Watch => daemon::run_watch(cfg).await,
        Command::Status => {
            let api = iris_common::api::IrisApi::with_timeout(
                &cfg,
                std::time::Duration::from_secs(cfg.watch.curl_timeout_secs),
            )?;
            let result = health::probe_all(&api).await;
            println!("{result}");
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::CommandFactory;

    #[test]
    fn init_without_args_uses_force_restart_mode_for_backward_compatibility() {
        let cli = Cli::try_parse_from(["iris-daemon", "init"]).expect("init should parse");

        match cli.command {
            Command::Init { mode } => assert_eq!(mode, init::InitMode::ForceRestart),
            _ => panic!("expected init command"),
        }
    }

    #[test]
    fn init_accepts_explicit_force_restart_mode_for_legacy_scripts() {
        let cli = Cli::try_parse_from(["iris-daemon", "init", "--mode", "force-restart"])
            .expect("init --mode force-restart should parse");

        match cli.command {
            Command::Init { mode } => assert_eq!(mode, init::InitMode::ForceRestart),
            _ => panic!("expected init command"),
        }
    }

    #[test]
    fn init_accepts_if_missing_mode_for_systemd_exec_start_pre() {
        let cli = Cli::try_parse_from(["iris-daemon", "init", "--mode", "if-missing"])
            .expect("init --mode if-missing should parse");

        match cli.command {
            Command::Init { mode } => assert_eq!(mode, init::InitMode::IfMissing),
            _ => panic!("expected init command"),
        }
    }

    #[test]
    fn init_help_exposes_force_restart_and_if_missing_modes() {
        let mut command = Cli::command();
        let init = command
            .find_subcommand_mut("init")
            .expect("init subcommand");
        let help = init.render_long_help().to_string();

        assert!(help.contains("force-restart"));
        assert!(help.contains("if-missing"));
        assert!(!help.contains("full"));
    }
}
