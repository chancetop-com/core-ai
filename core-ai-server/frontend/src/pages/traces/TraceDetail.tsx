import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Clock, Zap } from 'lucide-react';
import { api } from '../../api/client';
import type { Trace, Span } from '../../api/client';
import StatusBadge from '../../components/StatusBadge';
import SpanTypeIcon from '../../components/SpanTypeIcon';

interface SpanNode extends Span {
  children: SpanNode[];
  depth: number;
}

function buildTree(spans: Span[]): SpanNode[] {
  const map = new Map<string, SpanNode>();
  const roots: SpanNode[] = [];
  spans.forEach(s => map.set(s.span_id, { ...s, children: [], depth: 0 }));
  map.forEach(node => {
    if (node.parent_span_id && map.has(node.parent_span_id)) {
      const parent = map.get(node.parent_span_id)!;
      node.depth = parent.depth + 1;
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  });
  return roots;
}

function flattenTree(nodes: SpanNode[]): SpanNode[] {
  const result: SpanNode[] = [];
  function walk(list: SpanNode[]) {
    list.forEach(n => { result.push(n); walk(n.children); });
  }
  walk(nodes);
  return result;
}

export default function TraceDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [trace, setTrace] = useState<Trace | null>(null);
  const [spans, setSpans] = useState<Span[]>([]);
  const [selected, setSelected] = useState<Span | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!id) return;
    Promise.all([api.traces.get(id), api.traces.spans(id)])
      .then(([t, s]) => { setTrace(t); setSpans(s); })
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) return <div className="p-6" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;
  if (!trace) return <div className="p-6">Trace not found</div>;

  const tree = buildTree(spans);
  const flat = flattenTree(tree);
  const totalDuration = trace.duration_ms || 1;

  return (
    <div className="p-6">
      <button onClick={() => navigate('/')}
        className="flex items-center gap-1 text-sm mb-4 cursor-pointer"
        style={{ color: 'var(--color-primary)' }}>
        <ArrowLeft size={16} /> Back to Traces
      </button>

      {/* Trace header */}
      <div className="rounded-xl border p-5 mb-6"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <div className="flex items-center justify-between mb-3">
          <h1 className="text-xl font-semibold">{trace.name || trace.trace_id}</h1>
          <StatusBadge status={trace.status} />
        </div>
        <div className="flex gap-6 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          <span className="flex items-center gap-1"><Clock size={14} /> {trace.duration_ms ? `${(trace.duration_ms / 1000).toFixed(2)}s` : '-'}</span>
          <span className="flex items-center gap-1"><Zap size={14} /> {trace.total_tokens?.toLocaleString() || '0'} tokens</span>
          <span>Session: {trace.session_id || '-'}</span>
          <span>User: {trace.user_id || '-'}</span>
        </div>
        {trace.input && (
          <div className="mt-3 p-3 rounded-lg text-sm" style={{ background: 'var(--color-bg-tertiary)' }}>
            <div className="text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Input</div>
            <pre className="whitespace-pre-wrap break-words">{trace.input}</pre>
          </div>
        )}
        {trace.output && (
          <div className="mt-2 p-3 rounded-lg text-sm" style={{ background: 'var(--color-bg-tertiary)' }}>
            <div className="text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Output</div>
            <pre className="whitespace-pre-wrap break-words">{trace.output}</pre>
          </div>
        )}
      </div>

      {/* Waterfall */}
      <div className="flex gap-4">
        <div className="flex-1 rounded-xl border overflow-hidden"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <div className="px-4 py-3 border-b font-medium text-sm" style={{ borderColor: 'var(--color-border)' }}>
            Spans ({spans.length})
          </div>
          {flat.map(span => {
            const startOffset = span.started_at && trace.started_at
              ? (new Date(span.started_at).getTime() - new Date(trace.started_at).getTime()) / totalDuration * 100
              : 0;
            const width = Math.max((span.duration_ms || 0) / totalDuration * 100, 1);

            return (
              <div key={span.span_id}
                onClick={() => setSelected(span)}
                className="flex items-center px-4 py-2 border-t cursor-pointer transition-colors text-sm"
                style={{
                  borderColor: 'var(--color-border)',
                  background: selected?.span_id === span.span_id ? 'var(--color-bg-tertiary)' : 'transparent',
                  paddingLeft: `${span.depth * 24 + 16}px`,
                }}
                onMouseEnter={e => { if (selected?.span_id !== span.span_id) e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
                onMouseLeave={e => { if (selected?.span_id !== span.span_id) e.currentTarget.style.background = 'transparent'; }}>
                <SpanTypeIcon type={span.type} size={14} />
                <span className="ml-2 truncate flex-shrink-0" style={{ width: '180px' }}>{span.name}</span>
                <div className="flex-1 mx-3 h-5 rounded relative" style={{ background: 'var(--color-bg-tertiary)' }}>
                  <div className="absolute h-full rounded"
                    style={{
                      left: `${startOffset}%`,
                      width: `${width}%`,
                      background: span.status === 'ERROR' ? 'var(--color-error)' : 'var(--color-primary)',
                      opacity: 0.7,
                    }} />
                </div>
                <span className="text-xs flex-shrink-0" style={{ color: 'var(--color-text-secondary)', width: '60px', textAlign: 'right' }}>
                  {span.duration_ms ? `${span.duration_ms}ms` : '-'}
                </span>
              </div>
            );
          })}
          {flat.length === 0 && (
            <div className="px-4 py-8 text-center text-sm" style={{ color: 'var(--color-text-secondary)' }}>No spans</div>
          )}
        </div>

        {/* Span detail panel */}
        {selected && (
          <div className="w-96 rounded-xl border p-4 flex-shrink-0"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2">
                <SpanTypeIcon type={selected.type} />
                <span className="font-medium">{selected.name}</span>
              </div>
              <StatusBadge status={selected.status} />
            </div>
            <dl className="text-sm space-y-2">
              <div className="flex justify-between">
                <dt style={{ color: 'var(--color-text-secondary)' }}>Type</dt>
                <dd>{selected.type}</dd>
              </div>
              {selected.model && (
                <div className="flex justify-between">
                  <dt style={{ color: 'var(--color-text-secondary)' }}>Model</dt>
                  <dd>{selected.model}</dd>
                </div>
              )}
              <div className="flex justify-between">
                <dt style={{ color: 'var(--color-text-secondary)' }}>Duration</dt>
                <dd>{selected.duration_ms ? `${selected.duration_ms}ms` : '-'}</dd>
              </div>
              {(selected.input_tokens > 0 || selected.output_tokens > 0) && (
                <div className="flex justify-between">
                  <dt style={{ color: 'var(--color-text-secondary)' }}>Tokens</dt>
                  <dd>{selected.input_tokens} in / {selected.output_tokens} out</dd>
                </div>
              )}
            </dl>
            {selected.input && (
              <div className="mt-3 p-3 rounded-lg text-xs" style={{ background: 'var(--color-bg-tertiary)' }}>
                <div className="font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Input</div>
                <pre className="whitespace-pre-wrap break-words max-h-40 overflow-auto">{selected.input}</pre>
              </div>
            )}
            {selected.output && (
              <div className="mt-2 p-3 rounded-lg text-xs" style={{ background: 'var(--color-bg-tertiary)' }}>
                <div className="font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Output</div>
                <pre className="whitespace-pre-wrap break-words max-h-40 overflow-auto">{selected.output}</pre>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
