import { Handle, Position, type NodeProps } from '@xyflow/react';
import { nodeMeta, nodeSummary, RUN_STATUS_COLOR, type WorkflowRFNode } from './graph';

/** A canvas node card: type label, name, a one-line config summary, and — during a run — a status badge with
 *  elapsed time. Parameterized by node type via the registry; themed via the app's CSS variables. */
export default function WorkflowNode({ data, selected }: NodeProps<WorkflowRFNode>) {
  const meta = nodeMeta(data.nodeType);
  const isStart = data.nodeType === 'START';
  const isSink = data.nodeType === 'END' || data.nodeType === 'ANSWER';
  const summary = nodeSummary(data.nodeType, data.config ?? {});
  const runColor = data.runStatus ? RUN_STATUS_COLOR[data.runStatus] : undefined;
  const borderColor = runColor ?? (selected ? meta.color : 'var(--color-border)');
  return (
    <div
      style={{
        background: 'var(--color-bg-secondary)',
        border: `1px solid ${borderColor}`,
        borderLeft: `4px solid ${meta.color}`,
        borderRadius: 8,
        padding: '8px 12px',
        minWidth: 184,
        boxShadow: runColor ? `0 0 0 2px ${runColor}55` : selected ? `0 0 0 2px ${meta.color}55` : '0 1px 2px rgba(0,0,0,0.10)',
      }}
    >
      {!isStart && <Handle type="target" position={Position.Left} />}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span style={{ fontSize: 11, color: meta.color, fontWeight: 600, textTransform: 'uppercase', letterSpacing: 0.3 }}>
          {meta.label}
        </span>
        <div style={{ flex: 1 }} />
        {runColor ? (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            {data.runMs != null && data.runStatus !== 'RUNNING' && (
              <span style={{ fontSize: 10, color: 'var(--color-text-secondary)' }}>{fmtMs(data.runMs)}</span>
            )}
            <span style={{ width: 7, height: 7, borderRadius: '50%', background: runColor }} title={data.runStatus} />
          </span>
        ) : data.hasIssue ? (
          <span style={{ width: 7, height: 7, borderRadius: '50%', background: '#f59e0b' }} title="Needs configuration" />
        ) : null}
      </div>
      <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--color-text)' }}>{data.name}</div>
      {summary && (
        <div style={{ fontSize: 11, color: 'var(--color-text-secondary)', marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 200 }}>
          {summary}
        </div>
      )}
      {!isSink && <Handle type="source" position={Position.Right} />}
    </div>
  );
}

function fmtMs(ms: number): string {
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
}
