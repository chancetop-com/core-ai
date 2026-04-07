import { useEffect, useState, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Clock, Zap, ChevronRight, ChevronDown, ChevronsUpDown, X, Bot, Brain, Wrench, GitBranch, Users } from 'lucide-react';
import { api } from '../../api/client';
import type { Trace, Span } from '../../api/client';
import StatusBadge from '../../components/StatusBadge';

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

const SPAN_COLORS: Record<string, string> = {
  LLM: '#8b5cf6',
  AGENT: '#6366f1',
  TOOL: '#f59e0b',
  FLOW: '#06b6d4',
  GROUP: '#ec4899',
};

const SPAN_ICONS: Record<string, React.ComponentType<{ size?: number; style?: React.CSSProperties }>> = {
  LLM: Brain,
  AGENT: Bot,
  TOOL: Wrench,
  FLOW: GitBranch,
  GROUP: Users,
};

function tryParseJson(str: string): unknown | null {
  try { return JSON.parse(str); } catch { return null; }
}

interface ExtractedMessage {
  role: string;
  content: string;
  tool_calls?: { id: string; function: { name: string; arguments: string } }[];
  tool_call_id?: string;
  name?: string;
}

function extractMessages(input: string): ExtractedMessage[] {
  const parsed = tryParseJson(input);
  if (parsed && typeof parsed === 'object' && 'messages' in (parsed as Record<string, unknown>)) {
    const msgs = (parsed as { messages: unknown[] }).messages;
    return msgs.map((m: unknown) => {
      const msg = m as Record<string, unknown>;
      let content = '';
      if (typeof msg.content === 'string') content = msg.content;
      else if (Array.isArray(msg.content)) {
        content = msg.content.map((c: unknown) => (c as Record<string, unknown>).text || '').join('');
      }
      const result: ExtractedMessage = { role: String(msg.role || 'unknown'), content };
      if (Array.isArray(msg.tool_calls) && msg.tool_calls.length > 0) {
        result.tool_calls = msg.tool_calls as ExtractedMessage['tool_calls'];
      }
      if (msg.tool_call_id) result.tool_call_id = String(msg.tool_call_id);
      if (msg.name) result.name = String(msg.name);
      return result;
    });
  }
  return [];
}

function extractAssistantContent(output: string): { content: string; reasoning?: string; tool_calls?: { id: string; function: { name: string; arguments: string } }[] } {
  const parsed = tryParseJson(output);
  if (parsed && typeof parsed === 'object') {
    const obj = parsed as Record<string, unknown>;
    const result: { content: string; reasoning?: string; tool_calls?: { id: string; function: { name: string; arguments: string } }[] } = {
      content: String(obj.content || output),
      reasoning: obj.reasoning_content ? String(obj.reasoning_content) : undefined,
    };
    if (Array.isArray(obj.tool_calls) && obj.tool_calls.length > 0) {
      result.tool_calls = obj.tool_calls as typeof result.tool_calls;
    }
    return result;
  }
  return { content: output };
}

export default function TraceDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [trace, setTrace] = useState<Trace | null>(null);
  const [spans, setSpans] = useState<Span[]>([]);
  const [selected, setSelected] = useState<Span | null>(null);
  const [loading, setLoading] = useState(true);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [detailTab, setDetailTab] = useState<'overview' | 'messages' | 'attributes'>('overview');

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
        {/* Stat pills */}
        <div className="flex gap-3 flex-wrap">
          <StatPill icon={<Clock size={13} />} label="Duration" value={trace.durationMs ? `${(trace.durationMs / 1000).toFixed(2)}s` : '-'} />
          <StatPill icon={<Zap size={13} />} label="Tokens"
            value={trace.totalTokens ? `${trace.totalTokens.toLocaleString()}` : '0'}
            sub={`${trace.inputTokens?.toLocaleString() || 0} in / ${trace.outputTokens?.toLocaleString() || 0} out`} />
          <StatPill icon={<Bot size={13} />} label="Spans" value={String(spans.length)} />
          {trace.sessionId && <StatPill label="Session" value={trace.sessionId} mono />}
          {trace.userId && <StatPill label="User" value={trace.userId} />}
        </div>
        {trace.startedAt && (
          <div className="text-xs mt-3" style={{ color: 'var(--color-text-secondary)' }}>
            {new Date(trace.startedAt).toLocaleString()}
            {trace.completedAt && ` → ${new Date(trace.completedAt).toLocaleString()}`}
          </div>
        )}
      </div>

      {/* I/O */}
      {(trace.input || trace.output) && (
        <div className="grid grid-cols-2 gap-4 mb-6">
          {trace.input && (
            <div className="rounded-xl border p-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
              <div className="text-xs font-medium mb-2" style={{ color: 'var(--color-text-secondary)' }}>Input</div>
              <pre className="text-sm whitespace-pre-wrap overflow-auto max-h-32">{trace.input}</pre>
            </div>
          )}
          {trace.output && (
            <div className="rounded-xl border p-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
              <div className="text-xs font-medium mb-2" style={{ color: 'var(--color-text-secondary)' }}>Output</div>
              <pre className="text-sm whitespace-pre-wrap overflow-auto max-h-32">{trace.output}</pre>
            </div>
          )}
        </div>
      )}

      {/* Waterfall + Detail */}
      <div className="grid gap-4" style={{ minHeight: '400px', gridTemplateColumns: selected ? '1fr 1fr' : '1fr' }}>
        {/* Waterfall */}
        <div className="rounded-xl border overflow-hidden min-w-0"
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
            const width = Math.max((span.durationMs || 0) / totalDuration * 100, 2);
            const hasChildren = span.children.length > 0;
            const isCollapsed = collapsed.has(span.spanId);
            const isSelected = selected?.spanId === span.spanId;
            const color = SPAN_COLORS[span.type] || '#94a3b8';
            const Icon = SPAN_ICONS[span.type] || Bot;

            return (
              <div key={span.spanId}
                onClick={() => { setSelected(span); setDetailTab('overview'); }}
                className="flex items-center px-3 py-1.5 border-t cursor-pointer transition-all"
                style={{
                  borderColor: 'var(--color-border)',
                  background: isSelected ? `${color}10` : 'transparent',
                  borderLeft: isSelected ? `3px solid ${color}` : '3px solid transparent',
                  paddingLeft: `${span.depth * 16 + 8}px`,
                }}
                onMouseEnter={e => { if (!isSelected) e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
                onMouseLeave={e => { if (!isSelected) e.currentTarget.style.background = 'transparent'; }}>

                <span className="w-4 flex-shrink-0 flex items-center justify-center"
                  onClick={e => { if (hasChildren) { e.stopPropagation(); toggleCollapse(span.spanId); } }}>
                  {hasChildren ? (
                    isCollapsed ? <ChevronRight size={11} style={{ color: 'var(--color-text-secondary)' }} />
                      : <ChevronDown size={11} style={{ color: 'var(--color-text-secondary)' }} />
                  ) : null}
                </span>

                <div className="w-4 h-4 rounded flex items-center justify-center flex-shrink-0 ml-0.5" style={{ background: `${color}20` }}>
                  <Icon size={10} style={{ color }} />
                </div>

                <span className="ml-1.5 truncate flex-shrink-0 text-xs font-medium" style={{ maxWidth: '120px' }}>{span.name}</span>

                <div className="flex-1 mx-2 h-3 rounded-full relative" style={{ background: 'var(--color-bg-tertiary)', minWidth: '40px' }}>
                  <div className="absolute h-full rounded-full"
                    style={{
                      left: `${startOffset}%`,
                      width: `${width}%`,
                      background: span.status === 'ERROR' ? 'var(--color-error)' : color,
                      opacity: 0.6,
                    }} />
                </div>

                <span className="text-xs flex-shrink-0 text-right tabular-nums" style={{ color: 'var(--color-text-secondary)', width: '48px' }}>
                  {span.durationMs ? `${span.durationMs}ms` : '-'}
                </span>
              </div>
            );
          })}
          {flat.length === 0 && (
            <div className="px-4 py-12 text-center text-sm" style={{ color: 'var(--color-text-secondary)' }}>No spans recorded</div>
          )}
        </div>

        {/* Span detail panel */}
        {selected && <SpanDetail span={selected} tab={detailTab} setTab={setDetailTab} onClose={() => setSelected(null)} />}
      </div>
    </div>
  );
}

function StatPill({ icon, label, value, sub, mono }: { icon?: React.ReactNode; label: string; value: string; sub?: string; mono?: boolean }) {
  return (
    <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm"
      style={{ background: 'var(--color-bg-tertiary)' }}>
      {icon && <span style={{ color: 'var(--color-text-secondary)' }}>{icon}</span>}
      <span style={{ color: 'var(--color-text-secondary)' }}>{label}:</span>
      <span className={`font-medium ${mono ? 'font-mono text-xs' : ''}`}>{value}</span>
      {sub && <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>({sub})</span>}
    </div>
  );
}

function SpanDetail({ span, tab, setTab, onClose }: {
  span: Span;
  tab: 'overview' | 'messages' | 'attributes';
  setTab: (t: 'overview' | 'messages' | 'attributes') => void;
  onClose: () => void;
}) {
  const color = SPAN_COLORS[span.type] || '#94a3b8';
  const Icon = SPAN_ICONS[span.type] || Bot;

  const messages = useMemo(() => span.input ? extractMessages(span.input) : [], [span.input]);
  const assistantOutput = useMemo(() => span.output ? extractAssistantContent(span.output) : null, [span.output]);
  const hasMessages = messages.length > 0 || assistantOutput;

  const attrCount = span.attributes ? Object.keys(span.attributes).length : 0;

  const tabs = [
    { key: 'overview' as const, label: 'Overview' },
    ...(hasMessages ? [{ key: 'messages' as const, label: `Messages${messages.length > 0 ? ` (${messages.length + (assistantOutput ? 1 : 0)})` : ''}` }] : []),
    ...(attrCount > 0 ? [{ key: 'attributes' as const, label: `Attributes (${attrCount})` }] : []),
  ];

  return (
    <div className="rounded-xl border self-start sticky top-6 overflow-hidden min-w-0"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>

      {/* Header */}
      <div className="p-4 border-b" style={{ borderColor: 'var(--color-border)' }}>
        <div className="flex items-center justify-between mb-2">
          <div className="flex items-center gap-2">
            <div className="w-7 h-7 rounded-lg flex items-center justify-center" style={{ background: `${color}20` }}>
              <Icon size={15} style={{ color }} />
            </div>
            <div>
              <div className="font-semibold text-sm">{span.name}</div>
              <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{span.type}</div>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <StatusBadge status={span.status} />
            <button onClick={onClose} className="cursor-pointer p-1 rounded hover:bg-gray-100"
              style={{ color: 'var(--color-text-secondary)' }}>
              <X size={14} />
            </button>
          </div>
        </div>

        {/* Quick stats */}
        <div className="flex gap-2 flex-wrap">
          {span.model && (
            <span className="text-xs px-2 py-1 rounded-md font-mono"
              style={{ background: `${color}15`, color }}>
              {span.model}
            </span>
          )}
          {span.durationMs && (
            <span className="text-xs px-2 py-1 rounded-md flex items-center gap-1"
              style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
              <Clock size={11} /> {span.durationMs}ms
            </span>
          )}
          {(span.inputTokens || span.outputTokens) ? (
            <span className="text-xs px-2 py-1 rounded-md flex items-center gap-1"
              style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
              <Zap size={11} /> {span.inputTokens || 0} in / {span.outputTokens || 0} out
            </span>
          ) : null}
        </div>
      </div>

      {/* Tabs */}
      <div className="flex border-b" style={{ borderColor: 'var(--color-border)' }}>
        {tabs.map(t => (
          <button key={t.key}
            onClick={() => setTab(t.key)}
            className="px-4 py-2 text-xs font-medium cursor-pointer transition-colors"
            style={{
              color: tab === t.key ? 'var(--color-primary)' : 'var(--color-text-secondary)',
              borderBottom: tab === t.key ? '2px solid var(--color-primary)' : '2px solid transparent',
            }}>
            {t.label}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="p-4 overflow-auto" style={{ maxHeight: '500px' }}>
        {tab === 'overview' && <OverviewTab span={span} />}
        {tab === 'messages' && <MessagesTab messages={messages} output={assistantOutput} inputRaw={span.input} outputRaw={span.output} />}
        {tab === 'attributes' && <AttributesTab attributes={span.attributes} />}
      </div>
    </div>
  );
}

function OverviewTab({ span }: { span: Span }) {
  return (
    <div className="space-y-3">
      <InfoRow label="Span ID" value={span.spanId} mono />
      <InfoRow label="Trace ID" value={span.traceId} mono />
      {span.parentSpanId && <InfoRow label="Parent" value={span.parentSpanId} mono />}
      {span.model && <InfoRow label="Model" value={span.model} />}
      <InfoRow label="Duration" value={span.durationMs ? `${span.durationMs}ms (${(span.durationMs / 1000).toFixed(2)}s)` : '-'} />
      {(span.inputTokens || span.outputTokens) ? (
        <>
          <InfoRow label="Input Tokens" value={String(span.inputTokens || 0)} />
          <InfoRow label="Output Tokens" value={String(span.outputTokens || 0)} />
          <InfoRow label="Total Tokens" value={String((span.inputTokens || 0) + (span.outputTokens || 0))} />
        </>
      ) : null}
      {span.startedAt && <InfoRow label="Started" value={new Date(span.startedAt).toLocaleString()} />}
      {span.completedAt && <InfoRow label="Completed" value={new Date(span.completedAt).toLocaleString()} />}

      {/* Raw I/O for non-LLM spans */}
      {span.type !== 'LLM' && span.input && (
        <CollapsibleText label="Input" text={span.input} />
      )}
      {span.type !== 'LLM' && span.output && (
        <CollapsibleText label="Output" text={span.output} />
      )}
    </div>
  );
}

function MessagesTab({ messages, output, inputRaw, outputRaw }: {
  messages: ExtractedMessage[];
  output: { content: string; reasoning?: string; tool_calls?: { id: string; function: { name: string; arguments: string } }[] } | null;
  inputRaw: string;
  outputRaw: string;
}) {
  const ROLE_STYLES: Record<string, { bg: string; color: string; label: string }> = {
    system: { bg: '#8b5cf615', color: '#8b5cf6', label: 'System' },
    user: { bg: '#3b82f615', color: '#3b82f6', label: 'User' },
    assistant: { bg: '#22c55e15', color: '#22c55e', label: 'Assistant' },
    tool: { bg: '#f59e0b15', color: '#f59e0b', label: 'Tool' },
  };

  if (messages.length === 0 && !output) {
    return (
      <div className="space-y-3">
        {inputRaw && <CollapsibleText label="Raw Input" text={inputRaw} />}
        {outputRaw && <CollapsibleText label="Raw Output" text={outputRaw} />}
      </div>
    );
  }

  const formatArgs = (args: string) => {
    try { return JSON.stringify(JSON.parse(args), null, 2); } catch { return args; }
  };

  return (
    <div className="space-y-2">
      {messages.map((m, i) => {
        const style = ROLE_STYLES[m.role] || ROLE_STYLES.user;
        return (
          <div key={i} className="rounded-lg overflow-hidden" style={{ border: `1px solid ${style.color}30` }}>
            <div className="px-3 py-1.5 text-xs font-medium flex items-center gap-2" style={{ background: style.bg, color: style.color }}>
              {m.role === 'tool' && m.name ? `Tool: ${m.name}` : style.label}
              {m.tool_call_id && (
                <span className="font-mono text-[10px] opacity-60">{m.tool_call_id}</span>
              )}
            </div>
            <div className="px-3 py-2">
              {m.content && <pre className="text-xs whitespace-pre-wrap">{m.content}</pre>}
              {!m.content && !m.tool_calls && <pre className="text-xs whitespace-pre-wrap opacity-50">(empty)</pre>}
              {m.tool_calls && m.tool_calls.length > 0 && (
                <div className="space-y-1.5 mt-1">
                  {m.tool_calls.map((tc, j) => (
                    <div key={j} className="rounded-md overflow-hidden" style={{ border: '1px solid #f59e0b30' }}>
                      <div className="px-2.5 py-1 text-[11px] font-medium flex items-center gap-1.5"
                        style={{ background: '#f59e0b10', color: '#f59e0b' }}>
                        <Wrench size={11} /> {tc.function.name}
                        {tc.id && <span className="font-mono text-[10px] opacity-60 ml-auto">{tc.id}</span>}
                      </div>
                      <pre className="px-2.5 py-1.5 text-[11px] whitespace-pre-wrap overflow-auto"
                        style={{ background: 'var(--color-bg-tertiary)', maxHeight: '120px' }}>
                        {formatArgs(tc.function.arguments)}
                      </pre>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        );
      })}
      {output && (
        <div className="rounded-lg overflow-hidden" style={{ border: '1px solid #22c55e30' }}>
          <div className="px-3 py-1.5 text-xs font-medium" style={{ background: '#22c55e15', color: '#22c55e' }}>
            Assistant Response
          </div>
          <div className="px-3 py-2">
            {output.reasoning && (
              <details className="mb-2">
                <summary className="text-xs cursor-pointer font-medium" style={{ color: '#8b5cf6' }}>
                  Reasoning
                </summary>
                <pre className="text-xs whitespace-pre-wrap mt-1 p-2 rounded" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                  {output.reasoning}
                </pre>
              </details>
            )}
            {output.content && <pre className="text-xs whitespace-pre-wrap">{output.content}</pre>}
            {output.tool_calls && output.tool_calls.length > 0 && (
              <div className="space-y-1.5 mt-1">
                {output.tool_calls.map((tc, j) => (
                  <div key={j} className="rounded-md overflow-hidden" style={{ border: '1px solid #f59e0b30' }}>
                    <div className="px-2.5 py-1 text-[11px] font-medium flex items-center gap-1.5"
                      style={{ background: '#f59e0b10', color: '#f59e0b' }}>
                      <Wrench size={11} /> {tc.function.name}
                      {tc.id && <span className="font-mono text-[10px] opacity-60 ml-auto">{tc.id}</span>}
                    </div>
                    <pre className="px-2.5 py-1.5 text-[11px] whitespace-pre-wrap overflow-auto"
                      style={{ background: 'var(--color-bg-tertiary)', maxHeight: '120px' }}>
                      {formatArgs(tc.function.arguments)}
                    </pre>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function AttributesTab({ attributes }: { attributes: Record<string, string> }) {
  const [expandedKeys, setExpandedKeys] = useState<Set<string>>(new Set());

  if (!attributes || Object.keys(attributes).length === 0) {
    return <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>No attributes</div>;
  }

  const toggleKey = (k: string) => {
    setExpandedKeys(prev => {
      const next = new Set(prev);
      if (next.has(k)) next.delete(k); else next.add(k);
      return next;
    });
  };

  // Group attributes by prefix
  const groups: Record<string, [string, string][]> = {};
  Object.entries(attributes).forEach(([k, v]) => {
    const prefix = k.includes('.') ? k.split('.')[0] : 'other';
    if (!groups[prefix]) groups[prefix] = [];
    groups[prefix].push([k, v]);
  });

  const shortKey = (k: string) => {
    const parts = k.split('.');
    return parts.length > 1 ? parts.slice(1).join('.') : k;
  };

  return (
    <div className="space-y-3">
      {Object.entries(groups).map(([group, entries]) => (
        <div key={group}>
          <div className="text-xs font-medium mb-1.5 capitalize" style={{ color: 'var(--color-text-secondary)' }}>
            {group} <span className="font-normal">({entries.length})</span>
          </div>
          <div className="rounded-lg overflow-hidden" style={{ background: 'var(--color-bg-tertiary)' }}>
            {entries.map(([k, v], i) => {
              const isLong = v.length > 80;
              const isExpanded = expandedKeys.has(k);
              return (
                <div key={k}
                  className={`px-3 py-1.5 text-xs ${isLong ? 'cursor-pointer' : ''}`}
                  style={{ borderTop: i > 0 ? '1px solid var(--color-border)' : undefined }}
                  onClick={() => isLong && toggleKey(k)}>
                  <div className="flex items-center justify-between">
                    <span className="font-mono flex-shrink-0 truncate" style={{ color: 'var(--color-primary)', maxWidth: '45%' }} title={k}>
                      {shortKey(k)}
                    </span>
                    {!isLong && <span className="truncate ml-2 text-right" style={{ maxWidth: '50%' }} title={v}>{v}</span>}
                    {isLong && !isExpanded && <span className="truncate ml-2 text-right" style={{ maxWidth: '50%', color: 'var(--color-text-secondary)' }}>{v.slice(0, 50)}...</span>}
                    {isLong && isExpanded && <ChevronDown size={10} style={{ color: 'var(--color-text-secondary)', flexShrink: 0 }} />}
                  </div>
                  {isLong && isExpanded && (
                    <pre className="mt-1.5 p-2 rounded text-xs whitespace-pre-wrap break-all overflow-auto"
                      style={{ background: 'var(--color-bg-secondary)', maxHeight: '200px' }}>
                      {(() => { try { return JSON.stringify(JSON.parse(v), null, 2); } catch { return v; } })()}
                    </pre>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      ))}
    </div>
  );
}

function InfoRow({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex justify-between items-start text-xs">
      <span style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
      <span className={`text-right max-w-[60%] truncate ${mono ? 'font-mono' : ''}`} title={value}>{value}</span>
    </div>
  );
}

function CollapsibleText({ label, text }: { label: string; text: string }) {
  const [expanded, setExpanded] = useState(false);
  const formatted = useMemo(() => {
    try { return JSON.stringify(JSON.parse(text), null, 2); } catch { return text; }
  }, [text]);
  const isLong = formatted.length > 200;
  const display = !expanded && isLong ? formatted.slice(0, 200) + '...' : formatted;

  return (
    <div>
      <div className="flex items-center justify-between mb-1">
        <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
        {isLong && (
          <button onClick={() => setExpanded(!expanded)} className="text-xs cursor-pointer" style={{ color: 'var(--color-primary)' }}>
            {expanded ? 'Collapse' : 'Expand'}
          </button>
        )}
      </div>
      <pre className="text-xs whitespace-pre-wrap p-2.5 rounded-lg overflow-auto"
        style={{ background: 'var(--color-bg-tertiary)', maxHeight: expanded ? '400px' : '150px' }}>
        {display}
      </pre>
    </div>
  );
}
