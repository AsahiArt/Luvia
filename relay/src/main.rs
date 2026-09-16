use std::sync::Arc;

use luvia_relay::{router, AppState, Config};

#[tokio::main]
async fn main() {
    let config = match Config::from_env() {
        Ok(config) => config,
        Err(error) => {
            eprintln!("luvia-relay: {error}");
            std::process::exit(1);
        }
    };
    let listen = config.listen.clone();
    let state = match AppState::new(config) {
        Ok(state) => Arc::new(state),
        Err(error) => {
            eprintln!("luvia-relay: {error}");
            std::process::exit(1);
        }
    };
    let listener = match tokio::net::TcpListener::bind(&listen).await {
        Ok(listener) => listener,
        Err(error) => {
            eprintln!("luvia-relay: bind {listen}: {error}");
            std::process::exit(1);
        }
    };
    eprintln!("luvia-relay: listening on {listen}");
    if let Err(error) = axum::serve(listener, router(state))
        .with_graceful_shutdown(shutdown())
        .await
    {
        eprintln!("luvia-relay: {error}");
        std::process::exit(1);
    }
}

async fn shutdown() {
    let _ = tokio::signal::ctrl_c().await;
}
