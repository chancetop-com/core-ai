import { useEffect, useState, type CSSProperties } from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router-dom';
import { ChevronRight, ChevronDown, Download, ExternalLink, FileDown, FileText, RotateCcw } from 'lucide-react';
import { RUN_STATUS_COLOR, TERMINAL_RUN_STATUS, type WorkflowRFNode } from './graph';
import { type InputVar } from './configWidgets';
import type { WorkflowArtifactView, WorkflowNodeRunView } from '../../api/client';
import ArtifactDrawer from '../chat/components/ArtifactDrawer';
import type { ArtifactSpec } from '../chat/components/artifactTypes';

export interface ResumeBody { approve?: boolean; input?: string }

interface Props {
  nodes: WorkflowRFNode[];
  runStatus: string;
  runError?: string;
  nodeRuns: Record<string, WorkflowNodeRunView>;
  focusNodeId?: string | null;     // a node clicked on the canvas auto-expands in the trace
  onResume?: (nodeId: string, body: ResumeBody) => void;   // live test panel only; absent in read-only history
  onResumeFromNode?: (nodeId: string) => void;   // rerun from an executed node of a terminal run; absent = read-only
  resumedFrom?: { runId: string; nodeId: string };   // lineage banner when this run was itself a resume
  busy?: boolean;
}

/** Shared run trace: an overall status row, each node's execution (status, timing, input/output), and the final
 *  result. Used by the live test panel (RunPanel) and the run-history panel so both render runs identically. */
export default function RunTrace({ nodes, runStatus, runError, nodeRuns, focusNodeId, onResume, onResumeFromNode, resumedFrom, busy }: Props) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [active, setActive] = useState<WorkflowArtifactView | null>(null);
  useEffect(() => { if (focusNodeId) setExpanded((s) => new Set(s).add(focusNodeId)); }, [focusNodeId]);
  // Lock body scroll while the preview overlay is open; restore the prior value on close/unmount.
  useEffect(() => {
    if (!active) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, [active]);

  const nameOf = (id: string) => nodes.find((n) => n.id === id)?.data.name ?? id;
  const typeOf = (id: string) => nodes.find((n) => n.id === id)?.data.nodeType ?? '';
  const runs = Object.values(nodeRuns).sort((a, b) => (a.started_at ?? '').localeCompare(b.started_at ?? ''));
  // the END node-run carries the run's result AND its deliverable files (per-node artifacts above are trace-level)
  const endRun = runs.find((r) => (typeOf(r.node_id) === 'END' || typeOf(r.node_id) === 'ANSWER') && (r.output || (r.artifacts?.length ?? 0) > 0));
  const result = endRun?.output;
  const deliverables = endRun?.artifacts ?? [];
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
      {runError && <Field title="Error" body={runError} danger />}
      {resumedFrom && (
        <div style={lineageBanner}>
          <RotateCcw size={12} /> Resumed from <strong>{nameOf(resumedFrom.nodeId)}</strong> of run #{resumedFrom.runId.slice(0, 8)}
        </div>
      )}

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
                {r.status === 'WAITING' && onResume && (
                  <ResumeForm node={nodes.find((n) => n.id === r.node_id)} ask={r.input} busy={!!busy} onSubmit={(body) => onResume(r.node_id, body)} />
                )}
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
                {onResumeFromNode && TERMINAL_RUN_STATUS.has(runStatus)
                  && (r.status === 'COMPLETED' || r.status === 'FAILED_RETRYABLE')
                  && typeOf(r.node_id) !== 'START' && typeOf(r.node_id) !== 'END' && (
                  <button disabled={!!busy} onClick={() => onResumeFromNode(r.node_id)} style={resumeFromBtn}
                    title="Start a new run from this node — upstream nodes are reused, this node and everything after it re-run">
                    <RotateCcw size={12} /> Rerun from here
                  </button>
                )}
              </div>
            )}
          </div>
        );
      })}

      {(result || deliverables.length > 0) && (
        <>
          <label style={label}>Result</label>
          {result && <pre style={pre}>{fmt(result)}</pre>}
          {deliverables.length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: result ? 6 : 0 }}>
              {deliverables.map((a, i) => <ArtifactCard key={a.file_id ?? i} artifact={a} onOpen={setActive} />)}
            </div>
          )}
        </>
      )}
      {active && createPortal(
        <div style={overlayBackdrop} onClick={() => setActive(null)}>
          <div style={overlayPanel} onClick={(e) => e.stopPropagation()}>
            <ArtifactDrawer artifact={toSpec(active)} hideShare onClose={() => setActive(null)} />
          </div>
        </div>,
        document.body,
      )}
    </>
  );
}

/** A deliverable file of the run: type icon (inline preview for images), name, size; click to preview, download link. */
function ArtifactCard({ artifact, onOpen }: { artifact: WorkflowArtifactView; onOpen: (a: WorkflowArtifactView) => void }) {
  const isImage = (artifact.content_type ?? '').startsWith('image/') && !!artifact.url;
  const name = artifact.file_name || artifact.title || artifact.file_id || 'file';
  const meta = [artifact.content_type, fmtSize(artifact.size)].filter(Boolean).join(' · ');
  return (
    <div style={{ ...artifactCard, cursor: 'pointer' }} title={artifact.description || artifact.title || name} onClick={() => onOpen(artifact)}>
      {isImage
        ? <img src={artifact.url} alt={name} style={artifactThumb} />
        : <span style={artifactIcon}><FileText size={18} /></span>}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--color-text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{name}</div>
        {meta && <div style={{ fontSize: 11, color: 'var(--color-text-secondary)' }}>{meta}</div>}
      </div>
      {artifact.url && (
        <a href={artifact.url} target="_blank" rel="noopener noreferrer" style={artifactDownload} title="Download" onClick={(e) => e.stopPropagation()}>
          <Download size={14} />
        </a>
      )}
    </div>
  );
}

function fmtSize(size?: number): string {
  if (size === undefined || size === null || size < 0) return '';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

// Workflow artifact URLs are absolute (built from SYS_PUBLIC_URL). Fetch them as a same-origin
// relative path so the drawer's fetch() avoids CORS; the path is auth-exempt so no Bearer is sent.
function toRelative(url?: string): string | undefined {
  if (!url) return undefined;
  try {
    const u = new URL(url, window.location.origin);
    return u.pathname + u.search;
  } catch {
    return url;
  }
}

function toSpec(a: WorkflowArtifactView): ArtifactSpec {
  return {
    kind: 'file',
    title: a.title || a.file_name || a.file_id || 'file',
    fileName: a.file_name,
    contentType: a.content_type,
    size: a.size,
    contentUrl: toRelative(a.url),
    fileId: a.file_id,
  };
}

function Field({ title, body, danger }: { title: string; body?: string; danger?: boolean }) {
  return (
    <div style={{ marginBottom: 8 }}>
      <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-text-secondary)', marginBottom: 3 }}>{title}</div>
      <pre style={{ ...pre, color: danger ? '#dc2626' : 'var(--color-text)' }}>{fmt(body)}</pre>
    </div>
  );
}

/** The human-action form for a WAITING HUMAN_INPUT node: approve/reject buttons (approval mode) or a typed form
 *  whose object is submitted as the node output (input mode). Prompt comes from the node-run ask snapshot. */
function ResumeForm({ node, ask, busy, onSubmit }: {
  node?: WorkflowRFNode; ask?: string; busy: boolean; onSubmit: (body: ResumeBody) => void;
}) {
  const config = (node?.data.config ?? {}) as Record<string, unknown>;
  const mode = config.mode === 'input' ? 'input' : 'approval';
  const fields: InputVar[] = Array.isArray(config.fields) ? (config.fields as InputVar[]) : [];
  const [form, setForm] = useState<Record<string, string | boolean>>({});
  const prompt = parsePrompt(ask);

  const submitInput = () => {
    const payload: Record<string, unknown> = {};
    for (const f of fields) {
      if (!f.name) continue;
      const raw = form[f.name];
      if (f.type === 'boolean') payload[f.name] = !!raw;
      else if (f.type === 'number') { if (raw !== undefined && raw !== '') payload[f.name] = Number(raw); }
      else if (raw !== undefined && raw !== '') payload[f.name] = raw;
    }
    onSubmit({ input: JSON.stringify(payload) });
  };

  return (
    <div style={resumeBox}>
      {prompt && <div style={{ fontSize: 12, color: 'var(--color-text)', marginBottom: 8, lineHeight: 1.5 }}>{prompt}</div>}
      {mode === 'approval' ? (
        <div style={{ display: 'flex', gap: 8 }}>
          <button disabled={busy} onClick={() => onSubmit({ approve: true })} style={approveBtn}>Approve</button>
          <button disabled={busy} onClick={() => onSubmit({ approve: false })} style={rejectBtn}>Reject</button>
        </div>
      ) : (
        <>
          {fields.map((f, i) => (
            <div key={f.name || i} style={{ marginBottom: 8 }}>
              <div style={{ fontSize: 12, color: 'var(--color-text)', marginBottom: 3 }}>{f.label || f.name}{f.required && <span style={{ color: '#dc2626' }}> *</span>}</div>
              {f.type === 'boolean' ? (
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--color-text)' }}>
                  <input type="checkbox" checked={!!form[f.name]} disabled={busy} onChange={(e) => setForm((s) => ({ ...s, [f.name]: e.target.checked }))} /> {f.name}
                </label>
              ) : f.type === 'paragraph' ? (
                <textarea value={String(form[f.name] ?? '')} disabled={busy} onChange={(e) => setForm((s) => ({ ...s, [f.name]: e.target.value }))} rows={3} style={{ ...resumeInput, resize: 'vertical' }} />
              ) : (
                <input type={f.type === 'number' ? 'number' : 'text'} value={String(form[f.name] ?? '')} disabled={busy} onChange={(e) => setForm((s) => ({ ...s, [f.name]: e.target.value }))} style={resumeInput} />
              )}
            </div>
          ))}
          <button disabled={busy} onClick={submitInput} style={approveBtn}>Submit</button>
        </>
      )}
    </div>
  );
}

// The node-run ask snapshot is {"mode":..,"prompt":..} — show the rendered prompt; fall back to nothing.
function parsePrompt(ask?: string): string {
  if (!ask) return '';
  try {
    const o = JSON.parse(ask) as { prompt?: unknown };
    return typeof o.prompt === 'string' ? o.prompt : '';
  } catch {
    return '';
  }
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
const lineageBanner: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, marginTop: 8, padding: '6px 10px', fontSize: 12,
  color: 'var(--color-text-secondary)', border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg-tertiary)',
};
const resumeFromBtn: CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 5, marginTop: 8, padding: '5px 11px', fontSize: 12, fontWeight: 500,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', cursor: 'pointer',
};
const resumeBox: CSSProperties = {
  marginBottom: 10, padding: 10, borderRadius: 8,
  background: 'rgba(245,158,11,0.10)', border: '1px solid rgba(245,158,11,0.35)',
};
const resumeInput: CSSProperties = {
  width: '100%', boxSizing: 'border-box', padding: '6px 9px', fontSize: 12,
  border: '1px solid var(--color-border)', borderRadius: 6, background: 'var(--color-bg)', color: 'var(--color-text)', outline: 'none',
};
const approveBtn: CSSProperties = {
  padding: '6px 14px', fontSize: 13, fontWeight: 500, border: 'none', borderRadius: 7,
  background: 'var(--color-primary)', color: '#fff', cursor: 'pointer',
};
const rejectBtn: CSSProperties = {
  padding: '6px 14px', fontSize: 13, fontWeight: 500, borderRadius: 7,
  border: '1px solid #fecaca', background: 'transparent', color: '#dc2626', cursor: 'pointer',
};
const artifactLink: CSSProperties = { display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, color: 'var(--color-primary)', textDecoration: 'none', marginBottom: 3, wordBreak: 'break-all' };
const artifactCard: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 9, padding: '7px 9px',
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg-secondary)',
};
const artifactIcon: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 34, height: 34, flexShrink: 0,
  borderRadius: 6, background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)',
};
const artifactThumb: CSSProperties = { width: 34, height: 34, flexShrink: 0, borderRadius: 6, objectFit: 'cover', border: '1px solid var(--color-border)' };
const artifactDownload: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 28, height: 28, flexShrink: 0,
  borderRadius: 6, color: 'var(--color-primary)', textDecoration: 'none',
};
// zIndex 40 stays below the drawer's own maximize portal (z-50) so maximizing floats above this backdrop.
const overlayBackdrop: CSSProperties = {
  position: 'fixed', inset: 0, zIndex: 40,
  background: 'rgba(0,0,0,0.35)', display: 'flex', justifyContent: 'flex-end',
};
const overlayPanel: CSSProperties = { height: '100vh' };
