import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ComponentProps } from 'react';
import type ReactMarkdown from 'react-markdown';
import { useParams } from 'react-router-dom';
import { Download, Link2, Loader2, X } from 'lucide-react';
import type { Span, Trace } from '../../api/client';
import { downloadSharedFile, publicSessionShareApi, sharedFileUrl } from '../../api/sessionShares';
import type { SharedSessionArtifact, SharedSessionView } from '../../api/sessionShares';
import type { ChatMessage } from '../chat/types';
import { ChatMessageRow } from '../chat/components/ChatMessagesPanel';
import AuthedImage from '../chat/components/AuthedImage';
import TraceInspector from '../traces/TraceInspector';
import { sharedHistoryToChatMessages, sharedImageArtifacts } from './sharedHistory';

const noAction = () => {};

export default function SharedSession() {
  const { token = '' } = useParams();
  return <SharedSessionContent key={token} token={token} />;
}

function SharedSessionContent({ token }: { token: string }) {
  const [session, setSession] = useState<SharedSessionView | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [nextOffset, setNextOffset] = useState<number | undefined>();
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState('');
  const [downloadError, setDownloadError] = useState('');
  const [traceId, setTraceId] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([publicSessionShareApi.get(token), publicSessionShareApi.messages(token)])
      .then(([view, page]) => {
        if (cancelled) return;
        setSession(view);
        setMessages(sharedHistoryToChatMessages(page.messages ?? [], view.artifacts ?? [], token));
        setNextOffset(page.next_offset ?? undefined);
      })
      .catch(reason => { if (!cancelled) setError(reason instanceof Error ? reason.message : 'Shared conversation is unavailable.'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [token]);

  useEffect(() => {
    if (!session) return;
    let timer: number;
    const checkExpiry = () => {
      const remaining = new Date(session.expires_at).getTime() - Date.now();
      if (remaining > 0) {
        timer = window.setTimeout(checkExpiry, Math.min(remaining, 2_147_483_647));
        return;
      }
      setError('This share has expired. Ask the owner for a new link.');
      setSession(null);
      setMessages([]);
      setTraceId(null);
    };
    checkExpiry();
    return () => window.clearTimeout(timer);
  }, [session]);

  const download = useCallback(async (artifact: SharedSessionArtifact) => {
    setDownloadError('');
    try {
      await downloadSharedFile(artifact, token);
    } catch (reason) {
      setDownloadError(reason instanceof Error ? reason.message : 'File is unavailable.');
    }
  }, [token]);

  const images = useMemo(() => sharedImageArtifacts(session?.artifacts ?? [], token), [session?.artifacts, token]);
  const markdown = useMemo<ComponentProps<typeof ReactMarkdown>['components']>(() => ({
    img: ({ src, alt }) => images.has(sharedFileUrl(src, token) ?? '')
      ? <AuthedImage src={src} alt={alt} readOnly />
      : <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Image unavailable{alt ? `: ${alt}` : ''}</span>,
    a: ({ href, children }) => {
      const fileUrl = sharedFileUrl(href, token);
      if (fileUrl) {
        const artifact = session?.artifacts?.find(item => sharedFileUrl(item.download_url, token) === fileUrl);
        const image = images.get(fileUrl);
        if (image) {
          return <AuthedImage src={fileUrl} alt={image.file_name} readOnly />;
        }
        return <button type="button" className="underline cursor-pointer" onClick={() => void download(artifact ?? {
          file_id: '', file_name: 'download', download_url: fileUrl,
        })}>{children}</button>;
      }
      if (href && /^https?:\/\//i.test(href)) {
        try {
          if (new URL(href).origin !== window.location.origin) {
            return <a href={href} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer">{children}</a>;
          }
        } catch { /* Render malformed links as text. */ }
      }
      return <span>{children}</span>;
    },
  }), [download, session?.artifacts, token, images]);

  const loadMore = async () => {
    if (nextOffset === undefined || loadingMore) return;
    setLoadingMore(true);
    try {
      const page = await publicSessionShareApi.messages(token, nextOffset);
      setMessages(previous => [...previous, ...sharedHistoryToChatMessages(page.messages ?? [], session?.artifacts ?? [], token)]);
      setNextOffset(page.next_offset ?? undefined);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'Unable to load more messages.');
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <main className="min-h-screen flex flex-col" style={{ background: 'var(--color-bg)', color: 'var(--color-text)' }}>
      <meta name="robots" content="noindex, nofollow, noarchive" />
      <meta name="referrer" content="no-referrer" />
      <header className="border-b px-5 py-4" style={{ borderColor: 'var(--color-border)' }}>
        <div className="max-w-5xl mx-auto">
          <div className="flex items-center gap-2 text-xs mb-2" style={{ color: 'var(--color-text-secondary)' }}>
            <Link2 size={14} /> Shared conversation <span className="rounded border px-1.5 py-0.5" style={{ borderColor: 'var(--color-border)' }}>Read only</span>
          </div>
          <h1 className="font-semibold text-lg">{session?.title || 'Shared conversation'}</h1>
          {session && <p className="mt-1 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            Shared {new Date(session.created_at).toLocaleString()} · {session.message_count} messages · Expires {new Date(session.expires_at).toLocaleString()}
          </p>}
        </div>
      </header>
      {loading ? <div className="p-10 flex justify-center gap-2"><Loader2 size={18} className="animate-spin" /> Loading conversation…</div> : error ? (
        <div role="alert" className="p-10 text-center">{error}</div>
      ) : session && (
        <div className="flex flex-1 min-h-0">
          <section aria-label="Shared messages" className="flex-1 min-w-0 px-4 py-6">
            <div className="max-w-4xl mx-auto space-y-5">
              {messages.map((message, index) => <ChatMessageRow key={message.seq ?? index}
                msg={message} msgIndex={index} messagesLength={messages.length}
                isStreamingLast={false} isThinking={false} planTodos={null} sessionArtifacts={[]}
                agentMarkdownComponents={markdown} onOpenArtifact={noAction} terminalEnabled={false}
                onOpenSandboxTerminal={noAction} onApproval={noAction} showFeedback={false}
                onQuickReply={noAction} interactiveDisabled readOnly onViewTrace={setTraceId}
              />)}
              {nextOffset !== undefined && <button type="button" onClick={() => void loadMore()} disabled={loadingMore}
                className="block mx-auto rounded-lg border px-4 py-2 text-sm disabled:opacity-50 cursor-pointer"
                style={{ borderColor: 'var(--color-border)' }}>{loadingMore ? 'Loading…' : 'Load more messages'}</button>}
              {!!session.artifacts?.length && <section aria-label="Shared files" className="rounded-xl border p-4" style={{ borderColor: 'var(--color-border)' }}>
                <h2 className="text-sm font-medium mb-3">Files included in this share</h2>
                <div className="flex flex-wrap gap-2">
                  {session.artifacts.map(artifact => <button type="button" key={artifact.file_id}
                    onClick={() => void download(artifact)} disabled={!sharedFileUrl(artifact.download_url, token)}
                    className="flex items-center gap-2 rounded-lg border px-3 py-2 text-xs cursor-pointer disabled:opacity-40"
                    style={{ borderColor: 'var(--color-border)' }}><Download size={13} />{artifact.title || artifact.file_name}</button>)}
                </div>
                {downloadError && <p role="alert" className="mt-2 text-xs" style={{ color: 'var(--color-error)' }}>{downloadError}</p>}
              </section>}
              {downloadError && !session.artifacts?.length && <p role="alert">{downloadError}</p>}
            </div>
          </section>
          {traceId && <SharedTracePanel key={traceId} token={token} traceId={traceId} onClose={() => setTraceId(null)} />}
        </div>
      )}
    </main>
  );
}

function SharedTracePanel({ token, traceId, onClose }: { token: string; traceId: string; onClose: () => void }) {
  const [detail, setDetail] = useState<{ trace: Trace; spans: Span[] } | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let cancelled = false;
    Promise.all([publicSessionShareApi.trace(token, traceId), publicSessionShareApi.spans(token, traceId)])
      .then(([trace, spans]) => { if (!cancelled) setDetail({ trace, spans }); })
      .catch(() => { if (!cancelled) setError('Trace is currently unavailable. It may not have arrived yet, or the source data may no longer be retained.'); });
    return () => { cancelled = true; };
  }, [token, traceId]);
  const loadSpan = useCallback((id: string, spanId: string) => publicSessionShareApi.span(token, id, spanId), [token]);
  return (
    <aside aria-label="Shared trace" className="fixed inset-0 z-40 lg:sticky lg:top-0 lg:inset-auto lg:w-[560px] h-screen shrink-0 border-l overflow-auto"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      {!detail && <div className="p-5">
        <button type="button" aria-label="Close trace" className="float-right cursor-pointer" onClick={onClose}><X size={18} /></button>
        <p role={error ? 'status' : undefined} className="text-sm pr-6">{error || 'Loading trace…'}</p>
      </div>}
      {detail && <TraceInspector trace={detail.trace} spans={detail.spans} readOnly loadSpan={loadSpan} onClose={onClose} />}
    </aside>
  );
}
