import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { api, type SandboxSnapshotSettings } from '../../api/client';
import SnapshotRetentionSettings from './SnapshotRetentionSettings';

function mockLoad(settings: SandboxSnapshotSettings = { retention_days: 14 }) {
  return vi.spyOn(api.sandboxSnapshotSettings, 'get').mockResolvedValue(settings);
}

describe('Sandbox snapshot settings', () => {
  it('loads retention without accessing administrator settings', async () => {
    mockLoad();
    const adminSettings = vi.spyOn(api.systemSettings, 'get');
    const gatewayModels = vi.spyOn(api.gateway, 'listModels');

    render(<SnapshotRetentionSettings />);

    expect((await screen.findByRole<HTMLInputElement>('spinbutton', { name: /retention period/i })).value).toBe('14');
    expect(screen.getByText(/only to new snapshots/i)).toBeTruthy();
    expect(adminSettings).not.toHaveBeenCalled();
    expect(gatewayModels).not.toHaveBeenCalled();
  });

  it('saves the retention period and shows the server response', async () => {
    mockLoad();
    const update = vi.spyOn(api.sandboxSnapshotSettings, 'update').mockResolvedValue({ retention_days: 30 });

    render(<SnapshotRetentionSettings />);
    const input = await screen.findByRole<HTMLInputElement>('spinbutton', { name: /retention period/i });
    await userEvent.clear(input);
    await userEvent.type(input, '30');
    await userEvent.click(screen.getByRole('button', { name: /save retention/i }));

    expect(update).toHaveBeenCalledWith({ retention_days: 30 });
    expect(await screen.findByRole('status')).toHaveProperty('textContent', 'Sandbox snapshot settings saved.');
    expect(input.value).toBe('30');
  });

  it.each(['', '0', '3651', '1.5'])('rejects invalid retention %j before saving', async value => {
    mockLoad();
    const update = vi.spyOn(api.sandboxSnapshotSettings, 'update');

    render(<SnapshotRetentionSettings />);
    const input = await screen.findByRole('spinbutton', { name: /retention period/i });
    fireEvent.change(input, { target: { value } });
    await userEvent.click(screen.getByRole('button', { name: /save retention/i }));

    expect(update).not.toHaveBeenCalled();
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Retention period must be a whole number between 1 and 3650 days.');
  });

  it('allows retrying a failed load and never saves an unloaded default', async () => {
    const get = mockLoad();
    get.mockRejectedValueOnce(new Error('Settings unavailable'));
    const update = vi.spyOn(api.sandboxSnapshotSettings, 'update');

    render(<SnapshotRetentionSettings />);
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Settings unavailable');
    expect(screen.queryByRole('spinbutton')).toBeNull();
    expect(update).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: /refresh/i }));
    expect((await screen.findByRole<HTMLInputElement>('spinbutton', { name: /retention period/i })).value).toBe('14');
    expect(get).toHaveBeenCalledTimes(2);
  });

  it('preserves edits after a save fails', async () => {
    mockLoad();
    vi.spyOn(api.sandboxSnapshotSettings, 'update').mockRejectedValue(new Error('Permission denied'));

    render(<SnapshotRetentionSettings />);
    const input = await screen.findByRole<HTMLInputElement>('spinbutton', { name: /retention period/i });
    fireEvent.change(input, { target: { value: '30' } });
    await userEvent.click(screen.getByRole('button', { name: /save retention/i }));

    expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Permission denied');
    expect(input.value).toBe('30');
    expect(screen.queryByRole('status')).toBeNull();
    expect(screen.getByRole<HTMLButtonElement>('button', { name: /save retention/i }).disabled).toBe(false);
  });

  it('prevents concurrent edits and duplicate saves while saving', async () => {
    mockLoad();
    let completeSave!: (settings: SandboxSnapshotSettings) => void;
    const update = vi.spyOn(api.sandboxSnapshotSettings, 'update').mockReturnValue(new Promise(resolve => { completeSave = resolve; }));

    render(<SnapshotRetentionSettings />);
    const input = await screen.findByRole<HTMLInputElement>('spinbutton', { name: /retention period/i });
    await userEvent.click(screen.getByRole('button', { name: /save retention/i }));

    expect(input.disabled).toBe(true);
    expect(screen.getByRole<HTMLButtonElement>('button', { name: /refresh/i }).disabled).toBe(true);
    expect(screen.getByRole<HTMLButtonElement>('button', { name: /saving/i }).disabled).toBe(true);
    expect(update).toHaveBeenCalledTimes(1);

    await act(async () => { completeSave({ retention_days: 14 }); });
    expect(input.disabled).toBe(false);
  });
});
