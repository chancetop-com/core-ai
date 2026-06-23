import { useEffect, useState, type CSSProperties } from 'react';
import { api, type ApiAppView, type ApiServiceView } from '../../api/client';
import VariableMapEditor from './VariableMapEditor';
import type { WorkflowRFNode } from './graph';
import type { Edge } from '@xyflow/react';

interface Props {
  config: Record<string, unknown>;
  onConfig: (partial: Record<string, unknown>) => void;
  nodes: WorkflowRFNode[];
  edges: Edge[];
  selfId: string;
}

interface Op { service: string; operation: string; method: string; }

// Mirror the backend's ToolCall naming: app(- -> _)_service_operation (InternalApiToolLoader.functionCallName).
function toolCallName(appName: string, service: string, operation: string): string {
  return `${appName.replace(/-/g, '_')}_${service}_${operation}`;
}

/** API tool node config: pick a Service-API app, then one of its operations, then the JSON arguments. */
export default function ApiToolConfig({ config, onConfig, nodes, edges, selfId }: Props) {
  const [apps, setApps] = useState<ApiAppView[]>([]);
  const [ops, setOps] = useState<Op[]>([]);
  const [loadingOps, setLoadingOps] = useState(false);
  const appName = String(config.app_name ?? '');
  const toolName = String(config.tool_name ?? '');

  useEffect(() => {
    api.tools.listApiApps().then((res) => setApps(res.apps || [])).catch(() => { /* optional */ });
  }, []);

  useEffect(() => {
    if (!appName) { setOps([]); return; }
    setLoadingOps(true);
    api.tools.listApiAppServices(appName)
      .then((res) => setOps(flatten(res.services || [])))
      .catch(() => setOps([]))
      .finally(() => setLoadingOps(false));
  }, [appName]);

  // changing app or operation resets arguments — a different operation has different params, so stale args must not leak
  const selectApp = (name: string) => onConfig({ app_name: name, tool_name: undefined, service_name: undefined, operation_name: undefined, arguments: undefined });

  const selectOp = (value: string) => {
    const op = ops.find((o) => toolCallName(appName, o.service, o.operation) === value);
    if (!op) { onConfig({ tool_name: undefined, service_name: undefined, operation_name: undefined, arguments: undefined }); return; }
    onConfig({ tool_name: value, service_name: op.service, operation_name: op.operation, arguments: undefined });
  };

  return (
    <>
      <label style={label}>API app</label>
      <select value={appName} onChange={(e) => selectApp(e.target.value)} style={input}>
        <option value="">— select an API app —</option>
        {apps.map((a) => <option key={a.name} value={a.name}>{a.name}</option>)}
      </select>

      <label style={label}>Operation</label>
      <select value={toolName} onChange={(e) => selectOp(e.target.value)} style={input} disabled={!appName || loadingOps}>
        <option value="">{loadingOps ? 'Loading…' : '— select an operation —'}</option>
        {ops.map((o) => {
          const v = toolCallName(appName, o.service, o.operation);
          return <option key={v} value={v}>{o.service} · {o.operation} ({o.method})</option>;
        })}
      </select>

      <label style={label}>Arguments</label>
      {/* key forces a fresh editor per operation so rows reseed cleanly when the operation changes */}
      <VariableMapEditor key={appName + ':' + toolName} value={String(config.arguments ?? '')} onChange={(v) => onConfig({ arguments: v })} nodes={nodes} edges={edges} selfId={selfId} />
    </>
  );
}

function flatten(services: ApiServiceView[]): Op[] {
  return services.flatMap((s) => (s.operations || []).map((op) => ({ service: s.name, operation: op.name, method: op.method })));
}

const label: CSSProperties = { display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', margin: '12px 0 4px' };
const input: CSSProperties = {
  width: '100%', boxSizing: 'border-box', padding: '7px 10px', fontSize: 13,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', outline: 'none',
};
