// Typed client for the Photonic backend.
//
// In a packaged Tauri app the backend runs as a sidecar on a random port; we ask the Rust side
// (`get_backend_port`) for it. In a plain browser dev session we hit a fixed dev port instead
// (VITE_BACKEND_PORT, default 8899) so the UI is developable without Tauri.

export interface TimelineBucket {
  bucketStart: number;
  count: number;
}

export interface Photo {
  id: number;
  filePath: string;
  fileName: string;
  fileSize: number;
  fileMtime: number;
  createdDate: number;
  cameraMake?: string;
  cameraModel?: string;
  lens?: string;
  shutterSpeed?: string;
  aperture?: number;
  focalLength?: number;
  focalLength35?: number;
  gpsLat?: number;
  gpsLon?: number;
  width?: number;
  height?: number;
  hasThumbnail: boolean;
}

export interface Root {
  id: number;
  path: string;
  photoCount: number;
  lastScanAt: number | null;
}

export interface ScanJob {
  id: number;
  rootId: number;
  state: string;
  filesSeen: number;
  filesIndexed: number;
  errors: number;
  startedAt: number;
  finishedAt: number | null;
}

export interface CollectResult {
  copied: number;
  skipped: number;
  errors: string[];
}

export type Bucket = "day" | "month" | "year";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function isTauri(): boolean {
  return typeof window !== "undefined" && ("__TAURI_INTERNALS__" in window || "__TAURI__" in window);
}

let cachedBase: string | null = null;
let cachedToken: string | null = null;

async function base(): Promise<string> {
  if (cachedBase) return cachedBase;
  if (isTauri()) {
    const { invoke } = await import("@tauri-apps/api/core");
    for (let i = 0; i < 80; i++) {
      const port = await invoke<number | null>("get_backend_port");
      if (port) {
        cachedBase = `http://127.0.0.1:${port}`;
        return cachedBase;
      }
      await sleep(250);
    }
    throw new Error("backend did not start in time");
  }
  const port = (import.meta as { env?: Record<string, string> }).env?.VITE_BACKEND_PORT ?? "8899";
  cachedBase = `http://127.0.0.1:${port}`;
  return cachedBase;
}

// The backend authenticates every request (except /health) against a per-launch secret.
// In the packaged app the Tauri shell reads it from the backend's stdout handshake; in a plain
// browser dev session we use VITE_BACKEND_TOKEN (default "photonic-dev", which the backend must
// be launched with: `PHOTONIC_TOKEN=photonic-dev mvn exec:java`).
async function token(): Promise<string> {
  if (cachedToken != null) return cachedToken;
  if (isTauri()) {
    const { invoke } = await import("@tauri-apps/api/core");
    for (let i = 0; i < 80; i++) {
      const t = await invoke<string | null>("get_backend_token");
      if (t) {
        cachedToken = t;
        return cachedToken;
      }
      await sleep(250);
    }
    throw new Error("backend did not start in time");
  }
  cachedToken =
    (import.meta as { env?: Record<string, string> }).env?.VITE_BACKEND_TOKEN ?? "photonic-dev";
  return cachedToken;
}

async function authHeaders(extra?: Record<string, string>): Promise<Record<string, string>> {
  return { "X-Photonic-Token": await token(), ...extra };
}

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch((await base()) + path, { headers: await authHeaders() });
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  return res.json() as Promise<T>;
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch((await base()) + path, {
    method: "POST",
    headers: await authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  return res.json() as Promise<T>;
}

export const api = {
  roots: () => getJson<Root[]>("/roots"),

  startScan: (path: string) => postJson<{ jobId: number }>("/roots/scan", { path }),

  scanStatus: (jobId: number) => getJson<ScanJob>(`/scans/${jobId}`),

  timeline: (bucket: Bucket, from?: number, to?: number) => {
    const qs = new URLSearchParams({ bucket });
    if (from != null) qs.set("from", String(from));
    if (to != null) qs.set("to", String(to));
    return getJson<TimelineBucket[]>(`/timeline?${qs}`);
  },

  // Pass `bucket` + `from` (a bucket start) to let the backend derive the exclusive period end
  // itself — keeps the drill-in aligned with the timeline bar counts across timezones.
  photos: (opts: { from?: number; to?: number; bucket?: Bucket; limit?: number; offset?: number }) => {
    const qs = new URLSearchParams({ limit: String(opts.limit ?? 500) });
    if (opts.from != null) qs.set("from", String(opts.from));
    if (opts.to != null) qs.set("to", String(opts.to));
    if (opts.bucket) qs.set("bucket", opts.bucket);
    if (opts.offset) qs.set("offset", String(opts.offset));
    return getJson<Photo[]>(`/photos?${qs}`);
  },

  // <img> can't send headers, so the token rides along as a query param (the backend accepts both).
  // `v` (the file's mtime) makes the URL change when the source file is re-indexed, so the
  // backend can serve these with immutable cache headers and the WebView never refetches.
  thumbnailUrl: async (photo: Photo) =>
    `${await base()}/photos/${photo.id}/thumbnail?token=${encodeURIComponent(await token())}&v=${photo.fileMtime}`,

  // Larger preview for the lightbox; rendered + cached on the backend on first request.
  previewUrl: async (photo: Photo) =>
    `${await base()}/photos/${photo.id}/preview?token=${encodeURIComponent(await token())}&v=${photo.fileMtime}`,

  collect: (photoIds: number[], targetFolder: string) =>
    postJson<CollectResult>("/collect", { photoIds, targetFolder }),
};
