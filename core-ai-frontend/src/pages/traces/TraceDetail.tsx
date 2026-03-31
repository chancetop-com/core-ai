import { useEffect, useState, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Clock, Zap, ChevronRight, ChevronDown, ChevronsUpDown } from 'lucide-react';
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
  spans.forEach(s => map.set(s.spanId, { ...s, children: [], depth: 0 }));
  map.forEach(node => {
    if (node.parentSpanId && map.has(node.parentSpanId)) {
      const parent = map.get(node.parentSpanId)!;
      node.depth = parent.depth + 1;
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  });
  return roots;
}

function flattenTree(nodes: SpanNode[], collapsed: Set<string>): SpanNode[] {
  const result: SpanNode[] = [];
  function walk(list: SpanNode[]) {
    list.forEach(n => {
      result.push(n);
      if (!collapsed.has(n.spanId)) walk(n.children);
    });
  }
  walk(nodes);
  return result;
}

function collectAllIds(nodes: SpanNode[]): string[] {
  const ids: string[] = [];
  function walk(list: SpanNode[]) {
    list.forEach(n => { if (n.children.length > 0) ids.push(n.spanId); walk(n.children); });
  }
  walk(nodes);
  return ids;
}

function JsonView({ data, label }: { data: string; label: string }) {
  const [expanded, setExpanded] = useState(false);
  const parsed = useMemo(() => {
    try { return JSON.parse(data); } catch { return null; }
  }, [data]);

  const isJson = parsed !== null && typeof parsed === 'object';
  const formatted = isJson ? JSON.stringify(parsed, null, 2) : data;
  const isLong = formatted.length > 300;
  const displayText = !expanded && isLong ? formatted.slice(0, 300) + '...' : formatted;

  return (
    <div className="mt-2 rounded-lg text-xs overflow-hidden" style={{ background: 'var(--color-bg-tertiary)' }}>
      <div className="flex items-center justify-between px-3 py-1.5 cursor-pointer select-none"
        style={{ borderBottom: '1px solid var(--color-border)' }}
        onClick={() => setExpanded(!expanded)}>
        <span className="font-medium" style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
        <div className="flex items-center gap-2">
          {isJson && (
            <span className="px-1.5 py-0.5 rounded text-xs"
              style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}>
              JSON
            </span>
          )}
          {isLong && (
            <span style={{ color: 'var(--color-text-secondary)' }}>
              {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
            </span>
          )}
        </div>
      </div>
      <pre className="px-3 py-2 whitespace-pre-wrap break-words overflow-auto" style={{ maxHeight: expanded ? '500px' : '200px' }}>
        {isJson ? <JsonHighlight text={displayText} /> : displayText}
      </pre>
    </div>
  );
}

function JsonHighlight({ text }: { text: string }) {
  const parts = text.split(/("(?:[^"\\]|\\.)*")\s*:/g);
  const result: React.ReactNode[] = [];
  for (let i = 0; i < parts.length; i++) {
    if (i % 2 === 1) {
      result.push(<span key={i} style={{ color: '#6366f1' }}>{parts[i]}</span>);
      result.push(':');
    } else {
      const valueParts = parts[i].split(/("(?:[^"\\]|\\.)*")/g);
      valueParts.forEach((vp, j) => {
        if (j % 2 === 1) {
          result.push(<span key={`${i}-${j}`} style={{ color: '#22c55e' }}>{vp}</span>);
        } else {
          const numParts = vp.split(/\b(\d+\.?\d*)\b/g);
          numParts.forEach((np, k) => {
            if (k % 2 === 1) {
              result.push(<span key={`${i}-${j}-${k}`} style={{ color: '#f59e0b' }}>{np}</span>);
            } else {
              const boolParts = np.split(/\b(true|false|null)\b/g);
              boolParts.forEach((bp, l) => {
                if (l % 2 === 1) {
                  result.push(<span key={`${i}-${j}-${k}-${l}`} style={{ color: '#ef4444' }}>{bp}</span>);
                } else {
                  result.push(bp);
                }
              });
            }
          });
        }
      });
    }
  }
  return <>{result}</>;
}

export default function TraceDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [trace, setTrace] = useState<Trace | null>(null);
  const [spans, setSpans] = useState<Span[]>([]);
  const [selected, setSelected] = useState<Span | null>(null);
  const [loading, setLoading] = useState(true);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (!id) return;
    Promise.all([api.traces.get(id), api.traces.spans(id)])
      .then(([t, s]) => { setTrace(t); setSpans(s); })
      .finally(() => setLoading(false));
  }, [id]);

  if (loading) return <div className="p-6" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;
  if (!trace) return <div className="p-6">Trace not found</div>;

  const tree = buildTree(spans);
  const flat = flattenTree(tree, collapsed);
  const totalDuration = trace.durationMs || 1;
  const allParentIds = collectAllIds(tree);

  const toggleCollapse = (spanId: string) => {
    setCollapsed(prev => {
      const next = new Set(prev);
      if (next.has(spanId)) next.delete(spanId); else next.add(spanId);
      return next;
    });
  };

  const collapseAll = () => setCollapsed(new Set(allParentIds));
  const expandAll = () => setCollapsed(new Set());

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
          <h1 className="text-xl font-semibold">{trace.name || trace.traceId}</h1>
          <StatusBadge status={trace.status} />
        </div>
        <div className="flex gap-6 text-sm flex-wrap" style={{ color: 'var(--color-text-secondary)' }}>
          <span className="flex items-center gap-1"><Clock size={14} /> {trace.durationMs ? `${(trace.durationMs / 1000).toFixed(2)}s` : '-'}</span>
          <span className="flex items-center gap-1">
            <Zap size={14} />
            {trace.totalTokens ? `${trace.totalTokens.toLocaleString()} tokens` : '0 tokens'}
            {(trace.inputTokens > 0 || trace.outputTokens > 0) && (
              <span className="text-xs ml-1">({trace.inputTokens?.toLocaleString() || 0} in / {trace.outputTokens?.toLocaleString() || 0} out)</span>
            )}
          </span>
          {trace.sessionId && <span>Session: {trace.sessionId}</span>}
          {trace.userId && <span>User: {trace.userId}</span>}
        </div>
        {trace.startedAt && (
          <div className="flex gap-6 text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            <span>Started: {new Date(trace.startedAt).toLocaleString()}</span>
            {trace.completedAt && <span>Completed: {new Date(trace.completedAt).toLocaleString()}</span>}
          </div>
        )}
        {trace.input && <JsonView data={trace.input} label="Input" />}
        {trace.output && <JsonView data={trace.output} label="Output" />}
      </div>

      {/* Waterfall */}
      <div className="flex gap-4">
        <div className="flex-1 rounded-xl border overflow-hidden"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          <div className="px-4 py-3 border-b font-medium text-sm flex items-center justify-between" style={{ borderColor: 'var(--color-border)' }}>
            <span>Spans ({spans.length})</span>
            {allParentIds.length > 0 && (
              <button onClick={collapsed.size === allParentIds.length ? expandAll : collapseAll}
                className="flex items-center gap-1 text-xs cursor-pointer px-2 py-1 rounded"
                style={{ color: 'var(--color-text-secondary)' }}
                onMouseEnter={e => (e.currentTarget.style.background = 'var(--color-bg-tertiary)')}
                onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
                <ChevronsUpDown size={12} />
                {collapsed.size === allParentIds.length ? 'Expand All' : 'Collapse All'}
              </button>
            )}
          </div>
          {flat.map(span => {
            const startOffset = span.startedAt && trace.startedAt
              ? (new Date(span.startedAt).getTime() - new Date(trace.startedAt).getTime()) / totalDuration * 100
              : 0;
            const width = Math.max((span.durationMs || 0) / totalDuration * 100, 1);
            const hasChildren = span.children.length > 0;
            const isCollapsed = collapsed.has(span.spanId);

            return (
              <div key={span.spanId}
                onClick={() => setSelected(span)}
                className="flex items-center px-4 py-2 border-t cursor-pointer transition-colors text-sm"
                style={{
                  borderColor: 'var(--color-border)',
                  background: selected?.spanId === span.spanId ? 'var(--color-bg-tertiary)' : 'transparent',
                  paddingLeft: `${span.depth * 20 + 16}px`,
                }}
                onMouseEnter={e => { if (selected?.spanId !== span.spanId) e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
                onMouseLeave={e => { if (selected?.spanId !== span.spanId) e.currentTarget.style.background = 'transparent'; }}>

                {/* Collapse toggle */}
                <span className="w-4 flex-shrink-0 flex items-center justify-center"
                  onClick={e => { if (hasChildren) { e.stopPropagation(); toggleCollapse(span.spanId); } }}>
                  {hasChildren ? (
                    isCollapsed ? <ChevronRight size={12} style={{ color: 'var(--color-text-secondary)' }} />
                      : <ChevronDown size={12} style={{ color: 'var(--color-text-secondary)' }} />
                  ) : null}
                </span>

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
                <span className="text-xs flex-shrink-0" style={{ color: 'var(--color-text-secondary)', width: '120px', textAlign: 'right' }}>
                  {(span.inputTokens > 0 || span.outputTokens > 0) && (
                    <span className="mr-2">{(span.inputTokens || 0) + (span.outputTokens || 0)}t</span>
                  )}
                  {span.durationMs ? `${span.durationMs}ms` : '-'}
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
          <div className="w-96 rounded-xl border p-4 flex-shrink-0 self-start sticky top-6"
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
                  <dd className="font-mono text-xs">{selected.model}</dd>
                </div>
              )}
              <div className="flex justify-between">
                <dt style={{ color: 'var(--color-text-secondary)' }}>Duration</dt>
                <dd>{selected.durationMs ? `${selected.durationMs}ms` : '-'}</dd>
              </div>
              {(selected.inputTokens > 0 || selected.outputTokens > 0) && (
                <div className="flex justify-between">
                  <dt style={{ color: 'var(--color-text-secondary)' }}>Tokens</dt>
                  <dd>{selected.inputTokens} in / {selected.outputTokens} out</dd>
                </div>
              )}
              {selected.startedAt && (
                <div className="flex justify-between">
                  <dt style={{ color: 'var(--color-text-secondary)' }}>Started</dt>
                  <dd className="text-xs">{new Date(selected.startedAt).toLocaleString()}</dd>
                </div>
              )}
            </dl>

            {/* Attributes */}
            {selected.attributes && Object.keys(selected.attributes).length > 0 && (
              <div className="mt-3">
                <div className="text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Attributes</div>
                <div className="rounded-lg p-2 text-xs space-y-1" style={{ background: 'var(--color-bg-tertiary)' }}>
                  {Object.entries(selected.attributes).map(([k, v]) => (
                    <div key={k} className="flex gap-2">
                      <span className="font-mono flex-shrink-0" style={{ color: '#6366f1' }}>{k}:</span>
                      <span className="break-all">{v}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {selected.input && <JsonView data={selected.input} label="Input" />}
            {selected.output && <JsonView data={selected.output} label="Output" />}
          </div>
        )}
      </div>
    </div>
  );
}
