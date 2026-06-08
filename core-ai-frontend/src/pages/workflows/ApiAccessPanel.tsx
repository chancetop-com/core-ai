import { useState, type CSSProperties } from 'react';
import { X, Copy, Check } from 'lucide-react';
import { type WorkflowRFNode } from './graph';
import { startInputVars } from './configWidgets';

interface Props {
  workflowId: string;
  status: string;                   // DRAFT | PUBLISHED — the API only serves the published version
  nodes: WorkflowRFNode[];          // to derive the input schema from the Start node
  onClose: () => void;
}

/** API access panel (Dify-style): shows how to call the published workflow over HTTP — endpoints, auth,
 *  the input schema derived from the Start node, and a ready-to-run curl example. */
export default function ApiAccessPanel({ workflowId, status, nodes, onClose }: Props) {
  const origin = window.location.origin;
  const published = status === 'PUBLISHED';
  const vars = startInputVars(nodes).filter((v) => v.name);
  const inputObj: Record<string, unknown> | null = vars.length
    ? Object.fromEntries(vars.map((v) => [v.name, sampleValue(v.type)]))
    : null;
  const inputStr = inputObj ? JSON.stringify(inputObj) : 'your input text';
  const body = JSON.stringify({ input: inputStr }, null, 2);
  const curl = `curl -X POST ${origin}/api/workflows/${workflowId}/run-sync \\
  -H "Authorization: Bearer YOUR_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '${JSON.stringify({ input: inputStr })}'`;

  return (
    <div style={panel}>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}>
        <span style={{ fontWeight: 600, fontSize: 14, color: 'var(--color-text)' }}>API access</span>
        <div style={{ flex: 1 }} />
        <button onClick={onClose} style={iconBtn} title="Close"><X size={15} /></button>
      </div>

      {!published && (
        <div style={notice}>Publish this workflow first — the API serves the latest published version.</div>
      )}

      <label style={label}>Authentication</label>
      <div style={dim}>Send your account API key as a bearer token:</div>
      <Code text="Authorization: Bearer YOUR_API_KEY" />

      {vars.length > 0 && (
        <>
          <label style={label}>Input variables</label>
          {vars.map((v) => (
            <div key={v.name} style={varRow}>
              <code style={varName}>{v.name}</code>
              <span style={dim}>{v.type}{v.required ? ' · required' : ''}</span>
            </div>
          ))}
          <div style={{ ...dim, marginTop: 6 }}>Pass them JSON-encoded in the <code>input</code> field (a string).</div>
        </>
      )}

      <label style={label}>Synchronous run</label>
      <div style={dim}>Runs and returns the final output in one call (blocks up to 2 min).</div>
      <Code text={`POST ${origin}/api/workflows/${workflowId}/run-sync`} />
      <div style={{ ...dim, margin: '8px 0 4px' }}>Request body</div>
      <Code text={body} />
      <div style={{ ...dim, margin: '8px 0 4px' }}>curl</div>
      <Code text={curl} />

      <label style={label}>Asynchronous run</label>
      <div style={dim}>Start a run, then poll for the result.</div>
      <Code text={`POST ${origin}/api/workflows/${workflowId}/runs   → { "runId": "…", "status": "PENDING" }
GET  ${origin}/api/workflow-runs/{runId}   → poll until status COMPLETED, read .output`} />

      <div style={{ ...dim, marginTop: 12 }}>Response: <code>status</code> (COMPLETED / FAILED / TIMEOUT), <code>output</code>, <code>error</code>.</div>
    </div>
  );
}

function Code({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const copy = () => {
    navigator.clipboard?.writeText(text).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1200);
    });
  };
  return (
    <div style={codeWrap}>
      <pre style={codePre}>{text}</pre>
      <button onClick={copy} style={copyBtn} title="Copy">{copied ? <Check size={13} /> : <Copy size={13} />}</button>
    </div>
  );
}

function sampleValue(type: string): unknown {
  switch (type) {
    case 'number': return 0;
    case 'boolean': return true;
    case 'select': return 'option';
    case 'paragraph': return 'long text';
    default: return 'example';
  }
}

const panel: CSSProperties = {
  width: '100%', height: '100%', boxSizing: 'border-box', padding: 16, overflowY: 'auto',
  background: 'var(--color-bg-secondary)',
};
const label: CSSProperties = { display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', margin: '16px 0 4px' };
const dim: CSSProperties = { fontSize: 11.5, color: 'var(--color-text-secondary)' };
const notice: CSSProperties = {
  fontSize: 12, color: 'var(--color-text)', padding: '8px 10px', borderRadius: 8,
  border: '1px solid var(--color-border)', background: 'var(--color-bg-tertiary)',
};
const varRow: CSSProperties = { display: 'flex', alignItems: 'center', gap: 8, padding: '3px 0' };
const varName: CSSProperties = { fontFamily: 'monospace', fontSize: 12, color: 'var(--color-text)' };
const codeWrap: CSSProperties = { position: 'relative', marginTop: 4 };
const codePre: CSSProperties = {
  margin: 0, padding: '8px 32px 8px 10px', fontFamily: 'monospace', fontSize: 11.5, whiteSpace: 'pre-wrap', wordBreak: 'break-all',
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)',
};
const copyBtn: CSSProperties = {
  position: 'absolute', top: 6, right: 6, display: 'flex', alignItems: 'center', justifyContent: 'center',
  width: 24, height: 24, border: '1px solid var(--color-border)', borderRadius: 6, background: 'var(--color-bg-secondary)',
  color: 'var(--color-text-secondary)', cursor: 'pointer',
};
const iconBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 28, height: 28,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', cursor: 'pointer',
};
