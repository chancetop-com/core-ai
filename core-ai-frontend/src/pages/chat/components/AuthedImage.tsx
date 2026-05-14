import { useEffect, useState } from 'react';
import { Loader2, X as CloseIcon } from 'lucide-react';

interface Props {
  src?: string;
  alt?: string;
}

function needsAuthFetch(url: string): boolean {
  // Anything pointing at /api/files/{id}/content on this backend needs the bearer token
  // — absolute (https://host/api/...) or relative (/api/...) both match.
  return /\/api\/files\/[^/?#]+\/content/.test(url);
}

async function fetchBlob(url: string): Promise<Blob> {
  const apiKey = localStorage.getItem('apiKey');
  const headers: Record<string, string> = {};
  if (apiKey) headers['Authorization'] = `Bearer ${apiKey}`;
  const res = await fetch(url, { headers });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.blob();
}

export default function AuthedImage({ src, alt }: Props) {
  const [resolved, setResolved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [lightboxOpen, setLightboxOpen] = useState(false);

  useEffect(() => {
    if (!src) {
      setResolved(null);
      return;
    }
    if (!needsAuthFetch(src)) {
      setResolved(src);
      return;
    }
    let cancelled = false;
    let createdUrl: string | null = null;
    setResolved(null);
    setError(null);
    fetchBlob(src).then(blob => {
      if (cancelled) return;
      createdUrl = URL.createObjectURL(blob);
      setResolved(createdUrl);
    }).catch(err => {
      if (cancelled) return;
      setError(err instanceof Error ? err.message : String(err));
    });
    return () => {
      cancelled = true;
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, [src]);

  // ESC closes lightbox; lock body scroll while open so the page behind doesn't scroll
  useEffect(() => {
    if (!lightboxOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setLightboxOpen(false);
    };
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [lightboxOpen]);

  if (error) {
    return (
      <span className="inline-flex items-center gap-2 px-3 py-2 my-2 rounded-lg border text-xs"
        style={{ borderColor: 'var(--color-error)', background: 'var(--color-error)' + '12', color: 'var(--color-text-secondary)' }}
        title={src}>
        <span style={{ color: 'var(--color-error)' }}>⚠</span>
        Failed to load image: {error}
      </span>
    );
  }
  if (!resolved) {
    return (
      <span className="inline-flex items-center gap-2 text-xs my-2" style={{ color: 'var(--color-text-muted)' }}>
        <Loader2 size={14} className="animate-spin" /> Loading image…
      </span>
    );
  }
  return (
    <>
      <img
        src={resolved}
        alt={alt}
        className="max-w-full rounded my-2 cursor-zoom-in"
        onClick={() => setLightboxOpen(true)}
      />
      {lightboxOpen && (
        <div
          role="dialog"
          aria-modal="true"
          className="fixed inset-0 z-[1000] flex items-center justify-center cursor-zoom-out"
          style={{ background: 'rgba(0,0,0,0.85)' }}
          onClick={() => setLightboxOpen(false)}
        >
          <button
            type="button"
            aria-label="Close"
            className="absolute top-4 right-4 p-2 rounded-full hover:opacity-80"
            style={{ background: 'rgba(255,255,255,0.12)', color: 'white' }}
            onClick={(e) => { e.stopPropagation(); setLightboxOpen(false); }}
          >
            <CloseIcon size={20} />
          </button>
          <img
            src={resolved}
            alt={alt}
            className="max-w-[95vw] max-h-[95vh] object-contain cursor-default"
            onClick={(e) => e.stopPropagation()}
          />
        </div>
      )}
    </>
  );
}
