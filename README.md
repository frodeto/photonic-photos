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

Supported formats: `.jpg .jpeg .cr2 .dng`. See [`docs/architecture.md`](docs/architecture.md) for the
full design, data model, and packaging notes.

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
native-image). `npm run tauri build` produces the `.dmg` (run on a Mac).
