# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Photonic Photos indexes a scattered photo library **in place** (read-only) and explores it on a zoomable timeline. Two independent halves talk over a localhost HTTP/JSON contract:

- **`backend/`** — Kotlin + Ktor + Exposed/SQLite + ExifTool. Recursive indexer + REST API. Runs and is testable on its own.
- **`frontend/`** — Tauri v2 + Vite + React + TypeScript. Desktop GUI that spawns the backend as a sidecar.

**Read `docs/architecture.md` first** — it is the authoritative design doc (full API table, data model, scanner/thumbnail/collect behavior, packaging). This file covers only commands and the cross-cutting facts that aren't obvious from a single file.

## Commands

Backend (from `backend/`):
```bash
mvn compile                       # build
mvn test                          # all unit + integration tests (JUnit 5)
mvn test -Dtest=ScannerTest       # one test class
mvn test -Dtest=ScannerTest#methodName   # one test method
mvn exec:java                     # run the server standalone (random port + token)
PHOTONIC_PORT=8899 PHOTONIC_TOKEN=photonic-dev mvn exec:java   # stable port+token for browser dev
mvn -DskipTests package           # build the shaded fat jar (Tauri sidecar payload)
```

Frontend (from `frontend/`):
```bash
npm install
VITE_BACKEND_PORT=8899 npm run dev   # browser dev against a standalone backend (http://localhost:5173)
npm run tauri dev                    # full native shell (spawns its own backend; needs Rust toolchain + macOS/WebKit)
npx tsc --noEmit                     # typecheck (strict)
npm run build                        # tsc + vite build
npm run tauri build                  # produce the macOS .dmg (see docs/architecture.md#packaging for staging)
```

CI (`.github/workflows/`) runs `mvn -B test`, then `tsc --noEmit` + `npm run build` on every push/PR. Run these before committing.

## Dev workflow (the non-obvious part)

- **Start the backend first**, then point the frontend at it. In browser dev the two are separate processes; only `npm run tauri dev` auto-spawns the backend.
- **Backend↔shell handshake is over stdout.** On startup the backend prints exactly two lines — `PHOTONIC_PORT=<n>` and `PHOTONIC_TOKEN=<secret>` — *after* the socket is bound (the `ServerReady` event). **stdout is reserved for this handshake; all logging goes to stderr** (`logback.xml`). Do not add `println` to backend startup — a stray stdout line breaks the Tauri shell's port/token parsing (`frontend/src-tauri/src/lib.rs`).
- **Every route except `/health` requires the `X-Photonic-Token` header.** The packaged app generates a random per-launch token and hands it to the GUI; browser dev uses the fixed token `photonic-dev` (frontend default `VITE_BACKEND_TOKEN`). curl examples must include `-H 'X-Photonic-Token: photonic-dev'`.
- **App data lives outside the repo:** `~/Library/Application Support/PhotonicPhotos/` (`photonic.db` in WAL mode + `thumbnails/`). See `AppPaths.kt`. There is no repo-local database; the `identifier.sqlite` at the repo root is an unused IntelliJ artifact.
- **ExifTool is optional at runtime.** The backend resolves it from `PHOTONIC_EXIFTOOL` else `exiftool` on `PATH`; if absent, it falls back to filesystem metadata + ImageIO (JPEG-only thumbnails). Don't assume ExifTool is present in code paths.

## Invariants to preserve

- **Originals are strictly read-only** — the scanner and collect flow never move, rename, or write source files. `CollectService` copies (never moves) and de-dupes collisions as `name (2).ext`.
- **Incremental scans keep photo `id` stable.** A changed file is re-indexed in place under its existing id so UI selections and cached renders stay valid; a file with matching `(size, mtime)` is skipped. One scan per root at a time. Don't introduce logic that reassigns ids on re-scan.
- **The timeline hot path is `photo.createdDate`** (epoch ms; EXIF `DateTimeOriginal`, else file mtime; indexed). `/timeline` bucketing and `/photos` ordering both key off it.
- **Image cache-busting relies on `fileMtime`.** Thumbnails/previews are served `immutable`; the client appends `v=<fileMtime>` so the URL changes only when the source is re-indexed. Keep `fileMtime` flowing through when touching the render/serve path.
- **SQLite pragmas (`WAL`, `foreign_keys`) are set via the JDBC URL** in `db/Db.kt`, not inside a transaction (they can't be changed mid-transaction).

## Layout pointers

Backend entry `photos.Application` (`Application.kt`) wires `ExifToolService` → `ThumbnailService` → `Scanner` + `CollectService` into `photonicModule` (routes in `api/Routes.kt`). Tables/DTOs in `db/Tables.kt` and `model/Dtos.kt`. Frontend: `api/client.ts` (backend port/token resolution), `components/Timeline.tsx` (zoomable histogram), `PhotoStrip.tsx`, `Lightbox.tsx`, orchestrated by `App.tsx`.
