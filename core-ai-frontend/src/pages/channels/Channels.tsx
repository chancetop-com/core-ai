import { useEffect, useState } from 'react';
import { Plus, Edit2, Trash2, X, Radio, MessageCircle } from 'lucide-react';
import { api } from '../../api/client';
import type { ChannelView, ChannelTypeInfo } from '../../api/client';
import KeyValueVariablesEditor from '../../components/KeyValueVariablesEditor';
import type { AgentDefinition } from '../../api/client';

interface EditorState {
  open: boolean;
  editing: ChannelView | null;
  channelId: string;
  channelType: string;
  agentId: string;
  enabled: boolean;
  requireAuth: boolean;
  sessionTtlMinutes: number;
  config: Record<string, string>;
  filterConfig: Record<string, string>;
}

function emptyEditor(): EditorState {
  return {
    open: false,
    editing: null,
    channelId: '',
    channelType: 'slack',
    agentId: '',
    enabled: true,
    requireAuth: true,
    sessionTtlMinutes: 60,
    config: {},
    filterConfig: {},
  };
}

export default function Channels() {
  const [channels, setChannels] = useState<ChannelView[]>([]);
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [channelTypes, setChannelTypes] = useState<ChannelTypeInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [editor, setEditor] = useState<EditorState>(emptyEditor());

  const load = () => {
    setLoading(true);
    Promise.all([
      api.channels.list(),
      api.agents.list(),
      api.channels.types(),
    ])
      .then(([channelsRes, agentsRes, typesRes]) => {
        setChannels(channelsRes.channels || []);
        setAgents(agentsRes.agents || []);
        setChannelTypes(typesRes.types || []);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const agentMap: Record<string, AgentDefinition> = {};
  for (const a of agents) agentMap[a.id] = a;

  const typeLabel = (type: string) => {
    const t = channelTypes.find(ct => ct.type === type);
    return t ? t.label : type;
  };

  const openNew = () => {
    const defaultAgentId = agents[0]?.id ?? '';
    setEditor({ ...emptyEditor(), open: true, agentId: defaultAgentId });
  };

  const openEdit = (c: ChannelView) => {
    setEditor({
      open: true,
      editing: c,
      channelId: c.channelId,
      channelType: c.channelType,
      agentId: c.agentId || '',
      enabled: c.enabled,
      requireAuth: c.requireAuth ?? true,
      sessionTtlMinutes: c.sessionTtlMinutes || 60,
      config: { ...c.config },
      filterConfig: { ...(c.filterConfig || {}) },
    });
  };

  const close = () => setEditor(prev => ({ ...prev, open: false }));

  const save = async () => {
    if (!editor.channelId.trim()) { alert('Channel ID is required'); return; }
    if (!editor.channelType) { alert('Channel type is required'); return; }
    if (!editor.agentId) { alert('Please select an agent'); return; }

    const data: Record<string, unknown> = {
      channelId: editor.channelId.trim(),
      channelType: editor.channelType,
      agentId: editor.agentId,
      enabled: editor.enabled,
      requireAuth: editor.requireAuth,
      sessionTtlMinutes: editor.sessionTtlMinutes,
      config: editor.config,
      filterConfig: Object.keys(editor.filterConfig).length > 0 ? editor.filterConfig : null,
    };

    try {
      if (editor.editing) {
        await api.channels.update(editor.channelId, data);
      } else {
        await api.channels.create(data);
      }
      close();
      load();
    } catch (e) {
      alert(`Save failed: ${e instanceof Error ? e.message : e}`);
    }
  };

  const remove = async (c: ChannelView) => {
    if (!confirm(`Delete channel "${c.channelId}"?`)) return;
    await api.channels.delete(c.channelId);
    load();
  };

  const toggleEnabled = async (c: ChannelView) => {
    await api.channels.update(c.channelId, {
      channelId: c.channelId,
      channelType: c.channelType,
      agentId: c.agentId || '',
      enabled: !c.enabled,
      requireAuth: c.requireAuth ?? true,
      sessionTtlMinutes: c.sessionTtlMinutes || 60,
      config: c.config,
      filterConfig: c.filterConfig,
    });
    load();
  };

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">Channels</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Configure messaging channels (Slack, Telegram, WeClaw) for AI agent interactions
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={openNew} disabled={agents.length === 0}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
            style={{ background: 'var(--color-primary)' }}>
            <Plus size={16} /> New Channel
          </button>
        </div>
      </div>

      <div className="rounded-xl border overflow-hidden"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        {loading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : channels.length === 0 ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>
            <MessageCircle size={32} className="mx-auto mb-3 opacity-30" />
            No channels configured yet. Add one to enable AI conversations on external platforms.
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                <th className="text-left px-4 py-3 font-medium">Channel</th>
                <th className="text-left px-4 py-3 font-medium">Type</th>
                <th className="text-left px-4 py-3 font-medium">Agent</th>
                <th className="text-left px-4 py-3 font-medium">Session TTL</th>
                <th className="text-left px-4 py-3 font-medium">Enabled</th>
                <th className="text-left px-4 py-3 font-medium">Auth</th>
                <th className="text-right px-4 py-3 font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {channels.map(c => (
                <tr key={c.channelId} style={{ borderTop: '1px solid var(--color-border)' }}>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <Radio size={14} style={{ color: 'var(--color-primary)' }} />
                      <span className="font-medium">{c.channelId}</span>
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <span className="inline-block px-2 py-0.5 rounded text-xs font-medium"
                      style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}>
                      {typeLabel(c.channelType)}
                    </span>
                  </td>
                  <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>
                    {c.agentId ? (agentMap[c.agentId]?.name ?? c.agentId) : '-'}
                  </td>
                  <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>
                    {c.sessionTtlMinutes || 60}m
                  </td>
                  <td className="px-4 py-3">
                    <button onClick={() => toggleEnabled(c)}
                      className="relative inline-flex items-center w-10 h-5 rounded-full cursor-pointer transition-colors"
                      style={{ background: c.enabled ? 'var(--color-primary)' : 'var(--color-bg-tertiary)' }}>
                      <span className="absolute w-4 h-4 bg-white rounded-full transition-transform"
                        style={{ transform: c.enabled ? 'translateX(22px)' : 'translateX(2px)' }} />
                    </button>
                  </td>
                  <td className="px-4 py-3">
                    <span className="inline-block px-2 py-0.5 rounded text-xs font-medium"
                      style={{ background: c.requireAuth === false ? '#fef2cd' : 'var(--color-bg-tertiary)', color: c.requireAuth === false ? '#946800' : 'var(--color-text-secondary)' }}>
                      {c.requireAuth === false ? 'Off' : 'On'}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <button onClick={() => openEdit(c)}
                      className="inline-flex items-center justify-center w-8 h-8 rounded-lg border cursor-pointer mr-1"
                      style={{ borderColor: 'var(--color-border)' }} title="Edit">
                      <Edit2 size={14} />
                    </button>
                    <button onClick={() => remove(c)}
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
          <div className="w-full max-w-md h-full overflow-y-auto p-6"
            style={{ background: 'var(--color-bg)', borderLeft: '1px solid var(--color-border)' }}
            onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-6">
              <h2 className="text-lg font-semibold">{editor.editing ? 'Edit Channel' : 'New Channel'}</h2>
              <button onClick={close} className="p-1 rounded cursor-pointer"><X size={18} /></button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Channel ID
                </label>
                <input value={editor.channelId}
                  onChange={e => setEditor({ ...editor, channelId: e.target.value })}
                  disabled={!!editor.editing}
                  placeholder="e.g. slack-prod"
                  className="w-full px-3 py-2 rounded-lg border text-sm disabled:opacity-50"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Unique identifier. Used in webhook URL: /api/channels/{'{id}'}
                </p>
              </div>

              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Channel Type
                </label>
                <select value={editor.channelType}
                  onChange={e => setEditor({ ...editor, channelType: e.target.value })}
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                  {channelTypes.map(ct => (
                    <option key={ct.type} value={ct.type}>{ct.label}</option>
                  ))}
                </select>
              </div>

              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Agent
                </label>
                <select value={editor.agentId}
                  onChange={e => setEditor({ ...editor, agentId: e.target.value })}
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                  <option value="">Select agent</option>
                  {agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
                </select>
                <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  AI agent that responds to messages from this channel.
                </p>
              </div>

              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Session TTL (minutes)
                </label>
                <input type="number" value={editor.sessionTtlMinutes}
                  onChange={e => setEditor({ ...editor, sessionTtlMinutes: parseInt(e.target.value) || 60 })}
                  min={1} max={1440}
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Conversation session expires after this many idle minutes.
                </p>
              </div>

              <div className="pt-2 pb-1">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input type="checkbox" checked={editor.enabled}
                    onChange={e => setEditor({ ...editor, enabled: e.target.checked })}
                    className="w-4 h-4 rounded" style={{ accentColor: 'var(--color-primary)' }} />
                  <span className="text-sm font-medium">Enabled</span>
                </label>
              </div>

              <div className="pb-1">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input type="checkbox" checked={editor.requireAuth}
                    onChange={e => setEditor({ ...editor, requireAuth: e.target.checked })}
                    className="w-4 h-4 rounded" style={{ accentColor: 'var(--color-primary)' }} />
                  <span className="text-sm font-medium">Require Authentication</span>
                </label>
                <p className="text-xs mt-1 ml-6" style={{ color: 'var(--color-text-secondary)' }}>
                  If disabled, requests to this channel bypass API key auth. Turn off for platforms like WeClaw that call directly.
                </p>
              </div>

              <div className="pt-3">
                <div className="text-xs font-semibold uppercase tracking-wider mb-2" style={{ color: 'var(--color-text-secondary)' }}>
                  Platform Config
                </div>
                <p className="text-xs mb-3" style={{ color: 'var(--color-text-secondary)' }}>
                  Channel-specific settings. For Slack: <code className="text-[11px] px-1 py-0.5 rounded" style={{ background: 'var(--color-bg-tertiary)' }}>bot_token</code>, <code className="text-[11px] px-1 py-0.5 rounded" style={{ background: 'var(--color-bg-tertiary)' }}>slack_signing_secret</code>.
                  For WeClaw: <code className="text-[11px] px-1 py-0.5 rounded" style={{ background: 'var(--color-bg-tertiary)' }}>weclaw_api_base</code>.
                </p>
                <KeyValueVariablesEditor
                  value={editor.config}
                  onChange={(v) => setEditor({ ...editor, config: v || {} })}
                  keyPlaceholder="e.g. bot_token"
                  valuePlaceholder="e.g. xoxb-..."
                />
              </div>

              <div className="pt-3">
                <div className="text-xs font-semibold uppercase tracking-wider mb-2" style={{ color: 'var(--color-text-secondary)' }}>
                  Event Filters
                </div>
                <p className="text-xs mb-3" style={{ color: 'var(--color-text-secondary)' }}>
                  Optional: skip events that don't match. For Slack: <code className="text-[11px] px-1 py-0.5 rounded" style={{ background: 'var(--color-bg-tertiary)' }}>filter_channels</code> (comma-separated channel IDs).
                </p>
                <KeyValueVariablesEditor
                  value={editor.filterConfig}
                  onChange={(v) => setEditor({ ...editor, filterConfig: v || {} })}
                  keyPlaceholder="e.g. filter_channels"
                  valuePlaceholder="e.g. C123, C456"
                />
              </div>
            </div>

            <div className="flex gap-2 mt-6">
              <button onClick={close}
                className="flex-1 px-3 py-2 rounded-lg border text-sm cursor-pointer"
                style={{ borderColor: 'var(--color-border)' }}>Cancel</button>
              <button onClick={save}
                className="flex-1 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
                style={{ background: 'var(--color-primary)' }}>Save</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
