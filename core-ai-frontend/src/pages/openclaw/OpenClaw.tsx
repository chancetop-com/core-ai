import { useEffect, useState } from 'react';
import { CheckCircle2, Edit2, Play, Plus, Radio, Square, Trash2, X, Zap } from 'lucide-react';
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
