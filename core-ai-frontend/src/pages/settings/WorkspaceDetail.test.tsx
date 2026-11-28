import { readFileSync } from 'node:fs';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type WorkspaceArchiveView, type WorkspaceEventView, type WorkspaceUsageView, type WorkspaceView } from '../../api/client';
import AgentSelector from '../chat/components/AgentSelector';
import Workspaces from './Workspaces';
import WorkspaceDetail from './WorkspaceDetail';

const GIB = 1024 ** 3;

function makeWorkspace(overrides: Partial<WorkspaceView> = {}): WorkspaceView {
  return {
    id: 'ws-1',
    name: 'dev-workspace',
    agent_id: 'default-assistant',
    is_default: true,
    volume_status: 'READY',
    runtime_status: 'READY',
    active_volume_generation: 0,
    runtime_generation: 3,
    size_gb: 32,
    used_bytes: 10 * GIB,
    capacity_bytes: 32 * GIB,
    storage_class: 'managed-csi',
    profile: 'workspace-default',
    mount_path: '/workspace',
    network_enabled: true,
    idle_sleep_minutes: 15,
    last_used_at: new Date(Date.now() - 2 * 60_000).toISOString(),
    last_woken_at: new Date(Date.now() - 10 * 60_000).toISOString(),
    last_slept_at: new Date(Date.now() - 120 * 60_000).toISOString(),
    bootstrap_completed_at: new Date(Date.now() - 24 * 3600_000).toISOString(),
    bootstrap_script_hash: 'abcdef1234567890abcdef',
    start_script_hash: '123456abcdef7890123456',
    bootstrap_script_changed: false,
    last_bootstrap_log: '[/workspace/.core-ai/logs/bootstrap-1.log]\n' + Array.from({ length: 25 }, (_, i) => `line-${i + 1}`).join('\n'),
    env_file_present: true,
    created_at: new Date(Date.now() - 48 * 3600_000).toISOString(),
    updated_at: new Date().toISOString(),
    ...overrides,
  };
}

function renderDetail(id = 'ws-1') {
  return render(
    <MemoryRouter initialEntries={[`/settings/workspaces/${id}`]}>
      <Routes>
        <Route path="/settings/workspaces/:id" element={<WorkspaceDetail />} />
        <Route path="/settings" element={<div>Settings Home</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('WorkspaceDetail page', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders status, usage, wake/sleep times, env presence, bound agent, and truncated bootstrap log', async () => {
    vi.spyOn(api.workspaces, 'get').mockResolvedValue(makeWorkspace());
    const usage: WorkspaceUsageView = {
      used_bytes: 10 * GIB,
      capacity_bytes: 32 * GIB,
      available_bytes: 22 * GIB,
      inodes_used: 5000,
      inodes_total: 1000000,
      sampled_at: new Date(Date.now() - 3 * 60_000).toISOString(),
    };
    vi.spyOn(api.workspaces, 'getUsage').mockResolvedValue(usage);
    vi.spyOn(api.workspaces, 'events').mockResolvedValue([
      { id: 'ev-1', event_type: 'woken', actor: 'u1', payload: '{"runtimeGeneration":3}', created_at: new Date().toISOString() },
    ]);
    vi.spyOn(api.workspaces, 'listArchives').mockResolvedValue([]);

    renderDetail();

    expect(await screen.findByText('dev-workspace')).toBeTruthy();
    expect(screen.getByText('Running')).toBeTruthy();
    expect(screen.getByText('default-assistant')).toBeTruthy();
    expect(screen.getByText('/workspace/.core-ai/env present')).toBeTruthy();
    expect(screen.getByText('10.0 GiB / 32.0 GiB (31%)')).toBeTruthy();
    expect(screen.getByText('22.0 GiB')).toBeTruthy();
    expect(screen.getByText('10m ago')).toBeTruthy();
    expect(screen.getByText('2h ago')).toBeTruthy();
    expect(screen.getByText('abcdef123456')).toBeTruthy();
    expect(screen.getByText('123456abcdef')).toBeTruthy();
    expect(screen.getByText('Show full log')).toBeTruthy();
    expect(screen.getByText('woken')).toBeTruthy();

    await userEvent.click(screen.getByText('Show full log'));
    expect(screen.getByText('Show truncated log')).toBeTruthy();
  });

  it('renders ERROR state with retry confirmation dialog and optional bootstrap rerun', async () => {
    vi.spyOn(api.workspaces, 'get').mockResolvedValue(makeWorkspace({
      runtime_status: 'ERROR',
      error_code: 'WORKSPACE_START_FAILED',
      error_message: 'start.sh exited with code 7',
      bootstrap_script_changed: true,
      env_file_present: false,
    }));
    vi.spyOn(api.workspaces, 'getUsage').mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));
    vi.spyOn(api.workspaces, 'events').mockResolvedValue([]);
    vi.spyOn(api.workspaces, 'listArchives').mockResolvedValue([]);
    const retry = vi.spyOn(api.workspaces, 'retry').mockResolvedValue(makeWorkspace({ runtime_status: 'PROVISIONING' }));

    renderDetail();

    expect(await screen.findByText('WORKSPACE_START_FAILED')).toBeTruthy();
    expect(screen.getByText('start.sh exited with code 7')).toBeTruthy();
    expect(screen.getByText('No env file')).toBeTruthy();
    expect(screen.getByText(/bootstrap\.sh changed since last run/i)).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: /retry bootstrap\/start/i }));
    expect(screen.getByRole('dialog')).toBeTruthy();
    await userEvent.click(screen.getByLabelText(/also re-run bootstrap\.sh/i));
    await userEvent.click(screen.getByRole('button', { name: 'Confirm retry' }));

    expect(retry).toHaveBeenCalledWith('ws-1', { rerun_bootstrap: true });
  });

  it('requires typing workspace name before confirming reset or delete', async () => {
    vi.spyOn(api.workspaces, 'get').mockResolvedValue(makeWorkspace());
    vi.spyOn(api.workspaces, 'getUsage').mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));
    vi.spyOn(api.workspaces, 'events').mockResolvedValue([]);
    vi.spyOn(api.workspaces, 'listArchives').mockResolvedValue([]);
    const reset = vi.spyOn(api.workspaces, 'reset').mockResolvedValue(makeWorkspace());
    const remove = vi.spyOn(api.workspaces, 'delete').mockResolvedValue(undefined);

    renderDetail();
    await screen.findByText('dev-workspace');

    await userEvent.click(screen.getByRole('button', { name: 'Reset' }));
    const resetConfirm = screen.getByRole('button', { name: 'Reset workspace' }) as HTMLButtonElement;
    expect(resetConfirm.disabled).toBe(true);
    await userEvent.type(screen.getByLabelText(/type/i), 'dev-workspace');
    expect(resetConfirm.disabled).toBe(false);
    await userEvent.click(resetConfirm);
    expect(reset).toHaveBeenCalledWith('ws-1', { confirm_name: 'dev-workspace' });

    await userEvent.click(screen.getByRole('button', { name: 'Delete' }));
    const deleteConfirm = screen.getByRole('button', { name: 'Delete workspace' }) as HTMLButtonElement;
    expect(deleteConfirm.disabled).toBe(true);
    await userEvent.type(screen.getByLabelText(/type/i), 'wrong');
    expect(deleteConfirm.disabled).toBe(true);
    await userEvent.clear(screen.getByLabelText(/type/i));
    await userEvent.type(screen.getByLabelText(/type/i), 'dev-workspace');
    await userEvent.click(deleteConfirm);
    expect(remove).toHaveBeenCalledWith('ws-1');
  });

  it('renders backup list, creates backup, validates restore-to-new dialog, and confirms archive deletion', async () => {
    const archiveReady: WorkspaceArchiveView = {
      id: 'arch-1',
      workspace_id: 'ws-1',
      volume_generation: 2,
      sha256: 'deadbeef',
      size_bytes: 5 * 1024 * 1024,
      file_count: 42,
      image: 'core-ai-sandbox:v1',
      status: 'READY',
      created_at: new Date(Date.now() - 15 * 60_000).toISOString(),
      updated_at: new Date(Date.now() - 15 * 60_000).toISOString(),
    };
    const archiveFailed: WorkspaceArchiveView = {
      id: 'arch-2',
      workspace_id: 'ws-1',
      volume_generation: 1,
      sha256: '',
      size_bytes: 0,
      status: 'FAILED',
      failure_reason: 'blob upload failed',
      created_at: new Date(Date.now() - 60 * 60_000).toISOString(),
      updated_at: new Date(Date.now() - 60 * 60_000).toISOString(),
    };

    vi.spyOn(api.workspaces, 'get').mockResolvedValue(makeWorkspace({
      image: 'core-ai-sandbox:v1',
      archive_count: 1,
      latest_archive_at: archiveReady.created_at,
      recoverable: false,
    }));
    vi.spyOn(api.workspaces, 'getUsage').mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));
    vi.spyOn(api.workspaces, 'events').mockResolvedValue([]);
    vi.spyOn(api.workspaces, 'listArchives').mockResolvedValue([archiveReady, archiveFailed]);
    const createArchive = vi.spyOn(api.workspaces, 'createArchive').mockResolvedValue(archiveReady);
    const restoreArchive = vi.spyOn(api.workspaces, 'restoreArchive').mockResolvedValue(makeWorkspace({ id: 'ws-2', name: 'dev-restored' }));
    const deleteArchive = vi.spyOn(api.workspaces, 'deleteArchive').mockResolvedValue(undefined);

    renderDetail();

    expect(await screen.findByText('备份与恢复')).toBeTruthy();
    expect(screen.queryByTestId('volume-error-recovery-banner')).toBeNull();
    expect(screen.getByText('5.0 MiB')).toBeTruthy();
    expect(screen.getByText('gen 2')).toBeTruthy();
    expect(screen.getByText('READY')).toBeTruthy();
    expect(screen.getByText('FAILED')).toBeTruthy();
    expect(screen.getByText('blob upload failed')).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: '立即备份' }));
    expect(createArchive).toHaveBeenCalledWith('ws-1');

    // Restore to new Workspace dialog validation
    await userEvent.click(screen.getByRole('button', { name: '恢复到新 Workspace' }));
    const confirmRestore = screen.getByRole('button', { name: '确认恢复' }) as HTMLButtonElement;
    expect(confirmRestore.disabled).toBe(true);
    const nameInput = screen.getByLabelText('新 Workspace 名称');
    await userEvent.type(nameInput, 'dev-workspace');
    expect(confirmRestore.disabled).toBe(true);
    expect(screen.getByText('新名称不能与当前 Workspace 相同。')).toBeTruthy();
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, 'dev-restored');
    expect(confirmRestore.disabled).toBe(false);
    await userEvent.click(confirmRestore);
    expect(restoreArchive).toHaveBeenCalledWith('ws-1', { archive_id: 'arch-1', new_name: 'dev-restored' });

    // Delete backup requires secondary confirmation
    const deleteButtons = screen.getAllByRole('button', { name: '删除备份' });
    await userEvent.click(deleteButtons[0]);
    expect(screen.getByRole('dialog')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: '确认删除备份' }));
    expect(deleteArchive).toHaveBeenCalledWith('ws-1', 'arch-1');
  });

  it('shows volume ERROR recovery banner and supports image upgrade checkpoint notice', async () => {
    const archiveReady: WorkspaceArchiveView = {
      id: 'arch-dr',
      workspace_id: 'ws-1',
      volume_generation: 0,
      sha256: 'abc',
      size_bytes: 1024 * 1024,
      status: 'READY',
      created_at: new Date(Date.now() - 5 * 60_000).toISOString(),
      updated_at: new Date(Date.now() - 5 * 60_000).toISOString(),
    };

    // Case 1: volume ERROR + recoverable shows prominent banner with jump link
    vi.spyOn(api.workspaces, 'get').mockResolvedValue(makeWorkspace({
      volume_status: 'ERROR',
      recoverable: true,
      archive_count: 1,
      error_code: 'WORKSPACE_VOLUME_UNAVAILABLE',
      error_message: 'workspace volume is missing',
    }));
    vi.spyOn(api.workspaces, 'getUsage').mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));
    vi.spyOn(api.workspaces, 'events').mockResolvedValue([]);
    vi.spyOn(api.workspaces, 'listArchives').mockResolvedValue([archiveReady]);
    const restoreArchive = vi.spyOn(api.workspaces, 'restoreArchive').mockResolvedValue(makeWorkspace());

    const { unmount } = renderDetail();
    const banner = await screen.findByTestId('volume-error-recovery-banner');
    expect(banner.textContent).toContain('卷不可用，可从备份恢复');
    const jumpLink = screen.getByRole('link', { name: /前往备份与恢复/i });
    expect(jumpLink.getAttribute('href')).toBe('#workspace-archives-panel');

    await userEvent.click(screen.getByRole('button', { name: '原地恢复' }));
    await userEvent.click(screen.getByRole('button', { name: '确认原地恢复' }));
    expect(restoreArchive).toHaveBeenCalledWith('ws-1', { archive_id: 'arch-dr' });
    unmount();

    // Case 2: healthy volume allows image upgrade and shows auto-backup checkpoint notice
    vi.spyOn(api.workspaces, 'get').mockResolvedValue(makeWorkspace({
      volume_status: 'READY',
      image: 'core-ai-sandbox:v1',
      recoverable: false,
    }));
    const updateWorkspace = vi.spyOn(api.workspaces, 'update').mockResolvedValue(makeWorkspace({ image: 'core-ai-sandbox:v2' }));

    renderDetail();
    await screen.findByText('dev-workspace');
    expect(screen.queryByTestId('volume-error-recovery-banner')).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: '升级镜像' }));
    expect(screen.getByText(/将先自动创建备份/)).toBeTruthy();
    const upgradeSubmit = screen.getByRole('button', { name: '备份并升级' }) as HTMLButtonElement;
    expect(upgradeSubmit.disabled).toBe(true);
    const imageInput = screen.getByLabelText('目标镜像');
    await userEvent.clear(imageInput);
    await userEvent.type(imageInput, 'core-ai-sandbox:v2');
    expect(upgradeSubmit.disabled).toBe(false);
    await userEvent.click(upgradeSubmit);
    expect(updateWorkspace).toHaveBeenCalledWith('ws-1', { image: 'core-ai-sandbox:v2' });
  });
});

if (process.env.DEV_WORKSPACE_SNAPSHOT) {
  const snapshot = JSON.parse(readFileSync(process.env.DEV_WORKSPACE_SNAPSHOT, 'utf8')) as {
    list: WorkspaceView[];
    detail: WorkspaceView;
    usage: WorkspaceUsageView | null;
    events: WorkspaceEventView[];
  };

  describe('Workspace UI with live dev-ai snapshot', () => {
    it('renders Workspaces, WorkspaceDetail, and AgentSelector chip from real dev-ai payloads', async () => {
      vi.spyOn(api.workspaces, 'list').mockResolvedValue(snapshot.list);
      vi.spyOn(api.workspaces, 'get').mockResolvedValue(snapshot.detail);
      vi.spyOn(api.workspaces, 'getUsage').mockImplementation(() =>
        snapshot.usage ? Promise.resolve(snapshot.usage) : Promise.reject(Object.assign(new Error('not found'), { status: 404 })),
      );
      vi.spyOn(api.workspaces, 'events').mockResolvedValue(snapshot.events);

      const { unmount: unmountList } = render(
        <MemoryRouter>
          <Workspaces />
        </MemoryRouter>,
      );
      const link = await screen.findByRole('link', { name: snapshot.detail.name });
      expect(link.getAttribute('href')).toBe(`/settings/workspaces/${snapshot.detail.id}`);
      unmountList();

      const { unmount: unmountDetail } = renderDetail(snapshot.detail.id);
      expect(await screen.findByRole('heading', { name: snapshot.detail.name })).toBeTruthy();
      expect(screen.getByText('Running')).toBeTruthy();
      if (snapshot.detail.agent_id) {
        expect(screen.getByText(snapshot.detail.agent_id)).toBeTruthy();
      }
      expect(screen.getByText(snapshot.detail.env_file_present ? '/workspace/.core-ai/env present' : 'No env file')).toBeTruthy();
      expect(screen.getByRole('button', { name: /retry bootstrap\/start/i })).toBeTruthy();
      expect(screen.getByRole('button', { name: 'Reset' })).toBeTruthy();
      expect(screen.getByRole('button', { name: 'Delete' })).toBeTruthy();
      unmountDetail();

      render(
        <MemoryRouter>
          <AgentSelector
            status="idle"
            myAgents={[]}
            favoriteAgents={[]}
            selectedAgentId="default-assistant"
            onSelectAgent={vi.fn()}
            onToggleFavorite={vi.fn()}
            workspace={{
              id: snapshot.detail.id,
              name: snapshot.detail.name,
              runtimeStatus: snapshot.detail.runtime_status,
              volumeStatus: snapshot.detail.volume_status,
              usedBytes: snapshot.detail.used_bytes,
              capacityBytes: snapshot.detail.capacity_bytes,
              sizeGb: snapshot.detail.size_gb,
            }}
          />
        </MemoryRouter>,
      );
      const chip = screen.getByRole('link', { name: `Workspace ${snapshot.detail.name}` });
      expect(chip.getAttribute('href')).toBe(`/settings/workspaces/${snapshot.detail.id}`);
      expect(screen.getByTestId('workspace-chip-usage').textContent).toContain('2.1 MiB / 31.2 GiB');
    });
  });
}
