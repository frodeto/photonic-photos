use std::sync::Mutex;

use tauri::path::BaseDirectory;
use tauri::{async_runtime, Manager, State};
use tauri_plugin_shell::process::{CommandChild, CommandEvent};
use tauri_plugin_shell::ShellExt;

/// Holds the port and auth token the Kotlin backend chose at startup
/// (parsed from its stdout handshake).
#[derive(Default)]
struct BackendState {
    port: Mutex<Option<u16>>,
    token: Mutex<Option<String>>,
}

/// The WebView calls this to discover where the backend is listening.
/// Returns null until the handshake line has been seen.
#[tauri::command]
fn get_backend_port(state: State<BackendState>) -> Option<u16> {
    *state.port.lock().unwrap()
}

/// The WebView calls this to learn the per-launch token it must send with every request.
/// Returns null until the handshake line has been seen.
#[tauri::command]
fn get_backend_token(state: State<BackendState>) -> Option<String> {
    state.token.lock().unwrap().clone()
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(BackendState::default())
        .setup(|app| {
            let handle = app.handle().clone();

            // Spawn the bundled Kotlin backend as a sidecar.
            let mut sidecar = app
                .shell()
                .sidecar("photonic-backend")
                .expect("failed to create `photonic-backend` sidecar command");

            // Resolve the bundled JRE, fat jar, and exiftool from the app's Resources
            // dir and hand them to the launcher script via the environment. Each is
            // best-effort: the launcher falls back to `java`/relative paths and the
            // backend falls back to `exiftool` on PATH if a var is absent.
            let resolver = app.path();
            if let Ok(java) = resolver.resolve("resources/runtime/bin/java", BaseDirectory::Resource)
            {
                sidecar = sidecar.env("PHOTONIC_JAVA", java.to_string_lossy().to_string());
            }
            if let Ok(jar) =
                resolver.resolve("resources/photonic-backend-0.1.0.jar", BaseDirectory::Resource)
            {
                sidecar = sidecar.env("PHOTONIC_JAR", jar.to_string_lossy().to_string());
            }
            if let Ok(exiftool) =
                resolver.resolve("resources/exiftool/exiftool", BaseDirectory::Resource)
            {
                sidecar = sidecar.env("PHOTONIC_EXIFTOOL", exiftool.to_string_lossy().to_string());
            }

            let (mut rx, child) = sidecar.spawn().expect("failed to spawn backend sidecar");

            // Keep the child handle alive for the app's lifetime so the process isn't reaped early.
            app.manage(Mutex::new(Some::<CommandChild>(child)));

            // Read the backend's stdout; capture the `PHOTONIC_PORT=` / `PHOTONIC_TOKEN=`
            // handshake lines and store them.
            async_runtime::spawn(async move {
                while let Some(event) = rx.recv().await {
                    if let CommandEvent::Stdout(bytes) = event {
                        let line = String::from_utf8_lossy(&bytes);
                        let trimmed = line.trim();
                        if let Some(rest) = trimmed.strip_prefix("PHOTONIC_PORT=") {
                            if let Ok(port) = rest.trim().parse::<u16>() {
                                let state: State<BackendState> = handle.state();
                                *state.port.lock().unwrap() = Some(port);
                            }
                        } else if let Some(rest) = trimmed.strip_prefix("PHOTONIC_TOKEN=") {
                            let state: State<BackendState> = handle.state();
                            *state.token.lock().unwrap() = Some(rest.trim().to_string());
                        }
                    }
                }
            });

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![get_backend_port, get_backend_token])
        .run(tauri::generate_context!())
        .expect("error while running Photonic Photos");
}
