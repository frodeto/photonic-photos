# Photonic Photos — Architecture

A local-first tool to **index a scattered photo library in place** (read-only) and explore it on a
**timeline**, then copy selected originals out for editing. Two independent parts over localhost
HTTP/JSON.

```
┌─────────────────────────── Photonic.app (.dmg) ───────────────────────────┐
│   Tauri shell (Rust)                                                       │
│   ├─ WebView UI  (Vite + React + TypeScript)                               │
│   │     timeline histogram · zoom · hover-thumbnail · select · "copy out"  │
│   │     ──HTTP/JSON──▶ http://127.0.0.1:<port>                             │
│   └─ spawns the backend sidecar, reads PHOTONIC_PORT from its stdout       │
│                                                                            │
│   Backend sidecar (Kotlin + Ktor)                                          │
│   ├─ REST API (Ktor + kotlinx.serialization)                               │
│   ├─ Scanner (recursive, incremental)                                      │
│   ├─ ExifToolService (bundled exiftool, batch reads + graceful fallback)   │
│   ├─ ThumbnailService (ImageIO for JPEG, exiftool preview for RAW)         │
│   └─ SQLite via Exposed                                                     │
│                                                                            │
│   App data:  ~/Library/Application Support/PhotonicPhotos/                  │
│              ├─ photonic.db  (SQLite, WAL)                                  │
│              └─ thumbnails/  (cached JPEGs)                                 │
└────────────────────────────────────────────────────────────────────────────┘
```

**Why this shape:** the Kotlin backend runs and is testable on its own (`mvn exec:java` + curl); the
Tauri UI just talks to it over localhost. Integration is a plain HTTP contract, so either half can be
swapped without touching the other.

## Integration handshake

The backend picks a free loopback port (or honors `PHOTONIC_PORT`) and prints exactly one line to
**stdout**: `PHOTONIC_PORT=<n>` (logs go to stderr — see `backend/src/main/resources/logback.xml`).
The Tauri shell (`frontend/src-tauri/src/lib.rs`) reads that line, stores the port, and the WebView
fetches it via the `get_backend_port` command (`frontend/src/api/client.ts`). In browser-only dev the
UI falls back to `VITE_BACKEND_PORT` (default 8899).

## API (`backend/src/main/kotlin/photos/api/Routes.kt`)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | readiness |
| `GET` | `/roots` | indexed roots + photo counts |
| `POST` | `/roots/scan` | `{path}` → start/refresh an incremental scan, returns `{jobId}` |
| `GET` | `/scans/{id}` | scan progress (seen / indexed / errors / state) |
| `GET` | `/timeline?from&to&bucket=day\|month\|year` | histogram buckets `{bucketStart, count}` |
| `GET` | `/photos?from&to&limit&offset` | photo rows in a date range, ordered by date |
| `GET` | `/photos/{id}` | full metadata |
| `GET` | `/photos/{id}/thumbnail` | cached JPEG bytes |
| `POST` | `/collect` | `{photoIds[], targetFolder}` → copy originals out, returns counts |

## Data model (`backend/src/main/kotlin/photos/db/Tables.kt`)

- **`scan_root`** — path (unique), addedAt, lastScanAt.
- **`photo`** — rootId, filePath (unique), fileName, fileSize, **fileMtime**, contentHash (reserved),
  **createdDate** (epoch ms; EXIF DateTimeOriginal, else file mtime; indexed — the timeline hot path),
  cameraMake/Model, lens, shutterSpeed, aperture, focalLength, focalLength35, gpsLat/Lon, width/height,
  orientation, thumbPath, indexedAt, exifStatus.
- **`scan_job`** — rootId, state, filesSeen/Indexed/errors, started/finishedAt, errorSummary.

WAL + `foreign_keys` are set via the JDBC URL (they can't be changed inside a transaction).

## Behaviour

- **Scanner** (`scan/Scanner.kt`): `Files.walk`, filters `jpg/jpeg/cr2/dng`. **Incremental** — a file
  with matching `(size, mtime)` is skipped, so re-scans are cheap; new/changed files are (re)indexed.
  Strictly read-only on originals. Long scans run on a background coroutine; the UI polls `/scans/{id}`.
- **ExifToolService** (`scan/ExifToolService.kt`): drives exiftool in **batches** (`-json -n`, one
  process per chunk of files, not per photo). Resolves the binary from `PHOTONIC_EXIFTOOL`. If exiftool
  is unavailable, batch reads return empty and the scanner falls back to filesystem metadata.
  *(Future optimization: `-stay_open True` for a single long-lived process per scan.)*
- **ThumbnailService** (`thumbnail/ThumbnailService.kt`): JPEG → ImageIO; RAW → exiftool embedded
  preview → ImageIO downscale to ~256px; written to the on-disk cache (kept out of the DB).
- **CollectService** (`collect/CollectService.kt`): copies (never moves) selected originals into the
  target folder, de-duplicating collisions as `name (2).ext`.

## Frontend (`frontend/src/`)

- `components/Timeline.tsx` — SVG bar histogram; click a bar to drill into that bucket.
- `components/PhotoStrip.tsx` — thumbnails with hover-to-load preview and click-to-select.
- `App.tsx` — folder picker (native dialog under Tauri), scan + progress polling, granularity
  switch, selection, and copy-out.

## Packaging

Tauri's `externalBin` wants a **single executable** named with the target triple
(`photonic-backend-aarch64-apple-darwin`). A JVM app isn't naturally one file:

- **Path A (recommended first):** `jlink` a trimmed JRE + the shaded fat-jar
  (`mvn -DskipTests package` → `backend/target/photonic-backend-0.1.0.jar`), bundle the runtime as a
  Tauri **resource**, and make a tiny `java -jar` launcher the sidecar binary.
- **Path B (optimization):** GraalVM `native-image` → a true single binary that drops straight into
  `externalBin`.

ExifTool ships as a Tauri **resource** (`frontend/src-tauri/resources/exiftool/`); the shell sets
`PHOTONIC_EXIFTOOL` to its resolved path at launch. `npm run tauri build` produces the `.dmg` (macOS).

## Status / verified

- ✅ Backend compiles (`mvn compile`), unit/integration test passes (`mvn test`): recursive scan,
  metadata indexing, ImageIO thumbnails, incremental re-scan.
- ✅ Live API verified end-to-end (scan → poll → timeline bucketing → photos → thumbnail bytes →
  collect; originals left intact; 404 paths).
- ✅ Frontend type-checks (strict) and the web bundle builds (`npm run build`).
- ⏳ Not built here (needs macOS/WebKit + Rust): the Tauri shell compile and the `.dmg`. The Rust in
  `src-tauri/` is written but unbuilt — expect minor API tweaks against the generated capability schema.

## Backlog / next steps

- `-stay_open` exiftool mode; content-hash for move/duplicate detection.
- Optional startup token (`X-Photonic-Token`) enforced on all routes but `/health`.
- Mark rows whose files have disappeared; surface them in the UI.
- Map view for GPS-tagged photos; richer photo-detail panel.
