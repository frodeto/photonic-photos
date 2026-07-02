import { useCallback, useEffect, useState } from "react";
import { api, type Bucket, type Photo, type Root, type TimelineBucket } from "./api/client";
import Timeline from "./components/Timeline";
import PhotoStrip from "./components/PhotoStrip";
import Lightbox from "./components/Lightbox";

function isTauri(): boolean {
  return typeof window !== "undefined" && ("__TAURI_INTERNALS__" in window || "__TAURI__" in window);
}

/** Pick a folder via the native dialog under Tauri, else fall back to a prompt in the browser. */
async function pickFolder(title: string): Promise<string | null> {
  if (isTauri()) {
    const { open } = await import("@tauri-apps/plugin-dialog");
    const result = await open({ directory: true, multiple: false, title });
    return typeof result === "string" ? result : null;
  }
  return window.prompt(title) || null;
}

/** Native confirm dialog under Tauri, window.confirm in the browser. */
async function confirmDialog(message: string): Promise<boolean> {
  if (isTauri()) {
    const { confirm } = await import("@tauri-apps/plugin-dialog");
    return confirm(message, { title: "Photonic Photos", kind: "warning" });
  }
  return window.confirm(message);
}

/** How many photos to fetch per drill-in page. */
const PAGE_SIZE = 500;

/** Refresh the timeline every Nth scan-status poll (polls are 500 ms apart). */
const TIMELINE_REFRESH_EVERY = 4;

export default function App() {
  const [bucket, setBucket] = useState<Bucket>("year");
  const [buckets, setBuckets] = useState<TimelineBucket[]>([]);
  const [roots, setRoots] = useState<Root[]>([]);
  const [selectedBucket, setSelectedBucket] = useState<number | null>(null);
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [bucketTotal, setBucketTotal] = useState(0);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [lightbox, setLightbox] = useState<number | null>(null);
  const [status, setStatus] = useState<string>("");
  const [busy, setBusy] = useState(false);

  const refreshTimeline = useCallback(async (b: Bucket) => {
    setBuckets(await api.timeline(b));
  }, []);

  const refreshRoots = useCallback(async () => {
    setRoots(await api.roots());
  }, []);

  useEffect(() => {
    refreshRoots().catch((e) => setStatus(String(e)));
  }, [refreshRoots]);

  useEffect(() => {
    // Changing granularity invalidates any drill-in: a selected start is no longer a valid
    // bucket boundary, so clear it rather than paginate against a mismatched range.
    setSelectedBucket(null);
    setPhotos([]);
    setBucketTotal(0);
    setLightbox(null);
    refreshTimeline(bucket).catch((e) => setStatus(String(e)));
  }, [bucket, refreshTimeline]);

  const clearDrillIn = () => {
    setSelectedBucket(null);
    setPhotos([]);
    setBucketTotal(0);
    setLightbox(null);
  };

  const scanFolder = async (folder: string) => {
    setBusy(true);
    setStatus(`Scanning ${folder} …`);
    try {
      const { jobId } = await api.startScan(folder);
      // Poll until the job finishes, refreshing the timeline periodically so photos
      // appear while a long scan is still running.
      for (let poll = 1; ; poll++) {
        const job = await api.scanStatus(jobId);
        const errs = job.errors > 0 ? `, ${job.errors} errors` : "";
        if (job.state !== "running") {
          setStatus(`Scan ${job.state}: ${job.filesIndexed} indexed${errs}`);
          break;
        }
        setStatus(
          job.filesIndexed === 0
            ? `Scanning … discovering files, ${job.filesSeen} found${errs}`
            : `Scanning … ${job.filesIndexed}/${job.filesSeen} indexed${errs}`,
        );
        if (poll % TIMELINE_REFRESH_EVERY === 0) refreshTimeline(bucket).catch(() => {});
        await new Promise((r) => setTimeout(r, 500));
      }
      await refreshTimeline(bucket);
      await refreshRoots();
    } catch (e) {
      setStatus(`Scan failed: ${e}`);
    } finally {
      setBusy(false);
    }
  };

  const onScan = async () => {
    const folder = await pickFolder("Choose a folder to index");
    if (folder) await scanFolder(folder);
  };

  const onRemoveRoot = async (root: Root) => {
    const ok = await confirmDialog(
      `Forget "${root.path}" and its ${root.photoCount} indexed photo(s)?\n` +
        "The original files on disk are not touched.",
    );
    if (!ok) return;
    setBusy(true);
    try {
      const res = await api.deleteRoot(root.id);
      setStatus(`Forgot ${root.path} (${res.removedPhotos} photos removed from the index)`);
      clearDrillIn();
      setSelectedIds(new Set());
      await refreshRoots();
      await refreshTimeline(bucket);
    } catch (e) {
      setStatus(`Remove failed: ${e}`);
    } finally {
      setBusy(false);
    }
  };

  const onSelectBucket = async (start: number) => {
    setSelectedBucket(start);
    setLightbox(null);
    // The timeline bar's own count is the authoritative total for this bucket.
    setBucketTotal(buckets.find((b) => b.bucketStart === start)?.count ?? 0);
    try {
      setPhotos(await api.photos({ from: start, bucket, limit: PAGE_SIZE }));
    } catch (e) {
      setStatus(String(e));
    }
  };

  const loadMore = async () => {
    if (selectedBucket == null) return;
    try {
      const more = await api.photos({
        from: selectedBucket,
        bucket,
        limit: PAGE_SIZE,
        offset: photos.length,
      });
      setPhotos((prev) => [...prev, ...more]);
    } catch (e) {
      setStatus(String(e));
    }
  };

  const toggle = (id: number) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const onCollect = async () => {
    if (selectedIds.size === 0) return;
    const target = await pickFolder("Choose the 'look closer at' folder");
    if (!target) return;
    setBusy(true);
    try {
      const res = await api.collect([...selectedIds], target);
      setStatus(`Copied ${res.copied} photo(s) to ${target}${res.skipped ? `, ${res.skipped} skipped` : ""}`);
      setSelectedIds(new Set());
    } catch (e) {
      setStatus(`Collect failed: ${e}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="app">
      <header>
        <h1>Photonic Photos</h1>
        <div className="toolbar">
          <button onClick={onScan} disabled={busy}>
            Add / rescan folder…
          </button>
          <label>
            Granularity{" "}
            <select value={bucket} onChange={(e) => setBucket(e.target.value as Bucket)}>
              <option value="year">Year</option>
              <option value="month">Month</option>
              <option value="day">Day</option>
            </select>
          </label>
          <button onClick={onCollect} disabled={busy || selectedIds.size === 0}>
            Copy {selectedIds.size > 0 ? `${selectedIds.size} ` : ""}to “look closer at”…
          </button>
          {selectedIds.size > 0 && (
            <button onClick={() => setSelectedIds(new Set())} disabled={busy}>
              Clear selection
            </button>
          )}
        </div>
      </header>

      {roots.length > 0 && (
        <section className="panel roots">
          {roots.map((r) => (
            <div key={r.id} className="root-row">
              <span className="root-path" title={r.path}>
                {r.path}
              </span>
              <span className="root-meta">
                {r.photoCount} photo{r.photoCount === 1 ? "" : "s"}
                {r.lastScanAt != null && ` · scanned ${new Date(r.lastScanAt).toLocaleString()}`}
              </span>
              <button onClick={() => scanFolder(r.path)} disabled={busy}>
                Rescan
              </button>
              <button onClick={() => onRemoveRoot(r)} disabled={busy} title="Remove from the index (files on disk are untouched)">
                Forget
              </button>
            </div>
          ))}
        </section>
      )}

      <section className="panel">
        <Timeline buckets={buckets} bucket={bucket} selected={selectedBucket} onSelect={onSelectBucket} />
      </section>

      <section className="panel">
        <PhotoStrip photos={photos} selectedIds={selectedIds} onToggleSelect={toggle} onOpen={setLightbox} />
        {photos.length < bucketTotal && (
          <div className="more">
            Showing {photos.length} of {bucketTotal}.{" "}
            <button onClick={loadMore} disabled={busy}>
              Load {Math.min(PAGE_SIZE, bucketTotal - photos.length)} more
            </button>
          </div>
        )}
      </section>

      <footer className="status">{status || "Ready."}</footer>

      {lightbox != null && photos[lightbox] && (
        <Lightbox
          photos={photos}
          index={lightbox}
          selectedIds={selectedIds}
          onToggleSelect={toggle}
          onNavigate={setLightbox}
          onClose={() => setLightbox(null)}
        />
      )}
    </div>
  );
}
