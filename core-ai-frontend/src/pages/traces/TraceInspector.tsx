import { useMemo, useState, type ComponentType, type CSSProperties, type ReactNode } from 'react';
import {
  AlertCircle,
  ArrowLeft,
  Bot,
  Brain,
  ChevronDown,
  ChevronRight,
  Clock,
  Copy,
  Database,
  DollarSign,
  ExternalLink,
  FileJson,
  GitBranch,
  ListTree,
  MessageCircle,
  PanelTop,
  UserCircle,
  Users,
  Wrench,
  X,
  Zap,
} from 'lucide-react';
import type { Span, Trace } from '../../api/client';
import StatusBadge from '../../components/StatusBadge';
import { sourceColors, typeColors } from './colors';
import {
  buildSpanTree,
  collectExpandableSpanIds,
  extractAssistantContent,
  extractMessages,
  findDefaultSpan,
  flattenSpanTree,
  formatDuration,
  formatCostUsd,
  formatRelativeTime,
  formatTokenCount,
  formatTokenPair,
  getSpanTiming,
  getTimelineBounds,
  prettyContent,
  resolveTraceSource,
  resolveTraceType,
  spanOperationLabel,
  spanOwnerLabel,
  traceDisplayName,
  type ExtractedAssistantOutput,
  type ExtractedMessage,
  type SpanNode,
} from './traceViewModel';

type InspectorMode = 'panel' | 'page';
type TraceTab = 'timeline' | 'io' | 'raw';
type SpanTab = 'summary' | 'messages' | 'attributes' | 'raw';

interface TraceInspectorProps {
  trace: Trace;
  spans: Span[];
  mode?: InspectorMode;
  onBack?: () => void;
  onClose?: () => void;
}

const SPAN_COLORS: Record<string, string> = {
  LLM: '#7c3aed',
  AGENT: '#4f46e5',
  TOOL: '#d97706',
  FLOW: '#0891b2',
  GROUP: '#db2777',
};

const SPAN_ICONS: Record<string, ComponentType<{ size?: number; style?: CSSProperties }>> = {
  LLM: Brain,
  AGENT: Bot,
  TOOL: Wrench,
  FLOW: GitBranch,
  GROUP: Users,
};

export default function TraceInspector({ trace, spans, mode = 'panel', onBack, onClose }: TraceInspectorProps) {
  const [tabState, setTabState] = useState<{ traceId: string; tab: TraceTab }>({ traceId: trace.traceId, tab: 'timeline' });
  const tab = tabState.traceId === trace.traceId ? tabState.tab : 'timeline';
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null);
  const selectedSpan = useMemo(() => {
    if (selectedSpanId) {
      const span = spans.find(item => item.spanId === selectedSpanId);
      if (span) return span;
    }
    return findDefaultSpan(spans);
  }, [selectedSpanId, spans]);

  const containerClass = mode === 'page'
    ? 'p-6 h-full overflow-auto'
    : 'h-full min-h-0 flex flex-col';
  const setTraceTab = (nextTab: TraceTab) => {
    setTabState({ traceId: trace.traceId, tab: nextTab });
  };

  const content = (
    <>
      <TraceHeader trace={trace} spans={spans} mode={mode} onClose={onClose} />
      <div className="flex border-b shrink-0" style={{ borderColor: 'var(--color-border)' }}>
        <TraceTabButton active={tab === 'timeline'} icon={<ListTree size={14} />} onClick={() => setTraceTab('timeline')}>
          Timeline
        </TraceTabButton>
        <TraceTabButton active={tab === 'io'} icon={<PanelTop size={14} />} onClick={() => setTraceTab('io')}>
          I/O
        </TraceTabButton>
        <TraceTabButton active={tab === 'raw'} icon={<FileJson size={14} />} onClick={() => setTraceTab('raw')}>
          Raw
        </TraceTabButton>
      </div>
      <div className="flex-1 min-h-0 overflow-auto">
        {tab === 'timeline' && (
          <TimelineTab trace={trace} spans={spans} selectedSpan={selectedSpan} onSelectSpan={span => setSelectedSpanId(span.spanId)} mode={mode} />
        )}
        {tab === 'io' && <TraceIOTab trace={trace} />}
        {tab === 'raw' && <CodeBlock content={JSON.stringify({ trace, spans }, null, 2)} />}
      </div>
    </>
  );

  if (mode === 'page') {
    return (
      <div className={containerClass}>
        {onBack && (
          <button onClick={onBack}
            className="mb-4 inline-flex items-center gap-1.5 text-sm cursor-pointer"
            style={{ color: 'var(--color-primary)' }}>
            <ArrowLeft size={16} /> Back to traces
          </button>
        )}
        <div className="rounded-lg border overflow-hidden min-h-[640px] flex flex-col"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          {content}
        </div>
      </div>
    );
  }

  return <div className={containerClass}>{content}</div>;
}

function TraceHeader({ trace, spans, mode, onClose }: {
  trace: Trace;
  spans: Span[];
  mode: InspectorMode;
  onClose?: () => void;
}) {
  const traceType = typeColors(resolveTraceType(trace));
  const source = sourceColors(resolveTraceSource(trace));
  const traceId = trace.traceId || trace.id;
  const title = traceDisplayName(trace);
  const workflowRunId = trace.metadata?.workflow_run_id;
  const workflowNodeId = trace.metadata?.workflow_node_id;

  const copyTraceId = () => {
    navigator.clipboard?.writeText(traceId);
  };

  return (
    <div className="px-4 py-3 border-b shrink-0" style={{ borderColor: 'var(--color-border)' }}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 min-w-0">
            <SourceBadge label={source.label} color={source.color} />
            <span className="text-xs px-2 py-0.5 rounded-md font-medium shrink-0"
              style={{ background: traceType.bg, color: traceType.text }}>
              {traceType.label}
            </span>
            <h2 className="font-semibold truncate text-base">{title}</h2>
            <StatusBadge status={trace.status} />
          </div>

          <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
            {trace.agentName && <NeutralChip>{trace.agentName}</NeutralChip>}
            {trace.model && <NeutralChip mono>{trace.model}</NeutralChip>}
            {workflowRunId && <NeutralChip mono>workflow run {workflowRunId.slice(0, 8)}</NeutralChip>}
            {workflowNodeId && <NeutralChip mono>node {workflowNodeId}</NeutralChip>}
            {trace.userId && (
              <NeutralChip>
                <UserCircle size={11} /> {accountLabel(trace)}
              </NeutralChip>
            )}
            {trace.sessionId && <NeutralChip mono>session {trace.sessionId.slice(0, 8)}</NeutralChip>}
            <span className="inline-flex items-center gap-1" style={{ color: 'var(--color-text-secondary)' }}>
              <Clock size={12} /> {formatDuration(trace.durationMs)}
            </span>
            <span className="inline-flex items-center gap-1" style={{ color: 'var(--color-text-secondary)' }}>
              <Zap size={12} /> {trace.totalTokens || 0} tok ({formatTokenPair(trace.inputTokens, trace.outputTokens)})
            </span>
            <span className="inline-flex items-center gap-1" style={{ color: 'var(--color-text-secondary)' }}>
              <Database size={12} /> {formatTokenCount(trace.cachedTokens)} cached
            </span>
            <span className="inline-flex items-center gap-1" style={{ color: 'var(--color-text-secondary)' }}>
              <DollarSign size={12} /> {formatCostUsd(trace.costUsd)}
            </span>
            <span style={{ color: 'var(--color-text-secondary)' }}>{spans.length} spans</span>
            <span style={{ color: 'var(--color-text-secondary)' }}>{formatRelativeTime(trace.startedAt || trace.createdAt)}</span>
          </div>

          <div className="mt-1.5 flex items-center gap-1 text-[11px] font-mono min-w-0"
            style={{ color: 'var(--color-text-secondary)' }}>
            <span className="truncate">{traceId}</span>
            <button onClick={copyTraceId} className="p-0.5 rounded cursor-pointer hover:opacity-80" title="Copy trace ID">
              <Copy size={11} />
            </button>
          </div>
        </div>

        <div className="flex items-center gap-1 shrink-0">
          {trace.sessionId && (
            <a href={`/chat?sessionId=${encodeURIComponent(trace.sessionId)}`}
              className="px-2 py-1.5 rounded-md text-xs inline-flex items-center gap-1 cursor-pointer"
              style={{ color: 'var(--color-primary)', background: 'var(--color-primary-bg)' }}
              title="Open session in chat">
              <MessageCircle size={13} /> Chat
            </a>
          )}
          {mode === 'panel' && (
            <a href={`/traces/${traceId}`} target="_blank" rel="noopener noreferrer"
              className="p-1.5 rounded-md cursor-pointer"
              style={{ color: 'var(--color-text-secondary)' }}
              title="Open full trace page">
              <ExternalLink size={15} />
            </a>
          )}
          {onClose && (
            <button onClick={onClose}
              className="p-1.5 rounded-md cursor-pointer"
              style={{ color: 'var(--color-text-secondary)' }}
              title="Close">
              <X size={16} />
            </button>
          )}
        </div>
      </div>
      {trace.status === 'ERROR' && trace.errorMessage && (
        <div className="mt-3">
          <ErrorBlock message={trace.errorMessage} />
        </div>
      )}
    </div>
  );
}

function accountLabel(trace: Trace): string {
  return trace.account?.name || trace.account?.email || trace.userId;
}

function ErrorBlock({ message }: { message: string }) {
  const [expanded, setExpanded] = useState(false);
  const isLong = message.length > 240;
  const display = !expanded && isLong ? message.slice(0, 240) + '…' : message;
  const copy = () => { navigator.clipboard?.writeText(message); };
  return (
    <div className="rounded-md border text-xs"
      style={{ borderColor: 'var(--color-error)', background: 'var(--color-error-bg, rgba(239,68,68,0.08))' }}>
      <div className="flex items-center justify-between px-3 py-1.5 border-b"
        style={{ borderColor: 'var(--color-error)', color: 'var(--color-error)' }}>
        <div className="flex items-center gap-1.5 font-semibold">
          <AlertCircle size={13} /> Error
        </div>
        <div className="flex items-center gap-1">
          {isLong && (
            <button onClick={() => setExpanded(v => !v)}
              className="px-1.5 py-0.5 rounded text-[11px] cursor-pointer hover:opacity-80"
              style={{ color: 'var(--color-error)' }}>
              {expanded ? 'Collapse' : 'Expand'}
            </button>
          )}
          <button onClick={copy}
            className="p-0.5 rounded cursor-pointer hover:opacity-80"
            style={{ color: 'var(--color-error)' }}
            title="Copy error message">
            <Copy size={12} />
          </button>
        </div>
      </div>
      <pre className="px-3 py-2 font-mono whitespace-pre-wrap break-words max-h-72 overflow-auto"
        style={{ color: 'var(--color-text)' }}>{display}</pre>
    </div>
  );
}

function TimelineTab({ trace, spans, selectedSpan, onSelectSpan, mode }: {
  trace: Trace;
  spans: Span[];
  selectedSpan: Span | null;
  onSelectSpan: (span: Span) => void;
  mode: InspectorMode;
}) {
  if (spans.length === 0) {
    return <EmptyState>No spans recorded</EmptyState>;
  }

  const layoutClass = mode === 'page'
    ? 'grid gap-4 p-4 lg:grid-cols-[minmax(0,1.15fr)_minmax(360px,0.85fr)]'
    : 'p-3 space-y-3';

  const parentSpan = selectedSpan?.parentSpanId
    ? spans.find(item => item.spanId === selectedSpan.parentSpanId)
    : undefined;

  return (
    <div className={layoutClass}>
      <SpanTimeline trace={trace} spans={spans} selectedSpanId={selectedSpan?.spanId} onSelectSpan={onSelectSpan} />
      {selectedSpan && <SpanInspector span={selectedSpan} parentSpan={parentSpan} />}
    </div>
  );
}

function SpanTimeline({ trace, spans, selectedSpanId, onSelectSpan }: {
  trace: Trace;
  spans: Span[];
  selectedSpanId?: string;
  onSelectSpan: (span: Span) => void;
}) {
  const tree = useMemo(() => buildSpanTree(spans), [spans]);
  const parentIds = useMemo(() => collectExpandableSpanIds(tree), [tree]);
  const [collapsedState, setCollapsedState] = useState<{ traceId: string; ids: Set<string> }>({
    traceId: trace.traceId,
    ids: new Set(),
  });
  const collapsed = useMemo(
    () => (collapsedState.traceId === trace.traceId ? collapsedState.ids : new Set<string>()),
    [collapsedState, trace.traceId],
  );
  const flat = useMemo(() => flattenSpanTree(tree, collapsed), [tree, collapsed]);
  const bounds = useMemo(() => getTimelineBounds(trace, spans), [trace, spans]);

  const toggleCollapse = (spanId: string) => {
    setCollapsedState(prev => {
      const current = prev.traceId === trace.traceId ? prev.ids : new Set<string>();
      const next = new Set(current);
      if (next.has(spanId)) next.delete(spanId);
      else next.add(spanId);
      return { traceId: trace.traceId, ids: next };
    });
  };

  const allCollapsed = parentIds.length > 0 && collapsed.size === parentIds.length;

  return (
    <div className="rounded-lg border overflow-hidden min-w-0"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      <div className="px-3 py-2 border-b flex items-center justify-between gap-2"
        style={{ borderColor: 'var(--color-border)' }}>
        <div className="text-sm font-medium">Span timeline</div>
        {parentIds.length > 0 && (
          <button onClick={() => setCollapsedState({ traceId: trace.traceId, ids: allCollapsed ? new Set() : new Set(parentIds) })}
            className="px-2 py-1 rounded-md text-xs cursor-pointer"
            style={{ color: 'var(--color-text-secondary)' }}>
            {allCollapsed ? 'Expand all' : 'Collapse all'}
          </button>
        )}
      </div>
      <div>
        {flat.map(span => (
          <SpanTimelineRow
            key={span.spanId}
            span={span}
            bounds={bounds}
            selected={span.spanId === selectedSpanId}
            collapsed={collapsed.has(span.spanId)}
            onSelect={onSelectSpan}
            onToggleCollapse={toggleCollapse}
          />
        ))}
      </div>
    </div>
  );
}

function SpanTimelineRow({ span, bounds, selected, collapsed, onSelect, onToggleCollapse }: {
  span: SpanNode;
  bounds: ReturnType<typeof getTimelineBounds>;
  selected: boolean;
  collapsed: boolean;
  onSelect: (span: Span) => void;
  onToggleCollapse: (spanId: string) => void;
}) {
  const color = SPAN_COLORS[span.type] || '#64748b';
  const Icon = SPAN_ICONS[span.type] || Bot;
  const hasChildren = span.children.length > 0;
  const { left, width } = getSpanTiming(span, bounds);
  const operation = spanOperationLabel(span);
  const owner = spanOwnerLabel(span);
  const title = span.type === 'TOOL'
    ? operation
    : owner || (operation !== span.name ? operation : span.name);
  const details = [
    span.type === 'TOOL' ? owner : operation,
    span.model,
    span.workflowNodeId ? `node ${span.workflowNodeId}` : '',
  ].filter(Boolean);
  // Subtle zebra striping by turn so users can see iteration boundaries at a glance.
  const turnTint = span.turnIndex !== undefined && span.turnIndex % 2 === 1
    ? 'rgba(148, 163, 184, 0.08)'
    : 'transparent';
  const defaultBg = selected ? `${color}10` : turnTint;

  return (
    <div onClick={() => onSelect(span)}
      className="grid grid-cols-[minmax(150px,1fr)_minmax(90px,38%)_54px] items-center gap-2 px-3 py-2 border-t cursor-pointer transition-colors"
      style={{
        borderColor: 'var(--color-border)',
        background: defaultBg,
        borderLeft: selected ? `3px solid ${color}` : '3px solid transparent',
      }}
      onMouseEnter={event => { if (!selected) event.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
      onMouseLeave={event => { if (!selected) event.currentTarget.style.background = defaultBg; }}>
      <div className="flex items-center min-w-0" style={{ paddingLeft: span.depth * 14 }}>
        <button
          onClick={event => {
            if (!hasChildren) return;
            event.stopPropagation();
            onToggleCollapse(span.spanId);
          }}
          className="w-4 h-4 flex items-center justify-center shrink-0 cursor-pointer"
          style={{ color: 'var(--color-text-secondary)' }}>
          {hasChildren ? (collapsed ? <ChevronRight size={12} /> : <ChevronDown size={12} />) : null}
        </button>
        <div className="w-5 h-5 rounded-md flex items-center justify-center shrink-0 ml-1"
          style={{ background: `${color}18` }}>
          <Icon size={12} style={{ color }} />
        </div>
        <div className="ml-2 min-w-0">
          <div className="text-xs font-medium truncate" title={title}>{title}</div>
          <div className="text-[10px] truncate" title={details.join(' · ')}
            style={{ color: 'var(--color-text-secondary)' }}>
            <span className="font-medium" style={{ color }}>{span.type}</span>
            {details.length > 0 && <span> · {details.join(' · ')}</span>}
          </div>
        </div>
      </div>

      <div className="relative h-3 rounded-full min-w-0" style={{ background: 'var(--color-bg-tertiary)' }}>
        <div className="absolute h-3 rounded-full"
          style={{
            left: `${left}%`,
            width: `${width}%`,
            background: span.status === 'ERROR' ? 'var(--color-error)' : span.status === 'CANCELLED' ? '#b45309' : color,
            opacity: 0.72,
          }} />
      </div>

      <div className="text-xs text-right tabular-nums" style={{ color: 'var(--color-text-secondary)' }}>
        {formatDuration(span.durationMs)}
      </div>
    </div>
  );
}

function SpanInspector({ span, parentSpan }: { span: Span; parentSpan?: Span }) {
  const [tabState, setTabState] = useState<{ spanId: string; tab: SpanTab }>({ spanId: span.spanId, tab: 'summary' });
  const tab = tabState.spanId === span.spanId ? tabState.tab : 'summary';
  const messages = useMemo(() => extractMessages(span.input), [span.input]);
  const output = useMemo(() => extractAssistantContent(span.output), [span.output]);
  const hasMessages = messages.length > 0 || output;
  const attributeCount = span.attributes ? Object.keys(span.attributes).length : 0;
  const color = SPAN_COLORS[span.type] || '#64748b';
  const Icon = SPAN_ICONS[span.type] || Bot;

  const setTab = (nextTab: SpanTab) => {
    setTabState({ spanId: span.spanId, tab: nextTab });
  };

  return (
    <div className="rounded-lg border overflow-hidden min-w-0"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      <div className="p-3 border-b" style={{ borderColor: 'var(--color-border)' }}>
        <div className="flex items-start justify-between gap-2">
          <div className="flex items-center gap-2 min-w-0">
            <div className="w-7 h-7 rounded-md flex items-center justify-center shrink-0" style={{ background: `${color}18` }}>
              <Icon size={15} style={{ color }} />
            </div>
            <div className="min-w-0">
              <div className="text-sm font-semibold truncate">{span.name}</div>
              <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{span.type}</div>
            </div>
          </div>
          <StatusBadge status={span.status} />
        </div>
        <div className="flex flex-wrap gap-2 mt-3">
          {span.model && <NeutralChip mono>{span.model}</NeutralChip>}
          <NeutralChip><Clock size={11} /> {formatDuration(span.durationMs)}</NeutralChip>
          {(span.inputTokens || span.outputTokens) ? (
            <NeutralChip><Zap size={11} /> {formatTokenPair(span.inputTokens, span.outputTokens)}</NeutralChip>
          ) : null}
          <NeutralChip><Database size={11} /> {formatTokenCount(span.cachedTokens)} cached</NeutralChip>
          <NeutralChip><DollarSign size={11} /> {formatCostUsd(span.costUsd)}</NeutralChip>
        </div>
      </div>

      <div className="flex border-b overflow-x-auto" style={{ borderColor: 'var(--color-border)' }}>
        <SpanTabButton active={tab === 'summary'} onClick={() => setTab('summary')}>Summary</SpanTabButton>
        {hasMessages && <SpanTabButton active={tab === 'messages'} onClick={() => setTab('messages')}>Messages</SpanTabButton>}
        {attributeCount > 0 && <SpanTabButton active={tab === 'attributes'} onClick={() => setTab('attributes')}>Attributes</SpanTabButton>}
        <SpanTabButton active={tab === 'raw'} onClick={() => setTab('raw')}>Raw</SpanTabButton>
      </div>

      <div className="p-3 max-h-[520px] overflow-auto">
        {span.status === 'ERROR' && span.errorMessage && (
          <div className="mb-3">
            <ErrorBlock message={span.errorMessage} />
          </div>
        )}
        {tab === 'summary' && <SpanSummary span={span} parentSpan={parentSpan} />}
        {tab === 'messages' && <MessagesView messages={messages} output={output} inputRaw={span.input} outputRaw={span.output} />}
        {tab === 'attributes' && <AttributesView attributes={span.attributes} />}
        {tab === 'raw' && <CodeBlock content={JSON.stringify(span, null, 2)} />}
      </div>
    </div>
  );
}

// Map span type to user-facing input/output labels. Tool spans hold function arguments
// and results; agent/flow spans hold the user prompt and final assistant response.
function getPayloadLabels(spanType: string): { inputLabel: string; outputLabel: string } {
  switch (spanType) {
    case 'TOOL': return { inputLabel: 'Arguments', outputLabel: 'Result' };
    case 'FLOW': return { inputLabel: 'User prompt', outputLabel: 'Final response' };
    case 'AGENT': return { inputLabel: 'Agent input', outputLabel: 'Agent output' };
    case 'GROUP': return { inputLabel: 'Group input', outputLabel: 'Group output' };
    default: return { inputLabel: 'Input', outputLabel: 'Output' };
  }
}

function SpanSummary({ span, parentSpan }: { span: Span; parentSpan?: Span }) {
  const { inputLabel, outputLabel } = getPayloadLabels(span.type);
  // LLM spans render input/output in the dedicated Messages tab; skip raw payload here.
  const showPayload = span.type !== 'LLM';
  // When tool span sits under an LLM span, surface the causal chain explicitly.
  const parentLabel = parentSpan
    ? `${parentSpan.name} (${parentSpan.type})`
    : span.parentSpanId;

  return (
    <div className="space-y-2">
      <InfoRow label="Span ID" value={span.spanId} mono />
      <InfoRow label="Trace ID" value={span.traceId} mono />
      {span.parentSpanId && <InfoRow label="Parent" value={parentLabel || span.parentSpanId} mono={!parentSpan} />}
      <InfoRow label="Tokens" value={formatTokenPair(span.inputTokens, span.outputTokens)} />
      <InfoRow label="Cached tokens" value={formatTokenCount(span.cachedTokens)} />
      <InfoRow label="Cost" value={formatCostUsd(span.costUsd)} />
      {span.startedAt && <InfoRow label="Started" value={new Date(span.startedAt).toLocaleString()} />}
      {span.completedAt && <InfoRow label="Completed" value={new Date(span.completedAt).toLocaleString()} />}
      {showPayload && span.input && <CollapsibleCode label={inputLabel} content={span.input} />}
      {showPayload && span.output && <CollapsibleCode label={outputLabel} content={span.output} />}
    </div>
  );
}

function MessagesView({ messages, output, inputRaw, outputRaw }: {
  messages: ExtractedMessage[];
  output: ExtractedAssistantOutput | null;
  inputRaw?: string;
  outputRaw?: string;
}) {
  if (messages.length === 0 && !output) {
    return (
      <div className="space-y-3">
        {inputRaw && <CollapsibleCode label="Raw input" content={inputRaw} />}
        {outputRaw && <CollapsibleCode label="Raw output" content={outputRaw} />}
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {messages.map((message, index) => (
        <MessageCard key={`${message.role}-${index}`} message={message} />
      ))}
      {output && <AssistantOutputCard output={output} />}
    </div>
  );
}

function MessageCard({ message }: { message: ExtractedMessage }) {
  const style = roleStyle(message.role);

  return (
    <div className="rounded-lg overflow-hidden" style={{ border: `1px solid ${style.color}30` }}>
      <div className="px-3 py-1.5 text-xs font-medium flex items-center gap-2"
        style={{ background: style.bg, color: style.color }}>
        {message.role === 'tool' && message.name ? `Tool: ${message.name}` : style.label}
        {message.tool_call_id && <span className="font-mono text-[10px] opacity-70">{message.tool_call_id}</span>}
      </div>
      <div className="px-3 py-2">
        {message.content && <pre className="text-xs whitespace-pre-wrap">{message.content}</pre>}
        {!message.content && !message.tool_calls && (
          <pre className="text-xs whitespace-pre-wrap opacity-50">(empty)</pre>
        )}
        {message.tool_calls && <ToolCallList calls={message.tool_calls} />}
      </div>
    </div>
  );
}

function AssistantOutputCard({ output }: { output: ExtractedAssistantOutput }) {
  return (
    <div className="rounded-lg overflow-hidden" style={{ border: '1px solid rgba(34, 197, 94, 0.3)' }}>
      <div className="px-3 py-1.5 text-xs font-medium"
        style={{ background: 'rgba(34, 197, 94, 0.12)', color: '#16a34a' }}>
        Assistant response
      </div>
      <div className="px-3 py-2">
        {output.reasoning && <CollapsibleCode label="Reasoning" content={output.reasoning} />}
        {output.content && <pre className="text-xs whitespace-pre-wrap">{output.content}</pre>}
        {output.tool_calls && <ToolCallList calls={output.tool_calls} />}
      </div>
    </div>
  );
}

function ToolCallList({ calls }: { calls: { id: string; function: { name: string; arguments: string } }[] }) {
  return (
    <div className="space-y-1.5 mt-2">
      {calls.map((call, index) => (
        <div key={`${call.id || call.function.name}-${index}`} className="rounded-md overflow-hidden"
          style={{ border: '1px solid rgba(217, 119, 6, 0.28)' }}>
          <div className="px-2.5 py-1 text-[11px] font-medium flex items-center gap-1.5"
            style={{ background: 'rgba(217, 119, 6, 0.1)', color: '#d97706' }}>
            <Wrench size={11} /> {call.function.name}
            {call.id && <span className="font-mono text-[10px] opacity-70 ml-auto">{call.id}</span>}
          </div>
          <pre className="px-2.5 py-1.5 text-[11px] whitespace-pre-wrap overflow-auto"
            style={{ background: 'var(--color-bg-tertiary)', maxHeight: '160px' }}>
            {prettyContent(call.function.arguments)}
          </pre>
        </div>
      ))}
    </div>
  );
}

function AttributesView({ attributes }: { attributes?: Record<string, string> }) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  if (!attributes || Object.keys(attributes).length === 0) {
    return <EmptyState>No attributes</EmptyState>;
  }

  const groups = Object.entries(attributes).reduce<Record<string, [string, string][]>>((result, [key, value]) => {
    const prefix = key.includes('.') ? key.split('.')[0] : 'other';
    if (!result[prefix]) result[prefix] = [];
    result[prefix].push([key, value]);
    return result;
  }, {});

  const toggle = (key: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  return (
    <div className="space-y-3">
      {Object.entries(groups).map(([group, entries]) => (
        <div key={group}>
          <div className="text-xs font-medium mb-1.5 capitalize" style={{ color: 'var(--color-text-secondary)' }}>
            {group} <span className="font-normal">({entries.length})</span>
          </div>
          <div className="rounded-lg overflow-hidden" style={{ background: 'var(--color-bg-tertiary)' }}>
            {entries.map(([key, rawValue], index) => {
              const value = String(rawValue);
              const isLong = value.length > 80;
              const isExpanded = expanded.has(key);
              return (
                <div key={key}
                  className={`px-3 py-1.5 text-xs ${isLong ? 'cursor-pointer' : ''}`}
                  style={{ borderTop: index > 0 ? '1px solid var(--color-border)' : undefined }}
                  onClick={() => isLong && toggle(key)}>
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono flex-shrink-0 truncate" style={{ color: 'var(--color-primary)', maxWidth: '48%' }} title={key}>
                      {shortAttributeKey(key)}
                    </span>
                    {!isExpanded && (
                      <span className="truncate text-right" style={{ color: 'var(--color-text-secondary)' }} title={value}>
                        {value}
                      </span>
                    )}
                    {isLong && isExpanded && <ChevronDown size={12} style={{ color: 'var(--color-text-secondary)', flexShrink: 0 }} />}
                  </div>
                  {isLong && isExpanded && (
                    <pre className="mt-1.5 p-2 rounded text-xs whitespace-pre-wrap break-all overflow-auto"
                      style={{ background: 'var(--color-bg-secondary)', maxHeight: '220px' }}>
                      {prettyContent(value)}
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

function TraceIOTab({ trace }: { trace: Trace }) {
  return (
    <div className="grid gap-3 p-3 lg:grid-cols-2">
      <CodePanel title="Input" content={trace.input} />
      <CodePanel title="Output" content={trace.output} />
    </div>
  );
}

function CodePanel({ title, content }: { title: string; content?: string }) {
  return (
    <div className="rounded-lg border overflow-hidden min-w-0"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      <div className="px-3 py-2 border-b text-xs font-medium"
        style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
        {title}
      </div>
      {content ? <CodeBlock content={prettyContent(content)} /> : <EmptyState>(empty)</EmptyState>}
    </div>
  );
}

function CodeBlock({ content }: { content: string }) {
  return (
    <pre className="p-3 text-xs whitespace-pre-wrap font-mono overflow-auto"
      style={{ color: 'var(--color-text)' }}>
      {content}
    </pre>
  );
}

function CollapsibleCode({ label, content }: { label: string; content: string }) {
  const [expanded, setExpanded] = useState(false);
  const formatted = useMemo(() => prettyContent(content), [content]);
  const isLong = formatted.length > 260;
  const display = expanded || !isLong ? formatted : `${formatted.slice(0, 260)}...`;

  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
        {isLong && (
          <button onClick={() => setExpanded(prev => !prev)}
            className="text-xs cursor-pointer"
            style={{ color: 'var(--color-primary)' }}>
            {expanded ? 'Collapse' : 'Expand'}
          </button>
        )}
      </div>
      <pre className="text-xs whitespace-pre-wrap p-2.5 rounded-lg overflow-auto"
        style={{ background: 'var(--color-bg-tertiary)', maxHeight: expanded ? '420px' : '170px' }}>
        {display}
      </pre>
    </div>
  );
}

function TraceTabButton({ active, icon, onClick, children }: {
  active: boolean;
  icon: ReactNode;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button onClick={onClick}
      className="px-3 py-2 text-xs cursor-pointer border-b-2 -mb-[2px] transition-colors inline-flex items-center gap-1.5"
      style={{
        borderColor: active ? 'var(--color-primary)' : 'transparent',
        color: active ? 'var(--color-primary)' : 'var(--color-text-secondary)',
        fontWeight: active ? 600 : 400,
      }}>
      {icon}
      {children}
    </button>
  );
}

function SpanTabButton({ active, onClick, children }: {
  active: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button onClick={onClick}
      className="px-3 py-2 text-xs cursor-pointer border-b-2 -mb-[2px] transition-colors whitespace-nowrap"
      style={{
        borderColor: active ? 'var(--color-primary)' : 'transparent',
        color: active ? 'var(--color-primary)' : 'var(--color-text-secondary)',
        fontWeight: active ? 600 : 400,
      }}>
      {children}
    </button>
  );
}

function SourceBadge({ label, color }: { label: string; color: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-xs font-medium whitespace-nowrap shrink-0"
      style={{ color }}>
      <span className="w-1.5 h-1.5 rounded-full" style={{ background: color }} />
      {label}
    </span>
  );
}

function NeutralChip({ children, mono }: { children: ReactNode; mono?: boolean }) {
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-xs ${mono ? 'font-mono' : ''}`}
      style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
      {children}
    </span>
  );
}

function InfoRow({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex justify-between items-start gap-3 text-xs">
      <span className="shrink-0" style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
      <span className={`text-right min-w-0 break-all ${mono ? 'font-mono' : ''}`} title={value}>{value}</span>
    </div>
  );
}

function EmptyState({ children }: { children: ReactNode }) {
  return (
    <div className="p-6 text-sm text-center" style={{ color: 'var(--color-text-secondary)' }}>
      {children}
    </div>
  );
}

function roleStyle(role: string) {
  switch (role) {
    case 'system':
      return { bg: 'rgba(124, 58, 237, 0.1)', color: '#7c3aed', label: 'System' };
    case 'assistant':
      return { bg: 'rgba(22, 163, 74, 0.1)', color: '#16a34a', label: 'Assistant' };
    case 'tool':
      return { bg: 'rgba(217, 119, 6, 0.1)', color: '#d97706', label: 'Tool' };
    case 'user':
      return { bg: 'rgba(37, 99, 235, 0.1)', color: '#2563eb', label: 'User' };
    default:
      return { bg: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)', label: role };
  }
}

function shortAttributeKey(key: string): string {
  const parts = key.split('.');
  return parts.length > 1 ? parts.slice(1).join('.') : key;
}
