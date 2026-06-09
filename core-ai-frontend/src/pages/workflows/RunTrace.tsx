import { useEffect, useState, type CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import { ChevronRight, ChevronDown, ExternalLink, FileDown } from 'lucide-react';
import { RUN_STATUS_COLOR, TERMINAL_RUN_STATUS, type WorkflowRFNode } from './graph';
import type { WorkflowNodeRunView } from '../../api/client';

interface Props {
  nodes: WorkflowRFNode[];
  runStatus: string;
  nodeRuns: Record<string, WorkflowNodeRunView>;
  focusNodeId?: string | null;     // a node clicked on the canvas auto-expands in the trace
}

/** Shared run trace: an overall status row, each node's execution (status, timing, input/output), and the final
 *  result. Used by the live test panel (RunPanel) and the run-history panel so both render runs identically. */
export default function RunTrace({ nodes, runStatus, nodeRuns, focusNodeId }: Props) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  useEffect(() => { if (focusNodeId) setExpanded((s) => new Set(s).add(focusNodeId)); }, [focusNodeId]);

  const nameOf = (id: string) => nodes.find((n) => n.id === id)?.data.name ?? id;
  const typeOf = (id: string) => nodes.find((n) => n.id === id)?.data.nodeType ?? '';
  const runs = Object.values(nodeRuns).sort((a, b) => (a.started_at ?? '').localeCompare(b.started_at ?? ''));
  const result = runs.find((r) => (typeOf(r.node_id) === 'END' || typeOf(r.node_id) === 'ANSWER') && r.output)?.output;
  const toggle = (id: string) => setExpanded((s) => {
    const next = new Set(s);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  return (
    <>
      <div style={statusRow}>
        <span style={{ width: 9, height: 9, borderRadius: '50%', background: RUN_STATUS_COLOR[runStatus] ?? 'var(--color-text-secondary)' }} />
        <span style={{ fontWeight: 600, color: 'var(--color-text)' }}>{runStatus || 'PENDING'}</span>
        {!TERMINAL_RUN_STATUS.has(runStatus) && <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>running…</span>}
      </div>

      <label style={label}>Trace</label>
      {runs.length === 0 && <div style={dim}>No node runs.</div>}
      {runs.map((r) => {
        const open = expanded.has(r.node_id);
        const color = r.status ? RUN_STATUS_COLOR[r.status] : 'var(--color-text-secondary)';
        return (
          <div key={r.node_id} style={nodeCard}>
            <div style={nodeHead} onClick={() => toggle(r.node_id)}>
              {open ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
              <span style={{ width: 7, height: 7, borderRadius: '50%', background: color }} />
              <span style={{ fontWeight: 500, color: 'var(--color-text)', flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{nameOf(r.node_id)}</span>
              <span style={dim}>{r.status === 'SKIPPED' ? 'skipped' : elapsed(r)}</span>
            </div>
            {open && (
              <div style={nodeBody}>
                {r.error && <Field title="Error" body={r.error} danger />}
                <Field title="Input" body={r.input} />
                <Field title="Output" body={r.output} />
                {r.artifacts && r.artifacts.length > 0 && (
                  <div style={{ marginBottom: 8 }}>
                    <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-text-secondary)', marginBottom: 3 }}>Artifacts</div>
                    {r.artifacts.map((a, i) => (
                      <a key={a.file_id ?? i} href={a.url} target="_blank" rel="noopener noreferrer" style={artifactLink} title={a.description || a.title || a.file_name}>
                        <FileDown size={12} /> {a.file_name || a.title || a.file_id}
                      </a>
                    ))}
                  </div>
                )}
                {r.child_run_id && (
                  <Link to={`/runs/${r.child_run_id}`} style={childLink}><ExternalLink size={12} /> open child run</Link>
                )}
              </div>
            )}
          </div>
        );
      })}

      {result && (
        <>
          <label style={label}>Result</label>
          <pre style={pre}>{fmt(result)}</pre>
        </>
      )}
    </>
  );
}

function Field({ title, body, danger }: { title: string; body?: string; danger?: boolean }) {
  return (
    <div style={{ marginBottom: 8 }}>
      <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-text-secondary)', marginBottom: 3 }}>{title}</div>
      <pre style={{ ...pre, color: danger ? '#dc2626' : 'var(--color-text)' }}>{fmt(body)}</pre>
    </div>
  );
}

function elapsed(r: WorkflowNodeRunView): string {
  if (!r.started_at) return '';
  const end = r.completed_at ? new Date(r.completed_at).getTime() : Date.now();
  const ms = end - new Date(r.started_at).getTime();
  if (Number.isNaN(ms) || ms < 0) return '';
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
}

function fmt(body?: string): string {
  if (!body) return '—';
  try { return JSON.stringify(JSON.parse(body), null, 2); } catch { return body; }
}

const label: CSSProperties = { display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', margin: '14px 0 4px' };
const statusRow: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 8, marginTop: 14, padding: '8px 10px',
  border: '1px solid var(--color-border)', borderRadius: 8, background: 'var(--color-bg-tertiary)',
};
const nodeCard: CSSProperties = { border: '1px solid var(--color-border)', borderRadius: 7, marginBottom: 6, background: 'var(--color-bg)' };
const nodeHead: CSSProperties = { display: 'flex', alignItems: 'center', gap: 7, padding: '7px 9px', cursor: 'pointer', color: 'var(--color-text-secondary)' };
const nodeBody: CSSProperties = { padding: '4px 9px 9px 26px' };
const pre: CSSProperties = {
  margin: 0, padding: '7px 9px', fontFamily: 'monospace', fontSize: 11.5, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
  border: '1px solid var(--color-border)', borderRadius: 6, background: 'var(--color-bg-secondary)', maxHeight: 200, overflowY: 'auto', color: 'var(--color-text)',
};
const dim: CSSProperties = { fontSize: 11, color: 'var(--color-text-secondary)' };
const childLink: CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 12, color: 'var(--color-primary)', textDecoration: 'none' };
const artifactLink: CSSProperties = { display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, color: 'var(--color-primary)', textDecoration: 'none', marginBottom: 3, wordBreak: 'break-all' };
