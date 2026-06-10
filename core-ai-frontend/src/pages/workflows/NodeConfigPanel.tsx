import { useState, type CSSProperties } from 'react';
import type { Edge } from '@xyflow/react';
import { AlertTriangle, Trash2, X } from 'lucide-react';
import { nodeMeta, type WorkflowNodeData, type WorkflowRFNode } from './graph';
import { nodeIssues } from './validation';
import IfElseConfig from './IfElseConfig';
import HumanInputConfig from './HumanInputConfig';
import CodeConfig from './CodeConfig';
import StartConfig from './StartConfig';
import McpToolConfig from './McpToolConfig';
import ApiToolConfig from './ApiToolConfig';
import HttpConfig from './HttpConfig';
import RetryFields from './RetryFields';
import VariableChipField from './VariableChipField';

interface AgentOption { id: string; name: string; }

interface Props {
  node: WorkflowRFNode;
  nodes: WorkflowRFNode[];
  edges: Edge[];
  agents: AgentOption[];
  onChange: (partial: Partial<WorkflowNodeData>) => void;
  onDelete: () => void;
  onClose: () => void;
}

export default function NodeConfigPanel({ node, nodes, edges, agents, onChange, onDelete, onClose }: Props) {
  const meta = nodeMeta(node.data.nodeType);
  const isAgentNode = node.data.nodeType === 'AGENT' || node.data.nodeType === 'LLM';
  const isMcpTool = node.data.nodeType === 'MCP_TOOL';
  const isApiTool = node.data.nodeType === 'API_TOOL';
  const isHttp = node.data.nodeType === 'HTTP';
  const isIfElse = node.data.nodeType === 'IF_ELSE';
  const isCode = node.data.nodeType === 'CODE';
  const isEnd = node.data.nodeType === 'END';
  const isAggregator = node.data.nodeType === 'AGGREGATOR';
  const isStart = node.data.nodeType === 'START';
  const isHumanInput = node.data.nodeType === 'HUMAN_INPUT';
  const isTemplate = node.data.nodeType === 'TEMPLATE';
  const config = (node.data.config ?? {}) as Record<string, unknown>;
  const onConfig = (partial: Record<string, unknown>) => onChange({ config: { ...config, ...partial } });
  const hasRetry = isAgentNode || isMcpTool || isApiTool || isHttp;
  // The panel is keyed by node.id at the call site, so it remounts per node and these initialize once per node —
  // no effect, no stale-render window, and no feedback loop with the config this panel itself mutates.
  const [configText, setConfigText] = useState(() => JSON.stringify(node.data.config ?? {}, null, 2));
  const [configError, setConfigError] = useState('');

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
    const agentName = agents.find((a) => a.id === agentId)?.name;
    onChange({ config: { ...config, agent_id: agentId, agent_name: agentName } });
  };

  const issues = nodeIssues(node, nodes, edges);

  return (
    <div style={panel}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <span style={{ fontSize: 11, color: meta.color, fontWeight: 700, textTransform: 'uppercase' }}>{meta.label}</span>
        <div style={{ flex: 1 }} />
        <button onClick={onClose} style={iconBtn} title="Close"><X size={15} /></button>
      </div>

      {meta.description && <div style={desc}>{meta.description}</div>}
      {meta.outputHint && <div style={outHint}>↳ Outputs: {meta.outputHint}</div>}
      {issues.length > 0 && (
        <div style={issueBox}>
          <AlertTriangle size={13} style={{ flexShrink: 0, marginTop: 1 }} />
          <div>{issues.map((m, i) => <div key={i}>{m}</div>)}</div>
        </div>
      )}

      <label style={label}>Name</label>
      <input value={node.data.name} onChange={(e) => onChange({ name: e.target.value })} style={input} />

      {isAgentNode ? (
        <>
          <label style={label}>Agent</label>
          <select value={String(config.agent_id ?? '')} onChange={(e) => setAgentId(e.target.value)} style={input}>
            <option value="">— select a published agent —</option>
            {agents.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
          <label style={label}>Input</label>
          <VariableChipField
            value={String(config.input ?? '')}
            onChange={(v) => onConfig({ input: v })}
            nodes={nodes}
            selfId={node.id}
            placeholder="defaults to the run input"
            multiline
          />
        </>
      ) : isMcpTool ? (
        <McpToolConfig config={config} onConfig={onConfig} nodes={nodes} selfId={node.id} />
      ) : isApiTool ? (
        <ApiToolConfig config={config} onConfig={onConfig} nodes={nodes} selfId={node.id} />
      ) : isHttp ? (
        <HttpConfig config={config} onConfig={onConfig} nodes={nodes} selfId={node.id} />
      ) : isIfElse ? (
        <>
          <label style={label}>Branches</label>
          <IfElseConfig node={node} nodes={nodes} edges={edges} onChange={onChange} />
        </>
      ) : isCode ? (
        <CodeConfig node={node} nodes={nodes} onChange={onChange} />
      ) : isHumanInput ? (
        <HumanInputConfig node={node} nodes={nodes} edges={edges} onChange={onChange} />
      ) : isTemplate ? (
        <>
          <label style={label}>Text</label>
          <VariableChipField
            value={String(config.template ?? '')}
            onChange={(v) => onConfig({ template: v })}
            nodes={nodes}
            selfId={node.id}
            placeholder="fixed text, or mix in {{ variables }}"
            multiline
          />
          <div style={hint}>Outputs this text (variables rendered). Empty branches render empty.</div>
        </>
      ) : isStart ? (
        <StartConfig node={node} onChange={onChange} />
      ) : isEnd ? (
        <>
          <label style={label}>Output</label>
          <VariableChipField
            value={String(config.output ?? '')}
            onChange={(v) => onConfig({ output: v })}
            nodes={nodes}
            selfId={node.id}
            placeholder="leave empty to auto-pass the branch / merge parallel inputs"
            multiline
          />
          <div style={hint}>Empty = pass through the single completed input, or merge multiple parallel inputs into an object.</div>
        </>
      ) : isAggregator ? (
        <>
          <label style={label}>Output</label>
          <VariableChipField
            value={String(config.output ?? '')}
            onChange={(v) => onConfig({ output: v })}
            nodes={nodes}
            selfId={node.id}
            placeholder="combine inputs, e.g. A: {{a}} B: {{b}}"
            multiline
          />
          <div style={hint}>Combine its inputs into one value for downstream. Empty = auto-merge; a template is preferred for parallel inputs.</div>
        </>
      ) : (
        <>
          <label style={label}>Config (JSON)</label>
          <textarea
            value={configText}
            onChange={(e) => commitConfig(e.target.value)}
            spellCheck={false}
            style={{ ...input, fontFamily: 'monospace', fontSize: 12, minHeight: 160, resize: 'vertical' }}
          />
          {configError && <div style={{ color: '#dc2626', fontSize: 12, marginTop: 4 }}>{configError}</div>}
        </>
      )}

      {hasRetry && <RetryFields config={config} onConfig={onConfig} />}

      {!isStart && !isEnd && <button onClick={onDelete} style={deleteBtn}><Trash2 size={15} /> Delete node</button>}
    </div>
  );
}

const panel: CSSProperties = {
  width: '100%', height: '100%', boxSizing: 'border-box', padding: 16, overflowY: 'auto',
  background: 'var(--color-bg-secondary)',
};
const label: CSSProperties = {
  display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', margin: '12px 0 4px',
};
const hint: CSSProperties = { fontSize: 11, color: 'var(--color-text-secondary)', marginTop: 4, lineHeight: 1.4 };
const desc: CSSProperties = { fontSize: 12, color: 'var(--color-text-secondary)', lineHeight: 1.5 };
const outHint: CSSProperties = { fontSize: 11, color: 'var(--color-text-secondary)', marginTop: 4, opacity: 0.85 };
const issueBox: CSSProperties = {
  display: 'flex', gap: 6, marginTop: 10, padding: '8px 10px', fontSize: 12, lineHeight: 1.5, borderRadius: 7,
  color: '#b45309', background: 'rgba(245,158,11,0.12)', border: '1px solid rgba(245,158,11,0.35)',
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
