import { useMemo } from "react";
import type { Bucket, TimelineBucket } from "../api/client";

interface Props {
  buckets: TimelineBucket[];
  bucket: Bucket;
  selected: number | null;
  onSelect: (bucketStart: number) => void;
}

const WIDTH = 1100;
const HEIGHT = 220;
const PAD = 28;

function label(ms: number, bucket: Bucket): string {
  const d = new Date(ms);
  if (bucket === "year") return String(d.getFullYear());
  if (bucket === "month") return d.toLocaleString(undefined, { month: "short", year: "2-digit" });
  return d.toLocaleDateString();
}

/** SVG bar histogram of photo counts per time bucket. Click a bar to drill in. */
export default function Timeline({ buckets, bucket, selected, onSelect }: Props) {
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
        const h = (b.count / max) * innerH;
        const x = PAD + i * bw;
        const y = PAD + (innerH - h);
        const isSel = b.bucketStart === selected;
        return (
          <g key={b.bucketStart} className="bar" onClick={() => onSelect(b.bucketStart)}>
            <rect
              x={x + 1}
              y={y}
              width={Math.max(1, bw - 2)}
              height={h}
              className={isSel ? "bar-rect sel" : "bar-rect"}
            >
              <title>{`${label(b.bucketStart, bucket)}: ${b.count}`}</title>
            </rect>
            {i % labelEvery === 0 && (
              <text x={x + bw / 2} y={HEIGHT - 8} textAnchor="middle" className="bar-label">
                {label(b.bucketStart, bucket)}
              </text>
            )}
          </g>
        );
      })}
    </svg>
  );
}
