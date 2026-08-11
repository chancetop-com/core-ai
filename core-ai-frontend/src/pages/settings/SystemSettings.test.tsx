import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { api, type SystemSettings as SystemSettingsData } from '../../api/client';
import SystemSettings from './SystemSettings';

function mockLoad(settings: SystemSettingsData) {
  vi.spyOn(api.systemSettings, 'get').mockResolvedValue(settings);
  vi.spyOn(api.gateway, 'listModels').mockResolvedValue({ models: [] });
}

describe('SystemSettings sandbox resume control', () => {
  it('shows the requested and server-authoritative runtime states', async () => {
    mockLoad({
      sandbox_snapshot_enabled: true,
      sandbox_snapshot_deployment_allowed: true,
      sandbox_snapshot_storage_ready: false,
      sandbox_snapshot_effective: false,
    });

    render(<SystemSettings />);

    const checkbox = await screen.findByRole<HTMLInputElement>('checkbox', { name: /enable filesystem snapshot and resume/i });
    expect(checkbox.checked).toBe(true);
    expect(screen.getByText('Allowed')).toBeTruthy();
    expect(screen.getByText('Not ready')).toBeTruthy();
    expect(screen.getByText('Inactive')).toBeTruthy();
  });

  it('disables the toggle when deployment blocks the capability', async () => {
    mockLoad({
      sandbox_snapshot_enabled: false,
      sandbox_snapshot_deployment_allowed: false,
      sandbox_snapshot_storage_ready: true,
      sandbox_snapshot_effective: false,
    });

    render(<SystemSettings />);

    const checkbox = await screen.findByRole<HTMLInputElement>('checkbox', { name: /enable filesystem snapshot and resume/i });
    expect(checkbox.disabled).toBe(true);
    expect(screen.getByText('Blocked')).toBeTruthy();
  });

  it('sends the toggle and refreshes status from the update response', async () => {
    mockLoad({
      sandbox_snapshot_enabled: false,
      sandbox_snapshot_deployment_allowed: true,
      sandbox_snapshot_storage_ready: true,
      sandbox_snapshot_effective: false,
    });
    const update = vi.spyOn(api.systemSettings, 'update').mockResolvedValue({
      sandbox_snapshot_enabled: true,
      sandbox_snapshot_deployment_allowed: true,
      sandbox_snapshot_storage_ready: true,
      sandbox_snapshot_effective: true,
    });

    render(<SystemSettings />);
    await userEvent.click(await screen.findByRole('checkbox', { name: /enable filesystem snapshot and resume/i }));
    await userEvent.click(screen.getByRole('button', { name: /save settings/i }));

    expect(update).toHaveBeenCalledWith(expect.objectContaining({ sandbox_snapshot_enabled: true }));
    expect(await screen.findByText('Active')).toBeTruthy();
  });
});
