import { useState, type CSSProperties, type ReactNode } from 'react';
import { X, Copy, Check, ChevronRight, ChevronDown, KeyRound, Info } from 'lucide-react';
import { type WorkflowRFNode } from './graph';
import { startInputVars } from './configWidgets';

interface Props {
  workflowId: string;
  status: string;                   // PUBLIC workflows expose the API run endpoints
  nodes: WorkflowRFNode[];          // to derive the input schema and human-input contract from the graph
  onClose: () => void;
}

/** API access panel (Dify-style): one collapsible section per interface, each with a complete curl + response
 *  example derived from THIS graph (input schema, human-input nodes). */
export default function ApiAccessPanel({ workflowId, status, nodes, onClose }: Props) {
  const origin = window.location.origin;
  const published = status === 'PUBLIC';
  const vars = startInputVars(nodes).filter((v) => v.name);
  const humanNodes = nodes.filter((n) => n.data.nodeType === 'HUMAN_INPUT');
  const inputObj: Record<string, unknown> | null = vars.length
    ? Object.fromEntries(vars.map((v) => [v.name, sampleValue(v.type)]))
    : null;
  const inputStr = inputObj ? JSON.stringify(inputObj) : 'your input text';
  const requestBody = JSON.stringify({ input: inputStr, visibility: 'PRIVATE' });
  const firstHuman = humanNodes[0];
  const pausedLines = firstHuman
    ? `\n  "pending_inputs": [ { "node_id": "${firstHuman.id}", "mode": "${modeOf(firstHuman)}", "prompt": "…" } ]`
    : '';

  const finalResponse = `{
  "id": "<runId>",
  "status": "COMPLETED",      ← or FAILED (read .error) / TIMEOUT${humanNodes.length > 0 ? ' / PAUSED (→ Human input)' : ''}
  "output": "final result text",
  "artifacts": [ {
    "file_name": "report.html", "content_type": "text/html", "size": 5176,
    "url": "…/api/public/artifacts/…"   ← public download link, no auth needed
  } ]
}`;

  return (
    <div style={panel}>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 12 }}>
        <span style={{ fontWeight: 600, fontSize: 14, color: 'var(--color-text)' }}>API access</span>
        <div style={{ flex: 1 }} />
        <button onClick={onClose} style={iconBtn} title="Close"><X size={15} /></button>
      </div>

      {!published && (
        <div style={notice}><Info size={14} style={{ flexShrink: 0, marginTop: 1 }} />
          Publish this workflow first from the toolbar — the API serves the public version.</div>
      )}

      <Section icon={<KeyRound size={13} />} title="Authentication" defaultOpen>
        <div style={dim}>Every request sends your account API key (Settings → API keys) as a bearer token:</div>
        <Code text="Authorization: Bearer YOUR_API_KEY" />
        {vars.length > 0 && (
          <>
            <div style={{ ...dim, marginTop: 8 }}>The Start node declares these input variables — pass them
              JSON-encoded as a <em>string</em> in the <code>input</code> field (note the double encoding):</div>
            {vars.map((v) => (
              <div key={v.name} style={varRow}>
                <code style={varName}>{v.name}</code>
                <span style={dim}>{v.type}{v.required ? ' · required' : ''}</span>
              </div>
            ))}
          </>
        )}
      </Section>

      <Section method="POST" path="/run-sync" title="Synchronous run" defaultOpen>
        <div style={dim}>One call that blocks until the run finishes (up to 2 min) and returns the final run
          {humanNodes.length > 0 ? ' — or returns immediately when it pauses on a human-input node' : ''}.
          Best for short workflows and simple scripts.</div>
        <Code text={`curl -X POST ${origin}/api/workflows/${workflowId}/run-sync \\
  -H "Authorization: Bearer YOUR_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '${requestBody}'`} />
        <div style={{ ...dim, margin: '8px 0 4px' }}>Response — <b><code>id</code> is the run id</b>, used by every follow-up call:</div>
        <Code text={firstHuman
          ? `{
  "id": "<runId>",            ← keep this
  "status": "PAUSED",         ← waiting for human input (→ Human input section)
  "output": null,
  "artifacts": [],${pausedLines}
}`
          : finalResponse} />
        {humanNodes.length === 0 && (
          <div style={{ ...dim, marginTop: 6 }}>If it returns while still <code>RUNNING</code> (hit the 2 min cap), keep polling as in the asynchronous flow.</div>
        )}
      </Section>

      <Section method="POST" path="/runs" title="Asynchronous run (start + poll)">
        <div style={dim}>Returns immediately; poll for the result. Best for long workflows, agent chains
          {humanNodes.length > 0 ? ', and human-in-the-loop flows like this one' : ''}.</div>
        <div style={stepLabel}>1 · Start</div>
        <Code text={`curl -X POST ${origin}/api/workflows/${workflowId}/runs \\
  -H "Authorization: Bearer YOUR_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '${requestBody}'`} />
        <div style={{ ...dim, margin: '6px 0 4px' }}>Returns <code>202</code> right away — <b><code>run_id</code> is the run id</b> (same id the sync call returns as <code>id</code>):</div>
        <Code text={'{ "run_id": "<runId>", "status": "PENDING" }'} />
        <div style={stepLabel}>2 · Poll until terminal</div>
        <Code text={`curl ${origin}/api/workflow-runs/{runId} \\
  -H "Authorization: Bearer YOUR_API_KEY"`} />
        <div style={{ ...dim, margin: '6px 0 4px' }}>Repeat every few seconds while <code>status</code> is <code>PENDING</code> / <code>RUNNING</code>; the final response:</div>
        <Code text={finalResponse} />
        <div style={{ ...dim, marginTop: 6 }}><code>output</code> is the End node's text result; <code>artifacts</code> lists the files the End node delivers (empty if none).</div>
      </Section>

      {humanNodes.length > 0 && (
        <Section method="POST" path="/resume" title="Human input" defaultOpen>
          <div style={dim}>
            This workflow contains human-input node(s). When a run reaches one it pauses — <code>status: "PAUSED"</code> is
            not an error. <code>pending_inputs</code> in the run response tells you which node (<code>node_id</code>),
            what it asks (<code>prompt</code>), and how to answer (<code>mode</code>):
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
          <div style={{ ...dim, marginTop: 6 }}>Returns <code>202 {'{ "run_id": "…", "status": "PENDING" }'}</code> — the run resumes; keep polling until terminal (it pauses again if another human-input node is reached).</div>
        </Section>
      )}

      <Section method="GET" path="/workflow-runs/{runId}/nodes" title="Per-node trace (debugging)">
        <div style={dim}>The execution detail of every node — status, timing, input/output, per-node files. Useful when a run fails and <code>error</code> alone is not enough.</div>
        <Code text={`curl ${origin}/api/workflow-runs/{runId}/nodes \\
  -H "Authorization: Bearer YOUR_API_KEY"`} />
      </Section>
    </div>
  );
}

/** Collapsible interface section: chevron + optional method/path chips in the header, body folds away. */
function Section({ icon, method, path, title, defaultOpen, children }: {
  icon?: ReactNode; method?: string; path?: string; title: string; defaultOpen?: boolean; children: ReactNode;
}) {
  const [open, setOpen] = useState(!!defaultOpen);
  return (
    <div style={card}>
      <div style={sectionHead} onClick={() => setOpen((v) => !v)}>
        {open ? <ChevronDown size={14} style={{ flexShrink: 0 }} /> : <ChevronRight size={14} style={{ flexShrink: 0 }} />}
        {icon}
        {method && <span style={{ ...methodChip, color: method === 'GET' ? '#16a34a' : '#2563eb' }}>{method}</span>}
        <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--color-text)' }}>{title}</span>
        {path && <code style={pathChip}>{path}</code>}
      </div>
      {open && <div style={{ padding: '0 12px 10px 12px' }}>{children}</div>}
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
  marginBottom: 10, borderRadius: 9,
  border: '1px solid var(--color-border)', background: 'var(--color-bg)',
};
const sectionHead: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 7, padding: '9px 12px', cursor: 'pointer',
  color: 'var(--color-text-secondary)', flexWrap: 'wrap',
};
const methodChip: CSSProperties = {
  flexShrink: 0, padding: '0 6px', fontSize: 10.5, fontWeight: 700, borderRadius: 5,
  border: '1px solid currentColor', lineHeight: '16px',
};
const pathChip: CSSProperties = {
  fontFamily: 'monospace', fontSize: 10.5, color: 'var(--color-text-secondary)',
  padding: '1px 6px', borderRadius: 5, background: 'var(--color-bg-tertiary)', wordBreak: 'break-all',
};
const stepLabel: CSSProperties = {
  fontSize: 11, fontWeight: 700, color: 'var(--color-text)', margin: '10px 0 2px',
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
