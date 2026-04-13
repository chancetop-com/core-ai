import { useEffect, useMemo, useState } from 'react';
import { Plus, Calendar, Edit2, Trash2, X } from 'lucide-react';
import { api } from '../../api/client';
import type { AgentDefinition, AgentScheduleView, CreateScheduleRequest, UpdateScheduleRequest } from '../../api/client';
import CronEditor from './CronEditor';

type ConcurrencyPolicy = 'SKIP' | 'ALLOW' | 'REPLACE';

interface EditorState {
  open: boolean;
  editing: AgentScheduleView | null;
  agentId: string;
  cronExpression: string;
  timezone: string;
  input: string;
  concurrencyPolicy: ConcurrencyPolicy;
  enabled: boolean;
}

const TIMEZONES = ['UTC', 'Asia/Shanghai', 'Asia/Tokyo', 'America/New_York', 'America/Los_Angeles', 'Europe/London'];

function emptyEditor(): EditorState {
  return {
    open: false,
    editing: null,
    agentId: '',
    cronExpression: '0 * * * *',
    timezone: 'Asia/Shanghai',
    input: '',
    concurrencyPolicy: 'SKIP',
    enabled: true,
  };
}

function formatTime(iso: string) {
  if (!iso) return '-';
  const d = new Date(iso);
  const diff = d.getTime() - Date.now();
  const abs = Math.abs(diff);
  const future = diff > 0;
  if (abs < 60_000) return future ? 'in <1m' : 'just now';
  if (abs < 3_600_000) return `${future ? 'in ' : ''}${Math.floor(abs / 60_000)}m${future ? '' : ' ago'}`;
  if (abs < 86_400_000) return `${future ? 'in ' : ''}${Math.floor(abs / 3_600_000)}h${future ? '' : ' ago'}`;
  return d.toLocaleString();
}

export default function Scheduler() {
  const [schedules, setSchedules] = useState<AgentScheduleView[]>([]);
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [filterAgentId, setFilterAgentId] = useState<string>('');
  const [filterEnabled, setFilterEnabled] = useState<'all' | 'enabled' | 'disabled'>('all');
  const [editor, setEditor] = useState<EditorState>(emptyEditor());

  const load = () => {
    setLoading(true);
    Promise.all([api.schedules.list(), api.agents.list()])
      .then(([scheduleRes, agentRes]) => {
        setSchedules(scheduleRes.schedules || []);
        setAgents(agentRes.agents || []);
      })
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const agentMap = useMemo(() => {
    const m: Record<string, AgentDefinition> = {};
    for (const a of agents) m[a.id] = a;
    return m;
  }, [agents]);

  const filtered = schedules.filter(s => {
    if (filterAgentId && s.agent_id !== filterAgentId) return false;
    if (filterEnabled === 'enabled' && !s.enabled) return false;
    if (filterEnabled === 'disabled' && s.enabled) return false;
    return true;
  });

  const openNew = () => setEditor({ ...emptyEditor(), open: true, agentId: agents[0]?.id ?? '' });

  const openEdit = (s: AgentScheduleView) => setEditor({
    open: true,
    editing: s,
    agentId: s.agent_id,
    cronExpression: s.cron_expression,
    timezone: s.timezone || 'UTC',
    input: s.input || '',
    concurrencyPolicy: (s.concurrency_policy as ConcurrencyPolicy) || 'SKIP',
    enabled: s.enabled,
  });

  const close = () => setEditor(prev => ({ ...prev, open: false }));

  const save = async () => {
    if (!editor.agentId || !editor.cronExpression.trim()) {
      alert('Agent and cron expression are required');
      return;
    }
    try {
      if (editor.editing) {
        const payload: UpdateScheduleRequest = {
          cron_expression: editor.cronExpression,
          timezone: editor.timezone,
          input: editor.input,
          concurrency_policy: editor.concurrencyPolicy,
          enabled: editor.enabled,
        };
        await api.schedules.update(editor.editing.id, payload);
      } else {
        const payload: CreateScheduleRequest = {
          agent_id: editor.agentId,
          cron_expression: editor.cronExpression,
          timezone: editor.timezone,
          input: editor.input,
          concurrency_policy: editor.concurrencyPolicy,
        };
        await api.schedules.create(payload);
      }
      close();
      load();
    } catch (e) {
      alert(`Save failed: ${e instanceof Error ? e.message : e}`);
    }
  };

  const toggleEnabled = async (s: AgentScheduleView) => {
    await api.schedules.update(s.id, { enabled: !s.enabled });
    load();
  };

  const remove = async (s: AgentScheduleView) => {
    if (!confirm(`Delete schedule for "${agentMap[s.agent_id]?.name ?? s.agent_id}"?`)) return;
    await api.schedules.delete(s.id);
    load();
  };

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">Scheduler</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Schedule agents to run on a cron expression
          </p>
        </div>
        <div className="flex items-center gap-2">
          <select value={filterAgentId} onChange={e => setFilterAgentId(e.target.value)}
            className="px-3 py-2 rounded-lg border text-sm"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
            <option value="">All agents</option>
            {agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
          <select value={filterEnabled} onChange={e => setFilterEnabled(e.target.value as 'all' | 'enabled' | 'disabled')}
            className="px-3 py-2 rounded-lg border text-sm"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
            <option value="all">All status</option>
            <option value="enabled">Enabled</option>
            <option value="disabled">Disabled</option>
          </select>
          <button onClick={openNew} disabled={agents.length === 0}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
            style={{ background: 'var(--color-primary)' }}>
            <Plus size={16} /> New Schedule
          </button>
        </div>
      </div>

      <div className="rounded-xl border overflow-hidden"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        {loading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : filtered.length === 0 ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>
            {schedules.length === 0 ? 'No schedules yet. Create one to get started.' : 'No schedules match the current filter.'}
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                <th className="text-left px-4 py-3 font-medium">Agent</th>
                <th className="text-left px-4 py-3 font-medium">Cron</th>
                <th className="text-left px-4 py-3 font-medium">Timezone</th>
                <th className="text-left px-4 py-3 font-medium">Policy</th>
                <th className="text-left px-4 py-3 font-medium">Next Run</th>
                <th className="text-left px-4 py-3 font-medium">Enabled</th>
                <th className="text-right px-4 py-3 font-medium">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(s => (
                <tr key={s.id} style={{ borderTop: '1px solid var(--color-border)' }}>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <Calendar size={14} style={{ color: 'var(--color-primary)' }} />
                      <span className="font-medium">{agentMap[s.agent_id]?.name ?? s.agent_id}</span>
                    </div>
                  </td>
                  <td className="px-4 py-3 font-mono text-xs">{s.cron_expression}</td>
                  <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{s.timezone}</td>
                  <td className="px-4 py-3">
                    <span className="px-2 py-0.5 rounded text-xs"
                      style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                      {s.concurrency_policy}
                    </span>
                  </td>
                  <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>{formatTime(s.next_run_at)}</td>
                  <td className="px-4 py-3">
                    <button onClick={() => toggleEnabled(s)}
                      className="relative inline-flex items-center w-10 h-5 rounded-full cursor-pointer transition-colors"
                      style={{ background: s.enabled ? 'var(--color-primary)' : 'var(--color-bg-tertiary)' }}>
                      <span className="absolute w-4 h-4 bg-white rounded-full transition-transform"
                        style={{ transform: s.enabled ? 'translateX(22px)' : 'translateX(2px)' }} />
                    </button>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <button onClick={() => openEdit(s)}
                      className="inline-flex items-center justify-center w-8 h-8 rounded-lg border cursor-pointer mr-1"
                      style={{ borderColor: 'var(--color-border)' }} title="Edit">
                      <Edit2 size={14} />
                    </button>
                    <button onClick={() => remove(s)}
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
              <h2 className="text-lg font-semibold">{editor.editing ? 'Edit Schedule' : 'New Schedule'}</h2>
              <button onClick={close} className="p-1 rounded cursor-pointer"><X size={18} /></button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Agent</label>
                <select value={editor.agentId} disabled={!!editor.editing}
                  onChange={e => setEditor({ ...editor, agentId: e.target.value })}
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                  <option value="">Select agent</option>
                  {agents.map(a => <option key={a.id} value={a.id}>{a.name}</option>)}
                </select>
              </div>

              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Schedule</label>
                <CronEditor value={editor.cronExpression}
                  onChange={cron => setEditor({ ...editor, cronExpression: cron })} />
              </div>

              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Timezone</label>
                <select value={editor.timezone}
                  onChange={e => setEditor({ ...editor, timezone: e.target.value })}
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                  {TIMEZONES.map(tz => <option key={tz} value={tz}>{tz}</option>)}
                </select>
              </div>

              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Concurrency Policy</label>
                <div className="flex gap-2">
                  {(['SKIP', 'ALLOW', 'REPLACE'] as ConcurrencyPolicy[]).map(p => (
                    <button key={p} type="button"
                      onClick={() => setEditor({ ...editor, concurrencyPolicy: p })}
                      className="flex-1 px-3 py-2 rounded-lg border text-xs cursor-pointer"
                      style={{
                        borderColor: editor.concurrencyPolicy === p ? 'var(--color-primary)' : 'var(--color-border)',
                        background: editor.concurrencyPolicy === p ? 'var(--color-primary)' : 'transparent',
                        color: editor.concurrencyPolicy === p ? '#fff' : 'var(--color-text)',
                      }}>
                      {p}
                    </button>
                  ))}
                </div>
                <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  SKIP: drop tick if previous still running. ALLOW: run in parallel. REPLACE: cancel previous.
                </p>
              </div>

              <div>
                <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Input</label>
                <textarea value={editor.input}
                  onChange={e => setEditor({ ...editor, input: e.target.value })}
                  rows={4} placeholder="Input message passed to the agent on each run"
                  className="w-full px-3 py-2 rounded-lg border text-sm font-mono"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
              </div>

              {editor.editing && (
                <div className="flex items-center justify-between">
                  <label className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Enabled</label>
                  <button type="button" onClick={() => setEditor({ ...editor, enabled: !editor.enabled })}
                    className="relative inline-flex items-center w-10 h-5 rounded-full cursor-pointer"
                    style={{ background: editor.enabled ? 'var(--color-primary)' : 'var(--color-bg-tertiary)' }}>
                    <span className="absolute w-4 h-4 bg-white rounded-full transition-transform"
                      style={{ transform: editor.enabled ? 'translateX(22px)' : 'translateX(2px)' }} />
                  </button>
                </div>
              )}
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
