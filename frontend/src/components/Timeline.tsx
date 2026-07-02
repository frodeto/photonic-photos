import { useMemo } from "react";
import type { Bucket, TimelineBucket } from "../api/client";

interface Props {
  buckets: TimelineBucket[];
  level: Bucket;
  selected: number | null;
  onSelect: (bucketStart: number, count: number) => void;
}

const WIDTH = 1100;
const HEIGHT = 220;
const PAD = 28;

/** Short axis label; the breadcrumb already gives the year/month context. */
function label(ms: number, level: Bucket): string {
  const d = new Date(ms);
  if (level === "year") return String(d.getFullYear());
  if (level === "month") return d.toLocaleString(undefined, { month: "short" });
  return String(d.getDate());
}

/** Full label for tooltips. */
function tooltipLabel(ms: number, level: Bucket): string {
  const d = new Date(ms);
  if (level === "year") return String(d.getFullYear());
  if (level === "month") return d.toLocaleString(undefined, { month: "long", year: "numeric" });
  return d.toLocaleDateString();
}

/**
 * SVG bar histogram of photo counts per time bucket. The buckets arrive gap-filled from the
 * backend, so the x axis is linear in time — empty periods show as empty space. Clicking a bar
 * shows its photos (and, above day level, zooms the histogram into that bucket).
 */
export default function Timeline({ buckets, level, selected, onSelect }: Props) {
  const max = useMemo(() => Math.max(1, ...buckets.map((b) => b.count)), [buckets]);

  if (buckets.length === 0) {
    return <div className="empty">No photos in range. Point at a folder and scan to begin.</div>;
  }

  const innerW = WIDTH - PAD * 2;
  const innerH = HEIGHT - PAD * 2;
  const bw = innerW / buckets.length;
  // Avoid crowding labels when there are many buckets.
  const labelEvery = Math.ceil(buckets.length / 16);

  return (
    <svg className="timeline" viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label="photo timeline">
      {buckets.map((b, i) => {
        // A non-empty bucket always gets a visible bar, however small next to the max.
        const h = b.count === 0 ? 0 : Math.max(2, (b.count / max) * innerH);
        const x = PAD + i * bw;
        const y = PAD + (innerH - h);
        const isSel = b.bucketStart === selected;
        return (
          <g
            key={b.bucketStart}
            className={b.count > 0 ? "bar" : "bar bar-empty"}
            onClick={() => b.count > 0 && onSelect(b.bucketStart, b.count)}
          >
            {/* Invisible hit area the full column height, so short bars are easy to click. */}
            <rect x={x} y={PAD} width={Math.max(1, bw)} height={innerH} fill="transparent">
              <title>{`${tooltipLabel(b.bucketStart, level)}: ${b.count}`}</title>
            </rect>
            {b.count > 0 && (
              <rect
                x={x + 1}
                y={y}
                width={Math.max(1, bw - 2)}
                height={h}
                className={isSel ? "bar-rect sel" : "bar-rect"}
              />
            )}
            {i % labelEvery === 0 && (
              <text x={x + bw / 2} y={HEIGHT - 8} textAnchor="middle" className="bar-label">
                {label(b.bucketStart, level)}
              </text>
            )}
          </g>
        );
      })}
    </svg>
  );
}
