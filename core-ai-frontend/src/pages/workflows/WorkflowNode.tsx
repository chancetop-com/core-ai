import { Handle, Position, type NodeProps } from '@xyflow/react';
import { nodeMeta, RUN_STATUS_COLOR, type WorkflowRFNode } from './graph';

/** One generic canvas node, parameterized by node type via the registry (accent color/label). Themed via the
 *  app's CSS variables so it follows light/dark mode. During a run overlay, data.runStatus tints the border. */
export default function WorkflowNode({ data, selected }: NodeProps<WorkflowRFNode>) {
  const meta = nodeMeta(data.nodeType);
  const isStart = data.nodeType === 'START';
  const isSink = data.nodeType === 'END' || data.nodeType === 'ANSWER';
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
        minWidth: 160,
        boxShadow: runColor ? `0 0 0 2px ${runColor}55` : selected ? `0 0 0 2px ${meta.color}55` : '0 1px 2px rgba(0,0,0,0.10)',
      }}
    >
      {!isStart && <Handle type="target" position={Position.Left} />}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <span style={{ fontSize: 11, color: meta.color, fontWeight: 600, textTransform: 'uppercase', letterSpacing: 0.3 }}>
          {meta.label}
        </span>
        {runColor && <span style={{ width: 7, height: 7, borderRadius: '50%', background: runColor }} title={data.runStatus} />}
      </div>
      <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--color-text)' }}>{data.name}</div>
      {!isSink && <Handle type="source" position={Position.Right} />}
    </div>
  );
}
