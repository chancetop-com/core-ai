import { useEffect, useState } from 'react';
import { Check, Copy, Link2, Loader2, X } from 'lucide-react';
import { usePermission } from '../../../api/permissions';
import { sessionShareApi } from '../../../api/sessionShares';
import type { SessionShareView, SharedSessionArtifact } from '../../../api/sessionShares';

interface Props {
  sessionId: string;
  title?: string;
  running?: boolean;
  onClose: () => void;
}

export default function SessionShareModal({ sessionId, title, running = false, onClose }: Props) {
  const canViewTrace = usePermission('trace.view');
  const [share, setShare] = useState<SessionShareView | null>(null);
  const [artifacts, setArtifacts] = useState<SharedSessionArtifact[]>([]);
  const [expiry, setExpiry] = useState<1 | 7 | 30>(7);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);
  const active = share && !share.revoked_at && new Date(share.expires_at).getTime() > Date.now();
  const link = active && share?.url ? new URL(share.url, window.location.origin).href : '';

  useEffect(() => {
    let cancelled = false;
    sessionShareApi.get(sessionId).then(response => {
      if (cancelled) return;
      setShare(response.share ?? null);
      setArtifacts(response.artifacts ?? []);
    }).catch(reason => {
      if (!cancelled) {
        setLoadFailed(true);
        setError(reason instanceof Error ? reason.message : 'Unable to load sharing settings.');
      }
    })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [sessionId]);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onClose]);

  const copy = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
    } catch {
      setError('Link is ready. Select and copy it from the field below.');
    }
  };

  const create = async () => {
    setBusy(true);
    setError('');
    try {
      const created = await sessionShareApi.create(sessionId, expiry);
      setShare(created);
      if (!created.url) throw new Error('The server did not return a share link. Please reopen sharing settings.');
      await copy(new URL(created.url, window.location.origin).href);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Unable to create a share link.');
    } finally { setBusy(false); }
  };

  const revoke = async () => {
    if (!share) return;
    setBusy(true);
    setError('');
    try {
      await sessionShareApi.revoke(sessionId, share.id);
      setShare(null);
      setCopied(false);
      const current = await sessionShareApi.get(sessionId);
      setArtifacts(current.artifacts ?? []);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Unable to revoke this link.');
    } finally { setBusy(false); }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4" style={{ background: 'rgba(0,0,0,0.45)' }} onClick={onClose}>
      <section role="dialog" aria-modal="true" aria-labelledby="share-session-title" onClick={event => event.stopPropagation()}
        className="w-full max-w-lg rounded-xl border p-5 shadow-xl max-h-[90vh] overflow-auto"
        style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
        <div className="flex items-center justify-between gap-3 mb-4">
          <h2 id="share-session-title" className="font-semibold flex items-center gap-2"><Link2 size={18} /> Share conversation</h2>
          <button type="button" autoFocus aria-label="Close sharing" className="cursor-pointer" onClick={onClose}><X size={18} /></button>
        </div>
        {title && <p className="text-sm font-medium mb-3 truncate">{title}</p>}
        <div className="rounded-lg p-3 text-sm space-y-2" style={{ background: 'var(--color-bg-secondary)' }}>
          <p>Anyone with the link can read this conversation, its traces and included files. No login is required.</p>
          <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Trace inputs and outputs may contain system prompts, injected memories, retrieved content and tool results. Known credential fields are masked; review sensitive content before sharing.</p>
        </div>
        {loading ? <div className="py-6 flex justify-center"><Loader2 className="animate-spin" size={20} /></div> : <>
          {active && share ? <div className="mt-4 space-y-3">
            <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {share.message_count} messages · Shared {new Date(share.created_at).toLocaleString()}<br />
              Expires {new Date(share.expires_at).toLocaleString()}. Later messages are not included.
            </p>
            <label className="text-xs block">Share link
              <input aria-label="Share link" value={link} readOnly onFocus={event => event.target.select()}
                className="mt-1 w-full rounded-lg border px-3 py-2 text-xs" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }} />
            </label>
            <div className="flex justify-between gap-2">
              <button type="button" disabled={busy} onClick={() => void revoke()} className="text-sm cursor-pointer disabled:opacity-50" style={{ color: 'var(--color-error)' }}>Revoke link</button>
              <button type="button" onClick={() => void copy(link)} disabled={busy} className="flex items-center gap-2 rounded-lg px-4 py-2 text-sm cursor-pointer disabled:opacity-50" style={{ background: 'var(--color-primary)', color: 'white' }}>
                {copied ? <Check size={15} /> : <Copy size={15} />}{copied ? 'Copied' : 'Copy link'}
              </button>
            </div>
          </div> : <div className="mt-4 space-y-3">
            <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Includes the current conversation through its last completed turn. New messages will not be added to this link.</p>
            <label className="text-sm flex items-center gap-3">Expires after
              <select aria-label="Share expiry" value={expiry} onChange={event => setExpiry(Number(event.target.value) as 1 | 7 | 30)} disabled={busy}
                className="rounded-lg border px-3 py-2" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
                <option value={1}>1 day</option><option value={7}>7 days</option><option value={30}>30 days</option>
              </select>
            </label>
            {!canViewTrace && <p className="text-xs" role="status">Trace viewing permission is required to create a share.</p>}
            {running && <p className="text-xs" role="status">Wait for the current turn to finish before creating a share.</p>}
            <button type="button" disabled={busy || running || !canViewTrace || loadFailed} onClick={() => void create()}
              className="w-full flex justify-center items-center gap-2 rounded-lg px-4 py-2 text-sm cursor-pointer disabled:opacity-50"
              style={{ background: 'var(--color-primary)', color: 'white' }}>
              {busy ? <Loader2 size={15} className="animate-spin" /> : <Link2 size={15} />} Create and copy link
            </button>
          </div>}
          {!!artifacts.length && <div className="mt-4 pt-3 border-t" style={{ borderColor: 'var(--color-border)' }}>
            <p className="text-xs font-medium mb-2">Included files ({artifacts.length})</p>
            <ul className="text-xs space-y-1 max-h-28 overflow-auto" style={{ color: 'var(--color-text-secondary)' }}>{artifacts.map(artifact => <li key={artifact.file_id} className="truncate">{artifact.title || artifact.file_name}</li>)}</ul>
          </div>}
        </>}
        {error && <p role="alert" className="mt-3 text-xs" style={{ color: 'var(--color-error)' }}>{error}</p>}
      </section>
    </div>
  );
}
