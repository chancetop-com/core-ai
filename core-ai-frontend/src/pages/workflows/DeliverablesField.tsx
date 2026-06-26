import { type CSSProperties } from 'react';
import type { Edge } from '@xyflow/react';
import { Paperclip } from 'lucide-react';
import { flowOrderRanks, type WorkflowRFNode } from './graph';
import { variableGroups } from './variables';

const ARTIFACTS_SELECTOR = /^nodes\.[^.]+\.artifacts$/;

// END deliverables picker: the explicit `artifacts` selector list (config.artifacts) the run hands back as
// downloadable files. Checking any node makes the list AUTHORITATIVE — exactly the checked nodes are delivered
// (Output-referenced files are not auto-added). Leave empty for the default: the END's direct inputs plus files
// referenced in the Output template. Storage is `nodes.<id>.artifacts` selector strings (id-based, rename-safe) —
// the exact shape OutputComposer.composeDeliverables reads server-side.
export default function DeliverablesField({ nodes, edges, selfId, value, onChange }: {
  nodes: WorkflowRFNode[]; edges: Edge[]; selfId: string; value: string[]; onChange: (next: string[]) => void;
}) {
  // Match candidates by selector shape (nodes.<id>.artifacts), not field label — a run-input field literally
  // named "artifacts" exposes `sys.input.artifacts` which the backend's node selector would never accept.
  const ranks = flowOrderRanks(nodes, edges);
  const nodeIndex = new Map(nodes.map((n, i) => [n.id, i]));
  const candidates = variableGroups(nodes, edges, selfId)
    .map((g) => ({ id: g.key, name: g.nodeName, color: g.color, selector: g.fields.find((f) => ARTIFACTS_SELECTOR.test(f.selector))?.selector }))
    .filter((c) => c.selector)
    .sort((a, b) =>
      (ranks.get(a.id) ?? Number.MAX_SAFE_INTEGER) - (ranks.get(b.id) ?? Number.MAX_SAFE_INTEGER)
      || (nodeIndex.get(a.id) ?? Number.MAX_SAFE_INTEGER) - (nodeIndex.get(b.id) ?? Number.MAX_SAFE_INTEGER)) as {
        id: string; name: string; color: string; selector: string
      }[];

  const live = new Set(candidates.map((c) => c.selector));
  // Selectors stored for a node since deleted/disconnected from END: still shown so the user can untick them —
  // otherwise they persist in config invisibly and silently suppress the empty-list default.
  const stale = value.filter((s) => !live.has(s));

  const toggle = (selector: string) =>
    onChange(value.includes(selector) ? value.filter((s) => s !== selector) : [...value, selector]);

  if (candidates.length === 0 && stale.length === 0) {
    return <div style={empty}>No upstream node produces files yet — add an agent that calls submit_artifacts.</div>;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {candidates.map((c) => {
        const checked = value.includes(c.selector);
        return (
          <label key={c.id} style={{ ...row, ...(checked ? rowOn : {}) }}>
            <input type="checkbox" checked={checked} onChange={() => toggle(c.selector)} style={{ margin: 0, cursor: 'pointer' }} />
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: c.color, flexShrink: 0 }} />
            <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{c.name}</span>
            <Paperclip size={12} style={{ opacity: 0.55, flexShrink: 0 }} />
          </label>
        );
      })}
      {stale.map((s) => (
        <label key={s} style={{ ...row, ...rowStale }} title="This node is no longer connected to END — untick to remove">
          <input type="checkbox" checked onChange={() => toggle(s)} style={{ margin: 0, cursor: 'pointer' }} />
          <span style={{ width: 8, height: 8, borderRadius: '50%', background: 'var(--color-text-secondary)', flexShrink: 0 }} />
          <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{staleName(s)} (removed)</span>
        </label>
      ))}
    </div>
  );
}

function staleName(selector: string): string {
  return selector.match(/^nodes\.([^.]+)\.artifacts$/)?.[1] ?? selector;
}

const row: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 8, padding: '6px 9px', fontSize: 13, cursor: 'pointer',
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)',
};
const rowOn: CSSProperties = { borderColor: 'var(--color-primary)', background: 'var(--color-bg-secondary)' };
const rowStale: CSSProperties = { opacity: 0.7, borderStyle: 'dashed', color: 'var(--color-text-secondary)' };
const empty: CSSProperties = { fontSize: 11, color: 'var(--color-text-secondary)', lineHeight: 1.4 };
