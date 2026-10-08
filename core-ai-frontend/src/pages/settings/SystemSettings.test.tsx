import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { api, type SystemSettings as SystemSettingsData } from '../../api/client';
import SystemSettings from './SystemSettings';

function mockLoad(settings: SystemSettingsData) {
  vi.spyOn(api.systemSettings, 'get').mockResolvedValue(settings);
  vi.spyOn(api.gateway, 'listModels').mockResolvedValue({ models: [] });
}

async function openTab(name: string) {
  await userEvent.click(await screen.findByRole('button', { name }));
}

describe('SystemSettings sub-tabs', () => {
  it('switches between configuration sections', async () => {
    mockLoad({});

    render(<SystemSettings />);

    expect(await screen.findByText('Memory Extraction')).toBeTruthy();
    expect(screen.getByText('Default LLM Model')).toBeTruthy();
    expect(screen.queryByText('Object Storage')).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'Storage' }));
    expect(screen.getByText('Object Storage')).toBeTruthy();
    expect(screen.queryByText('Memory Extraction')).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'Sandbox' }));
    expect(screen.getByText('Sandbox Resume')).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: 'Skills' }));
    expect(screen.getByText('Skill Repo Sync')).toBeTruthy();
    expect(screen.queryByText('Sandbox Resume')).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'Integrations' }));
    expect(screen.getByText('Azure Speech')).toBeTruthy();
    expect(screen.getByText('GitHub App')).toBeTruthy();
    expect(screen.queryByText('Object Storage')).toBeNull();
  });
});

describe('SystemSettings sandbox resume control', () => {
  it('shows the requested and server-authoritative runtime states', async () => {
    mockLoad({
      sandbox_snapshot_enabled: true,
      sandbox_snapshot_deployment_allowed: true,
      sandbox_snapshot_storage_ready: false,
      sandbox_snapshot_effective: false,
    });

    render(<SystemSettings />);
    await openTab('Sandbox');

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
    await openTab('Sandbox');

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
    await openTab('Sandbox');
    await userEvent.click(await screen.findByRole('checkbox', { name: /enable filesystem snapshot and resume/i }));
    await userEvent.click(screen.getByRole('button', { name: /save settings/i }));

    expect(update).toHaveBeenCalledWith(expect.objectContaining({ sandbox_snapshot_enabled: true }));
    expect(await screen.findByText('Active')).toBeTruthy();
  });
});

describe('SystemSettings skill repo sync control', () => {
  it('defaults to enabled with the default interval when unset', async () => {
    mockLoad({});

    render(<SystemSettings />);
    await openTab('Skills');

    const toggle = await screen.findByRole<HTMLInputElement>('checkbox', { name: /keep repo-sourced skills up to date/i });
    expect(toggle.checked).toBe(true);
    const interval = screen.getByRole<HTMLInputElement>('spinbutton', { name: /sync interval/i });
    expect(interval.value).toBe('30');
    expect(interval.disabled).toBe(false);
  });

  it('sends the switch and interval when saving', async () => {
    mockLoad({ skill_repo_sync_enabled: true, skill_repo_sync_interval_minutes: 30 });
    const update = vi.spyOn(api.systemSettings, 'update').mockResolvedValue({
      skill_repo_sync_enabled: false,
      skill_repo_sync_interval_minutes: 60,
    });

    render(<SystemSettings />);
    await openTab('Skills');

    const interval = await screen.findByRole<HTMLInputElement>('spinbutton', { name: /sync interval/i });
    await userEvent.clear(interval);
    await userEvent.type(interval, '60');
    await userEvent.click(screen.getByRole('checkbox', { name: /keep repo-sourced skills up to date/i }));
    expect(interval.disabled).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: /save settings/i }));

    expect(update).toHaveBeenCalledWith(expect.objectContaining({
      skill_repo_sync_enabled: false,
      skill_repo_sync_interval_minutes: 60,
    }));
  });

  it('blocks saving an out-of-range interval while enabled', async () => {
    mockLoad({});
    const update = vi.spyOn(api.systemSettings, 'update').mockResolvedValue({});

    render(<SystemSettings />);
    await openTab('Skills');

    const interval = await screen.findByRole<HTMLInputElement>('spinbutton', { name: /sync interval/i });
    await userEvent.clear(interval);
    await userEvent.type(interval, '3');
    await userEvent.click(screen.getByRole('button', { name: /save settings/i }));

    expect(update).not.toHaveBeenCalled();
    expect(await screen.findByText(/between 5 and 10080 minutes/i)).toBeTruthy();
  });
});
