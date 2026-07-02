import { useCallback, useEffect, useRef, useState } from "react";
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

/**
 * Histogram drill-down levels. `path` below holds the chosen bucket starts on the way down
 * (path.length 0 → all years, 1 → months of path[0], 2 → days of path[1]).
 */
const LEVELS: Bucket[] = ["year", "month", "day"];

function crumbLabel(ms: number, level: Bucket): string {
  const d = new Date(ms);
  if (level === "year") return String(d.getFullYear());
  return d.toLocaleString(undefined, { month: "long", year: "numeric" });
}

/** The photo strip's query context: which bucket it shows, at which level, and its total. */
interface StripContext {
  from: number;
  level: Bucket;
  total: number;
}

export default function App() {
  const [buckets, setBuckets] = useState<TimelineBucket[]>([]);
  const [roots, setRoots] = useState<Root[]>([]);
  const [path, setPath] = useState<number[]>([]);
  const [strip, setStrip] = useState<StripContext | null>(null);
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [lightbox, setLightbox] = useState<number | null>(null);
  const [status, setStatus] = useState<string>("");
  const [busy, setBusy] = useState(false);

  const level = LEVELS[path.length];

  // Long-running scan loops refresh the timeline; a ref keeps them pointed at wherever the
  // user has navigated to since the scan started.
  const pathRef = useRef(path);
  useEffect(() => {
    pathRef.current = path;
  }, [path]);

  const loadTimeline = useCallback(async (p: number[]) => {
    const lvl = LEVELS[p.length];
    const opts =
      p.length === 0
        ? { fill: true }
        : { from: p[p.length - 1], within: LEVELS[p.length - 1], fill: true };
    const b = await api.timeline(lvl, opts);
    setBuckets(b);
    return b;
  }, []);

  const refreshRoots = useCallback(async () => {
    setRoots(await api.roots());
  }, []);

  useEffect(() => {
    loadTimeline([]).catch((e) => setStatus(String(e)));
    refreshRoots().catch((e) => setStatus(String(e)));
  }, [loadTimeline, refreshRoots]);

  /**
   * Show a bucket's photos in the strip and — above day level — zoom the histogram into it.
   * [prefix] is the path *above* the clicked bucket (the current path for bar clicks, a
   * truncated one for breadcrumb clicks); [barCount] is the clicked bar's own count, used as
   * the strip total at day level where no child histogram exists to sum.
   */
  const openBucket = async (prefix: number[], start: number, barCount?: number) => {
    const levelIdx = prefix.length;
    const lvl = LEVELS[levelIdx];
    const newPath = levelIdx < LEVELS.length - 1 ? [...prefix, start] : prefix;
    setPath(newPath);
    setLightbox(null);
    try {
      let total = barCount ?? 0;
      if (levelIdx < LEVELS.length - 1) {
        const kids = await loadTimeline(newPath);
        total = kids.reduce((sum, b) => sum + b.count, 0);
      }
      setStrip({ from: start, level: lvl, total });
      setPhotos(await api.photos({ from: start, bucket: lvl, limit: PAGE_SIZE }));
    } catch (e) {
      setStatus(String(e));
    }
  };

  const goToAllYears = () => {
    setPath([]);
    setStrip(null);
    setPhotos([]);
    setLightbox(null);
    loadTimeline([]).catch((e) => setStatus(String(e)));
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
        if (poll % TIMELINE_REFRESH_EVERY === 0) loadTimeline(pathRef.current).catch(() => {});
        await new Promise((r) => setTimeout(r, 500));
      }
      await loadTimeline(pathRef.current);
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
      setSelectedIds(new Set());
      goToAllYears();
      await refreshRoots();
    } catch (e) {
      setStatus(`Remove failed: ${e}`);
    } finally {
      setBusy(false);
    }
  };

  const loadMore = async () => {
    if (!strip) return;
    try {
      const more = await api.photos({
        from: strip.from,
        bucket: strip.level,
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
        <div className="crumbs">
          <button className="crumb" onClick={goToAllYears} disabled={path.length === 0}>
            All years
          </button>
          {path.map((start, i) => (
            <span key={start} className="crumb-seg">
              <span className="crumb-sep">›</span>
              <button
                className="crumb"
                onClick={() => openBucket(path.slice(0, i), start)}
                disabled={i === path.length - 1}
              >
                {crumbLabel(start, LEVELS[i])}
              </button>
            </span>
          ))}
          <span className="crumb-hint">
            {level === "day" ? "Click a day to see its photos" : "Click a bar to zoom in"}
          </span>
        </div>
        <Timeline
          buckets={buckets}
          level={level}
          selected={strip && strip.level === level ? strip.from : null}
          onSelect={(start, count) => openBucket(path, start, count)}
        />
      </section>

      <section className="panel">
        <PhotoStrip photos={photos} selectedIds={selectedIds} onToggleSelect={toggle} onOpen={setLightbox} />
        {strip != null && photos.length < strip.total && (
          <div className="more">
            Showing {photos.length} of {strip.total}.{" "}
            <button onClick={loadMore} disabled={busy}>
              Load {Math.min(PAGE_SIZE, strip.total - photos.length)} more
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
