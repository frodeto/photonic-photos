import { useEffect, useState } from "react";
import { api, type Photo } from "../api/client";

interface Props {
  photos: Photo[];
  selectedIds: Set<number>;
  onToggle: (id: number) => void;
}

/** A single thumbnail with hover-to-load preview and click-to-select. */
function Thumb({ photo, selected, onToggle }: { photo: Photo; selected: boolean; onToggle: () => void }) {
  const [url, setUrl] = useState<string | null>(null);
  const [hovered, setHovered] = useState(false);

  useEffect(() => {
    let active = true;
    if (hovered && !url && photo.hasThumbnail) {
      api.thumbnailUrl(photo.id).then((u) => active && setUrl(u));
    }
    return () => {
      active = false;
    };
  }, [hovered, url, photo.id, photo.hasThumbnail]);

  return (
    <div
      className={selected ? "thumb sel" : "thumb"}
      onMouseEnter={() => setHovered(true)}
      onClick={onToggle}
      title={`${photo.fileName}\n${new Date(photo.createdDate).toLocaleString()}${
        photo.cameraModel ? `\n${photo.cameraModel}` : ""
      }`}
    >
      {url ? <img src={url} alt={photo.fileName} loading="lazy" /> : <div className="thumb-ph" />}
      <span className="thumb-name">{photo.fileName}</span>
    </div>
  );
}

export default function PhotoStrip({ photos, selectedIds, onToggle }: Props) {
  if (photos.length === 0) return <div className="empty">Select a bar in the timeline to see photos.</div>;
  return (
    <div className="strip">
      {photos.map((p) => (
        <Thumb key={p.id} photo={p} selected={selectedIds.has(p.id)} onToggle={() => onToggle(p.id)} />
      ))}
    </div>
  );
}
