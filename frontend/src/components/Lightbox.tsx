import { useEffect, useState } from "react";
import { api, type Photo } from "../api/client";
import { exposureLine } from "./PhotoStrip";

interface Props {
  photos: Photo[];
  index: number;
  selectedIds: Set<number>;
  onToggleSelect: (id: number) => void;
  onNavigate: (index: number) => void;
  onClose: () => void;
}

/**
 * Full-window preview of a single photo: the larger ~1024px preview plus its metadata, with
 * keyboard/arrow navigation through the current strip and a toggle to add it to the copy set.
 */
export default function Lightbox({ photos, index, selectedIds, onToggleSelect, onNavigate, onClose }: Props) {
  const photo = photos[index];
  const [url, setUrl] = useState<string | null>(null);

  // Load the preview for whichever photo is showing; reset to the spinner state on each change.
  useEffect(() => {
    let active = true;
    setUrl(null);
    if (photo?.hasThumbnail) api.previewUrl(photo).then((u) => active && setUrl(u));
    return () => {
      active = false;
    };
  }, [photo]);

  // Warm the previews next to the current one: the backend renders+caches on first request and
  // the response is immutably cacheable, so stepping to a neighbour is instant instead of
  // waiting seconds for a RAW preview extraction.
  useEffect(() => {
    [index - 1, index + 1].forEach((i) => {
      const neighbour = photos[i];
      if (neighbour?.hasThumbnail) {
        api.previewUrl(neighbour).then((u) => {
          new Image().src = u;
        });
      }
    });
  }, [index, photos]);

  // Escape closes; arrows step through the strip (clamped at the ends); Space toggles the
  // copy-set flag, enabling keyboard-only triage. Ignore keys aimed at a focused control —
  // e.g. Space on a still-focused nav button must not both navigate and toggle.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      if (t && (t.tagName === "BUTTON" || t.tagName === "INPUT")) return;
      if (e.key === "Escape") onClose();
      else if (e.key === "ArrowLeft" && index > 0) onNavigate(index - 1);
      else if (e.key === "ArrowRight" && index < photos.length - 1) onNavigate(index + 1);
      else if (e.key === " ") {
        e.preventDefault(); // don't scroll the page behind the lightbox
        if (photo) onToggleSelect(photo.id);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [index, photos.length, photo, onClose, onNavigate, onToggleSelect]);

  if (!photo) return null;
  const selected = selectedIds.has(photo.id);
  const exposure = exposureLine(photo);

  return (
    <div className="lightbox" onClick={onClose}>
      <button className="lb-close" title="Close (Esc)" onClick={onClose}>
        ✕
      </button>
      {index > 0 && (
        <button
          className="lb-nav lb-prev"
          title="Previous (←)"
          onClick={(e) => {
            e.stopPropagation();
            onNavigate(index - 1);
          }}
        >
          ‹
        </button>
      )}
      {index < photos.length - 1 && (
        <button
          className="lb-nav lb-next"
          title="Next (→)"
          onClick={(e) => {
            e.stopPropagation();
            onNavigate(index + 1);
          }}
        >
          ›
        </button>
      )}

      {/* Stop clicks on the figure from bubbling to the backdrop (which closes). */}
      <figure className="lb-figure" onClick={(e) => e.stopPropagation()}>
        {url ? (
          <img src={url} alt={photo.fileName} />
        ) : photo.hasThumbnail ? (
          <div className="lb-loading">Loading preview…</div>
        ) : (
          <div className="lb-loading">No preview available</div>
        )}
        <figcaption className="lb-caption">
          <div className="lb-info">
            <strong>{photo.fileName}</strong>
            <span>{new Date(photo.createdDate).toLocaleString()}</span>
            {photo.cameraModel && <span>{photo.cameraModel}</span>}
            {photo.lens && <span>{photo.lens}</span>}
            {exposure && <span>{exposure}</span>}
            {photo.width && photo.height && (
              <span>
                {photo.width}×{photo.height}
              </span>
            )}
            <span className="lb-count">
              {index + 1} / {photos.length}
            </span>
          </div>
          <label className="lb-select" title="Toggle with Space">
            <input type="checkbox" checked={selected} onChange={() => onToggleSelect(photo.id)} />
            Add to “look closer at” copy set <span className="lb-key">Space</span>
          </label>
        </figcaption>
      </figure>
    </div>
  );
}
