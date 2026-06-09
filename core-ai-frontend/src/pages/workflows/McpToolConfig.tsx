import { useEffect, useMemo, useState, type CSSProperties } from 'react';
import { api, type ToolRegistryView, type McpToolInfo } from '../../api/client';
import VariableMapEditor, { parseMcpSchema } from './VariableMapEditor';
import type { WorkflowRFNode } from './graph';

interface Props {
  config: Record<string, unknown>;
  onConfig: (partial: Record<string, unknown>) => void;
  nodes: WorkflowRFNode[];
  selfId: string;
}

/** MCP tool node config: pick an MCP server, then one of its tools, then the JSON arguments (template-aware). */
export default function McpToolConfig({ config, onConfig, nodes, selfId }: Props) {
  const [servers, setServers] = useState<ToolRegistryView[]>([]);
  const [tools, setTools] = useState<McpToolInfo[]>([]);
  const [loadingTools, setLoadingTools] = useState(false);
  const serverId = String(config.server_id ?? '');
  const toolName = String(config.tool_name ?? '');
  // Declared params of the selected tool, parsed from its JSON schema — drives auto-filled argument rows.
  const params = useMemo(() => parseMcpSchema(tools.find((t) => t.name === toolName)?.input_schema), [tools, toolName]);

  useEffect(() => {
    api.tools.list().then((res) => setServers((res.tools || []).filter((t) => t.type === 'MCP'))).catch(() => { /* optional */ });
  }, []);

  useEffect(() => {
    if (!serverId) { setTools([]); return; }
    setLoadingTools(true);
    api.tools.listMcpServerTools(serverId)
      .then((res) => setTools(res.tools || []))
      .catch(() => setTools([]))
      .finally(() => setLoadingTools(false));
  }, [serverId]);

  const selectServer = (id: string) => {
    const name = servers.find((s) => s.id === id)?.name;
    onConfig({ server_id: id, server_name: name, tool_name: undefined });   // reset tool when server changes
  };

  return (
    <>
      <label style={label}>MCP server</label>
      <select value={serverId} onChange={(e) => selectServer(e.target.value)} style={input}>
        <option value="">— select an MCP server —</option>
        {servers.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
      </select>

      <label style={label}>Tool</label>
      <select value={toolName} onChange={(e) => onConfig({ tool_name: e.target.value })} style={input} disabled={!serverId || loadingTools}>
        <option value="">{loadingTools ? 'Loading…' : '— select a tool —'}</option>
        {tools.map((t) => <option key={t.name} value={t.name}>{t.name}</option>)}
      </select>

      <label style={label}>Arguments</label>
      <VariableMapEditor value={String(config.arguments ?? '')} onChange={(v) => onConfig({ arguments: v })} nodes={nodes} selfId={selfId} params={params} />
    </>
  );
}

const label: CSSProperties = { display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', margin: '12px 0 4px' };
const input: CSSProperties = {
  width: '100%', boxSizing: 'border-box', padding: '7px 10px', fontSize: 13,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', outline: 'none',
};
