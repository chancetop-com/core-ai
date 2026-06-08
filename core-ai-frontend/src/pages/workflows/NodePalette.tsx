import { type CSSProperties } from 'react';
import { NODE_TYPES, nodeMeta } from './graph';

// START is unique and seeded into every new workflow, so it is not offered for dragging.
const PALETTE_TYPES = NODE_TYPES.filter((t) => t !== 'START');

export default function NodePalette() {
  return (
    <div style={palette}>
      <div style={heading}>Nodes</div>
      <div style={{ fontSize: 11, color: 'var(--color-text-secondary)', marginBottom: 10 }}>Drag onto the canvas</div>
      {PALETTE_TYPES.map((type) => {
        const meta = nodeMeta(type);
        return (
          <div
            key={type}
            draggable
            onDragStart={(e) => {
              e.dataTransfer.setData('application/workflow-node', type);
              e.dataTransfer.effectAllowed = 'move';
            }}
            style={{ ...item, borderLeft: `3px solid ${meta.color}` }}
          >
            {meta.label}
          </div>
        );
      })}
    </div>
  );
}

const palette: CSSProperties = {
  width: 168, flexShrink: 0, padding: 12, overflowY: 'auto',
  borderRight: '1px solid var(--color-border)', background: 'var(--color-bg-secondary)',
};
const heading: CSSProperties = {
  fontSize: 11, color: 'var(--color-text-secondary)', textTransform: 'uppercase', letterSpacing: 0.4, fontWeight: 600, marginBottom: 4,
};
const item: CSSProperties = {
  padding: '7px 10px', marginBottom: 6, fontSize: 13, fontWeight: 500, color: 'var(--color-text)',
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', cursor: 'grab',
};
