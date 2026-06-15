import { useState, type CSSProperties } from 'react';
import { FlaskConical, X } from 'lucide-react';
import { type WorkflowRFNode } from './graph';
import { startInputVars, widgetInput } from './configWidgets';
import RunTrace from './RunTrace';
import type { WorkflowNodeRunView } from '../../api/client';

interface Props {
  nodes: WorkflowRFNode[];
  runId: string | null;
  runStatus: string;
  nodeRuns: Record<string, WorkflowNodeRunView>;
  busy: boolean;
  error: string;                     // save/validate/run failure, shown before the run starts
  focusNodeId: string | null;        // a node clicked on the canvas auto-expands in the trace
  onRun: (input: string) => void;
  onResume: (nodeId: string, body: { approve?: boolean; input?: string }) => void;
  onResumeFromNode: (nodeId: string) => void;
  resumedFrom?: { runId: string; nodeId: string };
  onClose: () => void;
}

/** The test panel (Dify-style): enter input → Test → watch each node execute with status, timing and
 *  input/output → see the final result. Runs the current draft, no publish required. */
export default function RunPanel({ nodes, runId, runStatus, nodeRuns, busy, error, focusNodeId, onRun, onResume, onResumeFromNode, resumedFrom, onClose }: Props) {
  const inputVars = startInputVars(nodes);
  const [input, setInput] = useState('');                                 // free-text/JSON fallback (no declared inputs)
  const [form, setForm] = useState<Record<string, string | boolean>>({}); // typed form values
  const [err, setErr] = useState('');

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

  return (
    <div style={panel}>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}>
        <span style={{ fontWeight: 600, fontSize: 14, color: 'var(--color-text)' }}>Test</span>
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
          <button onClick={submit} disabled={busy} style={runBtn}><FlaskConical size={15} /> Test draft</button>
          <div style={{ ...dim, marginTop: 6 }}>Runs the current draft — no need to publish.</div>
          {error && <div style={errText}>{error}</div>}
        </>
      )}

      {runId && <RunTrace nodes={nodes} runStatus={runStatus} runError={error} nodeRuns={nodeRuns} focusNodeId={focusNodeId} onResume={onResume} onResumeFromNode={onResumeFromNode} resumedFrom={resumedFrom} busy={busy} />}
    </div>
  );
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
const dim: CSSProperties = { fontSize: 11, color: 'var(--color-text-secondary)' };
const errText: CSSProperties = { color: '#dc2626', fontSize: 12, marginTop: 4 };
const iconBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 28, height: 28,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', cursor: 'pointer',
};
