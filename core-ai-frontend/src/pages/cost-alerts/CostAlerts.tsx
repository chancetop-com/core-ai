import { useEffect, useState } from 'react';
import { Plus, Edit2, Trash2, X, Bell, Radio, Copy, Check } from 'lucide-react';
import { api } from '../../api/client';
import type { CostAlertRuleView, CostAlertEventView, CostAlertTarget, ChannelView } from '../../api/client';

interface TargetRow {
  type: 'notification' | 'channel';
  userId: string;
  channelId: string;
  recipient: string;
}

interface EditorState {
  open: boolean;
  editing: CostAlertRuleView | null;
  name: string;
  metric: string;
  scope: string;
  scopeValue: string;
  threshold: string;
  targets: TargetRow[];
}

const METRIC_LABELS: Record<string, string> = {
  COST_USD: 'Cost (USD)',
  TOTAL_TOKENS: 'Total Tokens',
  CALL_COUNT: 'Call Count',
};

const SCOPE_LABELS: Record<string, string> = {
  GLOBAL: 'Global',
  USER: 'User',
  AGENT: 'Agent',
};

function emptyEditor(): EditorState {
  return {
    open: false,
    editing: null,
    name: '',
    metric: 'COST_USD',
    scope: 'GLOBAL',
    scopeValue: '',
    threshold: '',
    targets: [],
  };
}

function parseTargets(json: string): TargetRow[] {
  try {
    const list = JSON.parse(json || '[]') as CostAlertTarget[];
    return list.map(t => ({
      type: t.type,
      userId: t.userId || '',
      channelId: t.channelId || '',
      recipient: t.recipient || '',
    }));
  } catch {
    return [];
  }
}

function targetsToJson(targets: TargetRow[]): CostAlertTarget[] {
  return targets.map(t => t.type === 'notification'
    ? { type: 'notification', userId: t.userId.trim() }
    : { type: 'channel', channelId: t.channelId.trim(), recipient: t.recipient.trim() });
}

export default function CostAlerts() {
  const [tab, setTab] = useState<'rules' | 'events'>('rules');
  const [rules, setRules] = useState<CostAlertRuleView[]>([]);
  const [events, setEvents] = useState<CostAlertEventView[]>([]);
  const [channels, setChannels] = useState<ChannelView[]>([]);
  const [loading, setLoading] = useState(true);
  const [editor, setEditor] = useState<EditorState>(emptyEditor());
  const [eventRuleFilter, setEventRuleFilter] = useState('');
  const [eventDateFrom, setEventDateFrom] = useState('');
  const [eventDateTo, setEventDateTo] = useState('');
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const loadRules = () => {
    setLoading(true);
    Promise.all([api.costAlerts.listRules(), api.channels.list()])
      .then(([rulesRes, channelsRes]) => {
        setRules(rulesRes.rules || []);
        setChannels(channelsRes.channels || []);
      })
      .finally(() => setLoading(false));
  };

  const loadEvents = () => {
    api.costAlerts.listEvents({
      ruleId: eventRuleFilter || undefined,
      dateFrom: eventDateFrom || undefined,
      dateTo: eventDateTo || undefined,
      limit: 200,
    })
      .then(res => setEvents(res.events || []));
  };

  useEffect(() => { loadRules(); }, []);
  useEffect(() => { if (tab === 'events') loadEvents(); }, [tab, eventRuleFilter, eventDateFrom, eventDateTo]);

  const openNew = () => setEditor({ ...emptyEditor(), open: true, targets: [{ type: 'notification', userId: '', channelId: '', recipient: '' }] });

  const openEdit = (r: CostAlertRuleView) => {
    const targets = parseTargets(r.targets);
    setEditor({
      open: true,
      editing: r,
      name: r.name,
      metric: r.metric,
      scope: r.scope,
      scopeValue: r.scope_value,
      threshold: String(r.threshold),
      targets: targets.length > 0 ? targets : [{ type: 'notification', userId: '', channelId: '', recipient: '' }],
    });
  };

  const close = () => setEditor(prev => ({ ...prev, open: false }));

  const save = async () => {
    if (!editor.name.trim()) { alert('Name is required'); return; }
    const threshold = Number(editor.threshold);
    if (!threshold || threshold <= 0) { alert('Threshold must be a positive number'); return; }
    if (editor.scope !== 'GLOBAL' && !editor.scopeValue.trim()) { alert('Scope value is required for user/agent scope'); return; }
    if (editor.targets.length === 0) { alert('At least one notification target is required'); return; }
    for (const t of editor.targets) {
      if (t.type === 'notification' && !t.userId.trim()) { alert('Notification target requires a user id'); return; }
      if (t.type === 'channel' && (!t.channelId.trim() || !t.recipient.trim())) { alert('Channel target requires channel id and recipient'); return; }
    }

    const data: Record<string, unknown> = {
      name: editor.name.trim(),
      metric: editor.metric,
      scope: editor.scope,
      scope_value: editor.scope === 'GLOBAL' ? '' : editor.scopeValue.trim(),
      threshold,
      targets: JSON.stringify(targetsToJson(editor.targets)),
    };

    try {
      if (editor.editing) {
        await api.costAlerts.updateRule(editor.editing.id, data);
      } else {
        await api.costAlerts.createRule(data);
      }
      close();
      loadRules();
    } catch (e) {
      alert(`Save failed: ${e instanceof Error ? e.message : e}`);
    }
  };

  const remove = async (r: CostAlertRuleView) => {
    if (!confirm(`Delete cost alert rule "${r.name}"?`)) return;
    await api.costAlerts.deleteRule(r.id);
    loadRules();
  };

  const toggleEnabled = async (r: CostAlertRuleView) => {
    await api.costAlerts.updateRule(r.id, {
      name: r.name,
      metric: r.metric,
      scope: r.scope,
      scope_value: r.scope_value,
      threshold: r.threshold,
      targets: r.targets,
      enabled: !r.enabled,
    });
    loadRules();
  };

  const updateTarget = (index: number, patch: Partial<TargetRow>) => {
    setEditor(prev => {
      const targets = prev.targets.map((t, i) => i === index ? { ...t, ...patch } : t);
      return { ...prev, targets };
    });
  };

  const formatValue = (metric: string, value: number): string => {
    if (metric === 'COST_USD') return `$${value.toFixed(2)}`;
    if (metric === 'TOTAL_TOKENS') return value >= 1_000_000 ? `${(value / 1_000_000).toFixed(1)}M` : value >= 1_000 ? `${(value / 1_000).toFixed(1)}K` : String(value);
    return String(value);
  };

  const formatDate = (iso: string): string => {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    return d.toLocaleString();
  };

  const scopeDisplay = (r: CostAlertRuleView): string => {
    if (r.scope === 'GLOBAL') return 'Global';
    return `${SCOPE_LABELS[r.scope] || r.scope}: ${r.scope_value}`;
  };

  const targetSummary = (r: CostAlertRuleView): string => {
    const targets = parseTargets(r.targets);
    return targets.map(t => t.type === 'notification' ? `User ${t.userId}` : `Channel ${t.channelId}`).join(', ');
  };

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">Cost Alerts</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Alert when daily token/cost usage exceeds thresholds, delivered to in-app users or channels
          </p>
        </div>
        <div className="flex items-center gap-3">
          <div className="flex rounded-lg border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
            <button onClick={() => setTab('rules')}
              className="px-4 py-2 text-sm font-medium cursor-pointer"
              style={{ background: tab === 'rules' ? 'var(--color-primary)' : 'var(--color-bg-tertiary)', color: tab === 'rules' ? '#fff' : 'var(--color-text-secondary)' }}>
              Rules
            </button>
            <button onClick={() => setTab('events')}
              className="px-4 py-2 text-sm font-medium cursor-pointer"
              style={{ background: tab === 'events' ? 'var(--color-primary)' : 'var(--color-bg-tertiary)', color: tab === 'events' ? '#fff' : 'var(--color-text-secondary)' }}>
              Alert History
            </button>
          </div>
          {tab === 'rules' && (
            <button onClick={openNew}
              className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
              style={{ background: 'var(--color-primary)' }}>
              <Plus size={16} /> New Rule
            </button>
          )}
        </div>
      </div>

      {tab === 'rules' ? (
        <div className="rounded-xl border overflow-hidden"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
          {loading ? (
            <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
          ) : rules.length === 0 ? (
            <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>
              <Bell size={32} className="mx-auto mb-3 opacity-30" />
              No cost alert rules yet. Create one to get notified on usage spikes.
            </div>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                  <th className="text-left px-4 py-3 font-medium">Name</th>
                  <th className="text-left px-4 py-3 font-medium">Metric</th>
                  <th className="text-left px-4 py-3 font-medium">Scope</th>
                  <th className="text-left px-4 py-3 font-medium">Threshold</th>
                  <th className="text-left px-4 py-3 font-medium">Targets</th>
                  <th className="text-left px-4 py-3 font-medium">Enabled</th>
                  <th className="text-right px-4 py-3 font-medium">Actions</th>
                </tr>
              </thead>
              <tbody>
                {rules.map(r => (
                  <tr key={r.id} style={{ borderTop: '1px solid var(--color-border)' }}>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <Bell size={14} style={{ color: 'var(--color-primary)' }} />
                        <span className="font-medium">{r.name}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <span className="inline-block px-2 py-0.5 rounded text-xs font-medium"
                        style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}>
                        {METRIC_LABELS[r.metric] || r.metric}
                      </span>
                    </td>
                    <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{scopeDisplay(r)}</td>
                    <td className="px-4 py-3 font-medium">{formatValue(r.metric, r.threshold)}</td>
                    <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>
                      <div className="flex items-center gap-1 max-w-xs">
                        <span className="truncate block">{targetSummary(r)}</span>
                        <button
                          onClick={() => { navigator.clipboard.writeText(r.targets); setCopiedId(r.id); setTimeout(() => setCopiedId(null), 2000); }}
                          className="inline-flex items-center justify-center w-6 h-6 rounded cursor-pointer shrink-0"
                          style={{ color: copiedId === r.id ? '#16a34a' : 'var(--color-text-secondary)' }}
                          title="Copy targets JSON">
                          {copiedId === r.id ? <Check size={12} /> : <Copy size={12} />}
                        </button>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <button onClick={() => toggleEnabled(r)}
                        className="relative inline-flex items-center w-10 h-5 rounded-full cursor-pointer transition-colors"
                        style={{ background: r.enabled ? 'var(--color-primary)' : 'var(--color-bg-tertiary)' }}>
                        <span className="absolute w-4 h-4 bg-white rounded-full transition-transform"
                          style={{ transform: r.enabled ? 'translateX(22px)' : 'translateX(2px)' }} />
                      </button>
                    </td>
                    <td className="px-4 py-3 text-right">
                      <button onClick={() => openEdit(r)}
                        className="inline-flex items-center justify-center w-8 h-8 rounded-lg border cursor-pointer mr-1"
                        style={{ borderColor: 'var(--color-border)' }} title="Edit">
                        <Edit2 size={14} />
                      </button>
                      <button onClick={() => remove(r)}
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
      ) : (
        <div>
          <div className="flex items-center gap-3 mb-4 flex-wrap">
            <input value={eventRuleFilter} onChange={e => setEventRuleFilter(e.target.value)} placeholder="Filter by rule id"
              className="px-3 py-2 rounded-lg border text-sm"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
            <input type="date" value={eventDateFrom} onChange={e => setEventDateFrom(e.target.value)}
              className="px-3 py-2 rounded-lg border text-sm"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>to</span>
            <input type="date" value={eventDateTo} onChange={e => setEventDateTo(e.target.value)}
              className="px-3 py-2 rounded-lg border text-sm"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
          </div>
          <div className="rounded-xl border overflow-hidden"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            {events.length === 0 ? (
              <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>
                <Radio size={32} className="mx-auto mb-3 opacity-30" />
                No alerts fired yet.
              </div>
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                    <th className="text-left px-4 py-3 font-medium">Time</th>
                    <th className="text-left px-4 py-3 font-medium">Date</th>
                    <th className="text-left px-4 py-3 font-medium">Rule</th>
                    <th className="text-left px-4 py-3 font-medium">Scope</th>
                    <th className="text-left px-4 py-3 font-medium">Metric</th>
                    <th className="text-left px-4 py-3 font-medium">Actual</th>
                    <th className="text-left px-4 py-3 font-medium">Threshold</th>
                    <th className="text-left px-4 py-3 font-medium">Status</th>
                  </tr>
                </thead>
                <tbody>
                  {events.map(e => (
                    <tr key={e.id} style={{ borderTop: '1px solid var(--color-border)' }}>
                      <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{formatDate(e.created_at)}</td>
                      <td className="px-4 py-3">{String(e.date).slice(0, 10)}</td>
                      <td className="px-4 py-3 font-medium">{e.rule_name}</td>
                      <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>
                        {e.scope === 'GLOBAL' ? 'Global' : `${e.scope}: ${e.scope_value}`}
                      </td>
                      <td className="px-4 py-3">{METRIC_LABELS[e.metric] || e.metric}</td>
                      <td className="px-4 py-3 font-medium">{formatValue(e.metric, e.actual_value)}</td>
                      <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{formatValue(e.metric, e.threshold)}</td>
                      <td className="px-4 py-3">
                        <span className="inline-block px-2 py-0.5 rounded text-xs font-medium"
                          style={{ background: '#e6f4ea', color: '#1a7f37' }}>
                          {e.status}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      )}

      {editor.open && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50"
          onClick={close}>
          <div className="w-full max-w-xl rounded-xl p-6 max-h-[85vh] overflow-y-auto"
            style={{ background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)' }}
            onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-lg font-semibold">{editor.editing ? 'Edit Rule' : 'New Rule'}</h2>
              <button onClick={close} className="cursor-pointer" style={{ color: 'var(--color-text-secondary)' }}>
                <X size={18} />
              </button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium mb-1">Name</label>
                <input value={editor.name} onChange={e => setEditor({ ...editor, name: e.target.value })}
                  placeholder="e.g. Daily cost budget"
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium mb-1">Metric</label>
                  <select value={editor.metric} onChange={e => setEditor({ ...editor, metric: e.target.value })}
                    className="w-full px-3 py-2 rounded-lg border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                    {Object.entries(METRIC_LABELS).map(([value, label]) => (
                      <option key={value} value={value}>{label}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-sm font-medium mb-1">Threshold</label>
                  <input value={editor.threshold} onChange={e => setEditor({ ...editor, threshold: e.target.value })}
                    placeholder={editor.metric === 'COST_USD' ? 'e.g. 50' : 'e.g. 1000000'}
                    className="w-full px-3 py-2 rounded-lg border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium mb-1">Scope</label>
                  <select value={editor.scope} onChange={e => setEditor({ ...editor, scope: e.target.value })}
                    className="w-full px-3 py-2 rounded-lg border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                    {Object.entries(SCOPE_LABELS).map(([value, label]) => (
                      <option key={value} value={value}>{label}</option>
                    ))}
                  </select>
                </div>
                {editor.scope !== 'GLOBAL' && (
                  <div>
                    <label className="block text-sm font-medium mb-1">
                      {editor.scope === 'USER' ? 'User ID' : 'Agent ID'}
                    </label>
                    <input value={editor.scopeValue} onChange={e => setEditor({ ...editor, scopeValue: e.target.value })}
                      className="w-full px-3 py-2 rounded-lg border text-sm"
                      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                  </div>
                )}
              </div>

              <div>
                <label className="block text-sm font-medium mb-1">Notification Targets</label>
                <div className="space-y-2">
                  {editor.targets.map((t, i) => (
                    <div key={i} className="flex items-center gap-2">
                      <select value={t.type} onChange={e => updateTarget(i, { type: e.target.value as TargetRow['type'] })}
                        className="px-2 py-2 rounded-lg border text-sm shrink-0"
                        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                        <option value="notification">In-app User</option>
                        <option value="channel">Channel</option>
                      </select>
                      {t.type === 'notification' ? (
                        <input value={t.userId} onChange={e => updateTarget(i, { userId: e.target.value })}
                          placeholder="User ID (admin user)"
                          className="flex-1 px-3 py-2 rounded-lg border text-sm"
                          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                      ) : (
                        <>
                          <select value={t.channelId} onChange={e => updateTarget(i, { channelId: e.target.value })}
                            className="flex-1 px-3 py-2 rounded-lg border text-sm"
                            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                            <option value="">Select channel</option>
                            {channels.filter(c => c.enabled !== false && c.channelType === 'slack').map(c => (
                              <option key={c.channelId} value={c.channelId}>{c.channelId} ({c.channelType})</option>
                            ))}
                            {t.channelId && !channels.some(c => c.channelId === t.channelId) && (
                              <option value={t.channelId}>{t.channelId} (removed)</option>
                            )}
                          </select>
                          <input value={t.recipient} onChange={e => updateTarget(i, { recipient: e.target.value })}
                            placeholder="Recipient (Slack channel id, e.g. C123...)"
                            className="flex-1 px-3 py-2 rounded-lg border text-sm"
                            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                        </>
                      )}
                      <button onClick={() => setEditor(prev => ({ ...prev, targets: prev.targets.filter((_, j) => j !== i) }))}
                        className="shrink-0 cursor-pointer" style={{ color: '#e5484d' }} title="Remove target">
                        <Trash2 size={14} />
                      </button>
                    </div>
                  ))}
                  <button onClick={() => setEditor(prev => ({ ...prev, targets: [...prev.targets, { type: 'notification', userId: '', channelId: '', recipient: '' }] }))}
                    className="flex items-center gap-1 text-sm cursor-pointer" style={{ color: 'var(--color-primary)' }}>
                    <Plus size={14} /> Add target
                  </button>
                </div>
                <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Channel targets only support Slack channels (configured under Triggers → Channels). Recipient = the Slack channel id (C...).
                </p>
              </div>

              <div className="flex justify-end gap-2 pt-2">
                <button onClick={close}
                  className="px-4 py-2 rounded-lg text-sm cursor-pointer"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
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
        </div>
      )}
    </div>
  );
}
