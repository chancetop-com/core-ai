import { type CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import { ExternalLink, X } from 'lucide-react';
import { nodeMeta, RUN_STATUS_COLOR, type WorkflowRFNode } from './graph';
import type { WorkflowNodeRunView } from '../../api/client';

interface Props {
  node: WorkflowRFNode;
  nodeRun?: WorkflowNodeRunView;
  onClose: () => void;
}

/** Read-only right panel shown during a run overlay: the selected node's status, input, output and error. */
export default function NodeRunDetail({ node, nodeRun, onClose }: Props) {
  const meta = nodeMeta(node.data.nodeType);
  const status = nodeRun?.status;
  const statusColor = status ? RUN_STATUS_COLOR[status] : 'var(--color-text-secondary)';
  return (
    <div style={panel}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}>
        <span style={{ fontSize: 11, color: meta.color, fontWeight: 700, textTransform: 'uppercase' }}>{meta.label}</span>
        <div style={{ flex: 1 }} />
        <button onClick={onClose} style={iconBtn} title="Close"><X size={15} /></button>
      </div>

      <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--color-text)', marginBottom: 10 }}>{node.data.name}</div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 14 }}>
        <span style={{ width: 8, height: 8, borderRadius: '50%', background: statusColor }} />
        <span style={{ fontSize: 13, color: 'var(--color-text)' }}>{status ?? 'not started'}</span>
      </div>

      {nodeRun?.error && <Section title="Error" body={nodeRun.error} color="#dc2626" />}
      <Section title="Input" body={nodeRun?.input} />
      <Section title="Output" body={nodeRun?.output} />
      {nodeRun?.child_run_id && (
        <div style={{ marginBottom: 12 }}>
          <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', marginBottom: 4 }}>Child run</div>
          <Link to={`/runs/${nodeRun.child_run_id}`} style={childLink}>
            <ExternalLink size={13} /> {nodeRun.child_run_id}
          </Link>
        </div>
      )}
    </div>
  );
}

function Section({ title, body, color }: { title: string; body?: string; color?: string }) {
  return (
    <div style={{ marginBottom: 12 }}>
      <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', marginBottom: 4 }}>{title}</div>
      <pre style={{ ...pre, color: color ?? 'var(--color-text)' }}>{body ? format(body) : '—'}</pre>
    </div>
  );
}

function format(body: string): string {
  try {
    return JSON.stringify(JSON.parse(body), null, 2);
  } catch {
    return body;
  }
}

const panel: CSSProperties = {
  width: 320, flexShrink: 0, padding: 16, overflowY: 'auto',
  borderLeft: '1px solid var(--color-border)', background: 'var(--color-bg-secondary)',
};
const pre: CSSProperties = {
  margin: 0, padding: '8px 10px', fontFamily: 'monospace', fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', maxHeight: 240, overflowY: 'auto',
};
const iconBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 28, height: 28,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', cursor: 'pointer',
};
const childLink: CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, fontFamily: 'monospace',
  color: 'var(--color-primary)', textDecoration: 'none', wordBreak: 'break-all',
};
