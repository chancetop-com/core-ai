import { useEffect, useState, type CSSProperties } from 'react';
import type { Edge } from '@xyflow/react';
import { api } from '../../api/client';
import type { WorkflowNodeData, WorkflowRFNode } from './graph';
import { TemplateField, type InputVar } from './configWidgets';

// A published workflow the WORKFLOW node can call. versionId pins the immutable published version (the snapshot).
export interface SubWorkflowOption { id: string; name: string; versionId?: string; version?: number; }

interface Props {
  node: WorkflowRFNode;
  nodes: WorkflowRFNode[];
  edges: Edge[];
  workflows: SubWorkflowOption[];   // selectable published workflows (already excludes the current one)
  onChange: (partial: Partial<WorkflowNodeData>) => void;
}

// WORKFLOW node config: pick a published workflow to call, then map a value for each of its declared START inputs.
// The child's START input fields are read from its graph on selection; each gets a TemplateField so the mapping can
// reference upstream node outputs / the run input ({{ ... }}). Mappings are stored under config.input_mappings.
export default function WorkflowNodeConfig({ node, nodes, edges, workflows, onChange }: Props) {
  const config = (node.data.config ?? {}) as Record<string, unknown>;
  const sourceId = String(config.source_workflow_id ?? '');
  const mappings = config.input_mappings && typeof config.input_mappings === 'object'
    ? (config.input_mappings as Record<string, string>)
    : {};

  const [childInputs, setChildInputs] = useState<InputVar[]>([]);
  const [inputsKnown, setInputsKnown] = useState(false);   // false = could not read the child's input schema yet
  const [loadingInputs, setLoadingInputs] = useState(false);
  const [inputsError, setInputsError] = useState('');

  // Load the selected child's declared START inputs whenever the selection changes. The child's graph lives in its
  // draft_graph (published workflows expose the published graph there for read-only viewing).
  useEffect(() => {
    if (!sourceId) {
      setChildInputs([]);
      setInputsKnown(false);
      return;
    }
    let cancelled = false;
    setLoadingInputs(true);
    setInputsError('');
    api.workflows.get(sourceId)
      .then((wf) => {
        if (cancelled) return;
        const parsed = parseStartInputs(wf.draft_graph);   // null = schema unreadable
        setChildInputs(parsed ?? []);
        setInputsKnown(parsed !== null);
      })
      .catch((e) => { if (!cancelled) setInputsError((e as Error).message); })
      .finally(() => { if (!cancelled) setLoadingInputs(false); });
    return () => { cancelled = true; };
  }, [sourceId]);

  const pick = (id: string) => {
    const wf = workflows.find((w) => w.id === id);
    // reset mappings on a new selection — the previous workflow's fields no longer apply
    onChange({ config: { ...config, source_workflow_id: id, source_workflow_name: wf?.name, version_id: wf?.versionId ?? '', input_mappings: {} } });
  };

  const setMapping = (field: string, value: string) => {
    onChange({ config: { ...config, input_mappings: { ...mappings, [field]: value } } });
  };

  return (
    <>
      <label style={label}>Workflow</label>
      <select value={sourceId} onChange={(e) => pick(e.target.value)} style={input}>
        <option value="">— select a published workflow —</option>
        {workflows.map((w) => (
          <option key={w.id} value={w.id}>{w.name}{w.version ? ` · v${w.version}` : ''}</option>
        ))}
      </select>

      {sourceId && (
        <>
          <label style={label}>Inputs</label>
          {loadingInputs ? (
            <div style={hint}>Loading inputs…</div>
          ) : inputsError ? (
            <div style={{ ...hint, color: '#dc2626' }}>{inputsError}</div>
          ) : !inputsKnown ? (
            <div style={hint}>Couldn't read this workflow's inputs (it may have no published graph). The run input is passed through.</div>
          ) : childInputs.length === 0 ? (
            <div style={hint}>This workflow declares no inputs; the run input is passed through.</div>
          ) : (
            childInputs.map((iv) => (
              <div key={iv.name} style={{ marginBottom: 10 }}>
                <div style={fieldLabel}>
                  {iv.label || iv.name}{iv.required ? ' *' : ''} <span style={{ opacity: 0.6 }}>({iv.type})</span>
                </div>
                <TemplateField
                  value={mappings[iv.name] ?? ''}
                  onChange={(v) => setMapping(iv.name, v)}
                  nodes={nodes}
                  edges={edges}
                  selfId={node.id}
                  placeholder={`map a value for ${iv.name}`}
                />
              </div>
            ))
          )}
          <div style={hint}>Each field is sent to the sub-workflow's Start. Empty = the field is omitted.</div>
        </>
      )}
    </>
  );
}

// Pull the START node's declared input fields out of a child workflow's graph JSON. Returns null when the schema
// is unreadable (missing/invalid graph, or no START found) so the UI can distinguish "couldn't read" from the
// legitimate empty array "declares no inputs". Either way the node still works as a plain passthrough at runtime.
function parseStartInputs(graphJson?: string): InputVar[] | null {
  if (!graphJson) return null;
  try {
    const graph = JSON.parse(graphJson) as { nodes?: { type?: string; config?: { inputs?: InputVar[] } }[] };
    const start = (graph.nodes || []).find((n) => n.type === 'START');
    if (!start) return null;
    return Array.isArray(start.config?.inputs) ? (start.config!.inputs as InputVar[]) : [];
  } catch {
    return null;
  }
}

const label: CSSProperties = {
  display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', margin: '12px 0 4px',
};
const fieldLabel: CSSProperties = { fontSize: 12, fontWeight: 600, color: 'var(--color-text)', marginBottom: 4 };
const hint: CSSProperties = { fontSize: 11, color: 'var(--color-text-secondary)', marginTop: 4, lineHeight: 1.4 };
const input: CSSProperties = {
  width: '100%', boxSizing: 'border-box', padding: '7px 10px', fontSize: 13,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', outline: 'none',
};
