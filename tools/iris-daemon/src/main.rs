#![allow(dead_code)]

mod config;
mod health;
mod state;
mod process;
mod adb;
mod daemon;
mod init;
mod config_sync;
mod alert;
mod rollback;

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
    Init,
    Watch,
    Status,
}

fn init_tracing() {
    let fmt_layer = tracing_subscriber::fmt::layer()
        .with_target(true)
        .with_thread_ids(false);
    let journald_layer = tracing_journald::layer().ok();
    tracing_subscriber::registry()
        .with(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("iris_daemon=info")))
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
        Command::Init => init::run_init(&cfg).await,
        Command::Watch => daemon::run_watch(cfg).await,
        Command::Status => {
            let api = iris_common::api::IrisApi::with_timeout(&cfg, std::time::Duration::from_secs(cfg.watch.curl_timeout_secs))?;
            let result = health::probe_all(&api).await;
            println!("{result}");
            Ok(())
        }
    }
}
