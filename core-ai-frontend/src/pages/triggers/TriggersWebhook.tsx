import { useEffect, useState } from 'react';
import { Plus, Copy, RefreshCw, Edit2, Trash2, X, Check, Webhook } from 'lucide-react';
import { api } from '../../api/client';
import type { TriggerView, AgentDefinition } from '../../api/client';

interface EditorState {
  open: boolean;
  editing: TriggerView | null;
  name: string;
  description: string;
  agentId: string;
  inputTemplate: string;
  verifierType: 'bearer' | 'slack' | 'none';
  secret: string;
  slackSigningSecret: string;
  // Event filter settings
  filterEventTypes: string;
  filterIgnoreSubtypes: string;
  filterChannels: string;
}

function emptyEditor(): EditorState {
  return {
    open: false,
    editing: null,
    name: '',
    description: '',
    agentId: '',
    inputTemplate: '',
    verifierType: 'none',
    secret: '',
    slackSigningSecret: '',
    filterEventTypes: '',
    filterIgnoreSubtypes: '',
    filterChannels: '',
  };
}

function formatTime(iso: string) {
  if (!iso) return '-';
  const d = new Date(iso);
  if (d.getTime() === 0) return '-';
  const diff = Date.now() - d.getTime();
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return d.toLocaleString();
}

export default function TriggersWebhook() {
  const [triggers, setTriggers] = useState<TriggerView[]>([]);
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [editor, setEditor] = useState<EditorState>(emptyEditor());

  const load = () => {
    setLoading(true);
    Promise.all([api.triggers.list('WEBHOOK'), api.agents.list()])
      .then(([triggersRes, agentRes]) => {
        setTriggers(triggersRes.triggers || []);
        setAgents(agentRes.agents || []);
      })
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const agentMap: Record<string, AgentDefinition> = {};
  for (const a of agents) agentMap[a.id] = a;

  const copyUrl = async (trigger: TriggerView) => {
    if (trigger.webhook_url) {
      try {
        await navigator.clipboard.writeText(trigger.webhook_url);
        setCopiedId(trigger.id);
        setTimeout(() => setCopiedId(null), 2000);
      } catch {
        // fallback
      }
    }
  };

  const openNew = () => {
    const defaultAgentId = agents[0]?.id ?? '';
    setEditor({
      ...emptyEditor(),
      open: true,
      agentId: defaultAgentId,
    });
  };

  const openEdit = (t: TriggerView) => {
    const agentId = t.action_config?.agent_id ?? '';
    const cfg = t.config ?? {};
    const verifierType = cfg.verifier_type === 'slack' ? 'slack' : cfg.secret ? 'bearer' : 'none';
    setEditor({
      open: true,
      editing: t,
      name: t.name,
      description: t.description || '',
      agentId,
      inputTemplate: t.action_config?.input_template ?? '',
      verifierType,
      secret: cfg.secret ?? '',
      slackSigningSecret: cfg.slack_signing_secret ?? '',
      filterEventTypes: t.action_config?.filter_event_types ?? '',
      filterIgnoreSubtypes: t.action_config?.filter_ignore_subtypes ?? '',
      filterChannels: t.action_config?.filter_channels ?? '',
    });
  };

  const close = () => setEditor(prev => ({ ...prev, open: false }));

  const save = async () => {
    if (!editor.name.trim()) {
      alert('Name is required');
      return;
    }
    if (!editor.agentId) {
      alert('Please select an agent');
      return;
    }
    try {
      const actionConfig: Record<string, string> = { agent_id: editor.agentId };
      if (editor.inputTemplate.trim()) {
        actionConfig.input_template = editor.inputTemplate.trim();
      }
      if (editor.filterEventTypes.trim()) {
        actionConfig.filter_event_types = editor.filterEventTypes.trim();
      }
      if (editor.filterIgnoreSubtypes.trim()) {
        actionConfig.filter_ignore_subtypes = editor.filterIgnoreSubtypes.trim();
      }
      if (editor.filterChannels.trim()) {
        actionConfig.filter_channels = editor.filterChannels.trim();
      }

      const config: Record<string, string> = { verifier_type: editor.verifierType };
      if (editor.verifierType === 'bearer') {
        if (editor.secret.trim()) {
          config.secret = editor.secret.trim();
        }
      } else if (editor.verifierType === 'slack') {
        if (editor.slackSigningSecret.trim()) {
          config.slack_signing_secret = editor.slackSigningSecret.trim();
        }
      }
      // When verifierType is 'none', we still need to clear any existing secret/slack config on update
      // ... handled by sending verifier_type + empty values

      if (editor.editing) {
        await api.triggers.update(editor.editing.id, {
          name: editor.name.trim(),
          description: editor.description.trim() || undefined,
          action_config: actionConfig,
          config,
        });
      } else {
        await api.triggers.create({
          name: editor.name.trim(),
          description: editor.description.trim() || undefined,
          type: 'WEBHOOK',
          action_type: 'RUN_AGENT',
          action_config: actionConfig,
          config,
        });
      }
      close();
      load();
    } catch (e) {
      alert(`Save failed: ${e instanceof Error ? e.message : e}`);
    }
  };

  const toggleEnabled = async (t: TriggerView) => {
    if (t.enabled) {
      await api.triggers.disable(t.id);
    } else {
      await api.triggers.enable(t.id);
    }
    load();
  };

  const remove = async (t: TriggerView) => {
    if (!confirm(`Delete webhook "${t.name}"?`)) return;
    await api.triggers.delete(t.id);
    load();
  };

  const rotateSecret = async (t: TriggerView) => {
    if (!confirm(`Rotate secret for "${t.name}"? The current webhook URL will stop working until you update the endpoint secret.`)) return;
    await api.triggers.rotateSecret(t.id);
    load();
  };

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">Webhook Triggers</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Create webhook endpoints for external platforms to trigger agent runs
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={openNew} disabled={agents.length === 0}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
            style={{ background: 'var(--color-primary)' }}>
            <Plus size={16} /> New Webhook
          </button>
        </div>
      </div>

      <div className="rounded-xl border overflow-hidden"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        {loading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : triggers.length === 0 ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>
            No webhook triggers yet. Create one to get started.
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                <th className="text-left px-4 py-3 font-medium">Name</th>
                <th className="text-left px-4 py-3 font-medium">Agent</th>
                <th className="text-left px-4 py-3 font-medium">Webhook URL</th>
                <th className="text-left px-4 py-3 font-medium">Last Triggered</th>
                <th className="text-left px-4 py-3 font-medium">Enabled</th>
                <th className="text-right px-4 py-3 font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {triggers.map(t => (
                <tr key={t.id} style={{ borderTop: '1px solid var(--color-border)' }}>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <Webhook size={14} style={{ color: 'var(--color-primary)' }} />
                      <span className="font-medium">{t.name}</span>
                    </div>
                    {t.description && (
                      <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>{t.description}</div>
                    )}
                  </td>
                  <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>
                    {t.action_config?.agent_id ? (agentMap[t.action_config.agent_id]?.name ?? t.action_config.agent_id) : '-'}
                  </td>
                  <td className="px-4 py-3">
                    {t.webhook_url ? (
                      <div className="flex items-center gap-1">
                        <span className="text-xs font-mono truncate max-w-[220px]" title={t.webhook_url}>
                          {t.webhook_url}
                        </span>
                        <button onClick={() => copyUrl(t)}
                          className="inline-flex items-center justify-center w-6 h-6 rounded cursor-pointer"
                          style={{ color: 'var(--color-text-secondary)' }}
                          title="Copy URL">
                          {copiedId === t.id ? <Check size={12} /> : <Copy size={12} />}
                        </button>
                      </div>
                    ) : (
                      <span style={{ color: 'var(--color-text-secondary)' }}>-</span>
                    )}
                  </td>
                  <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>
                    {formatTime(t.last_triggered_at)}
                  </td>
                  <td className="px-4 py-3">
                    <button onClick={() => toggleEnabled(t)}
                      className="relative inline-flex items-center w-10 h-5 rounded-full cursor-pointer transition-colors"
                      style={{ background: t.enabled ? 'var(--color-primary)' : 'var(--color-bg-tertiary)' }}>
                      <span className="absolute w-4 h-4 bg-white rounded-full transition-transform"
                        style={{ transform: t.enabled ? 'translateX(22px)' : 'translateX(2px)' }} />
                    </button>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <button onClick={() => rotateSecret(t)}
                      className="inline-flex items-center justify-center w-8 h-8 rounded-lg border cursor-pointer mr-1"
                      style={{ borderColor: 'var(--color-border)' }} title="Rotate Secret">
                      <RefreshCw size={14} />
                    </button>
                    <button onClick={() => openEdit(t)}
                      className="inline-flex items-center justify-center w-8 h-8 rounded-lg border cursor-pointer mr-1"
                      style={{ borderColor: 'var(--color-border)' }} title="Edit">
                      <Edit2 size={14} />
                    </button>
                    <button onClick={() => remove(t)}
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
              <h2 className="text-lg font-semibold">{editor.editing ? 'Edit Webhook' : 'New Webhook'}</h2>
              <button onClick={close} className="p-1 rounded cursor-pointer"><X size={18} /></button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Name</label>
                <input value={editor.name}
                  onChange={e => setEditor({ ...editor, name: e.target.value })}
                  placeholder="e.g. Slack Notifier"
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
              </div>

              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Description</label>
                <input value={editor.description}
                  onChange={e => setEditor({ ...editor, description: e.target.value })}
                  placeholder="Optional description"
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
              </div>

              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Action: Run Agent</label>
                <select value={editor.agentId}
                  onChange={e => setEditor({ ...editor, agentId: e.target.value })}
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                  <option value="">Select agent</option>
                  {agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
                </select>
              </div>

              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Input Template</label>
                <textarea value={editor.inputTemplate}
                  onChange={e => setEditor({ ...editor, inputTemplate: e.target.value })}
                  rows={3}
                  placeholder="Leave empty to forward raw request body. Use {{payload}} to inject request body into template."
                  className="w-full px-3 py-2 rounded-lg border text-sm font-mono"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
              </div>

              <div className="pt-2 pb-1">
                <div className="text-xs font-semibold uppercase tracking-wider" style={{ color: 'var(--color-text-secondary)' }}>
                  Event Filters
                </div>
                <p className="text-xs mt-1 mb-2" style={{ color: 'var(--color-text-secondary)' }}>
                  Optional: skip events that don't match. Leave empty to process all events.
                  For Slack events, the filter checks fields inside the <code className="text-[11px] px-1 py-0.5 rounded" style={{ background: 'var(--color-bg-tertiary)' }}>event</code> object.
                </p>
              </div>

              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Allowed Event Types
                </label>
                <input value={editor.filterEventTypes}
                  onChange={e => setEditor({ ...editor, filterEventTypes: e.target.value })}
                  placeholder="e.g. message (comma-separated, leave empty for all)"
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Only process these Slack event types. Common: <code className="text-[11px]">message</code>, <code className="text-[11px]">app_mention</code>.
                </p>
              </div>

              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Ignore Subtypes
                </label>
                <input value={editor.filterIgnoreSubtypes}
                  onChange={e => setEditor({ ...editor, filterIgnoreSubtypes: e.target.value })}
                  placeholder="e.g. bot_message, channel_join"
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Skip events with these subtypes. Common: <code className="text-[11px]">bot_message</code> (ignore bot messages), <code className="text-[11px]">channel_join</code>.
                </p>
              </div>

              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Allowed Channel IDs
                </label>
                <input value={editor.filterChannels}
                  onChange={e => setEditor({ ...editor, filterChannels: e.target.value })}
                  placeholder="e.g. C123ABC, C456DEF (comma-separated)"
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Only process events from these Slack channel IDs. Leave empty for all channels.
                </p>
              </div>

              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Verification Method
                </label>
                <select value={editor.verifierType}
                  onChange={e => setEditor({ ...editor, verifierType: e.target.value as EditorState['verifierType'] })}
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                  <option value="none">None (No Auth)</option>
                  <option value="bearer">Bearer Token</option>
                  <option value="slack">Slack (HMAC-SHA256)</option>
                </select>
              </div>

              {editor.verifierType === 'bearer' && (
                <div>
                  <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                    Secret (Bearer Token)
                  </label>
                  <input value={editor.secret}
                    onChange={e => setEditor({ ...editor, secret: e.target.value })}
                    placeholder="Leave empty for auto-generated on create"
                    className="w-full px-3 py-2 rounded-lg border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                  <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                    Custom Bearer token. If empty, a random token is generated on save.
                  </p>
                </div>
              )}

              {editor.verifierType === 'slack' && (
                <div>
                  <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                    Slack Signing Secret
                  </label>
                  <input value={editor.slackSigningSecret}
                    onChange={e => setEditor({ ...editor, slackSigningSecret: e.target.value })}
                    placeholder="Paste your Slack app signing secret"
                    className="w-full px-3 py-2 rounded-lg border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                  <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                    Found in Slack API dashboard under "App Credentials" → "Signing Secret".
                  </p>
                </div>
              )}

              {editor.editing && ((editing) => {
                const webhookUrl = editing.webhook_url;
                return (
                <div className="space-y-3 px-4 py-3 rounded-lg text-xs" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                  <div>
                    <div className="font-medium mb-1">Webhook URL</div>
                    <div className="font-mono break-all flex items-center gap-1">
                      {webhookUrl || '-'}
                      {webhookUrl && (
                        <button onClick={() => {
                          navigator.clipboard.writeText(webhookUrl);
                        }}
                          className="inline-flex items-center justify-center w-5 h-5 rounded shrink-0 cursor-pointer"
                          style={{ color: 'var(--color-text-secondary)' }}>
                          <Copy size={11} />
                        </button>
                      )}
                    </div>
                  </div>
                  {editing.config && (() => {
                    const config = editing.config;
                    const vt = config.verifier_type;
                    if (!vt || vt === 'none') {
                      return <div className="font-medium">Auth: None</div>;
                    }
                    if (vt === 'slack') {
                      const ss = config.slack_signing_secret;
                      return (
                        <div>
                          <div className="font-medium mb-1">Auth: Slack (HMAC-SHA256)</div>
                          {ss && (
                            <div className="font-mono flex items-center gap-1">
                              {ss.substring(0, 8)}...{ss.substring(ss.length - 4)}
                              <button onClick={() => {
                                navigator.clipboard.writeText(ss);
                              }}
                                className="inline-flex items-center justify-center w-5 h-5 rounded shrink-0 cursor-pointer"
                                style={{ color: 'var(--color-text-secondary)' }}>
                                <Copy size={11} />
                              </button>
                            </div>
                          )}
                        </div>
                      );
                    }
                    // bearer
                    const secret = config.secret;
                    return (
                      <div>
                        <div className="font-medium mb-1">Auth: Bearer Token</div>
                        {secret && (
                          <>
                            <div className="font-mono break-all flex items-center gap-1"
                              style={{ color: 'var(--color-primary)' }}>
                              {secret}
                              <button onClick={() => {
                                navigator.clipboard.writeText(secret);
                              }}
                                className="inline-flex items-center justify-center w-5 h-5 rounded shrink-0 cursor-pointer"
                                style={{ color: 'var(--color-text-secondary)' }}>
                                <Copy size={11} />
                              </button>
                            </div>
                          </>
                        )}
                      </div>
                    );
                  })()}
                </div>
                );
              })(editor.editing)}
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
