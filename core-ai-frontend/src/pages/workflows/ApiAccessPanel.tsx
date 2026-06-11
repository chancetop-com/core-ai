import { useState, type CSSProperties, type ReactNode } from 'react';
import { X, Copy, Check, KeyRound, Info } from 'lucide-react';
import { type WorkflowRFNode } from './graph';
import { startInputVars } from './configWidgets';

interface Props {
  workflowId: string;
  status: string;                   // DRAFT | PUBLISHED — the API only serves the published version
  nodes: WorkflowRFNode[];          // to derive the input schema and human-input contract from the graph
  onClose: () => void;
}

/** API access panel (Dify-style): a step-by-step walkthrough of calling the published workflow over HTTP,
 *  with curl + expected response per step derived from THIS graph (input schema, human-input nodes). */
export default function ApiAccessPanel({ workflowId, status, nodes, onClose }: Props) {
  const origin = window.location.origin;
  const published = status === 'PUBLISHED';
  const vars = startInputVars(nodes).filter((v) => v.name);
  const humanNodes = nodes.filter((n) => n.data.nodeType === 'HUMAN_INPUT');
  const inputObj: Record<string, unknown> | null = vars.length
    ? Object.fromEntries(vars.map((v) => [v.name, sampleValue(v.type)]))
    : null;
  const inputStr = inputObj ? JSON.stringify(inputObj) : 'your input text';
  const firstHuman = humanNodes[0];

  return (
    <div style={panel}>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}>
        <span style={{ fontWeight: 600, fontSize: 14, color: 'var(--color-text)' }}>API access</span>
        <div style={{ flex: 1 }} />
        <button onClick={onClose} style={iconBtn} title="Close"><X size={15} /></button>
      </div>

      {!published && (
        <div style={notice}><Info size={14} style={{ flexShrink: 0, marginTop: 1 }} />
          Publish this workflow first — the API serves the latest published version.</div>
      )}

      <div style={card}>
        <div style={cardTitle}><KeyRound size={13} /> Authentication</div>
        <div style={dim}>Every request sends your account API key (Settings → API keys) as a bearer token:</div>
        <Code text="Authorization: Bearer YOUR_API_KEY" />
      </div>

      <div style={sectionHead}>Step by step</div>

      <Step n={1} title="Start a run">
        {vars.length > 0 && (
          <>
            <div style={dim}>This workflow's Start node declares the input variables below. Pass them as a
              JSON-encoded <em>string</em> in the <code>input</code> field (note the double encoding):</div>
            <div style={{ margin: '6px 0' }}>
              {vars.map((v) => (
                <div key={v.name} style={varRow}>
                  <code style={varName}>{v.name}</code>
                  <span style={dim}>{v.type}{v.required ? ' · required' : ''}</span>
                </div>
              ))}
            </div>
          </>
        )}
        <Code text={`curl -X POST ${origin}/api/workflows/${workflowId}/run-sync \\
  -H "Authorization: Bearer YOUR_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '${JSON.stringify({ input: inputStr })}'`} />
        <div style={{ ...dim, margin: '8px 0 4px' }}>Response — <b><code>id</code> is the run id</b>; every follow-up call uses it:</div>
        <Code text={`{
  "id": "<runId>",            ← keep this
  "status": ${firstHuman ? '"PAUSED",            ← waiting for human input (step 2)' : '"COMPLETED",'}
  "output": ${firstHuman ? 'null' : '"final result text"'},
  "artifacts": [],${firstHuman ? `
  "pending_inputs": [ { "node_id": "${firstHuman.id}", "mode": "${modeOf(firstHuman)}", "prompt": "…" } ]` : ''}
}`} />
        <div style={{ ...dim, marginTop: 6 }}>
          <code>run-sync</code> blocks up to 2 min and returns the final run{firstHuman ? ' — or immediately when it pauses on a human-input node' : ''}.
          Prefer non-blocking? <code>POST …/workflows/{workflowId.slice(0, 8)}…/runs</code> returns
          {' '}<code>{'{ "run_id": "…" }'}</code> right away (same id, different field name), then poll as in step {firstHuman ? 3 : 2}.
        </div>
      </Step>

      {humanNodes.length > 0 && (
        <Step n={2} title="Answer the human-input node">
          <div style={dim}>
            <code>status: "PAUSED"</code> is not an error — the run is parked until a human answers.
            <code> pending_inputs</code> tells you which node (<code>node_id</code>), what it asks (<code>prompt</code>),
            and how to answer (<code>mode</code>). Submit the answer to the resume endpoint:
          </div>
          {humanNodes.map((node) => {
            const mode = modeOf(node);
            return (
              <div key={node.id}>
                <div style={{ ...dim, margin: '8px 0 4px' }}>
                  <code>{node.data.name || node.id}</code> — {mode === 'approval'
                    ? <>approval mode: <code>approve: true</code> takes the approve branch, <code>false</code> the reject branch</>
                    : <>input mode: send the form values JSON-encoded in <code>input</code>; they become the node's output</>}
                </div>
                <Code text={`curl -X POST ${origin}/api/workflow-runs/{runId}/resume \\
  -H "Authorization: Bearer YOUR_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '${resumeSample(node)}'`} />
              </div>
            );
          })}
          <div style={{ ...dim, marginTop: 6 }}>Returns <code>202 {'{ "run_id": "…", "status": "PENDING" }'}</code> — the run resumes; continue to step 3.</div>
        </Step>
      )}

      <Step n={humanNodes.length > 0 ? 3 : 2} title="Poll until it finishes">
        <Code text={`curl ${origin}/api/workflow-runs/{runId} \\
  -H "Authorization: Bearer YOUR_API_KEY"`} />
        <div style={{ ...dim, margin: '8px 0 4px' }}>Repeat every few seconds until <code>status</code> is terminal, then read the result:</div>
        <Code text={`{
  "id": "<runId>",
  "status": "COMPLETED",      ← or FAILED (read .error) / TIMEOUT / CANCELLED${humanNodes.length > 0 ? ' / PAUSED (back to step 2)' : ''}
  "output": "final result text",
  "artifacts": [ {
    "file_name": "report.html", "content_type": "text/html", "size": 5176,
    "url": "…/api/public/artifacts/…"   ← public download link, no auth needed
  } ]
}`} />
        <div style={{ ...dim, marginTop: 6 }}><code>output</code> is the End node's text result; <code>artifacts</code> lists the files the End node delivers (empty if none).</div>
      </Step>

      <div style={sectionHead}>Endpoints</div>
      <div style={card}>
        <Endpoint method="POST" path={`/api/workflows/${workflowId}/run-sync`} desc="run and wait (≤2 min); returns the run" />
        <Endpoint method="POST" path={`/api/workflows/${workflowId}/runs`} desc="start async; returns { run_id }" />
        <Endpoint method="GET" path="/api/workflow-runs/{runId}" desc="poll status / output / artifacts / pending_inputs" />
        {humanNodes.length > 0 && <Endpoint method="POST" path="/api/workflow-runs/{runId}/resume" desc="answer a paused human-input node" />}
        <Endpoint method="GET" path="/api/workflow-runs/{runId}/nodes" desc="per-node trace (debugging)" />
      </div>
    </div>
  );
}

function Step({ n, title, children }: { n: number; title: string; children: ReactNode }) {
  return (
    <div style={card}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
        <span style={stepBadge}>{n}</span>
        <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--color-text)' }}>{title}</span>
      </div>
      {children}
    </div>
  );
}

function Endpoint({ method, path, desc }: { method: string; path: string; desc: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, padding: '4px 0', minWidth: 0 }}>
      <span style={{ ...methodChip, color: method === 'GET' ? '#16a34a' : '#2563eb', borderColor: 'currentColor' }}>{method}</span>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontFamily: 'monospace', fontSize: 11.5, color: 'var(--color-text)', wordBreak: 'break-all' }}>{path}</div>
        <div style={dim}>{desc}</div>
      </div>
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

function modeOf(node: WorkflowRFNode): 'approval' | 'input' {
  return (node.data.config as Record<string, unknown> | undefined)?.mode === 'input' ? 'input' : 'approval';
}

// resume request body derived from the node's actual contract (mode + form schema)
function resumeSample(node: WorkflowRFNode): string {
  const config = (node.data.config ?? {}) as Record<string, unknown>;
  if (modeOf(node) === 'approval') return JSON.stringify({ node_id: node.id, approve: true });
  const fields = Array.isArray(config.fields) ? (config.fields as { name?: string; type?: string }[]) : [];
  const values = Object.fromEntries(fields.filter((f) => f.name).map((f) => [f.name!, sampleValue(f.type ?? 'text')]));
  return JSON.stringify({ node_id: node.id, input: JSON.stringify(values) });
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
const card: CSSProperties = {
  padding: '10px 12px', marginBottom: 10, borderRadius: 9,
  border: '1px solid var(--color-border)', background: 'var(--color-bg)',
};
const cardTitle: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5, fontWeight: 600,
  color: 'var(--color-text)', marginBottom: 6,
};
const sectionHead: CSSProperties = {
  fontSize: 11, fontWeight: 700, letterSpacing: 0.6, textTransform: 'uppercase',
  color: 'var(--color-text-secondary)', margin: '14px 2px 6px',
};
const stepBadge: CSSProperties = {
  display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 20, height: 20, flexShrink: 0,
  borderRadius: '50%', background: 'var(--color-primary)', color: '#fff', fontSize: 11.5, fontWeight: 700,
};
const methodChip: CSSProperties = {
  flexShrink: 0, padding: '0 6px', fontSize: 10.5, fontWeight: 700, borderRadius: 5,
  border: '1px solid', lineHeight: '16px',
};
const dim: CSSProperties = { fontSize: 11.5, color: 'var(--color-text-secondary)', lineHeight: 1.55 };
const notice: CSSProperties = {
  display: 'flex', gap: 7, fontSize: 12, color: 'var(--color-text)', padding: '8px 10px', borderRadius: 8,
  border: '1px solid rgba(245,158,11,0.4)', background: 'rgba(245,158,11,0.10)', marginBottom: 10,
};
const varRow: CSSProperties = { display: 'flex', alignItems: 'center', gap: 8, padding: '2px 0' };
const varName: CSSProperties = { fontFamily: 'monospace', fontSize: 12, color: 'var(--color-text)' };
const codeWrap: CSSProperties = { position: 'relative', marginTop: 4 };
const codePre: CSSProperties = {
  margin: 0, padding: '8px 32px 8px 10px', fontFamily: 'monospace', fontSize: 11.5, whiteSpace: 'pre-wrap', wordBreak: 'break-all',
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg-secondary)', color: 'var(--color-text)',
};
const copyBtn: CSSProperties = {
  position: 'absolute', top: 6, right: 6, display: 'flex', alignItems: 'center', justifyContent: 'center',
  width: 24, height: 24, border: '1px solid var(--color-border)', borderRadius: 6, background: 'var(--color-bg)',
  color: 'var(--color-text-secondary)', cursor: 'pointer',
};
const iconBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 28, height: 28,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', cursor: 'pointer',
};
