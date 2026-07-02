# Photonic Photos — Architecture

A local-first tool to **index a scattered photo library in place** (read-only) and explore it on a
**timeline**, then copy selected originals out for editing. Two independent parts over localhost
HTTP/JSON.

```
┌─────────────────────────── Photonic.app (.dmg) ───────────────────────────┐
│   Tauri shell (Rust)                                                       │
│   ├─ WebView UI  (Vite + React + TypeScript)                               │
│   │     timeline histogram · zoom · hover-meta · click-preview · select · "copy out"  │
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
| `POST` | `/roots/scan` | `{path}` → start/refresh an incremental scan, returns `{jobId}` (the running job's id if that root is already being scanned) |
| `DELETE` | `/roots/{id}` | forget a root: removes its rows + cached renders (originals untouched); `409` while a scan runs |
| `GET` | `/scans/{id}` | scan progress (seen / indexed / errors / state) |
| `GET` | `/timeline?from&to&bucket=day\|month\|year` | histogram buckets `{bucketStart, count}` |
| `GET` | `/photos?from&to&limit&offset` | photo rows in a date range, ordered by date |
| `GET` | `/photos/{id}` | full metadata |
| `GET` | `/photos/{id}/thumbnail` | cached ~256px JPEG (generated during scan) |
| `GET` | `/photos/{id}/preview` | larger ~1024px JPEG for the lightbox (rendered + cached on first request) |

Thumbnails/previews are served with `Cache-Control: … immutable`; the client appends the photo's
`fileMtime` as a `v=` query param, so the URL — and thus the cache entry — changes whenever the
source file is re-indexed. The WebView never refetches an unchanged image.
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

- **Scanner** (`scan/Scanner.kt`): `Files.walkFileTree` (unreadable files/folders are skipped and
  counted as errors rather than aborting the scan), filters
  `jpg/jpeg/heic/heif/cr2/cr3/dng/nef/arw/orf/raf/rw2`. **Incremental** —
  everything known under the root is loaded in one query; a file with matching `(size, mtime)` is
  skipped, so re-scans are cheap. Changed files are re-indexed **in place, keeping their photo id
  stable** (so UI selections and cached renders stay valid), and rows whose files have verifiably
  disappeared are removed along with their cached thumbnails/previews. Only one scan runs per root
  at a time — a request for a busy root returns the running job's id. Strictly read-only on
  originals. Long scans run on a background coroutine; the UI polls `/scans/{id}` (`filesSeen`
  updates during discovery, then `filesIndexed` during indexing). Within each exiftool batch,
  files are indexed concurrently on a bounded dispatcher (min(cores, 8)) — thumbnail rendering
  dominates scan time and is independent per file.
- **ExifToolService** (`scan/ExifToolService.kt`): drives exiftool in **batches** (`-json -n`, one
  process per chunk of files, not per photo). Resolves the binary from `PHOTONIC_EXIFTOOL`. If exiftool
  is unavailable, batch reads return empty and the scanner falls back to filesystem metadata.
  *(Future optimization: `-stay_open True` for a single long-lived process per scan.)*
- **ThumbnailService** (`thumbnail/ThumbnailService.kt`): JPEG → ImageIO; HEIC/RAW → exiftool
  embedded preview → ImageIO downscale (best-effort: a file without a usable embedded preview
  indexes fine but renders no thumbnail); written to the on-disk cache (kept out of the DB). Two sizes share
  one pipeline: ~256px **thumbnails** generated eagerly during the scan, and ~1024px **previews**
  generated lazily on first `/preview` request (temp-file + atomic move, since requests are concurrent).
- **CollectService** (`collect/CollectService.kt`): copies (never moves) selected originals into the
  target folder, de-duplicating collisions as `name (2).ext`.

## Frontend (`frontend/src/`)

- `components/Timeline.tsx` — SVG bar histogram; click a bar to drill into that bucket.
- `components/PhotoStrip.tsx` — thumbnails for the bucket load eagerly; **hover** reveals a metadata
  overlay, the **corner checkbox** marks a photo for the copy set, and **clicking** opens the lightbox.
- `components/Lightbox.tsx` — full-window ~1024px preview with metadata, arrow/Esc keyboard nav,
  Space (or the checkbox) to toggle the shown photo in the copy set, and preloading of the
  neighbouring previews so stepping through is instant.
- `App.tsx` — folder picker (native dialog under Tauri), scan + progress polling (the timeline
  refreshes live while a scan runs), a roots panel listing indexed folders with per-root
  rescan/forget actions, granularity switch, selection, copy-out, and lightbox state.

## Packaging

Tauri's `externalBin` wants a **single executable** named with the target triple
(`photonic-backend-aarch64-apple-darwin`). A JVM app isn't naturally one file:

- **Path A (in use):** `jlink` a trimmed JRE + the shaded fat-jar
  (`mvn -DskipTests package` → `backend/target/photonic-backend-0.1.0.jar`), bundle the runtime as a
  Tauri **resource**, and make a tiny `java -jar` launcher the sidecar binary
  (`binaries/photonic-backend-<triple>`). This is what the current `.dmg` ships.
- **Path B (optimization):** GraalVM `native-image` → a true single binary that drops straight into
  `externalBin`.

The Rust shell (`src-tauri/src/lib.rs`) resolves the bundled JRE, fat-jar, and exiftool from the
app's Resources dir and passes them to the launcher/sidecar as `PHOTONIC_JAVA` / `PHOTONIC_JAR` /
`PHOTONIC_EXIFTOOL` (each best-effort; the launcher and backend fall back to `java`/`exiftool` on
`PATH`). ExifTool ships as a Tauri **resource** (`frontend/src-tauri/resources/exiftool/`) — the
self-contained distribution (script + `lib/`) that runs on the system `/usr/bin/perl`.
`npm run tauri build` produces the `.dmg` (macOS); see the README for the one-time staging steps.

## Status / verified

- ✅ Backend compiles (`mvn compile`), unit/integration test passes (`mvn test`): recursive scan,
  metadata indexing, ImageIO thumbnails, incremental re-scan.
- ✅ Live API verified end-to-end (scan → poll → timeline bucketing → photos → thumbnail bytes →
  collect; originals left intact; 404 paths).
- ✅ Frontend type-checks (strict) and the web bundle builds (`npm run build`).
- ✅ Tauri shell compiles and the **`.dmg` builds on Apple Silicon** (`aarch64-apple-darwin`, Path A
  jlink launcher). Verified end-to-end: the bundled jlink runtime boots the backend, the
  `PHOTONIC_PORT`/`PHOTONIC_TOKEN` handshake completes, `/health` returns 200, and the bundled
  ExifTool is detected. The `.dmg` is currently **unsigned** (no code signing / notarization yet).

## Backlog / next steps

- `-stay_open` exiftool mode; content-hash for move/duplicate detection.
- Optional startup token (`X-Photonic-Token`) enforced on all routes but `/health`.
- Map view for GPS-tagged photos; richer photo-detail panel.
