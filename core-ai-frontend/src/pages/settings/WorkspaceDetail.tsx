import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  AlertTriangle,
  Archive,
  ArrowDown,
  ArrowLeft,
  ArrowUpCircle,
  CheckCircle2,
  CopyPlus,
  FileCode2,
  HardDrive,
  Moon,
  Play,
  RefreshCw,
  RotateCcw,
  Trash2,
  Undo2,
  X,
} from 'lucide-react';
import {
  api,
  type WorkspaceArchiveView,
  type WorkspaceEventView,
  type WorkspaceUsageView,
  type WorkspaceView,
} from '../../api/client';
import {
  errorMessage,
  formatBytes,
  isNotFound,
  payloadSummary,
  PURGE_AFTER_DAYS,
  relativeTime,
  TONE_STYLES,
  truncateLog,
  usageSummary,
  workspaceStatus,
} from './workspaceHelpers';

type DialogKind =
  | 'retry'
  | 'reset'
  | 'delete'
  | 'restore-new'
  | 'restore-dr'
  | 'delete-archive'
  | 'upgrade-image';

type UsageState =
  | { status: 'loading' }
  | { status: 'loaded'; data: WorkspaceUsageView }
  | { status: 'empty' }
  | { status: 'error'; message: string };

function archiveStatusTone(status: string): { bg: string; text: string } {
  if (status === 'READY') return TONE_STYLES.ok;
  if (status === 'FAILED') return TONE_STYLES.error;
  if (status === 'CREATING') return TONE_STYLES.info;
  return TONE_STYLES.muted;
}

export default function WorkspaceDetail() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [workspace, setWorkspace] = useState<WorkspaceView | null>(null);
  const [usageState, setUsageState] = useState<UsageState>({ status: 'loading' });
  const [events, setEvents] = useState<WorkspaceEventView[] | null>(null);
  const [archives, setArchives] = useState<WorkspaceArchiveView[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [notFound, setNotFound] = useState(false);
  const [dialog, setDialog] = useState<DialogKind | null>(null);
  const [confirmName, setConfirmName] = useState('');
  const [rerunBootstrap, setRerunBootstrap] = useState(false);
  const [showFullLog, setShowFullLog] = useState(false);
  const [selectedArchive, setSelectedArchive] = useState<WorkspaceArchiveView | null>(null);
  const [restoreNewName, setRestoreNewName] = useState('');
  const [targetImage, setTargetImage] = useState('');

  const loadAll = useCallback((quiet = false) => {
    if (!id) return;
    if (!quiet) setLoading(true);
    api.workspaces.get(id)
      .then(view => {
        setWorkspace(view);
        setNotFound(false);
      })
      .catch(err => {
        if (isNotFound(err)) {
          setNotFound(true);
          return;
        }
        setError(errorMessage(err));
      })
      .finally(() => {
        if (!quiet) setLoading(false);
      });

    api.workspaces.getUsage(id)
      .then(data => setUsageState({ status: 'loaded', data }))
      .catch(err => {
        if (isNotFound(err)) {
          setUsageState({ status: 'empty' });
          return;
        }
        setUsageState({ status: 'error', message: errorMessage(err) });
      });

    api.workspaces.events(id, 30)
      .then(items => setEvents(items))
      .catch(() => setEvents([]));

    api.workspaces.listArchives(id)
      .then(items => setArchives(items))
      .catch(() => setArchives([]));
  }, [id]);

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  const transitioning = workspace != null && !workspace.deleted_at
    && (workspace.runtime_status === 'PROVISIONING' || workspace.volume_status === 'PROVISIONING');

  useEffect(() => {
    if (!transitioning) return;
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') loadAll(true);
    }, 5000);
    return () => clearInterval(timer);
  }, [transitioning, loadAll]);

  const runAction = async (action: () => Promise<unknown>, successMessage: string, after?: (result: unknown) => void) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await action();
      setNotice(successMessage);
      after?.(result);
      loadAll(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const closeDialog = () => {
    setDialog(null);
    setConfirmName('');
    setRerunBootstrap(false);
    setSelectedArchive(null);
    setRestoreNewName('');
    setTargetImage('');
  };

  const trimmedRestoreName = restoreNewName.trim();
  const restoreNameValid = workspace != null
    && trimmedRestoreName.length >= 1
    && trimmedRestoreName.length <= 64
    && trimmedRestoreName !== workspace.name;

  const trimmedTargetImage = targetImage.trim();
  const targetImageValid = workspace != null
    && trimmedTargetImage.length > 0
    && trimmedTargetImage !== (workspace.image ?? '');

  const submitDialog = () => {
    if (!workspace || !dialog) return;
    if ((dialog === 'reset' || dialog === 'delete') && confirmName !== workspace.name) return;
    if (dialog === 'restore-new' && (!selectedArchive || !restoreNameValid)) return;
    if ((dialog === 'restore-dr' || dialog === 'delete-archive') && !selectedArchive) return;
    if (dialog === 'upgrade-image' && !targetImageValid) return;

    const kind = dialog;
    const forceBootstrap = rerunBootstrap;
    const archive = selectedArchive;
    const newName = trimmedRestoreName;
    const nextImage = trimmedTargetImage;
    closeDialog();

    if (kind === 'retry') {
      runAction(
        () => api.workspaces.retry(workspace.id, { rerun_bootstrap: forceBootstrap }),
        forceBootstrap
          ? `Retrying bootstrap and start for "${workspace.name}"...`
          : `Retrying startup for "${workspace.name}"...`,
      );
    } else if (kind === 'reset') {
      runAction(
        () => api.workspaces.reset(workspace.id, { confirm_name: workspace.name }),
        `Workspace "${workspace.name}" was reset.`,
      );
    } else if (kind === 'delete') {
      runAction(
        () => api.workspaces.delete(workspace.id),
        `Workspace "${workspace.name}" moved to deleted; its volume is kept for ${PURGE_AFTER_DAYS} days.`,
        () => navigate('/settings'),
      );
    } else if (kind === 'restore-new' && archive) {
      runAction(
        () => api.workspaces.restoreArchive(workspace.id, { archive_id: archive.id, new_name: newName }),
        `Restored backup into new workspace "${newName}".`,
        (restored) => {
          const view = restored as WorkspaceView | undefined;
          if (view?.id) navigate(`/settings/workspaces/${view.id}`);
        },
      );
    } else if (kind === 'restore-dr' && archive) {
      runAction(
        () => api.workspaces.restoreArchive(workspace.id, { archive_id: archive.id }),
        `Workspace "${workspace.name}" restored from backup.`,
      );
    } else if (kind === 'delete-archive' && archive) {
      runAction(
        () => api.workspaces.deleteArchive(workspace.id, archive.id),
        'Backup deleted.',
      );
    } else if (kind === 'upgrade-image') {
      runAction(
        () => api.workspaces.update(workspace.id, { image: nextImage }),
        `Workspace image upgraded to "${nextImage}".`,
      );
    }
  };

  if (loading && !workspace) {
    return (
      <div className="p-6 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
        Loading workspace...
      </div>
    );
  }

  if (notFound || !workspace) {
    return (
      <div className="p-6">
        <Link to="/settings" className="inline-flex items-center gap-1.5 text-sm mb-4 hover:underline"
          style={{ color: 'var(--color-text-secondary)' }}>
          <ArrowLeft size={14} /> Back to Settings
        </Link>
        <div className="rounded-xl border p-5" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
          <h1 className="font-semibold">Workspace not found</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            {error || 'This workspace does not exist or you do not have access to it.'}
          </p>
        </div>
      </div>
    );
  }

  const status = workspaceStatus(workspace);
  const loadedUsage = usageState.status === 'loaded' ? usageState.data : null;
  const usedBytes = loadedUsage?.used_bytes ?? workspace.used_bytes;
  const capacityBytes = loadedUsage?.capacity_bytes ?? workspace.capacity_bytes;
  const summary = usageSummary(usedBytes, capacityBytes);
  const logPreview = truncateLog(workspace.last_bootstrap_log);
  const bootstrapFailed = workspace.runtime_status === 'ERROR'
    && (workspace.error_code === 'WORKSPACE_BOOTSTRAP_FAILED' || workspace.error_code === 'WORKSPACE_START_FAILED');
  const readyArchives = (archives ?? []).filter(a => a.status === 'READY');
  const volumeLost = !workspace.deleted_at && (workspace.volume_status === 'ERROR' || workspace.recoverable === true);
  const canRecoverFromArchive = volumeLost && (workspace.recoverable === true || readyArchives.length > 0 || (workspace.archive_count ?? 0) > 0);

  const jumpToArchives = () => {
    const el = document.getElementById('workspace-archives-panel');
    if (el && typeof el.scrollIntoView === 'function') {
      el.scrollIntoView({ behavior: 'smooth' });
    }
  };

  return (
    <div className="h-full overflow-auto">
      <div className="px-6 py-4 border-b flex items-center justify-between gap-4" style={{ borderColor: 'var(--color-border)' }}>
        <div className="min-w-0">
          <Link to="/settings" className="inline-flex items-center gap-1.5 text-xs mb-1 hover:underline"
            style={{ color: 'var(--color-text-secondary)' }}>
            <ArrowLeft size={12} /> Back to Settings
          </Link>
          <div className="flex items-center gap-2.5 flex-wrap">
            <HardDrive size={18} />
            <h1 className="text-lg font-semibold truncate">{workspace.name}</h1>
            {workspace.is_default && (
              <span className="px-2 py-0.5 rounded text-xs"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                default
              </span>
            )}
            <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium"
              style={{ background: TONE_STYLES[status.tone].bg, color: TONE_STYLES[status.tone].text }}>
              {status.label}
            </span>
          </div>
        </div>
        <div className="shrink-0 flex items-center gap-2 flex-wrap">
          <button
            type="button"
            onClick={() => { setError(''); setNotice(''); loadAll(); }}
            disabled={loading || busy}
            className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50"
            style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
            <RefreshCw size={14} /> Refresh
          </button>
          {workspace.deleted_at ? (
            <button
              type="button"
              onClick={() => runAction(() => api.workspaces.undelete(workspace.id), `Workspace "${workspace.name}" restored.`)}
              disabled={busy}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50"
              style={{ background: 'var(--color-primary)', color: '#fff' }}>
              <Undo2 size={14} /> Restore
            </button>
          ) : (
            <>
              {workspace.runtime_status === 'READY' ? (
                <button
                  type="button"
                  onClick={() => runAction(() => api.workspaces.sleep(workspace.id), `Workspace "${workspace.name}" is sleeping.`)}
                  disabled={busy}
                  className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                  <Moon size={14} /> Sleep
                </button>
              ) : (
                <button
                  type="button"
                  onClick={() => runAction(() => api.workspaces.wake(workspace.id), `Waking workspace "${workspace.name}"...`)}
                  disabled={busy || transitioning}
                  className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50"
                  style={{ background: 'var(--color-primary)', color: '#fff' }}>
                  <Play size={14} /> Wake
                </button>
              )}
              <button
                type="button"
                onClick={() => { setDialog('upgrade-image'); setTargetImage(workspace.image ?? ''); }}
                disabled={busy || transitioning || volumeLost}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                <ArrowUpCircle size={14} /> 升级镜像
              </button>
              <button
                type="button"
                onClick={() => { setDialog('retry'); setRerunBootstrap(false); }}
                disabled={busy || transitioning}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                <RefreshCw size={14} /> Retry bootstrap/start
              </button>
              <button
                type="button"
                onClick={() => { setDialog('reset'); setConfirmName(''); }}
                disabled={busy}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                <RotateCcw size={14} /> Reset
              </button>
              <button
                type="button"
                onClick={() => { setDialog('delete'); setConfirmName(''); }}
                disabled={busy}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50"
                style={{ background: 'rgba(220,38,38,0.1)', color: '#dc2626' }}>
                <Trash2 size={14} /> Delete
              </button>
            </>
          )}
        </div>
      </div>

      <div className="p-6 space-y-5 max-w-5xl">
        {volumeLost && (
          <div
            role="alert"
            data-testid="volume-error-recovery-banner"
            className="rounded-xl border-2 px-4 py-3.5 text-sm flex items-center justify-between gap-4 flex-wrap"
            style={{ borderColor: '#dc2626', background: 'rgba(220,38,38,0.12)', color: '#b91c1c' }}>
            <div className="flex items-center gap-2.5">
              <AlertTriangle size={18} className="shrink-0" />
              <div>
                <div className="font-semibold">
                  {canRecoverFromArchive ? '卷不可用，可从备份恢复' : '卷不可用'}
                </div>
                <div className="text-xs mt-0.5">
                  {canRecoverFromArchive
                    ? '当前工作区数据卷丢失或处于错误状态，工具调用已禁止。请前往下方「备份与恢复」面板从备份恢复。'
                    : '当前工作区数据卷处于错误状态，工具调用已禁止；暂无可用备份，可尝试重置工作区或联系管理员。'}
                </div>
              </div>
            </div>
            <a
              href="#workspace-archives-panel"
              onClick={jumpToArchives}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-white shrink-0"
              style={{ background: '#dc2626' }}>
              <ArrowDown size={13} /> 前往备份与恢复
            </a>
          </div>
        )}
        {error && (
          <div role="alert" className="rounded-lg border px-4 py-3 text-sm flex items-center gap-2"
            style={{ borderColor: '#ef4444', background: 'rgba(239,68,68,0.08)', color: '#ef4444' }}>
            <AlertTriangle size={16} className="shrink-0" />
            {error}
          </div>
        )}
        {notice && (
          <div role="status" className="rounded-lg border px-4 py-3 text-sm"
            style={{ borderColor: '#22c55e', background: 'rgba(34,197,94,0.08)', color: '#22c55e' }}>
            {notice}
          </div>
        )}
        {(workspace.error_code || workspace.error_message) && (
          <div role="alert" className="rounded-xl border p-4 text-sm flex items-start gap-2.5"
            style={{ borderColor: '#ef4444', background: 'rgba(239,68,68,0.08)', color: '#dc2626' }}>
            <AlertTriangle size={16} className="shrink-0 mt-0.5" />
            <div>
              <div className="font-semibold">{workspace.error_code || 'Workspace error'}</div>
              {workspace.error_message && <div className="mt-0.5">{workspace.error_message}</div>}
              {bootstrapFailed && (
                <div className="text-xs mt-1">
                  The persistent volume is intact. Fix the failing script and click Retry bootstrap/start to recover.
                </div>
              )}
            </div>
          </div>
        )}

        <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
          <section className="rounded-xl border p-4" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <h2 className="text-sm font-semibold mb-3">Overview &amp; Binding</h2>
            <dl className="grid grid-cols-2 gap-y-2 gap-x-4 text-xs">
              <dt style={{ color: 'var(--color-text-secondary)' }}>Bound agent</dt>
              <dd className="font-mono truncate" title={workspace.agent_id ?? ''}>{workspace.agent_id || '—'}</dd>
              <dt style={{ color: 'var(--color-text-secondary)' }}>Runtime status</dt>
              <dd>{workspace.runtime_status} (gen {workspace.runtime_generation})</dd>
              <dt style={{ color: 'var(--color-text-secondary)' }}>Volume status</dt>
              <dd>{workspace.volume_status} (gen {workspace.active_volume_generation ?? 0})</dd>
              <dt style={{ color: 'var(--color-text-secondary)' }}>Image</dt>
              <dd className="font-mono truncate" title={workspace.image ?? ''}>{workspace.image || '—'}</dd>
              <dt style={{ color: 'var(--color-text-secondary)' }}>Profile</dt>
              <dd>{workspace.profile}</dd>
              <dt style={{ color: 'var(--color-text-secondary)' }}>Storage class</dt>
              <dd>{workspace.storage_class}</dd>
              <dt style={{ color: 'var(--color-text-secondary)' }}>Mount path</dt>
              <dd className="font-mono">{workspace.mount_path}</dd>
              <dt style={{ color: 'var(--color-text-secondary)' }}>Idle sleep</dt>
              <dd>{workspace.idle_sleep_minutes} min</dd>
              <dt style={{ color: 'var(--color-text-secondary)' }}>Environment file</dt>
              <dd>
                <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium"
                  style={workspace.env_file_present
                    ? { background: TONE_STYLES.ok.bg, color: TONE_STYLES.ok.text }
                    : { background: TONE_STYLES.muted.bg, color: TONE_STYLES.muted.text }}>
                  <FileCode2 size={11} />
                  {workspace.env_file_present ? '/workspace/.core-ai/env present' : 'No env file'}
                </span>
              </dd>
            </dl>
          </section>

          <section className="rounded-xl border p-4" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <h2 className="text-sm font-semibold mb-3">Capacity &amp; Usage</h2>
            <div className="text-xs mb-2 flex items-center justify-between" style={{ color: 'var(--color-text-secondary)' }}>
              <span>Provisioned size: <strong style={{ color: 'var(--color-text)' }}>{workspace.size_gb} GB</strong></span>
              {summary && (
                <span style={summary.warning ? { color: summary.critical ? '#dc2626' : '#b45309', fontWeight: 600 } : undefined}>
                  {summary.label} ({summary.percent}%)
                </span>
              )}
            </div>
            {summary ? (
              <div className="w-full h-2 rounded-full overflow-hidden mb-3" style={{ background: 'var(--color-bg-tertiary)' }}>
                <div
                  className="h-full rounded-full"
                  style={{
                    width: `${Math.min(100, summary.percent)}%`,
                    background: summary.critical ? '#dc2626' : summary.warning ? '#d97706' : 'var(--color-primary)',
                  }}
                />
              </div>
            ) : (
              <p className="text-xs mb-3" style={{ color: 'var(--color-text-tertiary)' }}>
                {usageState.status === 'loading' ? 'Loading usage sample...' : 'No usage sample yet.'}
              </p>
            )}
            <dl className="grid grid-cols-2 gap-y-2 gap-x-4 text-xs">
              <dt style={{ color: 'var(--color-text-secondary)' }}>Available</dt>
              <dd>{loadedUsage?.available_bytes != null ? formatBytes(loadedUsage.available_bytes) : '—'}</dd>
              <dt style={{ color: 'var(--color-text-secondary)' }}>Inodes</dt>
              <dd>
                {loadedUsage?.inodes_used != null && loadedUsage?.inodes_total != null
                  ? `${loadedUsage.inodes_used.toLocaleString()} / ${loadedUsage.inodes_total.toLocaleString()}`
                  : '—'}
              </dd>
              <dt style={{ color: 'var(--color-text-secondary)' }}>Last sampled</dt>
              <dd title={loadedUsage?.sampled_at ?? ''}>{relativeTime(loadedUsage?.sampled_at)}</dd>
              <dt style={{ color: 'var(--color-text-secondary)' }}>Last wake</dt>
              <dd title={workspace.last_woken_at ?? ''}>{relativeTime(workspace.last_woken_at)}</dd>
              <dt style={{ color: 'var(--color-text-secondary)' }}>Last sleep</dt>
              <dd title={workspace.last_slept_at ?? ''}>{relativeTime(workspace.last_slept_at)}</dd>
              <dt style={{ color: 'var(--color-text-secondary)' }}>Last used</dt>
              <dd title={workspace.last_used_at ?? ''}>{relativeTime(workspace.last_used_at)}</dd>
            </dl>
          </section>
        </div>

        <section
          id="workspace-archives-panel"
          className="rounded-xl border p-4"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
          <div className="flex items-center justify-between gap-3 flex-wrap mb-3">
            <div className="flex items-center gap-2">
              <Archive size={16} />
              <h2 className="text-sm font-semibold">备份与恢复</h2>
              {workspace.archive_count != null && workspace.archive_count > 0 && (
                <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  ({workspace.archive_count} 个可用{workspace.latest_archive_at ? ` · 最近 ${relativeTime(workspace.latest_archive_at)}` : ''})
                </span>
              )}
            </div>
            {!workspace.deleted_at && (
              <button
                type="button"
                onClick={() => runAction(
                  () => api.workspaces.createArchive(workspace.id),
                  `Backup created for "${workspace.name}".`,
                )}
                disabled={busy || transitioning || workspace.volume_status !== 'READY'}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-white cursor-pointer disabled:opacity-40"
                style={{ background: 'var(--color-primary)' }}>
                <Archive size={13} /> 立即备份
              </button>
            )}
          </div>
          {archives == null && (
            <p className="text-xs" style={{ color: 'var(--color-text-tertiary)' }}>Loading backups...</p>
          )}
          {archives?.length === 0 && (
            <p className="text-xs" style={{ color: 'var(--color-text-tertiary)' }}>暂无备份记录。</p>
          )}
          {archives && archives.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-xs border-collapse">
                <thead>
                  <tr className="border-b text-left" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                    <th className="py-2 pr-3 font-medium">时间</th>
                    <th className="py-2 pr-3 font-medium">大小</th>
                    <th className="py-2 pr-3 font-medium">Generation</th>
                    <th className="py-2 pr-3 font-medium">状态</th>
                    <th className="py-2 font-medium text-right">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {archives.map(archive => {
                    const tone = archiveStatusTone(archive.status);
                    return (
                      <tr key={archive.id} className="border-b last:border-b-0" style={{ borderColor: 'var(--color-border)' }}>
                        <td className="py-2.5 pr-3 whitespace-nowrap" title={archive.created_at}>
                          {relativeTime(archive.created_at)}
                        </td>
                        <td className="py-2.5 pr-3 whitespace-nowrap">
                          {archive.status === 'READY' ? formatBytes(archive.size_bytes) : '—'}
                        </td>
                        <td className="py-2.5 pr-3 font-mono whitespace-nowrap">
                          gen {archive.volume_generation}
                        </td>
                        <td className="py-2.5 pr-3">
                          <span
                            className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
                            style={{ background: tone.bg, color: tone.text }}>
                            {archive.status}
                          </span>
                          {archive.failure_reason && (
                            <div className="text-xs mt-0.5" style={{ color: '#dc2626' }}>
                              {archive.failure_reason}
                            </div>
                          )}
                        </td>
                        <td className="py-2.5 text-right whitespace-nowrap space-x-2">
                          {archive.status === 'READY' && volumeLost && (
                            <button
                              type="button"
                              onClick={() => { setSelectedArchive(archive); setDialog('restore-dr'); }}
                              disabled={busy}
                              className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-medium text-white cursor-pointer disabled:opacity-40"
                              style={{ background: '#dc2626' }}>
                              <RotateCcw size={12} /> 原地恢复
                            </button>
                          )}
                          {archive.status === 'READY' && (
                            <button
                              type="button"
                              onClick={() => {
                                setSelectedArchive(archive);
                                setRestoreNewName('');
                                setDialog('restore-new');
                              }}
                              disabled={busy}
                              className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-medium cursor-pointer disabled:opacity-40"
                              style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                              <CopyPlus size={12} /> 恢复到新 Workspace
                            </button>
                          )}
                          <button
                            type="button"
                            onClick={() => { setSelectedArchive(archive); setDialog('delete-archive'); }}
                            disabled={busy}
                            className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-medium cursor-pointer disabled:opacity-40"
                            style={{ background: 'rgba(220,38,38,0.1)', color: '#dc2626' }}>
                            <Trash2 size={12} /> 删除备份
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <section className="rounded-xl border p-4" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
          <div className="flex items-center justify-between gap-3 flex-wrap mb-3">
            <h2 className="text-sm font-semibold">Bootstrap &amp; Startup</h2>
            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium"
              style={bootstrapFailed
                ? { background: TONE_STYLES.error.bg, color: TONE_STYLES.error.text }
                : workspace.bootstrap_completed_at
                  ? { background: TONE_STYLES.ok.bg, color: TONE_STYLES.ok.text }
                  : { background: TONE_STYLES.muted.bg, color: TONE_STYLES.muted.text }}>
              {workspace.bootstrap_completed_at && !bootstrapFailed && <CheckCircle2 size={12} />}
              {bootstrapFailed
                ? 'Failed'
                : workspace.bootstrap_completed_at
                  ? `Completed ${relativeTime(workspace.bootstrap_completed_at)}`
                  : 'Not completed'}
            </span>
          </div>
          {workspace.bootstrap_script_changed && (
            <div className="mb-3 rounded-lg border px-3 py-2 text-xs flex items-center gap-2"
              style={{ borderColor: '#f59e0b', background: 'rgba(245,158,11,0.08)', color: '#b45309' }}>
              <AlertTriangle size={14} className="shrink-0" />
              bootstrap.sh changed since last run. Changes are not auto-rerun; use Retry bootstrap/start with re-run bootstrap enabled if needed.
            </div>
          )}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs mb-3" style={{ color: 'var(--color-text-secondary)' }}>
            <span>
              bootstrap.sh hash:{' '}
              <span className="font-mono" style={{ color: 'var(--color-text)' }}>
                {workspace.bootstrap_script_hash ? workspace.bootstrap_script_hash.slice(0, 12) : '—'}
              </span>
            </span>
            <span>
              start.sh hash:{' '}
              <span className="font-mono" style={{ color: 'var(--color-text)' }}>
                {workspace.start_script_hash ? workspace.start_script_hash.slice(0, 12) : '—'}
              </span>
            </span>
          </div>
          <div className="text-xs font-medium mb-1">Recent bootstrap / start log</div>
          {workspace.last_bootstrap_log ? (
            <div>
              <pre className="rounded-lg border p-3 text-xs font-mono overflow-x-auto whitespace-pre-wrap max-h-64"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }}>
                {showFullLog ? workspace.last_bootstrap_log : logPreview.text}
              </pre>
              {logPreview.truncated && (
                <button
                  type="button"
                  onClick={() => setShowFullLog(v => !v)}
                  className="mt-1.5 text-xs underline cursor-pointer"
                  style={{ color: 'var(--color-text-secondary)' }}>
                  {showFullLog ? 'Show truncated log' : 'Show full log'}
                </button>
              )}
            </div>
          ) : (
            <p className="text-xs" style={{ color: 'var(--color-text-tertiary)' }}>
              No bootstrap or start log recorded yet.
            </p>
          )}
        </section>

        <section className="rounded-xl border p-4" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
          <h2 className="text-sm font-semibold mb-3">Recent events</h2>
          {events == null && (
            <p className="text-xs" style={{ color: 'var(--color-text-tertiary)' }}>Loading events...</p>
          )}
          {events?.length === 0 && (
            <p className="text-xs" style={{ color: 'var(--color-text-tertiary)' }}>No events recorded.</p>
          )}
          <div className="space-y-1.5">
            {events?.map(event => (
              <div key={event.id} className="text-xs">
                <span className="font-mono" style={{ color: 'var(--color-text)' }}>{event.event_type}</span>
                <span className="ml-2" style={{ color: 'var(--color-text-secondary)' }}>{relativeTime(event.created_at)}</span>
                {event.actor && <span className="ml-2" style={{ color: 'var(--color-text-secondary)' }}>by {event.actor}</span>}
                {payloadSummary(event.payload) && (
                  <div className="font-mono" style={{ color: 'var(--color-text-tertiary)' }}>
                    {payloadSummary(event.payload)}
                  </div>
                )}
              </div>
            ))}
          </div>
        </section>
      </div>

      {dialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4" role="dialog" aria-modal="true"
          aria-labelledby="workspace-detail-dialog-title" style={{ background: 'rgba(0,0,0,0.5)' }}>
          <div className="w-full max-w-md rounded-xl border p-5"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <div className="flex items-start justify-between gap-4">
              <h3 id="workspace-detail-dialog-title" className="font-semibold">
                {dialog === 'retry' && 'Retry bootstrap / start'}
                {dialog === 'reset' && 'Reset workspace'}
                {dialog === 'delete' && 'Delete workspace'}
                {dialog === 'restore-new' && '恢复到新 Workspace'}
                {dialog === 'restore-dr' && '从备份原地恢复'}
                {dialog === 'delete-archive' && '删除备份'}
                {dialog === 'upgrade-image' && '升级 Workspace 镜像'}
              </h3>
              <button type="button" onClick={closeDialog} aria-label="Close" className="p-1 rounded cursor-pointer hover:opacity-70">
                <X size={18} />
              </button>
            </div>

            {dialog === 'retry' && (
              <>
                <p className="text-sm mt-2" style={{ color: 'var(--color-text-secondary)' }}>
                  Restarts the workspace runtime on the existing volume and runs initialization scripts without deleting your files.
                </p>
                <label className="mt-4 flex items-center gap-2 text-sm cursor-pointer">
                  <input
                    type="checkbox"
                    checked={rerunBootstrap}
                    onChange={event => setRerunBootstrap(event.target.checked)}
                  />
                  <span>Also re-run bootstrap.sh</span>
                </label>
              </>
            )}

            {(dialog === 'reset' || dialog === 'delete') && (
              <>
                <p className="text-sm mt-2" style={{ color: 'var(--color-text-secondary)' }}>
                  {dialog === 'reset'
                    ? 'The volume is replaced with an empty one. All files in this workspace are permanently deleted.'
                    : `The workspace stops and is hidden. Its volume is kept for ${PURGE_AFTER_DAYS} days, then permanently deleted.`}
                </p>
                <label htmlFor="workspace-detail-confirm-name" className="block text-sm font-medium mt-4 mb-2">
                  Type <span className="font-mono">{workspace.name}</span> to confirm
                </label>
                <input
                  id="workspace-detail-confirm-name"
                  value={confirmName}
                  onChange={event => setConfirmName(event.target.value)}
                  autoFocus
                  className="w-full rounded-lg border px-3 py-2 text-sm outline-none"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }}
                />
              </>
            )}

            {dialog === 'restore-new' && selectedArchive && (
              <>
                <p className="text-sm mt-2" style={{ color: 'var(--color-text-secondary)' }}>
                  将从 gen {selectedArchive.volume_generation} 备份（{formatBytes(selectedArchive.size_bytes)}）创建一个新的 Workspace，不影响当前工作区数据。
                </p>
                <label htmlFor="workspace-restore-new-name" className="block text-sm font-medium mt-4 mb-2">
                  新 Workspace 名称
                </label>
                <input
                  id="workspace-restore-new-name"
                  value={restoreNewName}
                  onChange={event => setRestoreNewName(event.target.value)}
                  placeholder={`${workspace.name}-restored`}
                  autoFocus
                  className="w-full rounded-lg border px-3 py-2 text-sm outline-none"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }}
                />
                {trimmedRestoreName === workspace.name && (
                  <p className="text-xs mt-1.5" style={{ color: '#dc2626' }}>
                    新名称不能与当前 Workspace 相同。
                  </p>
                )}
              </>
            )}

            {dialog === 'restore-dr' && selectedArchive && (
              <p className="text-sm mt-2" style={{ color: 'var(--color-text-secondary)' }}>
                将为当前工作区创建新代际数据卷并从 gen {selectedArchive.volume_generation} 备份（{formatBytes(selectedArchive.size_bytes)}）恢复数据。
              </p>
            )}

            {dialog === 'delete-archive' && selectedArchive && (
              <p className="text-sm mt-2" style={{ color: 'var(--color-text-secondary)' }}>
                确认删除 gen {selectedArchive.volume_generation} 的备份吗？删除后归档数据将从存储中异步清除且不可恢复。
              </p>
            )}

            {dialog === 'upgrade-image' && (
              <>
                <div className="mt-3 rounded-lg border px-3 py-2 text-xs flex items-center gap-2"
                  style={{ borderColor: '#f59e0b', background: 'rgba(245,158,11,0.08)', color: '#b45309' }}>
                  <AlertTriangle size={14} className="shrink-0" />
                  <span>将先自动创建备份；若备份失败则拒绝升级，现有卷和镜像保持不变。</span>
                </div>
                <label htmlFor="workspace-upgrade-image-input" className="block text-sm font-medium mt-4 mb-2">
                  目标镜像
                </label>
                <input
                  id="workspace-upgrade-image-input"
                  value={targetImage}
                  onChange={event => setTargetImage(event.target.value)}
                  placeholder="registry/core-ai-sandbox:new-tag"
                  autoFocus
                  className="w-full rounded-lg border px-3 py-2 text-sm font-mono outline-none"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }}
                />
              </>
            )}

            <div className="mt-5 flex justify-end gap-2">
              <button type="button" onClick={closeDialog}
                className="px-3 py-2 rounded-lg text-sm font-medium cursor-pointer"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                Cancel
              </button>
              <button
                type="button"
                onClick={submitDialog}
                disabled={
                  busy
                  || ((dialog === 'reset' || dialog === 'delete') && confirmName !== workspace.name)
                  || (dialog === 'restore-new' && !restoreNameValid)
                  || (dialog === 'upgrade-image' && !targetImageValid)
                }
                className="px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
                style={{
                  background: (dialog === 'reset' || dialog === 'delete' || dialog === 'delete-archive' || dialog === 'restore-dr')
                    ? '#dc2626'
                    : 'var(--color-primary)',
                }}>
                {dialog === 'retry' && 'Confirm retry'}
                {dialog === 'reset' && 'Reset workspace'}
                {dialog === 'delete' && 'Delete workspace'}
                {dialog === 'restore-new' && '确认恢复'}
                {dialog === 'restore-dr' && '确认原地恢复'}
                {dialog === 'delete-archive' && '确认删除备份'}
                {dialog === 'upgrade-image' && '备份并升级'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
