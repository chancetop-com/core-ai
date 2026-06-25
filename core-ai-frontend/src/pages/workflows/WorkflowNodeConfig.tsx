import { useEffect, useMemo, useState, type CSSProperties } from 'react';
import type { Edge } from '@xyflow/react';
import { api } from '../../api/client';
import type { WorkflowNodeData, WorkflowRFNode } from './graph';
import { TemplateField, type InputVar } from './configWidgets';

// A published workflow the WORKFLOW node can call. versionId pins the immutable published version (the snapshot).
export interface SubWorkflowOption { id: string; name: string; versionId?: string; version?: number; visibility?: string; userName?: string; }

interface Props {
  node: WorkflowRFNode;
  nodes: WorkflowRFNode[];
  edges: Edge[];
  currentWorkflowId?: string;
  onChange: (partial: Partial<WorkflowNodeData>) => void;
}

const PAGE_SIZE = 20;
type Tab = 'my' | 'shared';

// WORKFLOW node config: pick a published workflow to call, then map a value for each of its declared START inputs.
// The child's START input fields are read from its graph on selection; each gets a TemplateField so the mapping can
// reference upstream node outputs / the run input ({{ ... }}). Mappings are stored under config.input_mappings.
export default function WorkflowNodeConfig({ node, nodes, edges, currentWorkflowId, onChange }: Props) {
  const config = (node.data.config ?? {}) as Record<string, unknown>;
  const sourceId = String(config.source_workflow_id ?? '');
  const versionId = String(config.version_id ?? '');
  const mappings = useMemo(() => (
    config.input_mappings && typeof config.input_mappings === 'object'
      ? (config.input_mappings as Record<string, string>)
      : {}
  ), [config.input_mappings]);

  const [tab, setTab] = useState<Tab>('my');
  const [query, setQuery] = useState('');
  const [offset, setOffset] = useState(0);
  const [options, setOptions] = useState<SubWorkflowOption[]>([]);
  const [total, setTotal] = useState(0);
  const [loadedOptionsKey, setLoadedOptionsKey] = useState('');
  const [optionsError, setOptionsError] = useState('');
  const [childInputs, setChildInputs] = useState<InputVar[]>([]);
  const [inputsKnown, setInputsKnown] = useState(false);   // false = could not read the child's input schema yet
  const [loadedInputVersionId, setLoadedInputVersionId] = useState('');
  const [inputsError, setInputsError] = useState('');
  const optionsKey = `${tab}:${offset}:${query.trim()}`;

  useEffect(() => {
    let cancelled = false;
    const keyword = query.trim();
    const request = tab === 'my'
      ? api.workflows.list(true, keyword, offset, PAGE_SIZE)
      : api.workflows.explore(keyword, offset, PAGE_SIZE);
    request
      .then((res) => {
        if (cancelled) return;
        const workflows = res.workflows || [];
        setOptionsError('');
        setOptions(workflows
          .filter((w) => w.id !== currentWorkflowId)
          .map((w) => ({
            id: w.id,
            name: w.name,
            versionId: w.published_version_id,
            version: w.published_version,
            visibility: w.visibility,
            userName: w.user_name,
          })));
        setTotal(res.total ?? workflows.length);
        setLoadedOptionsKey(optionsKey);
      })
      .catch((e) => { if (!cancelled) setOptionsError((e as Error).message); });
    return () => { cancelled = true; };
  }, [currentWorkflowId, offset, optionsKey, query, tab]);

  // Load the selected child's declared START inputs whenever the selection changes. The child's graph lives in its
  // pinned published version, not in the editable draft, so the mapping form matches what will run.
  useEffect(() => {
    if (!sourceId || !versionId) {
      return;
    }
    let cancelled = false;
    api.workflows.versionGraph(versionId)
      .then((wf) => {
        if (cancelled) return;
        const parsed = parseStartInputs(wf.graph);   // null = schema unreadable
        setInputsError('');
        setChildInputs(parsed ?? []);
        setInputsKnown(parsed !== null);
        setLoadedInputVersionId(versionId);
      })
      .catch((e) => {
        if (cancelled) return;
        setChildInputs([]);
        setInputsKnown(false);
        setInputsError((e as Error).message);
        setLoadedInputVersionId(versionId);
      });
    return () => { cancelled = true; };
  }, [sourceId, versionId]);

  const switchTab = (next: Tab) => {
    setTab(next);
    setOffset(0);
    setOptionsError('');
  };
  const updateQuery = (value: string) => {
    setQuery(value);
    setOffset(0);
    setOptionsError('');
  };
  const pageTo = (nextOffset: number) => {
    setOffset(Math.max(0, nextOffset));
    setOptionsError('');
  };
  const pick = (wf: SubWorkflowOption) => {
    if (!wf.versionId) return;
    // reset mappings on a new selection — the previous workflow's fields no longer apply
    setChildInputs([]);
    setInputsKnown(false);
    setInputsError('');
    setLoadedInputVersionId('');
    onChange({ config: { ...config, source_workflow_id: wf.id, source_workflow_name: wf.name, source_workflow_version: wf.version, version_id: wf.versionId, input_mappings: {} } });
  };

  const setMapping = (field: string, value: string) => {
    const next = { ...mappings };
    if (value.trim()) {
      next[field] = value;
    } else {
      delete next[field];
    }
    onChange({ config: { ...config, input_mappings: next } });
  };

  const missingRequired = childInputs.filter((iv) => iv.required && !String(mappings[iv.name] ?? '').trim());
  const mappedPayload = useMemo(() => {
    const entries = Object.entries(mappings).filter(([, value]) => String(value).trim());
    return entries.length === 0 ? '{}' : JSON.stringify(Object.fromEntries(entries), null, 2);
  }, [mappings]);
  const loadingOptions = loadedOptionsKey !== optionsKey && !optionsError;
  const inputStateCurrent = loadedInputVersionId === versionId;
  const currentInputsError = inputStateCurrent ? inputsError : '';
  const loadingInputs = !!sourceId && !!versionId && !inputStateCurrent;
  const hasNext = offset + PAGE_SIZE < total;

  return (
    <>
      <label style={label}>Workflow</label>
      <div style={selectedBox}>
        {sourceId ? (
          <>
            <div style={selectedName}>{String(config.source_workflow_name ?? sourceId)}</div>
            <div style={hint}>Locked to {config.source_workflow_version ? `v${config.source_workflow_version}` : versionId || 'unpublished'} · {versionId || 'missing version'}</div>
          </>
        ) : (
          <div style={hint}>No workflow selected.</div>
        )}
      </div>

      <div style={tabRow}>
        <button type="button" onClick={() => switchTab('my')} style={{ ...tabBtn, ...(tab === 'my' ? tabActive : {}) }}>My Workflows</button>
        <button type="button" onClick={() => switchTab('shared')} style={{ ...tabBtn, ...(tab === 'shared' ? tabActive : {}) }}>Shared</button>
      </div>
      <input value={query} onChange={(e) => updateQuery(e.target.value)} placeholder="Search published workflows" style={input} />
      <div style={optionList}>
        {loadingOptions ? (
          <div style={hint}>Loading workflows...</div>
        ) : optionsError ? (
          <div style={{ ...hint, color: '#dc2626' }}>{optionsError}</div>
        ) : options.length === 0 ? (
          <div style={hint}>No workflows found.</div>
        ) : (
          options.map((wf) => {
            const selected = sourceId === wf.id && versionId === wf.versionId;
            const callable = !!wf.versionId && (wf.visibility === 'PUBLIC' || tab === 'shared');
            return (
              <button
                type="button"
                key={wf.id}
                disabled={!callable}
                onClick={() => pick(wf)}
                style={{ ...optionRow, ...(selected ? optionSelected : {}), ...(!callable ? optionDisabled : {}) }}
              >
                <span style={optionName}>{wf.name}</span>
                <span style={optionMeta}>
                  {wf.userName ? `${wf.userName} · ` : ''}{callable ? `Public v${wf.version ?? '?'}` : wf.versionId ? `Private · last v${wf.version ?? '?'}` : 'draft only'}
                </span>
              </button>
            );
          })
        )}
      </div>
      <div style={pagerRow}>
        <button type="button" disabled={offset === 0 || loadingOptions} onClick={() => pageTo(offset - PAGE_SIZE)} style={pagerBtn}>Prev</button>
        <span style={hint}>{total ? `${offset + 1}-${Math.min(offset + PAGE_SIZE, total)} of ${total}` : '0'}</span>
        <button type="button" disabled={!hasNext || loadingOptions} onClick={() => pageTo(offset + PAGE_SIZE)} style={pagerBtn}>Next</button>
      </div>

      {sourceId && (
        <>
          <label style={label}>Inputs</label>
          {loadingInputs ? (
            <div style={hint}>Loading inputs…</div>
          ) : currentInputsError ? (
            <div style={{ ...hint, color: '#dc2626' }}>{currentInputsError}</div>
          ) : !inputsKnown ? (
            <div style={hint}>Couldn't read this workflow's inputs (it may have no published graph). The run input is passed through.</div>
          ) : childInputs.length === 0 ? (
            <div style={hint}>This workflow declares no inputs; the run input is passed through.</div>
          ) : (
            <>
              {missingRequired.length > 0 && <div style={warn}>Required: {missingRequired.map((iv) => iv.name).join(', ')}</div>}
              {childInputs.map((iv) => (
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
              ))}
              <div style={previewBox}>
                <div style={previewTitle}>Mapped payload</div>
                <pre style={previewPre}>{mappedPayload}</pre>
              </div>
            </>
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
const selectedBox: CSSProperties = {
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', padding: '8px 10px',
};
const selectedName: CSSProperties = { fontSize: 13, fontWeight: 600, color: 'var(--color-text)' };
const tabRow: CSSProperties = { display: 'flex', gap: 6, margin: '10px 0 8px' };
const tabBtn: CSSProperties = {
  flex: 1, padding: '6px 8px', border: '1px solid var(--color-border)', borderRadius: 7,
  background: 'var(--color-bg)', color: 'var(--color-text-secondary)', cursor: 'pointer', fontSize: 12, fontWeight: 600,
};
const tabActive: CSSProperties = { color: 'var(--color-primary)', borderColor: 'var(--color-primary)' };
const input: CSSProperties = {
  width: '100%', boxSizing: 'border-box', padding: '7px 10px', fontSize: 13,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', outline: 'none',
};
const optionList: CSSProperties = { display: 'flex', flexDirection: 'column', gap: 6, marginTop: 8, maxHeight: 210, overflowY: 'auto' };
const optionRow: CSSProperties = {
  display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 2, padding: '7px 9px',
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', cursor: 'pointer',
};
const optionSelected: CSSProperties = { borderColor: 'var(--color-primary)', background: 'rgba(37,99,235,0.08)' };
const optionDisabled: CSSProperties = { opacity: 0.55, cursor: 'not-allowed' };
const optionName: CSSProperties = { fontSize: 13, fontWeight: 600, maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' };
const optionMeta: CSSProperties = { fontSize: 11, color: 'var(--color-text-secondary)' };
const pagerRow: CSSProperties = { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginTop: 8 };
const pagerBtn: CSSProperties = {
  padding: '5px 8px', border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', cursor: 'pointer',
};
const warn: CSSProperties = {
  marginBottom: 8, padding: '7px 9px', border: '1px solid rgba(245,158,11,0.35)', borderRadius: 7,
  background: 'rgba(245,158,11,0.1)', color: '#b45309', fontSize: 12,
};
const previewBox: CSSProperties = { border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', padding: 8, marginTop: 8 };
const previewTitle: CSSProperties = { fontSize: 11, fontWeight: 600, color: 'var(--color-text-secondary)', marginBottom: 4 };
const previewPre: CSSProperties = { margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 11, color: 'var(--color-text)', background: 'transparent' };
