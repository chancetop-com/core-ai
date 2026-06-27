import { useEffect, useState } from 'react';
import { CheckCircle2, Edit2, FileText, Play, Plus, Radio, RotateCw, Square, Terminal, Trash2, X, Zap } from 'lucide-react';
import { api } from '../../api/client';
import type { ChannelView, OcgConfigView, OcgSandboxStatus } from '../../api/client';

interface EditorState {
  open: boolean;
  editing: OcgConfigView | null;
  id: string;
  channelId: string;
  callbackSecret: string;
  configJson: string;
}

interface LogsState {
  open: boolean;
  config: OcgConfigView | null;
  type: 'gateway' | 'terminal';
  content: string;
  command: string;
  running: boolean;
  loading: boolean;
}

function emptyEditor(): EditorState {
  return {
    open: false,
    editing: null,
    id: '',
    channelId: '',
    callbackSecret: '',
    configJson: '{\n  \n}',
  };
}

function statusColor(status: OcgSandboxStatus) {
  if (status === 'running') return { background: '#dcfce7', color: '#15803d' };
  if (status === 'error') return { background: '#fee2e2', color: '#b91c1c' };
  return { background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' };
}

function formatDate(value?: string) {
  if (!value) return '-';
  return new Date(value).toLocaleString();
}

export default function OpenClaw() {
  const [configs, setConfigs] = useState<OcgConfigView[]>([]);
  const [channels, setChannels] = useState<ChannelView[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [editor, setEditor] = useState<EditorState>(emptyEditor());
  const [logs, setLogs] = useState<LogsState>({ open: false, config: null, type: 'gateway', content: '', command: '', running: false, loading: false });

  const load = () => {
    setLoading(true);
    Promise.all([api.ocg.list(), api.channels.list()])
      .then(([configRes, channelRes]) => {
        setConfigs(configRes.configs || []);
        setChannels(channelRes.channels || []);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const openClawChannels = channels.filter(c => c.channelType === 'openclaw');
  const channelMap: Record<string, ChannelView> = {};
  for (const channel of channels) channelMap[channel.channelId] = channel;

  const openNew = () => {
    if (openClawChannels.length === 0) {
      alert('Please create an OpenClaw channel first: go to Triggers > Channels, create a new channel, and select Channel Type = OpenClaw.');
      return;
    }
    setEditor({ ...emptyEditor(), open: true, channelId: openClawChannels[0].channelId });
  };

  const openEdit = (config: OcgConfigView) => {
    setEditor({
      open: true,
      editing: config,
      id: config.id,
      channelId: config.channelId,
      callbackSecret: config.callbackSecret || '',
      configJson: config.configJson || '{\n  \n}',
    });
  };

  const close = () => setEditor(prev => ({ ...prev, open: false }));

  const save = async () => {
    if (!editor.id.trim()) { alert('Config ID is required'); return; }
    if (!editor.channelId) { alert('Please select an OpenClaw channel'); return; }
    if (!editor.configJson.trim()) { alert('Config JSON is required'); return; }
    try {
      const parsed = JSON.parse(editor.configJson);
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
        alert('Config JSON must be a JSON object');
        return;
      }
    } catch {
      alert('Config JSON is invalid');
      return;
    }

    const data: Record<string, unknown> = {
      id: editor.id.trim(),
      channelId: editor.channelId,
      configJson: editor.configJson.trim(),
      callbackSecret: editor.callbackSecret.trim() || undefined,
    };

    try {
      if (editor.editing) {
        await api.ocg.update(editor.id, data);
      } else {
        await api.ocg.create(data);
      }
      close();
      load();
    } catch (e) {
      alert(`Save failed: ${e instanceof Error ? e.message : e}`);
    }
  };

  const start = async (config: OcgConfigView) => {
    setBusyId(config.id);
    try {
      await api.ocg.start(config.id);
      load();
    } catch (e) {
      alert(`Start failed: ${e instanceof Error ? e.message : e}`);
    } finally {
      setBusyId(null);
    }
  };

  const stop = async (config: OcgConfigView) => {
    if (!confirm(`Stop OpenClaw gateway "${config.id}"?`)) return;
    setBusyId(config.id);
    try {
      await api.ocg.stop(config.id);
      load();
    } catch (e) {
      alert(`Stop failed: ${e instanceof Error ? e.message : e}`);
    } finally {
      setBusyId(null);
    }
  };

  const restart = async (config: OcgConfigView) => {
    if (!confirm(`Restart OCG process for "${config.id}"? The sandbox will stay, only the gateway process restarts.`)) return;
    setBusyId(config.id);
    try {
      await api.ocg.restart(config.id);
      load();
    } catch (e) {
      alert(`Restart failed: ${e instanceof Error ? e.message : e}`);
    } finally {
      setBusyId(null);
    }
  };

  const openTerminal = async (config: OcgConfigView) => {
    setLogs({ open: true, config, type: 'terminal', content: '', command: '', running: false, loading: true });
    await loadLogs(config, 'terminal');
  };

  const runTerminalCommand = async () => {
    if (!logs.config || !logs.command.trim()) return;
    const config = logs.config;
    const command = logs.command.trim();
    setLogs(prev => ({ ...prev, running: true, content: `${prev.content}\n$ ${command}\n` }));
    try {
      await api.ocg.command(config.id, command);
      setLogs(prev => ({ ...prev, command: '', running: false }));
      await loadLogs(config, 'terminal');
    } catch (e) {
      setLogs(prev => ({ ...prev, content: `${prev.content}\nCommand failed: ${e instanceof Error ? e.message : e}`, running: false }));
    }
  };

  const openLogs = async (config: OcgConfigView, type: 'gateway' | 'terminal' = 'gateway') => {
    setLogs({ open: true, config, type, content: '', command: logs.command, running: false, loading: true });
    await loadLogs(config, type);
  };

  const loadLogs = async (config: OcgConfigView, type: 'gateway' | 'terminal') => {
    try {
      const res = await api.ocg.logs(config.id, type, 1000);
      setLogs(prev => ({ ...prev, open: true, config, type, content: res.logs || '', loading: false }));
    } catch (e) {
      setLogs(prev => ({ ...prev, open: true, config, type, content: `Load logs failed: ${e instanceof Error ? e.message : e}`, loading: false, running: false }));
    }
  };

  const refreshLogs = async () => {
    if (!logs.config) return;
    await loadLogs(logs.config, logs.type);
  };

  useEffect(() => {
    if (!logs.open || logs.type !== 'terminal' || !logs.config) return;
    const timer = window.setInterval(() => {
      if (logs.config) loadLogs(logs.config, 'terminal');
    }, 2000);
    return () => window.clearInterval(timer);
  }, [logs.open, logs.type, logs.config]);

  const remove = async (config: OcgConfigView) => {
    if (!confirm(`Delete OpenClaw config "${config.id}"?`)) return;
    try {
      await api.ocg.delete(config.id);
      load();
    } catch (e) {
      alert(`Delete failed: ${e instanceof Error ? e.message : e}`);
    }
  };

  const selectedChannel = channelMap[editor.channelId];

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">OpenClaw</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Manage OpenClaw Channel Gateway configs and persistent sandboxes
          </p>
        </div>
        <button onClick={openNew}
          className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
          style={{ background: 'var(--color-primary)' }}>
          <Plus size={16} /> New OpenClaw Config
        </button>
      </div>

      {openClawChannels.length === 0 && !loading && (
        <div className="mb-4 rounded-lg border px-4 py-3 text-sm"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text-secondary)' }}>
          Create a channel with type OpenClaw on the Channels page before adding an OCG config.
        </div>
      )}

      <div className="rounded-xl border overflow-hidden"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        {loading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : configs.length === 0 ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>
            <Zap size={32} className="mx-auto mb-3 opacity-30" />
            No OpenClaw configs yet. Add one to start an OCG sandbox.
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                <th className="text-left px-4 py-3 font-medium">Config</th>
                <th className="text-left px-4 py-3 font-medium">Channel</th>
                <th className="text-left px-4 py-3 font-medium">Sandbox IP</th>
                <th className="text-left px-4 py-3 font-medium">Status</th>
                <th className="text-left px-4 py-3 font-medium">Updated</th>
                <th className="text-right px-4 py-3 font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {configs.map(config => (
                <tr key={config.id} style={{ borderTop: '1px solid var(--color-border)' }}>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <Zap size={14} style={{ color: 'var(--color-primary)' }} />
                      <span className="font-medium">{config.id}</span>
                    </div>
                  </td>
                  <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>
                    {channelMap[config.channelId]?.channelId ?? config.channelId}
                  </td>
                  <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>
                    {config.sandboxIp || '-'}
                  </td>
                  <td className="px-4 py-3">
                    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium" style={statusColor(config.sandboxStatus)}>
                      {config.sandboxStatus === 'running' && <CheckCircle2 size={12} />}
                      {config.sandboxStatus}
                    </span>
                  </td>
                  <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>
                    {formatDate(config.updatedAt)}
                  </td>
                  <td className="px-4 py-3 text-right">
                    {config.sandboxStatus === 'running' ? (
                      <button onClick={() => stop(config)} disabled={busyId === config.id}
                        className="inline-flex items-center justify-center w-8 h-8 rounded-lg border cursor-pointer mr-1 disabled:opacity-40"
                        style={{ borderColor: 'var(--color-border)' }} title="Stop">
                        <Square size={14} />
                      </button>
                    ) : (
                      <button onClick={() => start(config)} disabled={busyId === config.id}
                        className="inline-flex items-center justify-center w-8 h-8 rounded-lg border cursor-pointer mr-1 disabled:opacity-40"
                        style={{ borderColor: 'var(--color-border)' }} title="Start">
                        <Play size={14} />
                      </button>
                    )}
                    <button onClick={() => restart(config)} disabled={config.sandboxStatus !== 'running' || busyId === config.id}
                      className="inline-flex items-center justify-center w-8 h-8 rounded-lg border cursor-pointer mr-1 disabled:opacity-40"
                      style={{ borderColor: 'var(--color-border)' }} title="Restart OCG process">
                      <RotateCw size={14} />
                    </button>
                    <button onClick={() => openTerminal(config)} disabled={config.sandboxStatus !== 'running' || busyId === config.id}
                      className="inline-flex items-center justify-center w-8 h-8 rounded-lg border cursor-pointer mr-1 disabled:opacity-40"
                      style={{ borderColor: 'var(--color-border)' }} title="Open terminal">
                      <Terminal size={14} />
                    </button>
                    <button onClick={() => openLogs(config, 'gateway')} disabled={!config.sandboxId}
                      className="inline-flex items-center justify-center w-8 h-8 rounded-lg border cursor-pointer mr-1 disabled:opacity-40"
                      style={{ borderColor: 'var(--color-border)' }} title="View logs">
                      <FileText size={14} />
                    </button>
                    <button onClick={() => openEdit(config)}
                      className="inline-flex items-center justify-center w-8 h-8 rounded-lg border cursor-pointer mr-1"
                      style={{ borderColor: 'var(--color-border)' }} title="Edit">
                      <Edit2 size={14} />
                    </button>
                    <button onClick={() => remove(config)}
                      className="inline-flex items-center justify-center w-8 h-8 rounded-lg border cursor-pointer"
                      style={{ borderColor: 'var(--color-border)', color: '#e5484d' }} title="Delete">
                      <Trash2 size={14} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {logs.open && (
        <div className="fixed inset-0 z-50 flex justify-end"
          style={{ background: 'rgba(0,0,0,0.4)' }}
          onClick={() => setLogs(prev => ({ ...prev, open: false }))}>
          <div className="w-full max-w-3xl h-full overflow-y-auto p-6"
            style={{ background: 'var(--color-bg)', borderLeft: '1px solid var(--color-border)' }}
            onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <div>
                <h2 className="text-lg font-semibold">OCG Logs</h2>
                <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  {logs.config?.id} / {logs.type}
                </p>
              </div>
              <button onClick={() => setLogs(prev => ({ ...prev, open: false }))} className="p-1 rounded cursor-pointer"><X size={18} /></button>
            </div>
            <div className="flex items-center gap-2 mb-3">
              <button onClick={() => logs.config && openLogs(logs.config, 'gateway')}
                className="px-3 py-1.5 rounded-lg border text-sm cursor-pointer"
                style={{ borderColor: 'var(--color-border)', background: logs.type === 'gateway' ? 'var(--color-bg-tertiary)' : 'transparent' }}>
                Gateway
              </button>
              <button onClick={() => logs.config && openTerminal(logs.config)}
                className="px-3 py-1.5 rounded-lg border text-sm cursor-pointer"
                style={{ borderColor: 'var(--color-border)', background: logs.type === 'terminal' ? 'var(--color-bg-tertiary)' : 'transparent' }}>
                Terminal
              </button>
              <button onClick={refreshLogs}
                className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg border text-sm cursor-pointer"
                style={{ borderColor: 'var(--color-border)' }}>
                <RotateCw size={13} /> Refresh
              </button>
            </div>
            {logs.type === 'terminal' && (
              <div className="flex items-center gap-2 mb-3">
                <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>$</span>
                <input value={logs.command}
                  onChange={e => setLogs(prev => ({ ...prev, command: e.target.value }))}
                  onKeyDown={e => { if (e.key === 'Enter') runTerminalCommand(); }}
                  placeholder="Paste an OpenClaw/OCG command from docs, e.g. ocg channels login --channel openclaw-weixin"
                  className="flex-1 px-3 py-2 rounded-lg border text-sm font-mono"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                <button onClick={runTerminalCommand} disabled={!logs.command.trim() || logs.running}
                  className="px-4 py-2 rounded-lg text-sm text-white cursor-pointer disabled:opacity-40"
                  style={{ background: 'var(--color-primary)' }}>
                  Run
                </button>
              </div>
            )}
            <pre className="text-xs rounded-lg border p-3 whitespace-pre-wrap overflow-auto min-h-[480px]"
              style={{ borderColor: 'var(--color-border)', background: '#0f172a', color: '#e2e8f0' }}>
              {logs.loading ? 'Loading...' : logs.content || '(empty)'}
            </pre>
          </div>
        </div>
      )}

      {editor.open && (
        <div className="fixed inset-0 z-50 flex justify-end"
          style={{ background: 'rgba(0,0,0,0.4)' }}
          onClick={close}>
          <div className="w-full max-w-2xl h-full overflow-y-auto p-6"
            style={{ background: 'var(--color-bg)', borderLeft: '1px solid var(--color-border)' }}
            onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-6">
              <h2 className="text-lg font-semibold">{editor.editing ? 'Edit OpenClaw Config' : 'New OpenClaw Config'}</h2>
              <button onClick={close} className="p-1 rounded cursor-pointer"><X size={18} /></button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium mb-1">Config ID</label>
                <input value={editor.id} disabled={!!editor.editing}
                  onChange={e => setEditor(prev => ({ ...prev, id: e.target.value }))}
                  className="w-full px-3 py-2 rounded-lg border text-sm disabled:opacity-60"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
              </div>

              <div>
                <label className="block text-sm font-medium mb-1">OpenClaw Channel</label>
                <select value={editor.channelId}
                  onChange={e => setEditor(prev => ({ ...prev, channelId: e.target.value }))}
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                  <option value="">Select channel...</option>
                  {openClawChannels.map(channel => (
                    <option key={channel.channelId} value={channel.channelId}>{channel.channelId}</option>
                  ))}
                </select>
              </div>

              {selectedChannel && (
                <div>
                  <label className="block text-sm font-medium mb-1">Agent URL</label>
                  <div className="flex items-center gap-2 px-3 py-2 rounded-lg border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text-secondary)' }}>
                    <Radio size={14} />
                    <code className="truncate">{selectedChannel.webhookUrl}</code>
                  </div>
                  <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                    This URL is generated by the selected channel and injected at sandbox start. Do not duplicate it in config JSON.
                  </p>
                </div>
              )}

              <div>
                <label className="block text-sm font-medium mb-1">Callback Secret</label>
                <input value={editor.callbackSecret} type="password"
                  onChange={e => setEditor(prev => ({ ...prev, callbackSecret: e.target.value }))}
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Stored at the top level and injected into the runtime config only when starting the sandbox.
                </p>
              </div>

              <div>
                <label className="block text-sm font-medium mb-1">Config JSON</label>
                <textarea value={editor.configJson} rows={14}
                  onChange={e => setEditor(prev => ({ ...prev, configJson: e.target.value }))}
                  className="w-full px-3 py-2 rounded-lg border text-sm font-mono"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
              </div>
            </div>

            <div className="flex justify-end gap-2 mt-6">
              <button onClick={close}
                className="px-4 py-2 rounded-lg border text-sm cursor-pointer"
                style={{ borderColor: 'var(--color-border)' }}>
                Cancel
              </button>
              <button onClick={save}
                className="px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
                style={{ background: 'var(--color-primary)' }}>
                Save
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
