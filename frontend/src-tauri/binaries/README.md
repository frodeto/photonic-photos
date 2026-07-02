# Sidecar binaries

Tauri's `externalBin` expects a single executable here, named with the Rust target triple, e.g.:

- `photonic-backend-aarch64-apple-darwin`   (Apple Silicon)
- `photonic-backend-x86_64-apple-darwin`    (Intel)

A JVM app isn't a single file, so produce one of the following and place/symlink it here as that name:

**Path A — jlink runtime + launcher (in use):**
1. Build the fat jar: `cd ../../backend && mvn -DskipTests package`
2. Create a trimmed runtime with `jlink`, drop in `photonic-backend-0.1.0.jar`, and write a tiny
   launcher script that runs `java -jar`. Bundle the runtime + jar via Tauri `resources` and make the
   launcher the sidecar binary above. See the repo README for the exact `jlink --add-modules` line.

The launcher reads three env vars that the Rust shell (`../src/lib.rs`) sets from the resolved
Resources paths: `PHOTONIC_JAVA` (bundled JRE `java`), `PHOTONIC_JAR` (the fat jar), and
`PHOTONIC_EXIFTOOL` (bundled exiftool). It falls back to `java` on `PATH` and its own
`../Resources/resources/...` paths when run directly (e.g. during `tauri dev`).

The launcher must run java with `-Djava.awt.headless=true`
(`exec "$JAVA_BIN" -Djava.awt.headless=true -jar "$JAR_PATH"`). The backend uses AWT/ImageIO only
for **offscreen** thumbnailing, so headless keeps thumbnails working while stopping the JVM from
registering as a GUI app and showing a Java icon in the macOS Dock.

The `exec` is load-bearing: it makes the sidecar child PID *be* the JVM, so the Rust shell's
kill-on-exit (`RunEvent::Exit` in `../src/lib.rs`) terminates the backend and not just a wrapper
shell. (As a second line of defense the backend also exits on stdin EOF when the shell sets
`PHOTONIC_WATCH_STDIN=1`.)

**Path B — GraalVM native image (optimization):**
`native-image -jar photonic-backend-0.1.0.jar photonic-backend-<triple>` → a true single binary that
drops straight in here.

During development you don't need any of this — run the backend with `mvn exec:java` and the UI talks
to `http://127.0.0.1:8899` (see `VITE_BACKEND_PORT`).
