import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type WorkspaceUsageView, type WorkspaceView } from '../../api/client';
import Workspaces from './Workspaces';

const MIB = 1024 ** 2;
const GIB = 1024 ** 3;

function workspace(overrides: Partial<WorkspaceView>): WorkspaceView {
  return {
    id: 'ws-1',
    name: 'default',
    agent_id: 'assistant:stephen@example.com',
    is_default: true,
    volume_status: 'READY',
    runtime_status: 'READY',
    active_volume_generation: 2,
    runtime_generation: 3,
    size_gb: 32,
    storage_class: 'managed-csi',
    profile: 'workspace-default',
    mount_path: '/workspace',
    network_enabled: true,
    idle_sleep_minutes: 15,
    last_used_at: new Date(Date.now() - 3 * 60_000).toISOString(),
    created_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
    ...overrides,
  };
}

describe('Workspaces settings', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders runtime status and sizes', async () => {
    vi.spyOn(api.workspaces, 'list').mockResolvedValue([
      workspace({}),
      workspace({ id: 'ws-2', name: 'default-agent-builder', agent_id: 'agent-builder', is_default: false, runtime_status: 'NONE' }),
    ]);

    render(<Workspaces />);

    expect(await screen.findByText('default-agent-builder')).toBeTruthy();
    expect(screen.getByText('Running')).toBeTruthy();
    expect(screen.getByText('Sleeping')).toBeTruthy();
    expect(screen.getAllByText('32 GB')).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Sleep default' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Wake default-agent-builder' })).toBeTruthy();
  });

  it('requires the exact workspace name before deleting', async () => {
    vi.spyOn(api.workspaces, 'list').mockResolvedValue([workspace({})]);
    const remove = vi.spyOn(api.workspaces, 'delete').mockResolvedValue(undefined);

    render(<Workspaces />);
    await userEvent.click(await screen.findByRole('button', { name: 'Delete default' }));

    const dialog = screen.getByRole('dialog');
    const confirm = screen.getByRole('button', { name: 'Delete workspace' });
    expect(dialog).toBeTruthy();
    expect((confirm as HTMLButtonElement).disabled).toBe(true);

    await userEvent.type(screen.getByLabelText(/type/i), 'wrong');
    expect((confirm as HTMLButtonElement).disabled).toBe(true);

    await userEvent.clear(screen.getByLabelText(/type/i));
    await userEvent.type(screen.getByLabelText(/type/i), 'default');
    await userEvent.click(confirm);

    expect(remove).toHaveBeenCalledWith('ws-1');
  });

  it('explains when persistent workspaces are disabled', async () => {
    vi.spyOn(api.workspaces, 'list').mockRejectedValue(
      Object.assign(new Error('persistent workspace is not enabled'), { errorCode: 'WORKSPACE_UNSUPPORTED' }));

    render(<Workspaces />);

    expect(await screen.findByText(/not enabled on this deployment/i)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /refresh/i })).toBeNull();
  });

  it('creates a workspace with the chosen size, profile and idle period', async () => {
    vi.spyOn(api.workspaces, 'list').mockResolvedValue([]);
    const create = vi.spyOn(api.workspaces, 'create').mockResolvedValue(workspace({ id: 'ws-new', name: 'scratch' }));

    render(<Workspaces />);
    await userEvent.click(screen.getByRole('button', { name: 'New workspace' }));

    await userEvent.type(screen.getByLabelText('Name'), 'scratch');
    await userEvent.selectOptions(screen.getByLabelText('Volume size'), '64');
    await userEvent.selectOptions(screen.getByLabelText('Runtime profile'), 'workspace-build');
    await userEvent.click(screen.getByRole('button', { name: 'Create workspace' }));

    expect(create).toHaveBeenCalledWith(expect.objectContaining({
      name: 'scratch',
      size_gb: 64,
      profile: 'workspace-build',
      idle_sleep_minutes: 15,
    }));
    expect(await screen.findByText(/scratch.+created/i)).toBeTruthy();
  });

  it('lists soft-deleted workspaces and restores them', async () => {
    vi.spyOn(api.workspaces, 'list').mockResolvedValue([
      workspace({ id: 'ws-gone', name: 'old-project', is_default: false, deleted_at: new Date().toISOString() }),
    ]);
    const undelete = vi.spyOn(api.workspaces, 'undelete')
      .mockResolvedValue(workspace({ id: 'ws-gone', name: 'old-project', is_default: false }));

    render(<Workspaces />);

    expect(await screen.findByText('Deleted workspaces')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: 'Restore' }));
    expect(undelete).toHaveBeenCalledWith('ws-gone');
  });

  it('shows lifecycle events in the detail row', async () => {
    vi.spyOn(api.workspaces, 'list').mockResolvedValue([workspace({})]);
    vi.spyOn(api.workspaces, 'events').mockResolvedValue([
      { id: 'event-1', event_type: 'created', actor: 'user-1', payload: '{"sizeGb":32}', created_at: new Date().toISOString() },
    ]);
    vi.spyOn(api.workspaces, 'getUsage').mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));

    render(<Workspaces />);
    await userEvent.click(await screen.findByRole('button', { name: 'Details for default' }));

    expect(await screen.findByText('created')).toBeTruthy();
    expect(screen.getByText(/sizeGb=32/)).toBeTruthy();
  });

  it('formats cached usage in list rows and renders em dash when unsampled', async () => {
    vi.spyOn(api.workspaces, 'list').mockResolvedValue([
      workspace({ id: 'ws-gib', name: 'big-repo', used_bytes: 12.5 * GIB, capacity_bytes: 32 * GIB }),
      workspace({ id: 'ws-mib', name: 'small-repo', is_default: false, used_bytes: 256 * MIB, capacity_bytes: 16 * GIB }),
      workspace({ id: 'ws-none', name: 'fresh-repo', is_default: false }),
    ]);

    render(<Workspaces />);

    expect(await screen.findByText('12.5 GiB / 32.0 GiB')).toBeTruthy();
    expect(screen.getByText('256 MiB / 16.0 GiB')).toBeTruthy();
    expect(screen.getByText('—')).toBeTruthy();
    expect(screen.queryByLabelText('High disk usage')).toBeNull();
  });

  it('shows warning styling when usage reaches 90 percent or more', async () => {
    vi.spyOn(api.workspaces, 'list').mockResolvedValue([
      workspace({ id: 'ws-warn', name: 'almost-full', used_bytes: 29.5 * GIB, capacity_bytes: 32 * GIB }),
      workspace({ id: 'ws-ok', name: 'plenty-left', is_default: false, used_bytes: 10 * GIB, capacity_bytes: 32 * GIB }),
    ]);

    render(<Workspaces />);

    const warnLabel = await screen.findByText('29.5 GiB / 32.0 GiB');
    const warnContainer = warnLabel.closest('[data-usage-warning="true"]');
    expect(warnContainer).toBeTruthy();
    expect(screen.getAllByLabelText('High disk usage')).toHaveLength(1);
  });

  it('fetches usage in details panel with loading and loaded states', async () => {
    vi.spyOn(api.workspaces, 'list').mockResolvedValue([workspace({})]);
    vi.spyOn(api.workspaces, 'events').mockResolvedValue([]);
    let resolveUsage!: (value: WorkspaceUsageView) => void;
    const getUsage = vi.spyOn(api.workspaces, 'getUsage').mockImplementation(
      () => new Promise<WorkspaceUsageView>(resolve => { resolveUsage = resolve; }));

    render(<Workspaces />);
    await userEvent.click(await screen.findByRole('button', { name: 'Details for default' }));

    expect(getUsage).toHaveBeenCalledWith('ws-1');
    expect(screen.getByText('Loading usage...')).toBeTruthy();

    resolveUsage({
      used_bytes: 10 * GIB,
      capacity_bytes: 32 * GIB,
      available_bytes: 22 * GIB,
      inodes_used: 12_345,
      inodes_total: 2_000_000,
      sampled_at: new Date(Date.now() - 2 * 60_000).toISOString(),
    });

    expect(await screen.findByText('10.0 GiB / 32.0 GiB (31%)')).toBeTruthy();
    expect(screen.getByText('22.0 GiB')).toBeTruthy();
    expect(screen.getByText(`${(12_345).toLocaleString()} / ${(2_000_000).toLocaleString()}`)).toBeTruthy();
    expect(screen.getByText('2m ago')).toBeTruthy();
  });

  it('shows an explicit no-sample state when usage returns 404', async () => {
    vi.spyOn(api.workspaces, 'list').mockResolvedValue([workspace({})]);
    vi.spyOn(api.workspaces, 'events').mockResolvedValue([]);
    vi.spyOn(api.workspaces, 'getUsage').mockRejectedValue(
      Object.assign(new Error('workspace usage not found'), { status: 404, errorCode: 'WORKSPACE_NOT_FOUND' }));

    render(<Workspaces />);
    await userEvent.click(await screen.findByRole('button', { name: 'Details for default' }));

    expect(await screen.findByText('No sample yet.')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('links each workspace name to its detail page and renders bootstrap and env fields in expanded row', async () => {
    vi.spyOn(api.workspaces, 'list').mockResolvedValue([
      workspace({
        id: 'ws-42',
        name: 'ml-box',
        env_file_present: true,
        bootstrap_completed_at: new Date(Date.now() - 10 * 60_000).toISOString(),
        last_woken_at: new Date(Date.now() - 5 * 60_000).toISOString(),
        last_slept_at: new Date(Date.now() - 60 * 60_000).toISOString(),
        last_bootstrap_log: '[/workspace/.core-ai/logs/bootstrap-1.log]\nboot ok',
      }),
    ]);
    vi.spyOn(api.workspaces, 'events').mockResolvedValue([]);
    vi.spyOn(api.workspaces, 'getUsage').mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }));

    render(<Workspaces />);
    const link = await screen.findByRole('link', { name: 'ml-box' });
    expect(link.getAttribute('href')).toBe('/settings/workspaces/ws-42');

    await userEvent.click(screen.getByRole('button', { name: 'Details for ml-box' }));
    expect(await screen.findByText('present')).toBeTruthy();
    expect(screen.getByText(/boot ok/)).toBeTruthy();
  });
});
