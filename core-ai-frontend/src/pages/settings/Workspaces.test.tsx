import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type WorkspaceView } from '../../api/client';
import Workspaces from './Workspaces';

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
});
