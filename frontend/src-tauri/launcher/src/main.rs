//! Windows sidecar launcher for Photonic Photos.
//!
//! Tauri's `externalBin` needs a real PE executable, and Windows has no `exec`, so the
//! macOS trick — a shell script that `exec`s java, making the sidecar child PID *be* the
//! JVM — can't carry over. This tiny launcher stands in for it:
//!
//!   * It resolves the bundled JRE + backend jar the same env-then-fallback way the macOS
//!     launcher script does (`PHOTONIC_JAVA` / `PHOTONIC_JAR`, set by the Rust shell in
//!     `../../src/lib.rs`), falling back to paths beside this exe, then `java` on PATH.
//!   * It spawns `java.exe … -jar <jar>` with **inherited** stdio. That is load-bearing:
//!     the backend's `PHOTONIC_PORT=` / `PHOTONIC_TOKEN=` handshake flows straight up
//!     Tauri's pipe, and the JVM's stdin *is* Tauri's pipe, so the backend's existing
//!     stdin-EOF watchdog (`PHOTONIC_WATCH_STDIN=1`) still fires if the shell crashes.
//!     The launcher itself writes nothing to stdout.
//!   * It ties the JVM's lifetime to its own with a **Job Object** configured
//!     `KILL_ON_JOB_CLOSE`. On a normal quit Tauri kills *this* launcher (the PID it holds);
//!     closing our leaked job handle then kills java.exe synchronously — no orphaned JVM,
//!     no lingering console. The stdin-EOF watchdog remains the fallback for the crash path.
//!
//! Built with `CREATE_NO_WINDOW` at spawn (and this crate is a console app that inherits the
//! GUI-subsystem parent's absent console), so no console window ever flashes.

use std::env;
use std::os::windows::io::AsRawHandle;
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::{Command, Stdio};

use windows_sys::Win32::Foundation::HANDLE;
use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, SetInformationJobObject,
    JobObjectExtendedLimitInformation, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
    JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
};
use windows_sys::Win32::System::Threading::CREATE_NO_WINDOW;

/// Directory that holds this launcher exe. Tauri installs the sidecar next to the main app
/// exe with `resources\` beside it, so bundled payloads are resolved relative to here.
fn exe_dir() -> Option<PathBuf> {
    env::current_exe().ok()?.parent().map(|p| p.to_path_buf())
}

/// Resolve the `java.exe` to run: `PHOTONIC_JAVA`, else the bundled jlink runtime beside this
/// exe, else bare `java` on PATH. Mirrors the macOS launcher's env-then-fallback contract.
fn resolve_java() -> PathBuf {
    if let Some(p) = env::var_os("PHOTONIC_JAVA") {
        return PathBuf::from(p);
    }
    if let Some(dir) = exe_dir() {
        let bundled = dir
            .join("resources")
            .join("runtime")
            .join("bin")
            .join("java.exe");
        if bundled.exists() {
            return bundled;
        }
    }
    PathBuf::from("java")
}

/// Resolve the backend fat jar: `PHOTONIC_JAR`, else the copy staged beside this exe under
/// `resources\`.
fn resolve_jar() -> PathBuf {
    if let Some(p) = env::var_os("PHOTONIC_JAR") {
        return PathBuf::from(p);
    }
    exe_dir()
        .map(|d| d.join("resources").join("photonic-backend-0.1.0.jar"))
        .unwrap_or_else(|| PathBuf::from("photonic-backend-0.1.0.jar"))
}

/// Create a Job Object that kills every process assigned to it once the last handle closes,
/// and return its handle. Returns `None` if the job could not be created/configured — in that
/// case the backend's own stdin-EOF watchdog remains as the teardown fallback.
fn create_kill_on_close_job() -> Option<HANDLE> {
    // SAFETY: standard Win32 calls with valid, locally-owned arguments. `info` is fully
    // zero-initialised and sized correctly for the information class we pass.
    unsafe {
        let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
        if job.is_null() {
            return None;
        }
        let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        let ok = SetInformationJobObject(
            job,
            JobObjectExtendedLimitInformation,
            std::ptr::addr_of!(info).cast(),
            std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
        );
        if ok == 0 {
            return None;
        }
        Some(job)
    }
}

fn main() {
    let java = resolve_java();
    let jar = resolve_jar();

    // Create the job *before* spawning so the child can be assigned immediately, closing the
    // tiny window where a crash could leak java.exe.
    let job = create_kill_on_close_job();

    let mut child = Command::new(&java)
        // Headless: the backend uses AWT/ImageIO only for offscreen thumbnailing; this keeps
        // that working while stopping the JVM from registering as a GUI app.
        .arg("-Djava.awt.headless=true")
        .arg("-jar")
        .arg(&jar)
        // Inherit stdio: the handshake flows to Tauri's pipe and the JVM's stdin *is* that
        // pipe, so the stdin-EOF watchdog works unchanged.
        .stdin(Stdio::inherit())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit())
        // No console window when we spawn java.exe.
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .unwrap_or_else(|e| {
            eprintln!(
                "photonic-launcher: failed to start java ({}): {}",
                java.display(),
                e
            );
            std::process::exit(1);
        });

    if let Some(job) = job {
        // SAFETY: `job` is a live Job Object handle and the child is a running process whose
        // handle we own for the duration of this call.
        unsafe {
            // If this fails the stdin-EOF watchdog still covers teardown; nothing else to do.
            let _ = AssignProcessToJobObject(job, child.as_raw_handle() as HANDLE);
        }
        // Deliberately never CloseHandle(job): keeping the handle open until the launcher exits
        // is exactly what makes KILL_ON_JOB_CLOSE fire when Tauri kills us on quit. The handle
        // is reclaimed by the OS at process exit.
    }

    let code = child.wait().ok().and_then(|s| s.code()).unwrap_or(0);
    std::process::exit(code);
}
