import { useEffect, useState, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Server, RefreshCw, Plug, Play, ChevronDown, ChevronRight, Wrench, Loader2, Zap } from 'lucide-react';
import { api } from '../../api/client';
import type { ToolRegistryView, McpToolInfo, McpConnectionState } from '../../api/client';
import { ConnectionStateBadge, EnabledBadge } from './badges';

export default function McpDetail() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [server, setServer] = useState<ToolRegistryView | null>(null);
  const [tools, setTools] = useState<McpToolInfo[]>([]);
  const [state, setState] = useState<McpConnectionState>('NOT_CONNECTED');
  const [stateMessage, setStateMessage] = useState<string | undefined>();
  const [loading, setLoading] = useState(true);
  const [connecting, setConnecting] = useState(false);
  const [refreshingTools, setRefreshingTools] = useState(false);
  const [expandedTool, setExpandedTool] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const s = await api.tools.get(id);
      setServer(s);
      const isDynamic = s.config?.transport === 'sandbox_hosted';

      const stPromise = api.tools.getMcpServerStatus(id);

      if (isDynamic) {
        // Dynamic MCP: don't auto-list tools on first visit — triggers slow sandbox startup.
        // But if already connected (e.g., page refresh), load tools normally.
        const st = await stPromise;
        setState(st.state);
        setStateMessage(st.message);
        if (st.state === 'CONNECTED') {
          const t = await api.tools.listMcpServerTools(id);
          setTools(t.tools || []);
        }
      } else {
        const [t, st] = await Promise.all([
          api.tools.listMcpServerTools(id),
          stPromise,
        ]);
        setTools(t.tools || []);
        setState(st.state);
        setStateMessage(st.message);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load MCP server');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  const refreshTools = async () => {
    setRefreshingTools(true);
    try {
      const t = await api.tools.listMcpServerTools(id);
      setTools(t.tools || []);
    } finally {
      setRefreshingTools(false);
    }
  };

  const handleConnect = async () => {
    setConnecting(true);
    setError(null);
    let finalState: string | null = null;
    try {
      const r = await api.tools.connectMcpServer(id);
      setState(r.state);
      setStateMessage(r.message);
      finalState = r.state;

      // If the server returned a non-terminal state (CONNECTING / NOT_CONNECTED),
      // the connection is being established asynchronously on the backend.
      // Poll the status endpoint with exponential backoff until the state
      // settles to CONNECTED or FAILED.
      const terminalStates = new Set(['CONNECTED', 'FAILED', 'DISCONNECTED']);
      if (!terminalStates.has(r.state)) {
        const MAX_POLL_MS = 120_000;        // give up after 2 minutes
        const BASE_INTERVAL_MS = 1_000;     // start at 1s
        const MAX_INTERVAL_MS = 15_000;     // cap at 15s
        const startTime = Date.now();
        let attempt = 0;

        while (Date.now() - startTime < MAX_POLL_MS) {
          // Exponential backoff: base * 2^attempt, capped
          const delay = Math.min(BASE_INTERVAL_MS * (1 << attempt), MAX_INTERVAL_MS);
          await new Promise(resolve => setTimeout(resolve, delay));

          const status = await api.tools.getMcpServerStatus(id);
          setState(status.state);
          setStateMessage(status.message);
          finalState = status.state;

          if (status.state === 'CONNECTED') break;
          if (status.state === 'FAILED') {
            setStateMessage(status.message || 'Connection failed');
            break;
          }
          attempt++;
        }
      }

      // If we're now connected, fetch the tool list
      if (finalState === 'CONNECTED') {
        const t = await api.tools.listMcpServerTools(id);
        setTools(t.tools || []);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Connect failed');
    } finally {
      setConnecting(false);
    }
  };

  const alreadyConnected = state === 'CONNECTED';
  const connectDisabled = !server?.enabled || connecting || alreadyConnected;

  // Pretty-print raw_config JSON for Dynamic MCP display
  const rawConfigDisplay = useMemo(() => {
    if (!server?.raw_config) return null;
    try {
      return JSON.stringify(JSON.parse(server.raw_config), null, 2);
    } catch {
      return server.raw_config;
    }
  }, [server?.raw_config]);

  if (loading) {
    return <div className="p-6 text-sm" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;
  }
  if (error || !server) {
    return (
      <div className="p-6">
        <button onClick={() => navigate('/mcp')} className="flex items-center gap-1 text-sm mb-4 cursor-pointer"
          style={{ color: 'var(--color-text-secondary)' }}>
          <ArrowLeft size={14} /> Back to MCP Servers
        </button>
        <div className="text-sm" style={{ color: '#f87171' }}>{error ?? 'Server not found'}</div>
      </div>
    );
  }

  return (
    <div className="p-6">
      <button onClick={() => navigate('/mcp')} className="flex items-center gap-1 text-sm mb-4 cursor-pointer"
        style={{ color: 'var(--color-text-secondary)' }}>
        <ArrowLeft size={14} /> Back to MCP Servers
      </button>

      {server.config?.transport === 'sandbox_hosted' && (
        <div className="flex items-start gap-3 mb-4 px-4 py-3 rounded-lg border text-sm"
          style={{ background: '#1e3a5f', borderColor: '#2563eb', color: '#93c5fd' }}>
          <Zap size={16} className="shrink-0 mt-0.5" />
          <div>
            <span className="font-medium">Dynamic MCP</span> — this server runs in a sandbox environment.
            Click <strong>Connect</strong> to start the sandbox and discover available tools.
            Sandbox startup may take several seconds.
          </div>
        </div>
      )}

      <div className="rounded-xl border p-4 mb-4"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2">
              <Server size={18} className="shrink-0" style={{ color: 'var(--color-primary)' }} />
              <h1 className="text-xl font-semibold break-words" title={server.name}>{server.name}</h1>
            </div>
            <div className="flex flex-wrap items-center gap-2 mt-2">
              <EnabledBadge enabled={server.enabled} />
              <ConnectionStateBadge state={state} />
              {server.category && (
                <span className="px-2 py-0.5 rounded text-xs"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                  {server.category}
                </span>
              )}
            </div>
            {server.description && (
              <p className="text-sm mt-2" style={{ color: 'var(--color-text-secondary)' }}>{server.description}</p>
            )}
            {stateMessage && (
              <p className="text-xs mt-2" style={{ color: '#fbbf24' }}>{stateMessage}</p>
            )}
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <button onClick={handleConnect} disabled={connectDisabled}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm cursor-pointer disabled:opacity-40 text-white"
              style={{ background: 'var(--color-primary)' }}
              title={alreadyConnected ? 'Already connected' : 'Connect to MCP server'}>
              {connecting ? <Loader2 size={14} className="animate-spin" /> : <Plug size={14} />}
              {alreadyConnected ? 'Connected' : 'Connect'}
            </button>
            <button onClick={load}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-sm cursor-pointer"
              style={{ borderColor: 'var(--color-border)' }}
              title="Refresh status">
              <RefreshCw size={14} /> Refresh
            </button>
          </div>
        </div>

        {(Object.keys(server.config || {}).length > 0 || rawConfigDisplay) && (
          <div className="mt-4 pt-3 border-t" style={{ borderColor: 'var(--color-border)' }}>
            <div className="text-xs mb-2" style={{ color: 'var(--color-text-secondary)' }}>Configuration</div>
            {server.config?.transport === 'sandbox_hosted' ? (
              <pre className="text-xs font-mono p-2 rounded overflow-auto max-h-60"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                {rawConfigDisplay || 'No raw config'}
              </pre>
            ) : (
              <div className="grid gap-2 grid-cols-1 sm:grid-cols-2">
                {Object.entries(server.config).map(([k, v]) => (
                  <div key={k} className="text-xs font-mono rounded px-2 py-1 min-w-0"
                    style={{ background: 'var(--color-bg-tertiary)' }}>
                    <span style={{ color: 'var(--color-text-secondary)' }}>{k}: </span>
                    <span className="break-all">{v}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <Wrench size={16} style={{ color: 'var(--color-text-secondary)' }} />
          <h2 className="text-base font-medium">Tools</h2>
          <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            ({tools.length})
          </span>
        </div>
        <button onClick={refreshTools} disabled={refreshingTools || !alreadyConnected}
          className="flex items-center gap-1 px-2 py-1 rounded border text-xs cursor-pointer disabled:opacity-40"
          style={{ borderColor: 'var(--color-border)' }}
          title={alreadyConnected ? 'Refresh tools list' : 'Connect first'}>
          {refreshingTools ? <Loader2 size={12} className="animate-spin" /> : <RefreshCw size={12} />} Refresh tools
        </button>
      </div>

      {tools.length === 0 ? (
        <div className="text-center py-12 rounded-xl border text-sm"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
          {alreadyConnected ? 'No tools exposed by this MCP server.' : 'Connect to load tools.'}
        </div>
      ) : (
        <div className="space-y-2">
          {tools.map(t => (
            <ToolRow
              key={t.name}
              tool={t}
              serverId={id}
              expanded={expandedTool === t.name}
              onToggle={() => setExpandedTool(expandedTool === t.name ? null : t.name)}
              canTest={alreadyConnected}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function buildArgTemplate(schemaJson?: string): string {
  if (!schemaJson) return '{}';
  try {
    const schema = JSON.parse(schemaJson);
    const props = schema?.properties as Record<string, { type?: string }> | undefined;
    if (!props) return '{}';
    const sample: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(props)) {
      switch (v?.type) {
        case 'string': sample[k] = ''; break;
        case 'integer':
        case 'number': sample[k] = 0; break;
        case 'boolean': sample[k] = false; break;
        case 'array': sample[k] = []; break;
        case 'object': sample[k] = {}; break;
        default: sample[k] = null;
      }
    }
    return JSON.stringify(sample, null, 2);
  } catch {
    return '{}';
  }
}

function ToolRow({
  tool, serverId, expanded, onToggle, canTest,
}: {
  tool: McpToolInfo;
  serverId: string;
  expanded: boolean;
  onToggle: () => void;
  canTest: boolean;
}) {
  const initialArgs = useMemo(() => buildArgTemplate(tool.input_schema), [tool.input_schema]);
  const [args, setArgs] = useState(initialArgs);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<{ success: boolean; result: string; duration_ms: number } | null>(null);
  const [callError, setCallError] = useState<string | null>(null);

  const handleRun = async () => {
    setRunning(true);
    setCallError(null);
    setResult(null);
    try {
      // Validate JSON locally before sending
      try { JSON.parse(args); } catch { throw new Error('Arguments must be valid JSON'); }
      const r = await api.tools.testMcpServerTool(serverId, tool.name, args);
      setResult(r);
    } catch (e) {
      setCallError(e instanceof Error ? e.message : 'Tool call failed');
    } finally {
      setRunning(false);
    }
  };

  const schemaPretty = useMemo(() => {
    if (!tool.input_schema) return null;
    try { return JSON.stringify(JSON.parse(tool.input_schema), null, 2); } catch { return tool.input_schema; }
  }, [tool.input_schema]);

  return (
    <div className="rounded-lg border"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <div className="flex items-start gap-3 p-3 cursor-pointer" onClick={onToggle}>
        <div className="pt-0.5 shrink-0" style={{ color: 'var(--color-text-secondary)' }}>
          {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        </div>
        <div className="min-w-0 flex-1">
          <div className="font-mono text-sm break-all" title={tool.name}>{tool.name}</div>
          {tool.description && (
            <div className="text-xs mt-0.5 truncate" style={{ color: 'var(--color-text-secondary)' }} title={tool.description}>
              {tool.description}
            </div>
          )}
        </div>
        <button onClick={e => { e.stopPropagation(); if (!expanded) onToggle(); }}
          disabled={!canTest}
          className="shrink-0 flex items-center gap-1 px-2 py-1 rounded border text-xs cursor-pointer disabled:opacity-40"
          style={{ borderColor: 'var(--color-border)' }}
          title={canTest ? 'Test this tool' : 'Connect first'}>
          <Play size={12} /> Test
        </button>
      </div>

      {expanded && (
        <div className="p-3 border-t space-y-3" style={{ borderColor: 'var(--color-border)' }}>
          {tool.description && (
            <div>
              <div className="text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Description</div>
              <p className="text-sm">{tool.description}</p>
            </div>
          )}
          {schemaPretty && (
            <div>
              <div className="text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Input schema</div>
              <pre className="text-xs font-mono p-2 rounded overflow-auto max-h-48"
                style={{ background: 'var(--color-bg-tertiary)' }}>{schemaPretty}</pre>
            </div>
          )}
          <div>
            <div className="flex items-center justify-between mb-1">
              <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Arguments (JSON)</div>
              <button onClick={() => setArgs(initialArgs)}
                className="text-xs underline cursor-pointer"
                style={{ color: 'var(--color-text-secondary)' }}>Reset</button>
            </div>
            <textarea value={args} onChange={e => setArgs(e.target.value)}
              spellCheck={false}
              className="w-full font-mono text-xs px-2 py-2 rounded border resize-y"
              style={{
                minHeight: 100,
                borderColor: 'var(--color-border)',
                background: 'var(--color-bg)',
                color: 'var(--color-text)',
              }} />
          </div>
          <div className="flex items-center gap-2">
            <button onClick={handleRun} disabled={running || !canTest}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm cursor-pointer disabled:opacity-40 text-white"
              style={{ background: 'var(--color-primary)' }}>
              {running ? <Loader2 size={14} className="animate-spin" /> : <Play size={14} />} Run
            </button>
            {result && (
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {result.duration_ms} ms
              </span>
            )}
          </div>

          {callError && (
            <div className="text-xs rounded p-2" style={{ background: '#7f1d1d', color: '#fff' }}>{callError}</div>
          )}
          {result && (
            <div>
              <div className="text-xs mb-1 flex items-center gap-2" style={{ color: 'var(--color-text-secondary)' }}>
                Result
                <span className="px-1.5 py-0.5 rounded text-[10px]"
                  style={result.success
                    ? { background: '#065f46', color: '#fff' }
                    : { background: '#7f1d1d', color: '#fff' }}>
                  {result.success ? 'success' : 'failed'}
                </span>
              </div>
              <pre className="text-xs font-mono p-2 rounded overflow-auto max-h-72 whitespace-pre-wrap"
                style={{ background: 'var(--color-bg-tertiary)' }}>{result.result}</pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
