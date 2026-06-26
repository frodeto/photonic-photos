# Sidecar binaries

Tauri's `externalBin` expects a single executable here, named with the Rust target triple, e.g.:

- `photonic-backend-aarch64-apple-darwin`   (Apple Silicon)
- `photonic-backend-x86_64-apple-darwin`    (Intel)

A JVM app isn't a single file, so produce one of the following and place/symlink it here as that name:

**Path A — jlink runtime + launcher (recommended first):**
1. Build the fat jar: `cd ../../backend && mvn -DskipTests package`
2. Create a trimmed runtime with `jlink`, drop in `photonic-backend-0.1.0.jar`, and write a tiny
   launcher script that runs `java -jar`. Bundle the runtime via Tauri `resources` and make the
   launcher the sidecar binary above.

**Path B — GraalVM native image (optimization):**
`native-image -jar photonic-backend-0.1.0.jar photonic-backend-<triple>` → a true single binary that
drops straight in here.

During development you don't need any of this — run the backend with `mvn exec:java` and the UI talks
to `http://127.0.0.1:8899` (see `VITE_BACKEND_PORT`).
