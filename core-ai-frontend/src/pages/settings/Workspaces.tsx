import { Fragment, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { AlertTriangle, ChevronDown, ChevronRight, HardDrive, Moon, Play, Plus, RefreshCw, RotateCcw, Trash2, Undo2, X } from 'lucide-react';
import { api, type WorkspaceEventView, type WorkspaceView } from '../../api/client';

type Tone = 'ok' | 'info' | 'warn' | 'error' | 'muted';
type DialogKind = 'reset' | 'delete';

interface PendingAction {
  kind: DialogKind;
  workspace: WorkspaceView;
}

const IDLE_OPTIONS = [5, 15, 30, 60, 120];
const SIZE_OPTIONS = [8, 16, 32, 64, 96];
const PROFILE_OPTIONS = [
  { id: 'workspace-small', label: 'Small (2 GiB / 1 CPU)' },
  { id: 'workspace-default', label: 'Default (4 GiB / 2 CPU)' },
  { id: 'workspace-build', label: 'Build (8 GiB / 4 CPU)' },
];
const PURGE_AFTER_DAYS = 7;
const MAX_WORKSPACES_PER_USER = 3;
const MAX_TOTAL_GB_PER_USER = 96;

const TONE_STYLES: Record<Tone, { bg: string; text: string }> = {
  ok: { bg: '#dcfce7', text: '#16a34a' },
  info: { bg: '#dbeafe', text: '#2563eb' },
  warn: { bg: '#fef3c7', text: '#b45309' },
  error: { bg: '#fee2e2', text: '#dc2626' },
  muted: { bg: '#f1f5f9', text: '#64748b' },
};

function workspaceStatus(workspace: WorkspaceView): { label: string; tone: Tone } {
  if (workspace.volume_status === 'ERROR') return { label: 'Volume error', tone: 'error' };
  if (workspace.volume_status === 'PROVISIONING') return { label: 'Preparing', tone: 'warn' };
  if (workspace.runtime_status === 'READY') return { label: 'Running', tone: 'ok' };
  if (workspace.runtime_status === 'PROVISIONING') return { label: 'Waking', tone: 'info' };
  if (workspace.runtime_status === 'ERROR') return { label: 'Wake error', tone: 'error' };
  return { label: 'Sleeping', tone: 'muted' };
}

function relativeTime(iso?: string): string {
  if (!iso) return 'never';
  const diff = Date.now() - new Date(iso).getTime();
  if (!Number.isFinite(diff) || diff < 0) return 'just now';
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

function purgeDaysLeft(deletedAt: string): number {
  const elapsed = (Date.now() - new Date(deletedAt).getTime()) / 86_400_000;
  return Math.max(0, Math.ceil(PURGE_AFTER_DAYS - elapsed));
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Workspace request failed.';
}

function workspaceDisabled(err: unknown): boolean {
  return typeof err === 'object' && err !== null && (err as { errorCode?: string }).errorCode === 'WORKSPACE_UNSUPPORTED';
}

function payloadSummary(payload?: string): string {
  if (!payload) return '';
  try {
    const value = JSON.parse(payload) as Record<string, unknown>;
    return Object.entries(value).map(([key, entry]) => `${key}=${String(entry)}`).join(' · ');
  } catch {
    return payload;
  }
}

function CreateWorkspaceForm({ onCreated, onCancel }: { onCreated: (workspace: WorkspaceView) => void; onCancel: () => void }) {
  const [name, setName] = useState('');
  const [sizeGb, setSizeGb] = useState(16);
  const [profile, setProfile] = useState('workspace-default');
  const [idleMinutes, setIdleMinutes] = useState(15);
  const [agentId, setAgentId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const trimmed = name.trim();
    if (!trimmed) {
      setError('Name is required.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const workspace = await api.workspaces.create({
        name: trimmed,
        agent_id: agentId.trim() || undefined,
        size_gb: sizeGb,
        profile,
        idle_sleep_minutes: idleMinutes,
      });
      onCreated(workspace);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} className="mb-4 rounded-xl border p-4"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)' }}>
      <div className="flex items-start justify-between gap-4">
        <div>
          <h3 className="text-sm font-semibold">New workspace</h3>
          <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Quota: {MAX_WORKSPACES_PER_USER} workspaces and {MAX_TOTAL_GB_PER_USER} GB total per user.
          </p>
        </div>
        <button type="button" onClick={onCancel} aria-label="Close" className="p-1 rounded cursor-pointer hover:opacity-70">
          <X size={16} />
        </button>
      </div>
      {error && (
        <div role="alert" className="mt-3 rounded-lg border px-3 py-2 text-sm flex items-center gap-2"
          style={{ borderColor: '#ef4444', background: 'rgba(239,68,68,0.08)', color: '#ef4444' }}>
          <AlertTriangle size={14} className="shrink-0" />
          {error}
        </div>
      )}
      <div className="mt-3 grid grid-cols-2 gap-3">
        <div className="col-span-2">
          <label htmlFor="workspace-new-name" className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
            Name
          </label>
          <input
            id="workspace-new-name"
            value={name}
            onChange={event => setName(event.target.value)}
            maxLength={64}
            autoFocus
            placeholder="my-workspace"
            className="w-full rounded-lg border px-3 py-2 text-sm outline-none"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}
          />
        </div>
        <div>
          <label htmlFor="workspace-new-size" className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
            Volume size
          </label>
          <select
            id="workspace-new-size"
            value={sizeGb}
            onChange={event => setSizeGb(Number(event.target.value))}
            className="w-full rounded-lg border px-3 py-2 text-sm outline-none"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
            {SIZE_OPTIONS.map(size => <option key={size} value={size}>{size} GB</option>)}
          </select>
        </div>
        <div>
          <label htmlFor="workspace-new-idle" className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
            Sleep after idle
          </label>
          <select
            id="workspace-new-idle"
            value={idleMinutes}
            onChange={event => setIdleMinutes(Number(event.target.value))}
            className="w-full rounded-lg border px-3 py-2 text-sm outline-none"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
            {IDLE_OPTIONS.map(minutes => <option key={minutes} value={minutes}>{minutes} min</option>)}
          </select>
        </div>
        <div>
          <label htmlFor="workspace-new-profile" className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
            Runtime profile
          </label>
          <select
            id="workspace-new-profile"
            value={profile}
            onChange={event => setProfile(event.target.value)}
            className="w-full rounded-lg border px-3 py-2 text-sm outline-none"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
            {PROFILE_OPTIONS.map(option => <option key={option.id} value={option.id}>{option.label}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor="workspace-new-agent" className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>
            Agent id (optional)
          </label>
          <input
            id="workspace-new-agent"
            value={agentId}
            onChange={event => setAgentId(event.target.value)}
            placeholder="binds as the agent's default workspace"
            className="w-full rounded-lg border px-3 py-2 text-sm outline-none"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}
          />
        </div>
      </div>
      <div className="mt-4 flex justify-end gap-2">
        <button type="button" onClick={onCancel}
          className="px-3 py-2 rounded-lg text-sm font-medium cursor-pointer"
          style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
          Cancel
        </button>
        <button type="submit" disabled={busy}
          className="px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
          style={{ background: 'var(--color-primary)' }}>
          {busy ? 'Creating...' : 'Create workspace'}
        </button>
      </div>
    </form>
  );
}

export default function Workspaces() {
  const [workspaces, setWorkspaces] = useState<WorkspaceView[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [disabled, setDisabled] = useState(false);
  const [busyId, setBusyId] = useState('');
  const [pending, setPending] = useState<PendingAction | null>(null);
  const [confirmName, setConfirmName] = useState('');
  const [creating, setCreating] = useState(false);
  const [expandedId, setExpandedId] = useState('');
  const [events, setEvents] = useState<Record<string, WorkspaceEventView[]>>({});

  const load = (quiet = false) => {
    if (!quiet) setLoading(true);
    api.workspaces.list(true)
      .then(items => {
        setWorkspaces(items);
        setDisabled(false);
      })
      .catch(err => {
        if (workspaceDisabled(err)) {
          setDisabled(true);
          return;
        }
        setError(errorMessage(err));
      })
      .finally(() => {
        if (!quiet) setLoading(false);
      });
  };

  useEffect(() => {
    load();
  }, []);

  const active = workspaces?.filter(workspace => !workspace.deleted_at) ?? [];
  const deleted = workspaces?.filter(workspace => Boolean(workspace.deleted_at)) ?? [];
  const transitioning = active.some(workspace =>
    workspace.runtime_status === 'PROVISIONING' || workspace.volume_status === 'PROVISIONING');
  useEffect(() => {
    if (!transitioning) return;
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') load(true);
    }, 5000);
    return () => clearInterval(timer);
  }, [transitioning]);

  const run = async (workspace: WorkspaceView, action: () => Promise<unknown>, success: string) => {
    setBusyId(workspace.id);
    setError('');
    setNotice('');
    try {
      await action();
      setNotice(success);
      setEvents(previous => {
        const next = { ...previous };
        delete next[workspace.id];
        return next;
      });
      load(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusyId('');
    }
  };

  const changeIdle = (workspace: WorkspaceView, minutes: number) => {
    if (minutes === workspace.idle_sleep_minutes) return;
    run(workspace, () => api.workspaces.update(workspace.id, { idle_sleep_minutes: minutes }),
      `Idle sleep for "${workspace.name}" set to ${minutes} minutes.`);
  };

  const closeDialog = () => {
    setPending(null);
    setConfirmName('');
  };

  const confirmPending = () => {
    if (!pending || confirmName !== pending.workspace.name) return;
    const { kind, workspace } = pending;
    closeDialog();
    if (kind === 'reset') {
      run(workspace, () => api.workspaces.reset(workspace.id, { confirm_name: workspace.name }),
        `Workspace "${workspace.name}" was reset.`);
    } else {
      run(workspace, () => api.workspaces.delete(workspace.id),
        `Workspace "${workspace.name}" moved to deleted; its volume is kept for ${PURGE_AFTER_DAYS} days.`);
    }
  };

  const toggleDetail = (workspace: WorkspaceView) => {
    if (expandedId === workspace.id) {
      setExpandedId('');
      return;
    }
    setExpandedId(workspace.id);
    if (!events[workspace.id]) {
      api.workspaces.events(workspace.id, 20)
        .then(items => setEvents(previous => ({ ...previous, [workspace.id]: items })))
        .catch(err => setError(errorMessage(err)));
    }
  };

  if (disabled) {
    return (
      <section className="rounded-xl border mt-6 p-5" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="flex items-center gap-2">
          <HardDrive size={18} />
          <h2 className="font-semibold">Workspaces</h2>
        </div>
        <p className="text-sm mt-2" style={{ color: 'var(--color-text-secondary)' }}>
          Persistent workspaces are not enabled on this deployment.
        </p>
      </section>
    );
  }

  return (
    <section className="rounded-xl border mt-6" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      <div className="p-5 border-b flex items-start justify-between gap-4" style={{ borderColor: 'var(--color-border)' }}>
        <div>
          <div className="flex items-center gap-2">
            <HardDrive size={18} />
            <h2 className="font-semibold">Workspaces</h2>
          </div>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            A workspace keeps files, installed dependencies and HOME across conversations. Runtimes sleep
            after the idle period; the volume stays.
          </p>
        </div>
        <div className="shrink-0 flex items-center gap-2">
          <button
            onClick={() => {
              setError('');
              setNotice('');
              setCreating(value => !value);
            }}
            className="inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer"
            style={{ background: 'var(--color-primary)', color: '#fff' }}>
            <Plus size={16} />
            New workspace
          </button>
          <button
            onClick={() => {
              setError('');
              setNotice('');
              load();
            }}
            disabled={loading}
            className="inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50"
            style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
            <RefreshCw size={16} />
            Refresh
          </button>
        </div>
      </div>
      <div className="p-5">
        {error && (
          <div role="alert" className="mb-4 rounded-lg border px-4 py-3 text-sm flex items-center gap-2"
            style={{ borderColor: '#ef4444', background: 'rgba(239,68,68,0.08)', color: '#ef4444' }}>
            <AlertTriangle size={16} className="shrink-0" />
            {error}
          </div>
        )}
        {notice && (
          <div role="status" className="mb-4 rounded-lg border px-4 py-3 text-sm"
            style={{ borderColor: '#22c55e', background: 'rgba(34,197,94,0.08)', color: '#22c55e' }}>
            {notice}
          </div>
        )}
        {creating && (
          <CreateWorkspaceForm
            onCreated={workspace => {
              setCreating(false);
              setNotice(`Workspace "${workspace.name}" created; its volume is being prepared.`);
              load(true);
            }}
            onCancel={() => setCreating(false)}
          />
        )}
        {loading && !workspaces && (
          <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>Loading workspaces...</p>
        )}
        {!loading && workspaces && active.length === 0 && (
          <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            No workspaces yet. Create one above, or one is created automatically the first time you chat with an agent.
          </p>
        )}
        {active.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm table-fixed">
              <colgroup>
                <col style={{ width: '21%' }} />
                <col style={{ width: '19%' }} />
                <col style={{ width: '12%' }} />
                <col style={{ width: '8%' }} />
                <col style={{ width: '13%' }} />
                <col style={{ width: '10%' }} />
                <col style={{ width: '17%' }} />
              </colgroup>
              <thead>
                <tr className="text-left" style={{ color: 'var(--color-text-secondary)' }}>
                  <th className="py-2 pr-3 font-medium">Name</th>
                  <th className="py-2 pr-3 font-medium">Agent</th>
                  <th className="py-2 pr-3 font-medium">Status</th>
                  <th className="py-2 pr-3 font-medium">Size</th>
                  <th className="py-2 pr-3 font-medium">Idle</th>
                  <th className="py-2 pr-3 font-medium">Used</th>
                  <th className="py-2 font-medium text-right">Actions</th>
                </tr>
              </thead>
              <tbody>
                {active.map(workspace => {
                  const status = workspaceStatus(workspace);
                  const busy = busyId === workspace.id;
                  const expanded = expandedId === workspace.id;
                  return (
                    <Fragment key={workspace.id}>
                      <tr className="border-t align-top" style={{ borderColor: 'var(--color-border)' }}>
                        <td className="py-3 pr-3">
                          <div className="flex items-center gap-2 min-w-0">
                            <button
                              onClick={() => toggleDetail(workspace)}
                              title="Show details and recent events"
                              aria-label={`Details for ${workspace.name}`}
                              aria-expanded={expanded}
                              className="p-0.5 rounded cursor-pointer hover:opacity-70">
                              {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                            </button>
                            <span className="font-medium truncate" title={workspace.name}>{workspace.name}</span>
                            {workspace.is_default && (
                              <span className="px-1.5 py-0.5 rounded text-xs"
                                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                                default
                              </span>
                            )}
                          </div>
                          <div className="text-xs mt-0.5 truncate" style={{ color: 'var(--color-text-secondary)' }}
                            title={`${workspace.mount_path} · generation ${workspace.active_volume_generation ?? 0}`}>
                            {workspace.mount_path} · gen {workspace.active_volume_generation ?? 0}
                          </div>
                        </td>
                        <td className="py-3 pr-3 max-w-[12rem] truncate" title={workspace.agent_id ?? ''}>
                          {workspace.agent_id || '—'}
                        </td>
                        <td className="py-3 pr-3">
                          <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
                            style={{ background: TONE_STYLES[status.tone].bg, color: TONE_STYLES[status.tone].text }}>
                            {status.label}
                          </span>
                          {workspace.error_message && (
                            <div className="text-xs mt-1 max-w-[18rem]" style={{ color: '#dc2626' }} title={workspace.error_message}>
                              {workspace.error_message}
                            </div>
                          )}
                        </td>
                        <td className="py-3 pr-4 whitespace-nowrap">{workspace.size_gb} GB</td>
                        <td className="py-3 pr-3">
                          <select
                            value={workspace.idle_sleep_minutes}
                            disabled={busy}
                            aria-label={`Idle sleep minutes for ${workspace.name}`}
                            onChange={event => changeIdle(workspace, Number(event.target.value))}
                            className="rounded-lg border px-2 py-1 text-sm outline-none disabled:opacity-50"
                            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }}>
                            {!IDLE_OPTIONS.includes(workspace.idle_sleep_minutes) && (
                              <option value={workspace.idle_sleep_minutes}>{workspace.idle_sleep_minutes} min</option>
                            )}
                            {IDLE_OPTIONS.map(minutes => (
                              <option key={minutes} value={minutes}>{minutes} min</option>
                            ))}
                          </select>
                        </td>
                        <td className="py-3 pr-4 whitespace-nowrap" title={workspace.last_used_at ?? ''}>
                          {relativeTime(workspace.last_used_at)}
                        </td>
                        <td className="py-3 text-right whitespace-nowrap">
                          <div className="inline-flex items-center gap-0.5">
                            {workspace.runtime_status === 'READY' ? (
                              <button
                                onClick={() => run(workspace, () => api.workspaces.sleep(workspace.id),
                                  `Workspace "${workspace.name}" is sleeping.`)}
                                disabled={busy}
                                title="Sleep runtime (volume stays)"
                                aria-label={`Sleep ${workspace.name}`}
                                className="p-1.5 rounded-lg cursor-pointer disabled:opacity-40 hover:opacity-80"
                                style={{ background: 'var(--color-bg-tertiary)' }}>
                                <Moon size={15} />
                              </button>
                            ) : (
                              <button
                                onClick={() => run(workspace, () => api.workspaces.wake(workspace.id),
                                  `Waking workspace "${workspace.name}"...`)}
                                disabled={busy || workspace.runtime_status === 'PROVISIONING' || workspace.volume_status === 'PROVISIONING'}
                                title="Wake runtime"
                                aria-label={`Wake ${workspace.name}`}
                                className="p-1.5 rounded-lg cursor-pointer disabled:opacity-40 hover:opacity-80"
                                style={{ background: 'var(--color-bg-tertiary)' }}>
                                <Play size={15} />
                              </button>
                            )}
                            <button
                              onClick={() => {
                                setPending({ kind: 'reset', workspace });
                                setConfirmName('');
                              }}
                              disabled={busy}
                              title="Reset workspace (deletes all files)"
                              aria-label={`Reset ${workspace.name}`}
                              className="p-1.5 rounded-lg cursor-pointer disabled:opacity-40 hover:opacity-80"
                              style={{ background: 'var(--color-bg-tertiary)' }}>
                              <RotateCcw size={15} />
                            </button>
                            <button
                              onClick={() => {
                                setPending({ kind: 'delete', workspace });
                                setConfirmName('');
                              }}
                              disabled={busy}
                              title={`Delete workspace (soft delete, kept ${PURGE_AFTER_DAYS} days)`}
                              aria-label={`Delete ${workspace.name}`}
                              className="p-1.5 rounded-lg cursor-pointer disabled:opacity-40 hover:opacity-80"
                              style={{ background: 'var(--color-bg-tertiary)', color: '#dc2626' }}>
                              <Trash2 size={15} />
                            </button>
                          </div>
                        </td>
                      </tr>
                      {expanded && (
                        <tr key={`${workspace.id}-detail`} className="border-t" style={{ borderColor: 'var(--color-border)' }}>
                          <td colSpan={7} className="py-3 px-1">
                            <div className="grid grid-cols-2 gap-x-6 gap-y-1 text-xs"
                              style={{ color: 'var(--color-text-secondary)' }}>
                              <span>Profile: <span style={{ color: 'var(--color-text)' }}>{workspace.profile}</span></span>
                              <span>Storage class: <span style={{ color: 'var(--color-text)' }}>{workspace.storage_class}</span></span>
                              <span>Network: <span style={{ color: 'var(--color-text)' }}>{workspace.network_enabled ? (workspace.network_profile || 'enabled') : 'disabled'}</span></span>
                              <span>Runtime generation: <span style={{ color: 'var(--color-text)' }}>{workspace.runtime_generation}</span></span>
                              <span>Last provisioned: <span style={{ color: 'var(--color-text)' }}>{relativeTime(workspace.last_provisioned_at)}</span></span>
                              <span>Created: <span style={{ color: 'var(--color-text)' }}>{relativeTime(workspace.created_at)}</span></span>
                            </div>
                            {workspace.error_code && (
                              <div className="mt-2 text-xs" style={{ color: '#dc2626' }}>
                                {workspace.error_code}{workspace.error_message ? ` — ${workspace.error_message}` : ''}
                              </div>
                            )}
                            <div className="mt-3">
                              <div className="text-xs font-medium mb-1">Recent events</div>
                              {!events[workspace.id] && (
                                <p className="text-xs" style={{ color: 'var(--color-text-tertiary)' }}>Loading events...</p>
                              )}
                              {events[workspace.id]?.length === 0 && (
                                <p className="text-xs" style={{ color: 'var(--color-text-tertiary)' }}>No events recorded.</p>
                              )}
                              <div className="space-y-1">
                                {events[workspace.id]?.map(event => (
                                  <div key={event.id} className="text-xs">
                                    <span className="font-mono" style={{ color: 'var(--color-text)' }}>{event.event_type}</span>
                                    <span className="ml-2">{relativeTime(event.created_at)}</span>
                                    {event.actor && <span className="ml-2">by {event.actor}</span>}
                                    {payloadSummary(event.payload) && (
                                      <div className="font-mono" style={{ color: 'var(--color-text-tertiary)' }}>
                                        {payloadSummary(event.payload)}
                                      </div>
                                    )}
                                  </div>
                                ))}
                              </div>
                            </div>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {deleted.length > 0 && (
          <div className="mt-6 rounded-xl border p-4"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)' }}>
            <div className="flex items-center justify-between gap-4">
              <div>
                <h3 className="text-sm font-semibold">Deleted workspaces</h3>
                <p className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Volumes are kept {PURGE_AFTER_DAYS} days for restore, then purged.
                </p>
              </div>
            </div>
            <div className="mt-3 space-y-2">
              {deleted.map(workspace => {
                const busy = busyId === workspace.id;
                return (
                  <div key={workspace.id} className="flex items-center justify-between gap-3 text-sm">
                    <div className="min-w-0">
                      <div className="font-medium truncate" title={workspace.name}>{workspace.name}</div>
                      <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                        deleted {relativeTime(workspace.deleted_at)} · {purgeDaysLeft(workspace.deleted_at!)} day(s) until purge
                      </div>
                    </div>
                    <button
                      onClick={() => run(workspace, () => api.workspaces.undelete(workspace.id),
                        `Workspace "${workspace.name}" restored.`)}
                      disabled={busy}
                      className="shrink-0 inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium cursor-pointer disabled:opacity-40"
                      style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                      <Undo2 size={13} />
                      Restore
                    </button>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>

      {pending && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4" role="dialog" aria-modal="true"
          aria-labelledby="workspace-dialog-title" style={{ background: 'rgba(0,0,0,0.5)' }}>
          <div className="w-full max-w-md rounded-xl border p-5"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <div className="flex items-start justify-between gap-4">
              <h3 id="workspace-dialog-title" className="font-semibold">
                {pending.kind === 'reset' ? 'Reset workspace' : 'Delete workspace'}
              </h3>
              <button onClick={closeDialog} aria-label="Close" className="p-1 rounded cursor-pointer hover:opacity-70">
                <X size={18} />
              </button>
            </div>
            <p className="text-sm mt-2" style={{ color: 'var(--color-text-secondary)' }}>
              {pending.kind === 'reset'
                ? 'The volume is replaced with an empty one. All files in this workspace are permanently deleted.'
                : `The workspace stops and is hidden. Its volume is kept for ${PURGE_AFTER_DAYS} days, then permanently deleted.`}
            </p>
            <label htmlFor="workspace-confirm-name" className="block text-sm font-medium mt-4 mb-2">
              Type <span className="font-mono">{pending.workspace.name}</span> to confirm
            </label>
            <input
              id="workspace-confirm-name"
              value={confirmName}
              onChange={event => setConfirmName(event.target.value)}
              autoFocus
              className="w-full rounded-lg border px-3 py-2 text-sm outline-none"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }}
            />
            <div className="mt-5 flex justify-end gap-2">
              <button onClick={closeDialog}
                className="px-3 py-2 rounded-lg text-sm font-medium cursor-pointer"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                Cancel
              </button>
              <button
                onClick={confirmPending}
                disabled={confirmName !== pending.workspace.name || busyId === pending.workspace.id}
                className="px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
                style={{ background: '#dc2626' }}>
                {pending.kind === 'reset' ? 'Reset workspace' : 'Delete workspace'}
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
