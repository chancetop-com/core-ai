import { useEffect, useState, type CSSProperties } from 'react';
import { Trash2, X } from 'lucide-react';
import { nodeMeta, type WorkflowNodeData, type WorkflowRFNode } from './graph';

interface AgentOption { id: string; name: string; }

interface Props {
  node: WorkflowRFNode;
  agents: AgentOption[];
  onChange: (partial: Partial<WorkflowNodeData>) => void;
  onDelete: () => void;
  onClose: () => void;
}

export default function NodeConfigPanel({ node, agents, onChange, onDelete, onClose }: Props) {
  const meta = nodeMeta(node.data.nodeType);
  const isAgentNode = node.data.nodeType === 'AGENT' || node.data.nodeType === 'LLM';
  const config = (node.data.config ?? {}) as Record<string, unknown>;
  const [configText, setConfigText] = useState('{}');
  const [configError, setConfigError] = useState('');

  // reset the editable config text only when the SELECTED NODE changes — not on node.data.config, which this
  // panel itself mutates on every keystroke (that would re-serialize and fight the user's cursor).
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    setConfigText(JSON.stringify(node.data.config ?? {}, null, 2));
    setConfigError('');
  }, [node.id]);

  const commitConfig = (text: string) => {
    setConfigText(text);
    try {
      const parsed = JSON.parse(text || '{}');
      setConfigError('');
      onChange({ config: parsed });
    } catch {
      setConfigError('Invalid JSON');
    }
  };

  const setAgentId = (agentId: string) => {
    onChange({ config: { ...config, agent_id: agentId } });
  };

  return (
    <div style={panel}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}>
        <span style={{ fontSize: 11, color: meta.color, fontWeight: 700, textTransform: 'uppercase' }}>{meta.label}</span>
        <div style={{ flex: 1 }} />
        <button onClick={onClose} style={iconBtn} title="Close"><X size={15} /></button>
      </div>

      <label style={label}>Name</label>
      <input value={node.data.name} onChange={(e) => onChange({ name: e.target.value })} style={input} />

      {isAgentNode && (
        <>
          <label style={label}>Agent</label>
          <select value={String(config.agent_id ?? '')} onChange={(e) => setAgentId(e.target.value)} style={input}>
            <option value="">— select a published agent —</option>
            {agents.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </>
      )}

      <label style={label}>Config (JSON)</label>
      <textarea
        value={configText}
        onChange={(e) => commitConfig(e.target.value)}
        spellCheck={false}
        style={{ ...input, fontFamily: 'monospace', fontSize: 12, minHeight: 160, resize: 'vertical' }}
      />
      {configError && <div style={{ color: '#dc2626', fontSize: 12, marginTop: 4 }}>{configError}</div>}

      <button onClick={onDelete} style={deleteBtn}><Trash2 size={15} /> Delete node</button>
    </div>
  );
}

const panel: CSSProperties = {
  width: 300, flexShrink: 0, padding: 16, overflowY: 'auto',
  borderLeft: '1px solid var(--color-border)', background: 'var(--color-bg-secondary)',
};
const label: CSSProperties = {
  display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', margin: '12px 0 4px',
};
const input: CSSProperties = {
  width: '100%', boxSizing: 'border-box', padding: '7px 10px', fontSize: 13,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', outline: 'none',
};
const iconBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', justifyContent: 'center', width: 28, height: 28,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', cursor: 'pointer',
};
const deleteBtn: CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 6, marginTop: 18, padding: '7px 12px', width: '100%', justifyContent: 'center',
  border: '1px solid #fecaca', borderRadius: 8, background: 'transparent', color: '#dc2626', cursor: 'pointer', fontWeight: 500,
};
