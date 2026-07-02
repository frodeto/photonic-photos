# Photonic Photos

Get an overview of a large, scattered photo library **without moving or collecting the originals**.
Point it at a folder, it recursively indexes photo metadata into a local datastore, and a desktop
GUI shows a **zoomable timeline** of when photos were taken — with thumbnail-on-hover and a
"copy selected to a *look closer at* folder" action for editing elsewhere.

Built as two independent parts integrated over a localhost HTTP/JSON contract:

| Part | Tech | Role |
|------|------|------|
| **backend/** | Kotlin + Ktor + Exposed/SQLite, ExifTool | recursive indexer + REST API |
| **frontend/** | Tauri v2 + Vite + React + TypeScript | desktop GUI, runs the backend as a sidecar |

Supported formats: `.jpg .jpeg .heic .heif .cr2 .cr3 .dng .nef .arw .orf .raf .rw2`. JPEG renders
natively; HEIC and RAW metadata + thumbnails come from ExifTool embedded previews (see below). See
[`docs/architecture.md`](docs/architecture.md) for the full design, data model, and packaging notes.

## Quick start (development)

The two halves run independently. **Backend first:**

```bash
cd backend
mvn exec:java            # prints "PHOTONIC_PORT=<n>" and "PHOTONIC_TOKEN=<secret>", then serves on 127.0.0.1
# for a stable dev port + token (so the browser client can reach it): 
PHOTONIC_PORT=8899 PHOTONIC_TOKEN=photonic-dev mvn exec:java
```

The API authenticates every request (except `/health`) against `PHOTONIC_TOKEN` via the
`X-Photonic-Token` header — this stops other local processes / web pages from reaching your
library. The packaged app generates a random token and hands it to the GUI automatically; for
browser dev, launch the backend with the fixed `photonic-dev` token the dev client expects.

Smoke-test it with curl (replace the port):

```bash
curl localhost:8899/health                              # open, no token
curl -H 'X-Photonic-Token: photonic-dev' -XPOST localhost:8899/roots/scan \
     -H 'Content-Type: application/json' -d '{"path":"/path/to/photos"}'
curl -H 'X-Photonic-Token: photonic-dev' 'localhost:8899/timeline?bucket=month'
```

**Frontend** (talks to the backend on `VITE_BACKEND_PORT`, default 8899; sends `VITE_BACKEND_TOKEN`, default `photonic-dev`):

```bash
cd frontend
npm install
VITE_BACKEND_PORT=8899 npm run dev      # open http://localhost:5173 in a browser
# or, for the full native shell (requires the Rust toolchain + macOS/WebKit):
npm run tauri dev
```

## ExifTool

EXIF/RAW metadata and embedded RAW previews come from [ExifTool](https://exiftool.org). The backend
resolves it from `PHOTONIC_EXIFTOOL` (else `exiftool` on `PATH`). **If ExifTool is absent the backend
still runs** — it falls back to filesystem metadata and ImageIO thumbnails for JPEGs (RAW
thumbnails/metadata need ExifTool).

## Tests

```bash
cd backend && mvn test     # scanner integration test over generated fixture JPEGs
```

## Packaging (macOS `.dmg`)

The backend is bundled as a Tauri **sidecar**; ExifTool ships as a Tauri **resource**. See
[`docs/architecture.md`](docs/architecture.md#packaging) and
`frontend/src-tauri/binaries/README.md` for the two packaging paths (jlink launcher vs GraalVM
native-image). The current build uses **Path A (jlink launcher)** and produces an **unsigned**
`.dmg` — verified building and launching on Apple Silicon.

**One-time prerequisites** (Apple Silicon): a Rust toolchain (`rustup`), JDK 21 (`jlink`), Node, and
Maven. Then stage the sidecar payload once:

```bash
# 1. Build the backend fat jar
cd backend && mvn -DskipTests package

# 2. Trim a JRE and stage it + the jar as Tauri resources
cd ../frontend/src-tauri
jlink --add-modules java.base,java.desktop,java.instrument,java.management,java.naming,java.sql,\
jdk.unsupported,java.logging,java.xml,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.zipfs,\
java.security.sasl,java.security.jgss,jdk.localedata \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output resources/runtime
# jlink writes the legal/ notices read-only (mode 444). tauri-build copies resources into
# target/ preserving that mode, then fails ("Permission denied (os error 13)") when a *later*
# rebuild tries to overwrite them. Make the staged runtime user-writable so rebuilds succeed:
chmod -R u+w resources/runtime
cp ../../backend/target/photonic-backend-0.1.0.jar resources/photonic-backend-0.1.0.jar

# 3. Bundle a self-contained ExifTool (the exiftool script + lib/) into resources/exiftool/
#    (from the Image-ExifTool distribution at https://exiftool.org)

# 4. The sidecar launcher lives at binaries/photonic-backend-<target-triple> (a small
#    `java -jar` shell script; the Rust shell passes it PHOTONIC_JAVA/PHOTONIC_JAR/PHOTONIC_EXIFTOOL).
```

The jlink runtime, staged jar, launcher, and ExifTool payload are gitignored — build them locally.
Then build the bundle:

```bash
cd frontend && npm run tauri build
# → src-tauri/target/release/bundle/dmg/Photonic Photos_0.1.0_aarch64.dmg
```

If a rebuild still fails with `Permission denied (os error 13)`, you have read-only runtime copies
left in `target/` from a previous build — clear them and rebuild:
`rm -rf src-tauri/target/release/resources src-tauri/target/release/bundle`.

Because the `.dmg` is unsigned, first launch needs right-click → **Open** (or
`xattr -dr com.apple.quarantine "…/Photonic Photos.app"`).
