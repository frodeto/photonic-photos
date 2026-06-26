import { useCallback, useEffect, useState } from "react";
import { api, type Bucket, type Photo, type TimelineBucket } from "./api/client";
import Timeline from "./components/Timeline";
import PhotoStrip from "./components/PhotoStrip";

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

/** How many photos to fetch per drill-in page. */
const PAGE_SIZE = 500;

export default function App() {
  const [bucket, setBucket] = useState<Bucket>("year");
  const [buckets, setBuckets] = useState<TimelineBucket[]>([]);
  const [selectedBucket, setSelectedBucket] = useState<number | null>(null);
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [bucketTotal, setBucketTotal] = useState(0);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [status, setStatus] = useState<string>("");
  const [busy, setBusy] = useState(false);

  const refreshTimeline = useCallback(async (b: Bucket) => {
    setBuckets(await api.timeline(b));
  }, []);

  useEffect(() => {
    // Changing granularity invalidates any drill-in: a selected start is no longer a valid
    // bucket boundary, so clear it rather than paginate against a mismatched range.
    setSelectedBucket(null);
    setPhotos([]);
    setBucketTotal(0);
    refreshTimeline(bucket).catch((e) => setStatus(String(e)));
  }, [bucket, refreshTimeline]);

  const onScan = async () => {
    const folder = await pickFolder("Choose a folder to index");
    if (!folder) return;
    setBusy(true);
    setStatus(`Scanning ${folder} …`);
    try {
      const { jobId } = await api.startScan(folder);
      // Poll until the job finishes.
      for (;;) {
        const job = await api.scanStatus(jobId);
        setStatus(`Scanning … ${job.filesIndexed}/${job.filesSeen} indexed`);
        if (job.state !== "running") {
          setStatus(`Scan ${job.state}: ${job.filesIndexed} indexed, ${job.errors} errors`);
          break;
        }
        await new Promise((r) => setTimeout(r, 500));
      }
      await refreshTimeline(bucket);
    } catch (e) {
      setStatus(`Scan failed: ${e}`);
    } finally {
      setBusy(false);
    }
  };

  const onSelectBucket = async (start: number) => {
    setSelectedBucket(start);
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
        </div>
      </header>

      <section className="panel">
        <Timeline buckets={buckets} bucket={bucket} selected={selectedBucket} onSelect={onSelectBucket} />
      </section>

      <section className="panel">
        <PhotoStrip photos={photos} selectedIds={selectedIds} onToggle={toggle} />
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
    </div>
  );
}
