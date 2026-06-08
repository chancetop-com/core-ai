import { useEffect, useState, type CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import { Play, X, ChevronRight, ChevronDown, ExternalLink } from 'lucide-react';
import { RUN_STATUS_COLOR, TERMINAL_RUN_STATUS, type WorkflowRFNode } from './graph';
import { startInputVars, widgetInput } from './configWidgets';
import type { WorkflowNodeRunView } from '../../api/client';

interface Props {
  nodes: WorkflowRFNode[];
  runId: string | null;
  runStatus: string;
  nodeRuns: Record<string, WorkflowNodeRunView>;
  busy: boolean;
  error: string;                     // save/validate/publish/run failure, shown before the run starts
  focusNodeId: string | null;        // a node clicked on the canvas auto-expands in the trace
  onRun: (input: string) => void;
  onClose: () => void;
}

/** The unified run/preview panel (Dify-style): enter input → Run → watch each node execute with status, timing
 *  and input/output → see the final result. Replaces the old input modal + status strip + per-node detail. */
export default function RunPanel({ nodes, runId, runStatus, nodeRuns, busy, error, focusNodeId, onRun, onClose }: Props) {
  const inputVars = startInputVars(nodes);
  const [input, setInput] = useState('');                                 // free-text/JSON fallback (no declared inputs)
  const [form, setForm] = useState<Record<string, string | boolean>>({}); // typed form values
  const [err, setErr] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (focusNodeId) setExpanded((s) => new Set(s).add(focusNodeId));
  }, [focusNodeId]);

  const nameOf = (id: string) => nodes.find((n) => n.id === id)?.data.name ?? id;
  const typeOf = (id: string) => nodes.find((n) => n.id === id)?.data.nodeType ?? '';
  const runs = Object.values(nodeRuns).sort((a, b) => (a.started_at ?? '').localeCompare(b.started_at ?? ''));
  const result = runs.find((r) => (typeOf(r.node_id) === 'END' || typeOf(r.node_id) === 'ANSWER') && r.output)?.output;

  const submit = () => {
    setErr('');
    if (inputVars.length > 0) {
      const missing = inputVars.filter((v) => v.required && v.type !== 'boolean' && !form[v.name]);
      if (missing.length) { setErr(`Required: ${missing.map((v) => v.label || v.name).join(', ')}`); return; }
      const payload: Record<string, unknown> = {};
      for (const v of inputVars) {
        if (!v.name) continue;
        const raw = form[v.name];
        if (v.type === 'boolean') payload[v.name] = !!raw;
        else if (v.type === 'number') { if (raw !== undefined && raw !== '') payload[v.name] = Number(raw); }
        else if (raw !== undefined && raw !== '') payload[v.name] = raw;
      }
      onRun(JSON.stringify(payload));
      return;
    }
    // No declared inputs: accept plain text OR JSON as-is (downstream reads it via sys.input). Empty -> {}.
    onRun(input.trim() ? input : '{}');
  };

  const setField = (name: string, value: string | boolean) => setForm((f) => ({ ...f, [name]: value }));
  const toggle = (id: string) => setExpanded((s) => {
    const next = new Set(s);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  return (
    <div style={panel}>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}>
        <span style={{ fontWeight: 600, fontSize: 14, color: 'var(--color-text)' }}>Run</span>
        <div style={{ flex: 1 }} />
        <button onClick={onClose} style={iconBtn} title="Close"><X size={15} /></button>
      </div>

      <label style={label}>Input</label>
      {inputVars.length > 0 ? (
        inputVars.map((v, i) => (
          <div key={v.name || i} style={{ marginBottom: 8 }}>
            <div style={fieldLabel}>{v.label || v.name}{v.required && <span style={{ color: '#dc2626' }}> *</span>}</div>
            {v.type === 'boolean' ? (
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--color-text)' }}>
                <input type="checkbox" checked={!!form[v.name]} disabled={!!runId} onChange={(e) => setField(v.name, e.target.checked)} /> {v.name}
              </label>
            ) : v.type === 'select' ? (
              <select value={String(form[v.name] ?? '')} disabled={!!runId} onChange={(e) => setField(v.name, e.target.value)} style={fieldInput}>
                <option value="">— select —</option>
                {(v.options ?? '').split(',').map((o) => o.trim()).filter(Boolean).map((o) => <option key={o} value={o}>{o}</option>)}
              </select>
            ) : v.type === 'paragraph' ? (
              <textarea value={String(form[v.name] ?? '')} disabled={!!runId} onChange={(e) => setField(v.name, e.target.value)} rows={3} style={{ ...fieldInput, resize: 'vertical' }} />
            ) : (
              <input type={v.type === 'number' ? 'number' : 'text'} value={String(form[v.name] ?? '')} disabled={!!runId} onChange={(e) => setField(v.name, e.target.value)} style={fieldInput} />
            )}
          </div>
        ))
      ) : (
        <>
          <textarea
            value={input}
            onChange={(e) => { setInput(e.target.value); setErr(''); }}
            disabled={!!runId}
            spellCheck={false}
            placeholder="plain text or JSON — leave blank for none"
            style={textarea}
          />
          {!runId && <div style={{ ...dim, marginTop: 4 }}>Tip: add input variables on the Start node to get a typed form here.</div>}
        </>
      )}
      {err && <div style={errText}>{err}</div>}
      {!runId && (
        <>
          <button onClick={submit} disabled={busy} style={runBtn}><Play size={15} /> Run draft</button>
          <div style={{ ...dim, marginTop: 6 }}>Runs the current draft — no need to publish.</div>
          {error && <div style={errText}>{error}</div>}
        </>
      )}

      {runId && (
        <>
          <div style={statusRow}>
            <span style={{ width: 9, height: 9, borderRadius: '50%', background: RUN_STATUS_COLOR[runStatus] ?? 'var(--color-text-secondary)' }} />
            <span style={{ fontWeight: 600, color: 'var(--color-text)' }}>{runStatus || 'PENDING'}</span>
            {!TERMINAL_RUN_STATUS.has(runStatus) && <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>running…</span>}
          </div>

          <label style={label}>Trace</label>
          {runs.length === 0 && <div style={dim}>Waiting for the first node…</div>}
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
      )}
    </div>
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

const panel: CSSProperties = {
  width: '100%', height: '100%', boxSizing: 'border-box', padding: 16, overflowY: 'auto',
  background: 'var(--color-bg-secondary)',
};
const label: CSSProperties = { display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', margin: '14px 0 4px' };
const fieldLabel: CSSProperties = { fontSize: 12, color: 'var(--color-text)', marginBottom: 3 };
const fieldInput: CSSProperties = {
  ...widgetInput, width: '100%', boxSizing: 'border-box', fontSize: 13,
};
const textarea: CSSProperties = {
  width: '100%', boxSizing: 'border-box', minHeight: 64, padding: '7px 10px', fontFamily: 'monospace', fontSize: 12,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', outline: 'none', resize: 'vertical',
};
const runBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, marginTop: 10, padding: '8px 14px', width: '100%',
  border: 'none', borderRadius: 8, background: 'var(--color-primary)', color: '#fff', cursor: 'pointer', fontWeight: 500,
};
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
const errText: CSSProperties = { color: '#dc2626', fontSize: 12, marginTop: 4 };
const iconBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 28, height: 28,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', cursor: 'pointer',
};
const childLink: CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 12, color: 'var(--color-primary)', textDecoration: 'none' };
