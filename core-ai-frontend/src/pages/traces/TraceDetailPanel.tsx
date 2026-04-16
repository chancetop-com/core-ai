import { useEffect, useState } from 'react';
import { X, ExternalLink, Copy, Clock, Zap } from 'lucide-react';
import { api } from '../../api/client';
import type { Trace, Span } from '../../api/client';
import StatusBadge from '../../components/StatusBadge';

interface Props {
  traceId: string;
  onClose: () => void;
}

type Tab = 'overview' | 'spans' | 'input' | 'output' | 'raw';

export default function TraceDetailPanel({ traceId, onClose }: Props) {
  const [trace, setTrace] = useState<Trace | null>(null);
  const [spans, setSpans] = useState<Span[]>([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<Tab>('overview');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.all([api.traces.get(traceId), api.traces.spans(traceId)])
      .then(([t, s]) => {
        if (cancelled) return;
        setTrace(t);
        setSpans(s || []);
      })
      .catch(e => console.warn('load trace failed', e))
      .finally(() => !cancelled && setLoading(false));
    return () => { cancelled = true; };
  }, [traceId]);

  const formatDuration = (ms?: number) => {
    if (!ms) return '-';
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(1)}s`;
  };

  const copyId = () => {
    navigator.clipboard?.writeText(trace?.traceId || traceId);
  };

  return (
    <div className="flex flex-col border-l h-full w-[540px] shrink-0"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      {/* Header */}
      <div className="px-4 py-3 border-b flex items-start justify-between gap-2"
        style={{ borderColor: 'var(--color-border)' }}>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <div className="font-semibold text-base truncate">{trace?.name || 'Loading...'}</div>
            {trace && <StatusBadge status={trace.status} />}
          </div>
          {trace && (
            <div className="flex items-center gap-3 mt-1.5 flex-wrap">
              {trace.agentName && (
                <span className="text-xs px-2 py-0.5 rounded-full"
                  style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}>
                  {trace.agentName}
                </span>
              )}
              {trace.model && (
                <span className="text-xs px-2 py-0.5 rounded-full"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                  {trace.model}
                </span>
              )}
              {trace.sessionId && (
                <span className="text-xs font-mono" style={{ color: 'var(--color-text-secondary)' }}>
                  session: {trace.sessionId.substring(0, 8)}
                </span>
              )}
            </div>
          )}
          <div className="flex items-center gap-3 mt-1.5 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            {trace && (
              <>
                <span className="flex items-center gap-1"><Clock size={12} />{formatDuration(trace.durationMs)}</span>
                <span className="flex items-center gap-1"><Zap size={12} />{trace.totalTokens || 0} tok ({trace.inputTokens || 0}/{trace.outputTokens || 0})</span>
                <span>{spans.length} spans</span>
              </>
            )}
          </div>
          <div className="flex items-center gap-1 mt-1.5 text-[11px] font-mono" style={{ color: 'var(--color-text-secondary)' }}>
            <span className="truncate">{trace?.traceId || traceId}</span>
            <button onClick={copyId} className="p-0.5 rounded cursor-pointer hover:opacity-80" title="Copy trace ID">
              <Copy size={11} />
            </button>
          </div>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <a href={`/traces/${trace?.traceId || traceId}`} target="_blank" rel="noopener noreferrer"
            className="p-1.5 rounded cursor-pointer" title="Open in full page"
            style={{ color: 'var(--color-text-secondary)' }}>
            <ExternalLink size={14} />
          </a>
          <button onClick={onClose} className="p-1.5 rounded cursor-pointer"
            style={{ color: 'var(--color-text-secondary)' }}
            title="Close">
            <X size={16} />
          </button>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex border-b" style={{ borderColor: 'var(--color-border)' }}>
        {(['overview', 'spans', 'input', 'output', 'raw'] as Tab[]).map(t => (
          <button key={t} onClick={() => setTab(t)}
            className="px-3 py-2 text-xs cursor-pointer border-b-2 -mb-[2px] transition-colors uppercase tracking-wide"
            style={{
              borderColor: tab === t ? 'var(--color-primary)' : 'transparent',
              color: tab === t ? 'var(--color-primary)' : 'var(--color-text-secondary)',
              fontWeight: tab === t ? 600 : 400,
            }}>
            {t}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="flex-1 overflow-auto">
        {loading && <div className="p-6 text-sm text-center" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>}
        {!loading && !trace && <div className="p-6 text-sm text-center" style={{ color: 'var(--color-text-secondary)' }}>Trace not found</div>}
        {!loading && trace && tab === 'overview' && <OverviewTab trace={trace} spans={spans} />}
        {!loading && trace && tab === 'spans' && <SpansTab spans={spans} />}
        {!loading && trace && tab === 'input' && <CodePanel content={trace.input} />}
        {!loading && trace && tab === 'output' && <CodePanel content={trace.output} />}
        {!loading && trace && tab === 'raw' && <CodePanel content={JSON.stringify({ trace, spans }, null, 2)} />}
      </div>
    </div>
  );
}

function OverviewTab({ trace, spans }: { trace: Trace; spans: Span[] }) {
  const row = (label: string, value: React.ReactNode) => (
    <div className="flex items-start gap-3 py-1.5">
      <div className="text-xs w-28 shrink-0" style={{ color: 'var(--color-text-secondary)' }}>{label}</div>
      <div className="text-xs font-mono flex-1 break-all" style={{ color: 'var(--color-text)' }}>{value ?? '—'}</div>
    </div>
  );
  return (
    <div className="p-4 text-sm">
      <div className="mb-4">
        <h4 className="text-xs uppercase tracking-wide mb-2 font-semibold" style={{ color: 'var(--color-text-secondary)' }}>Meta</h4>
        {row('Type', trace.type || '—')}
        {row('Source', trace.source || '—')}
        {row('Session', trace.sessionId || '—')}
        {row('User', trace.userId || '—')}
        {row('Started', trace.startedAt)}
        {row('Completed', trace.completedAt)}
      </div>
      <div>
        <h4 className="text-xs uppercase tracking-wide mb-2 font-semibold" style={{ color: 'var(--color-text-secondary)' }}>Usage</h4>
        {row('Input tokens', trace.inputTokens ?? 0)}
        {row('Output tokens', trace.outputTokens ?? 0)}
        {row('Total tokens', trace.totalTokens ?? 0)}
        {row('Span count', spans.length)}
      </div>
    </div>
  );
}

function SpansTab({ spans }: { spans: Span[] }) {
  if (spans.length === 0) {
    return <div className="p-6 text-sm text-center" style={{ color: 'var(--color-text-secondary)' }}>No spans</div>;
  }
  const sorted = [...spans].sort((a, b) => {
    const at = new Date(a.startedAt || 0).getTime();
    const bt = new Date(b.startedAt || 0).getTime();
    return at - bt;
  });
  const min = Math.min(...sorted.map(s => new Date(s.startedAt || 0).getTime()));
  const max = Math.max(...sorted.map(s => new Date(s.completedAt || s.startedAt || 0).getTime()));
  const total = Math.max(max - min, 1);
  return (
    <div className="p-3 space-y-1">
      {sorted.map(s => {
        const start = new Date(s.startedAt || 0).getTime();
        const end = new Date(s.completedAt || s.startedAt || 0).getTime();
        const left = ((start - min) / total) * 100;
        const width = Math.max(((end - start) / total) * 100, 0.5);
        return (
          <div key={s.spanId} className="text-xs">
            <div className="flex items-center gap-2 mb-0.5">
              <span className="px-1.5 py-0.5 rounded text-[10px]"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                {s.type}
              </span>
              <span className="font-medium truncate flex-1">{s.name}</span>
              <span style={{ color: 'var(--color-text-secondary)' }}>{s.durationMs ? `${s.durationMs}ms` : '-'}</span>
            </div>
            <div className="relative h-2 rounded" style={{ background: 'var(--color-bg-tertiary)' }}>
              <div className="absolute h-2 rounded" style={{
                left: `${left}%`,
                width: `${width}%`,
                background: 'var(--color-primary)',
                opacity: 0.7,
              }} />
            </div>
          </div>
        );
      })}
    </div>
  );
}

function CodePanel({ content }: { content?: string }) {
  if (!content) return <div className="p-6 text-sm text-center" style={{ color: 'var(--color-text-secondary)' }}>(empty)</div>;
  let pretty = content;
  try {
    const parsed = JSON.parse(content);
    pretty = JSON.stringify(parsed, null, 2);
  } catch { /* keep as is */ }
  return (
    <pre className="p-3 text-xs whitespace-pre-wrap font-mono overflow-x-auto" style={{ color: 'var(--color-text)' }}>
      {pretty}
    </pre>
  );
}
