import { useMemo, useState } from 'react';
import { ChevronDown, ChevronRight, Copy, Check } from 'lucide-react';

type JsonValue = string | number | boolean | null | JsonValue[] | { [k: string]: JsonValue };

interface Props {
  // Raw JSON text. Parse failures fall back to a plain <pre> with the original text.
  value: string;
  // Initial depth that stays expanded. Deeper nodes start collapsed. Default: 2.
  defaultExpandDepth?: number;
  // Whether to render a small header with type + count + copy-all. Default: true.
  showHeader?: boolean;
  // Cap on rendered array/object children per node to keep huge payloads responsive.
  maxChildren?: number;
}

const DEFAULT_MAX_CHILDREN = 200;

function valueType(v: JsonValue): 'object' | 'array' | 'string' | 'number' | 'boolean' | 'null' {
  if (v === null) return 'null';
  if (Array.isArray(v)) return 'array';
  return typeof v as 'string' | 'number' | 'boolean' | 'object';
}

function colorForType(t: ReturnType<typeof valueType>): string {
  switch (t) {
    case 'string': return '#10b981';
    case 'number': return '#3b82f6';
    case 'boolean': return '#8b5cf6';
    case 'null': return 'var(--color-text-muted)';
    default: return 'var(--color-text)';
  }
}

function previewPrimitive(v: JsonValue): string {
  const t = valueType(v);
  if (t === 'string') return `"${v as string}"`;
  if (t === 'null') return 'null';
  return String(v);
}

function countSummary(v: JsonValue): string {
  if (Array.isArray(v)) return `${v.length} ${v.length === 1 ? 'item' : 'items'}`;
  if (v && typeof v === 'object') {
    const n = Object.keys(v as object).length;
    return `${n} ${n === 1 ? 'key' : 'keys'}`;
  }
  return '';
}

function CopyButton({ text, label }: { text: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  const onClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    void navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    });
  };
  return (
    <button onClick={onClick}
      className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded cursor-pointer opacity-0 group-hover:opacity-70 hover:!opacity-100 transition-opacity"
      style={{ color: copied ? 'var(--color-success)' : 'var(--color-text-secondary)', fontSize: '10px' }}
      title={copied ? 'Copied' : (label || 'Copy')}>
      {copied ? <Check size={10} /> : <Copy size={10} />}
    </button>
  );
}

interface NodeProps {
  k: string | number | null;
  value: JsonValue;
  depth: number;
  defaultExpandDepth: number;
  maxChildren: number;
  isLast: boolean;
}

function JsonNode({ k, value, depth, defaultExpandDepth, maxChildren, isLast }: NodeProps) {
  const type = valueType(value);
  const isContainer = type === 'object' || type === 'array';
  const [expanded, setExpanded] = useState(depth < defaultExpandDepth);

  const keyLabel = k === null ? null : (typeof k === 'number' ? String(k) : `"${k}"`);
  const indent = { paddingLeft: depth === 0 ? 0 : 12 };

  if (!isContainer) {
    return (
      <div className="group flex items-baseline gap-1 leading-6" style={indent}>
        {keyLabel !== null && (
          <>
            <span style={{ color: 'var(--color-text)' }}>{keyLabel}</span>
            <span style={{ color: 'var(--color-text-muted)' }}>:</span>
          </>
        )}
        <span style={{ color: colorForType(type), whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
          {previewPrimitive(value)}
        </span>
        {!isLast && <span style={{ color: 'var(--color-text-muted)' }}>,</span>}
        <CopyButton text={typeof value === 'string' ? value : String(value)} label="Copy value" />
      </div>
    );
  }

  const entries: Array<[string | number, JsonValue]> = Array.isArray(value)
    ? value.map((v, i) => [i, v])
    : Object.entries(value as Record<string, JsonValue>);
  const truncated = entries.length > maxChildren;
  const shown = truncated ? entries.slice(0, maxChildren) : entries;
  const openBracket = type === 'array' ? '[' : '{';
  const closeBracket = type === 'array' ? ']' : '}';

  return (
    <div className="group leading-6" style={indent}>
      <div className="flex items-center gap-1 cursor-pointer select-none"
        onClick={() => setExpanded(e => !e)}>
        <span className="inline-flex items-center justify-center shrink-0" style={{ width: 14, color: 'var(--color-text-muted)' }}>
          {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        </span>
        {keyLabel !== null && (
          <>
            <span style={{ color: 'var(--color-text)' }}>{keyLabel}</span>
            <span style={{ color: 'var(--color-text-muted)' }}>:</span>
          </>
        )}
        <span style={{ color: 'var(--color-text-muted)' }}>{openBracket}</span>
        {!expanded && (
          <>
            <span style={{ color: 'var(--color-text-muted)', fontStyle: 'italic' }}>{countSummary(value)}</span>
            <span style={{ color: 'var(--color-text-muted)' }}>{closeBracket}</span>
          </>
        )}
        {!isLast && !expanded && <span style={{ color: 'var(--color-text-muted)' }}>,</span>}
        <CopyButton text={JSON.stringify(value, null, 2)} label="Copy subtree" />
        {!expanded && (
          <span className="opacity-50 text-xs" style={{ color: 'var(--color-text-muted)' }}>
            {countSummary(value)}
          </span>
        )}
      </div>
      {expanded && (
        <div style={{ paddingLeft: 14, borderLeft: '1px dashed var(--color-border)', marginLeft: 6 }}>
          {shown.map(([childKey, childVal], i) => (
            <JsonNode key={String(childKey)}
              k={Array.isArray(value) ? (childKey as number) : (childKey as string)}
              value={childVal}
              depth={depth + 1}
              defaultExpandDepth={defaultExpandDepth}
              maxChildren={maxChildren}
              isLast={i === shown.length - 1 && !truncated} />
          ))}
          {truncated && (
            <div style={{ color: 'var(--color-text-muted)', fontStyle: 'italic', paddingLeft: 12 }}>
              … {entries.length - maxChildren} more {entries.length - maxChildren === 1 ? 'item' : 'items'} truncated
            </div>
          )}
        </div>
      )}
      {expanded && (
        <div style={{ color: 'var(--color-text-muted)' }}>
          {closeBracket}{!isLast && ','}
        </div>
      )}
    </div>
  );
}

export default function JsonTreeView({ value, defaultExpandDepth = 2, showHeader = true, maxChildren = DEFAULT_MAX_CHILDREN }: Props) {
  const parsed = useMemo<{ ok: true; data: JsonValue } | { ok: false; error: string }>(() => {
    try {
      return { ok: true, data: JSON.parse(value) as JsonValue };
    } catch (e) {
      return { ok: false, error: e instanceof Error ? e.message : 'Invalid JSON' };
    }
  }, [value]);

  if (!parsed.ok) {
    return (
      <div className="px-4 py-3 text-xs" style={{ color: 'var(--color-error)' }}>
        <div className="mb-1">Failed to parse JSON: {parsed.error}</div>
        <pre className="whitespace-pre-wrap font-mono p-2 rounded"
          style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)', maxHeight: 240, overflow: 'auto' }}>
          {value}
        </pre>
      </div>
    );
  }

  const top = parsed.data;
  const topType = valueType(top);
  const sizeStr = topType === 'object' || topType === 'array' ? countSummary(top) : previewPrimitive(top);

  return (
    <div className="font-mono text-xs px-4 py-3" style={{ color: 'var(--color-text)', lineHeight: '1.6' }}>
      {showHeader && (
        <div className="flex items-center gap-2 pb-2 mb-2 border-b text-xs"
          style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-muted)' }}>
          <span style={{ color: colorForType(topType), fontWeight: 500 }}>{topType}</span>
          <span>·</span>
          <span>{sizeStr}</span>
          <span className="ml-auto">
            <CopyAllButton text={JSON.stringify(top, null, 2)} />
          </span>
        </div>
      )}
      <JsonNode k={null} value={top} depth={0} defaultExpandDepth={defaultExpandDepth} maxChildren={maxChildren} isLast={true} />
    </div>
  );
}

function CopyAllButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const onClick = () => {
    void navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };
  return (
    <button onClick={onClick}
      className="inline-flex items-center gap-1 px-2 py-0.5 rounded cursor-pointer transition-colors"
      style={{
        color: copied ? 'var(--color-success)' : 'var(--color-text-secondary)',
        background: 'var(--color-bg-tertiary)',
        border: '1px solid var(--color-border)',
        fontSize: '10px',
      }}>
      {copied ? <Check size={11} /> : <Copy size={11} />}
      <span>{copied ? 'Copied' : 'Copy JSON'}</span>
    </button>
  );
}
