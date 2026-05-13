import { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';

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
  return <img src={resolved} alt={alt} className="max-w-full rounded my-2" />;
}
