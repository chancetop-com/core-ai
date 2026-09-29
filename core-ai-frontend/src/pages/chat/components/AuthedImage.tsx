import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { Lasso, Loader2, X as CloseIcon } from 'lucide-react';
import { fetchBlob, fileIdOf, needsAuthFetch, shareTokenOf } from '../../../api/authedBlob';
import ImageCanvasEditor from '../../../components/ImageCanvasEditor';

interface Props {
  src?: string;
  alt?: string;
  /** Image class; defaults to the inline message image. Callers that show a thumbnail pass their own. */
  className?: string;
}

interface EditTarget {
  fileId: string | null;
  shareToken: string | null;
  sourceUrl: string | null;
}

export default function AuthedImage({ src, alt, className }: Props) {
  const [resolved, setResolved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [lightboxOpen, setLightboxOpen] = useState(false);
  const [editorOpen, setEditorOpen] = useState(false);
  const editTarget = editTargetOf(src);

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
      <span className="relative inline-block group my-2">
        <img
          src={resolved}
          alt={alt}
          className={className ?? 'block max-w-full rounded cursor-zoom-in'}
          onClick={() => setLightboxOpen(true)}
        />
        {editTarget && (
          <button
            type="button"
            onClick={(e) => { e.stopPropagation(); setEditorOpen(true); }}
            className="absolute top-2 right-2 inline-flex items-center gap-1 px-2 py-1 rounded-md text-xs opacity-0 group-hover:opacity-100 focus:opacity-100 cursor-pointer"
            style={{ background: 'rgba(0,0,0,0.65)', color: 'white' }}
            title="圈选编辑">
            <Lasso size={12} /> 圈选编辑
          </button>
        )}
      </span>
      {editorOpen && editTarget && (
        <ImageCanvasEditor
          fileId={editTarget.fileId ?? undefined}
          shareToken={editTarget.shareToken ?? undefined}
          sourceUrl={editTarget.sourceUrl ?? undefined}
          src={src ?? ''}
          blobUrl={resolved}
          onClose={() => setEditorOpen(false)}
        />
      )}
      {lightboxOpen && createPortal(
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
          {editTarget && (
            <button
              type="button"
              className="absolute bottom-6 inline-flex items-center gap-1.5 px-3 py-2 rounded-md text-sm cursor-pointer"
              style={{ background: 'rgba(255,255,255,0.14)', color: 'white' }}
              onClick={(e) => { e.stopPropagation(); setLightboxOpen(false); setEditorOpen(true); }}>
              <Lasso size={14} /> 圈选编辑
            </button>
          )}
        </div>,
        document.body
      )}
    </>
  );
}

function editTargetOf(src?: string): EditTarget | null {
  if (!src) return null;
  const fileId = fileIdOf(src);
  if (fileId) return { fileId, shareToken: null, sourceUrl: null };
  const shareToken = shareTokenOf(src);
  if (shareToken) return { fileId: null, shareToken, sourceUrl: null };
  // an uploaded attachment lives only in object storage; the server reads it from there and refuses
  // anything that is not platform storage, so the button may appear before that check
  if (/^https?:\/\//i.test(src)) return { fileId: null, shareToken: null, sourceUrl: src };
  return null;
}
