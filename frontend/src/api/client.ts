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

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch((await base()) + path);
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  return res.json() as Promise<T>;
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch((await base()) + path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
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

  photos: (from?: number, to?: number, limit = 500) => {
    const qs = new URLSearchParams({ limit: String(limit) });
    if (from != null) qs.set("from", String(from));
    if (to != null) qs.set("to", String(to));
    return getJson<Photo[]>(`/photos?${qs}`);
  },

  thumbnailUrl: async (id: number) => `${await base()}/photos/${id}/thumbnail`,

  collect: (photoIds: number[], targetFolder: string) =>
    postJson<CollectResult>("/collect", { photoIds, targetFolder }),
};
