import { lazy, Suspense, useState } from 'react';
import { Loader2, ChevronDown, ChevronRight, Wrench, Copy, Check } from 'lucide-react';
import type { ToolEvent } from '../types';
import { normalizeArgs, getArgsPreview } from '../utils';

const JsonTreeView = lazy(() => import('../../../components/JsonTreeView'));

function tryParseJson(s: string): unknown | null {
  const trimmed = s.trim();
  if (!trimmed) return null;
  const first = trimmed[0];
  if (first !== '{' && first !== '[' && first !== '"') return null;
  try { return JSON.parse(trimmed); } catch { return null; }
}

function CopyIconButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const handleCopy = async (e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
      }
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch (err) {
      console.warn('copy failed', err);
    }
  };
  return (
    <button onClick={handleCopy}
      className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded cursor-pointer transition-opacity opacity-70 hover:opacity-100"
      style={{
        color: copied ? 'var(--color-success)' : 'var(--color-text-secondary)',
        background: 'var(--color-bg-tertiary)',
        border: '1px solid var(--color-border)',
        fontSize: '10px',
      }}
      title={copied ? 'Copied' : 'Copy detail'}>
      {copied ? <Check size={10} /> : <Copy size={10} />}
      <span>{copied ? 'Copied' : 'Copy'}</span>
    </button>
  );
}

function JsonResultView({ value }: { value: string }) {
  return (
    <Suspense fallback={
      <pre className="whitespace-pre-wrap font-mono px-3 py-2"
        style={{ color: 'var(--color-text-muted)', fontSize: '11px' }}>
        Loading JSON...
      </pre>
    }>
      <JsonTreeView value={value} defaultExpandDepth={1} showHeader={false} />
    </Suspense>
  );
}

export default function ToolsBlock({ tools }: { tools: ToolEvent[] }) {
  const [expanded, setExpanded] = useState(true);
  const [expandedResults, setExpandedResults] = useState<Set<string>>(new Set());
  const [collapsedChildren, setCollapsedChildren] = useState<Set<string>>(new Set());
  const toggleResult = (key: string) => {
    setExpandedResults(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  };
  const toggleChildren = (key: string) => {
    setCollapsedChildren(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  };
  const formatJson = (s: string) => {
    try { return JSON.stringify(JSON.parse(s), null, 2); } catch { return s; }
  };
  const doneCount = tools.filter(t => t.type === 'result' && (t.resultStatus === 'success' || t.resultStatus === 'COMPLETED')).length;

  const buildTaskToolLabel = (t: ToolEvent): string | null => {
    if (t.tool !== 'task') return null;
    const args = normalizeArgs(t.arguments);
    if (!args) return null;
    const subagentType = typeof args.subagent_type === 'string' ? args.subagent_type : null;
    if (!subagentType) return null;
    const model = t.model;
    if (model && model !== subagentType) {
      return `task(${subagentType}[${model}])`;
    }
    return `task(${subagentType})`;
  };

  // Check if any tool (including nested) is still running
  const isRunning = (t: ToolEvent): boolean => {
    if (t.type === 'start') return true;
    if (t.children && t.children.some(isRunning)) return true;
    return false;
  };
  const hasRunning = tools.some(isRunning);
  const toolDetail = (t: ToolEvent): string | undefined => {
    if (t.type === 'result' && t.result) return t.result;
    return t.output;
  };
   // Render a single tool row (used for both top-level and children)
  const renderToolRow = (t: ToolEvent, key: string, level: number = 0) => {
    const hasChildren = t.children && t.children.length > 0;
    const showTaskIdBadge = level === 0 && t.taskId;
    const detail = toolDetail(t);
    const headerContent = (
      <>
        {t.type === 'start' ? (
          <Loader2 size={14} className="animate-spin shrink-0" style={{ color: 'var(--color-warning)' }} />
        ) : (
          <span className="shrink-0" style={{ color: (t.resultStatus === 'success' || t.resultStatus === 'COMPLETED') ? 'var(--color-success)' : 'var(--color-error)' }}>
            {(t.resultStatus === 'success' || t.resultStatus === 'COMPLETED') ? '\u2713' : '\u2717'}
          </span>
        )}
        <span className="font-mono font-medium truncate" style={{ color: hasChildren ? '#8b5cf6' : 'var(--color-primary)' }}>{buildTaskToolLabel(t) ?? t.tool}</span>
        {showTaskIdBadge && (
          <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-xs font-mono shrink-0 max-w-[200px] truncate"
            style={{ background: t.runInBackground ? 'var(--color-warning)' + '20' : '#8b5cf6' + '20', color: t.runInBackground ? 'var(--color-warning)' : '#8b5cf6' }}>
            {t.runInBackground && <span className="opacity-70">bg:</span>}
            {t.taskId}
          </span>
        )}
        {hasChildren && (
          <span className="text-xs opacity-60 shrink-0" style={{ color: '#8b5cf6' }}>
            ({t.children!.length} sub-tools)
          </span>
        )}
        {t.arguments && normalizeArgs(t.arguments) && getArgsPreview(t.arguments) && (
          <span className="opacity-70 truncate min-w-0">{getArgsPreview(t.arguments)}</span>
        )}
        {t.type === 'result' && (
          <>
            <span className="shrink-0" style={{ color: (t.resultStatus === 'success' || t.resultStatus === 'COMPLETED') ? 'var(--color-success)' : 'var(--color-error)' }}>
              {(t.resultStatus === 'success' || t.resultStatus === 'COMPLETED') ? 'done' : (t.resultStatus || 'error')}
            </span>
            {detail && (
              <button onClick={() => toggleResult(key)}
                className="ml-auto flex items-center gap-0.5 cursor-pointer opacity-60 hover:opacity-100 shrink-0"
                style={{ color: 'var(--color-text-secondary)' }}>
                {expandedResults.has(key) ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                <span>detail</span>
              </button>
            )}
          </>
        )}
        {t.type === 'start' && t.output && (
          <button onClick={() => toggleResult(key)}
            className="ml-auto flex items-center gap-0.5 cursor-pointer opacity-60 hover:opacity-100 shrink-0"
            style={{ color: 'var(--color-text-secondary)' }}>
            {expandedResults.has(key) ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
            <span>output</span>
          </button>
        )}
      </>
    );

    if (!hasChildren) {
      return (
        <div key={key}>
          <div className="flex items-center gap-2 px-3 py-1.5 rounded"
            style={{
              color: 'var(--color-text-secondary)',
              background: 'var(--color-bg-secondary)',
              marginLeft: level > 0 ? `${level * 12}px` : undefined,
            }}>
            {headerContent}
          </div>
          {detail && expandedResults.has(key) && (
            <div className="px-3 pb-1.5" style={{ marginLeft: level > 0 ? `${level * 12}px` : undefined }}>
              <div className="relative rounded-b overflow-auto"
                style={{ maxHeight: '240px', background: 'var(--color-bg-secondary)' }}>
                <div className="absolute top-1 right-1 z-10">
                  <CopyIconButton text={formatJson(detail)} />
                </div>
                {tryParseJson(detail) !== null ? (
                  <JsonResultView value={detail} />
                ) : (
                  <pre className="whitespace-pre-wrap font-mono px-3 py-2"
                    style={{ color: 'var(--color-text-secondary)', fontSize: '11px' }}>
                    {detail}
                  </pre>
                )}
              </div>
            </div>
          )}
        </div>
      );
    }

    // With children: render as a box containing header + children
    const childrenCollapsed = collapsedChildren.has(key);
    return (
      <div key={key} className="rounded-lg overflow-hidden"
        style={{
          background: 'var(--color-bg-secondary)',
          border: '1px solid var(--color-border)',
          marginLeft: level > 0 ? `${level * 12}px` : undefined,
        }}>
        {/* Header bar — clickable to toggle children */}
        <button onClick={() => toggleChildren(key)}
          className="flex items-center gap-2 w-full text-left cursor-pointer"
          style={{
            padding: '6px 12px',
            color: 'var(--color-text-secondary)',
            background: 'var(--color-bg-tertiary)',
            borderBottom: '1px solid var(--color-border)',
          }}>
          {childrenCollapsed ? <ChevronRight size={14} className="shrink-0" /> : <ChevronDown size={14} className="shrink-0" />}
          <span className="flex items-center gap-2 flex-1 min-w-0">
            {headerContent}
          </span>
        </button>
        {/* Parent result detail */}
        {detail && expandedResults.has(key) && (
          <div className="relative" style={{ borderBottom: '1px solid var(--color-border)', maxHeight: '240px', overflow: 'auto' }}>
            <div className="absolute top-1 right-1 z-10">
              <CopyIconButton text={formatJson(detail)} />
            </div>
            {tryParseJson(detail) !== null ? (
              <JsonResultView value={detail} />
            ) : (
              <pre className="whitespace-pre-wrap font-mono px-3 py-2"
                style={{ color: 'var(--color-text-secondary)', fontSize: '11px' }}>
                {detail}
              </pre>
            )}
          </div>
        )}
        {/* Children inside the box */}
        {!childrenCollapsed && (
          <div className="flex flex-col gap-1.5 p-2">
            {t.children!.map((child, ci) => renderToolRow(child, `${key}-child-${ci}`, level + 1))}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="mb-2 rounded-xl border text-xs"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-tertiary)' }}>
      <button onClick={() => setExpanded(e => !e)}
        className="flex items-center gap-1.5 w-full px-3 py-2 cursor-pointer"
        style={{ color: 'var(--color-text-secondary)' }}>
        {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        <Wrench size={14} />
        {hasRunning && <Loader2 size={12} className="animate-spin" style={{ color: 'var(--color-warning)' }} />}
        <span className="font-medium">Tools ({tools.length})</span>
        {doneCount > 0 && <span className="opacity-60">({doneCount} done)</span>}
      </button>
      {expanded && (
        <div className="border-t flex flex-col gap-0" style={{ borderColor: 'var(--color-border)' }}>
          {tools.map((t, j) => renderToolRow(t, String(j), 0))}
        </div>
      )}
    </div>
  );
}
