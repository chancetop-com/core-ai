import { Handle, Position, type NodeProps } from '@xyflow/react';
import { nodeMeta, type WorkflowRFNode } from './graph';

/** One generic canvas node, parameterized by node type via the registry (accent color/label). Themed via the
 *  app's CSS variables so it follows light/dark mode. */
export default function WorkflowNode({ data, selected }: NodeProps<WorkflowRFNode>) {
  const meta = nodeMeta(data.nodeType);
  const isStart = data.nodeType === 'START';
  const isSink = data.nodeType === 'END' || data.nodeType === 'ANSWER';
  return (
    <div
      style={{
        background: 'var(--color-bg-secondary)',
        border: `1px solid ${selected ? meta.color : 'var(--color-border)'}`,
        borderLeft: `4px solid ${meta.color}`,
        borderRadius: 8,
        padding: '8px 12px',
        minWidth: 160,
        boxShadow: selected ? `0 0 0 2px ${meta.color}55` : '0 1px 2px rgba(0,0,0,0.10)',
      }}
    >
      {!isStart && <Handle type="target" position={Position.Left} />}
      <div style={{ fontSize: 11, color: meta.color, fontWeight: 600, textTransform: 'uppercase', letterSpacing: 0.3 }}>
        {meta.label}
      </div>
      <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--color-text)' }}>{data.name}</div>
      {!isSink && <Handle type="source" position={Position.Right} />}
    </div>
  );
}
