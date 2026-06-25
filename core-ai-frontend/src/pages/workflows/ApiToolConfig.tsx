import { useEffect, useMemo, useState, type CSSProperties } from 'react';
import { api, type ApiAppView, type ApiOperationView, type ApiServiceView } from '../../api/client';
import VariableMapEditor from './VariableMapEditor';
import { parseToolSchema } from './toolSchema';
import type { WorkflowRFNode } from './graph';
import type { Edge } from '@xyflow/react';

interface Props {
  config: Record<string, unknown>;
  onConfig: (partial: Record<string, unknown>) => void;
  nodes: WorkflowRFNode[];
  edges: Edge[];
  selfId: string;
}

interface Op extends ApiOperationView { service: string; operation: string; }

// Mirror the backend's ToolCall naming: app(- -> _)_service_operation (InternalApiToolLoader.functionCallName).
function toolCallName(appName: string, service: string, operation: string): string {
  return `${appName.replace(/-/g, '_')}_${service}_${operation}`;
}

/** API tool node config: pick a Service-API app, then one of its operations, then the JSON arguments. */
export default function ApiToolConfig({ config, onConfig, nodes, edges, selfId }: Props) {
  const [apps, setApps] = useState<ApiAppView[]>([]);
  const [opsState, setOpsState] = useState<{ appName: string; ops: Op[] }>({ appName: '', ops: [] });
  const appName = String(config.app_name ?? '');
  const toolName = String(config.tool_name ?? '');
  const ops = useMemo(() => (opsState.appName === appName ? opsState.ops : []), [appName, opsState.appName, opsState.ops]);
  const loadingOps = !!appName && opsState.appName !== appName;
  const selectedOp = useMemo(() => ops.find((o) => opToolName(appName, o) === toolName), [appName, ops, toolName]);
  const inputSchema = str(config.input_schema) || selectedOp?.input_schema;
  const params = useMemo(() => parseToolSchema(inputSchema), [inputSchema]);

  useEffect(() => {
    api.tools.listApiApps().then((res) => setApps(res.apps || [])).catch(() => { /* optional */ });
  }, []);

  useEffect(() => {
    if (!appName) return;
    let cancelled = false;
    api.tools.listApiAppServices(appName)
      .then((res) => {
        if (!cancelled) setOpsState({ appName, ops: flatten(res.services || []) });
      })
      .catch(() => {
        if (!cancelled) setOpsState({ appName, ops: [] });
      });
    return () => { cancelled = true; };
  }, [appName]);

  useEffect(() => {
    if (!selectedOp || !toolName) return;
    const patch: Record<string, unknown> = {};
    setMetadataIfChanged(patch, config, 'service_name', selectedOp.service);
    setMetadataIfChanged(patch, config, 'operation_name', selectedOp.operation);
    setMetadataIfChanged(patch, config, 'request_type', selectedOp.request_type);
    setMetadataIfChanged(patch, config, 'response_type', selectedOp.response_type);
    setMetadataIfChanged(patch, config, 'input_schema', selectedOp.input_schema);
    setMetadataIfChanged(patch, config, 'output_schema', selectedOp.output_schema);
    if (Object.keys(patch).length > 0) onConfig(patch);
  }, [config, onConfig, selectedOp, toolName]);

  // changing app or operation resets arguments — a different operation has different params, so stale args must not leak
  const selectApp = (name: string) => onConfig({
    app_name: name,
    tool_name: undefined,
    service_name: undefined,
    operation_name: undefined,
    request_type: undefined,
    response_type: undefined,
    input_schema: undefined,
    output_schema: undefined,
    arguments: undefined,
  });

  const selectOp = (value: string) => {
    const op = ops.find((o) => opToolName(appName, o) === value);
    if (!op) {
      onConfig({
        tool_name: undefined,
        service_name: undefined,
        operation_name: undefined,
        request_type: undefined,
        response_type: undefined,
        input_schema: undefined,
        output_schema: undefined,
        arguments: undefined,
      });
      return;
    }
    onConfig({
      tool_name: value,
      service_name: op.service,
      operation_name: op.operation,
      request_type: op.request_type,
      response_type: op.response_type,
      input_schema: op.input_schema,
      output_schema: op.output_schema,
      arguments: undefined,
    });
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
          const v = opToolName(appName, o);
          return <option key={v} value={v}>{o.service} · {o.operation} ({o.method})</option>;
        })}
      </select>

      <label style={label}>Arguments</label>
      {/* key forces a fresh editor per operation so rows reseed cleanly when the operation changes */}
      <VariableMapEditor key={appName + ':' + toolName} value={String(config.arguments ?? '')} onChange={(v) => onConfig({ arguments: v })} nodes={nodes} edges={edges} selfId={selfId} params={params} />
    </>
  );
}

function flatten(services: ApiServiceView[]): Op[] {
  return services.flatMap((s) => (s.operations || []).map((op) => ({ ...op, service: s.name, operation: op.name })));
}

function opToolName(appName: string, op: Op): string {
  return op.tool_name || toolCallName(appName, op.service, op.operation);
}

function str(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function setMetadataIfChanged(patch: Record<string, unknown>, config: Record<string, unknown>, key: string, value: unknown) {
  if (typeof value === 'string' && value) {
    if (config[key] !== value) patch[key] = value;
    return;
  }
  if (config[key] !== undefined) patch[key] = undefined;
}

const label: CSSProperties = { display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--color-text-secondary)', margin: '12px 0 4px' };
const input: CSSProperties = {
  width: '100%', boxSizing: 'border-box', padding: '7px 10px', fontSize: 13,
  border: '1px solid var(--color-border)', borderRadius: 7, background: 'var(--color-bg)', color: 'var(--color-text)', outline: 'none',
};
