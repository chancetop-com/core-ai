import { useEffect, useState } from 'react';
import { AlertTriangle, HardDrive, Moon, Play, RefreshCw, RotateCcw, Trash2, X } from 'lucide-react';
import { api, type WorkspaceView } from '../../api/client';

type Tone = 'ok' | 'info' | 'warn' | 'error' | 'muted';
type DialogKind = 'reset' | 'delete';

interface PendingAction {
  kind: DialogKind;
  workspace: WorkspaceView;
}

const IDLE_OPTIONS = [5, 15, 30, 60, 120];
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

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : 'Workspace request failed.';
}

function workspaceDisabled(err: unknown): boolean {
  return typeof err === 'object' && err !== null && (err as { errorCode?: string }).errorCode === 'WORKSPACE_UNSUPPORTED';
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

  const load = (quiet = false) => {
    if (!quiet) setLoading(true);
    api.workspaces.list()
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

  const transitioning = Boolean(workspaces?.some(workspace =>
    workspace.runtime_status === 'PROVISIONING' || workspace.volume_status === 'PROVISIONING'));
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
        `Workspace "${workspace.name}" moved to deleted; its volume is kept for 7 days.`);
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
        <button
          onClick={() => {
            setError('');
            setNotice('');
            load();
          }}
          disabled={loading}
          className="shrink-0 inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50"
          style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
          <RefreshCw size={16} />
          Refresh
        </button>
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
        {loading && !workspaces && (
          <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>Loading workspaces...</p>
        )}
        {!loading && workspaces && workspaces.length === 0 && (
          <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            No workspaces yet. One is created automatically the first time you chat with an agent.
          </p>
        )}
        {workspaces && workspaces.length > 0 && (
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
                {workspaces.map(workspace => {
                  const status = workspaceStatus(workspace);
                  const busy = busyId === workspace.id;
                  return (
                    <tr key={workspace.id} className="border-t align-top" style={{ borderColor: 'var(--color-border)' }}>
                      <td className="py-3 pr-3">
                        <div className="flex items-center gap-2 min-w-0">
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
                            title="Delete workspace (soft delete, kept 7 days)"
                            aria-label={`Delete ${workspace.name}`}
                            className="p-1.5 rounded-lg cursor-pointer disabled:opacity-40 hover:opacity-80"
                            style={{ background: 'var(--color-bg-tertiary)', color: '#dc2626' }}>
                            <Trash2 size={15} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
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
                : 'The workspace stops and is hidden. Its volume is kept for 7 days, then permanently deleted.'}
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
