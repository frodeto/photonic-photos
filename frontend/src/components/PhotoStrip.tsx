import { useEffect, useState } from "react";
import { api, type Photo } from "../api/client";

interface Props {
  photos: Photo[];
  selectedIds: Set<number>;
  onToggleSelect: (id: number) => void;
  onOpen: (index: number) => void;
}

/** Human-readable exposure summary from whatever EXIF fields are present, e.g. "35mm · f/2.8 · 1/200s". */
export function exposureLine(p: Photo): string {
  const parts: string[] = [];
  if (p.focalLength) parts.push(`${Math.round(p.focalLength)}mm`);
  if (p.aperture) parts.push(`f/${p.aperture}`);
  if (p.shutterSpeed) parts.push(`${p.shutterSpeed}s`);
  return parts.join(" · ");
}

/**
 * A single thumbnail. The image loads eagerly (so a whole bucket fills in at once); hovering
 * reveals a metadata overlay, the corner checkbox marks the photo for the copy set, and clicking
 * the thumbnail opens the large preview.
 */
function Thumb({
  photo,
  selected,
  onToggleSelect,
  onOpen,
}: {
  photo: Photo;
  selected: boolean;
  onToggleSelect: () => void;
  onOpen: () => void;
}) {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    if (photo.hasThumbnail) api.thumbnailUrl(photo).then((u) => active && setUrl(u));
    return () => {
      active = false;
    };
  }, [photo]);

  const exposure = exposureLine(photo);

  return (
    <div className={selected ? "thumb sel" : "thumb"} onClick={onOpen}>
      <input
        type="checkbox"
        className="thumb-check"
        checked={selected}
        title="Select for the “look closer at” copy set"
        // Stop the click from also opening the preview, and toggle on change.
        onClick={(e) => e.stopPropagation()}
        onChange={onToggleSelect}
      />
      {url ? <img src={url} alt={photo.fileName} loading="lazy" /> : <div className="thumb-ph" />}
      <div className="thumb-meta">
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
      </div>
      <span className="thumb-name">{photo.fileName}</span>
    </div>
  );
}

export default function PhotoStrip({ photos, selectedIds, onToggleSelect, onOpen }: Props) {
  if (photos.length === 0) return <div className="empty">Select a bar in the timeline to see photos.</div>;
  return (
    <div className="strip">
      {photos.map((p, i) => (
        <Thumb
          key={p.id}
          photo={p}
          selected={selectedIds.has(p.id)}
          onToggleSelect={() => onToggleSelect(p.id)}
          onOpen={() => onOpen(i)}
        />
      ))}
    </div>
  );
}
