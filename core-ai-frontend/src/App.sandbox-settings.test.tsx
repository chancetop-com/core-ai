import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Outlet } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { storeUser } from './api/auth';
import { api, authApi } from './api/client';
import * as capabilities from './api/capabilities';

vi.mock('./components/Layout', () => ({ default: () => <Outlet /> }));
vi.mock('./pages/for-you/ForYou', () => ({ default: () => <div>Personal dashboard</div> }));
vi.mock('./pages/settings/ApiKeys', () => ({ default: () => <div>API keys</div> }));

function signIn(role: string, permissions: string[]) {
  storeUser('test-key', 'test-user', 'Test User', role, permissions);
  vi.spyOn(authApi, 'me').mockResolvedValue({ user_id: 'test-user', name: 'Test User', role, permissions });
}

describe('System configuration sandbox permissions', () => {
  beforeEach(() => {
    const storage = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    });
    window.history.replaceState({}, '', '/settings/system');
    vi.spyOn(capabilities, 'fetchCapabilities').mockResolvedValue(capabilities.defaultCapabilities);
    vi.spyOn(api.sandboxSnapshotSettings, 'get').mockResolvedValue({ retention_days: 14 });
    vi.spyOn(api.sandboxSnapshotSettings, 'update').mockResolvedValue({ retention_days: 21 });
    vi.spyOn(api.systemSettings, 'get').mockResolvedValue({
      sandbox_snapshot_enabled: true,
      sandbox_snapshot_deployment_allowed: true,
      sandbox_snapshot_storage_ready: true,
      sandbox_snapshot_effective: true,
    });
    vi.spyOn(api.systemSettings, 'update').mockResolvedValue({});
    vi.spyOn(api.gateway, 'listModels').mockResolvedValue({ models: [] });
  });

  it.each([
    { permissions: ['sandbox.snapshot.manage'] },
    { permissions: ['sandbox.snapshot.manage', 'system.manage'] },
  ])('limits a nonadmin with $permissions to retention even when another tab is requested', async ({ permissions }) => {
    signIn('snapshot-manager', permissions);
    window.history.replaceState({}, '', '/settings/system?tab=storage');
    render(<App />);

    const input = await screen.findByRole<HTMLInputElement>('spinbutton', { name: /retention period/i });
    expect(input.value).toBe('14');
    expect(screen.getByRole('heading', { name: 'System Configuration' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'System Configuration' }).getAttribute('href')).toBe('/settings/system');
    expect(screen.queryByRole('link', { name: 'Sandbox' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Sandbox' })).toBeTruthy();
    for (const name of ['LLM Models', 'Media & Tools', 'Storage', 'Skills', 'Runtime', 'Integrations', 'Save Settings']) {
      expect(screen.queryByRole('button', { name })).toBeNull();
    }
    expect(screen.queryByRole('checkbox', { name: /enable filesystem snapshot/i })).toBeNull();
    expect(screen.queryByText('Private storage')).toBeNull();
    expect(api.systemSettings.get).not.toHaveBeenCalled();
    expect(api.gateway.listModels).not.toHaveBeenCalled();

    fireEvent.change(input, { target: { value: '21' } });
    await userEvent.click(screen.getByRole('button', { name: /save retention/i }));

    expect(await screen.findByRole('status')).toHaveProperty('textContent', 'Sandbox snapshot settings saved.');
    expect(api.sandboxSnapshotSettings.update).toHaveBeenCalledWith({ retention_days: 21 });
    expect(api.systemSettings.update).not.toHaveBeenCalled();
  });

  it('shows the admin sandbox controls and saves retention independently', async () => {
    signIn('admin', ['*']);
    render(<App />);
    await userEvent.click(await screen.findByRole('button', { name: 'Sandbox' }));

    expect(screen.getByRole<HTMLInputElement>('checkbox', { name: /enable filesystem snapshot/i }).checked).toBe(true);
    expect(screen.getByText('Active')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Storage' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Save Settings' })).toBeTruthy();
    const input = await screen.findByRole('spinbutton', { name: /retention period/i });
    fireEvent.change(input, { target: { value: '21' } });
    await userEvent.click(screen.getByRole('button', { name: /save retention/i }));

    expect(await screen.findByRole('status')).toBeTruthy();
    expect(api.sandboxSnapshotSettings.update).toHaveBeenCalledWith({ retention_days: 21 });
    expect(api.systemSettings.update).not.toHaveBeenCalled();
    expect(api.systemSettings.get).toHaveBeenCalledTimes(1);
    expect(api.gateway.listModels).toHaveBeenCalledTimes(1);
    expect(window.location.search).toBe('?tab=sandbox');
  });

  it.each([
    { role: 'snapshot-manager', permissions: ['sandbox.snapshot.manage'] },
    { role: 'admin', permissions: ['*'] },
  ])('redirects the legacy URL to the sandbox tab for $role', async ({ role, permissions }) => {
    signIn(role, permissions);
    window.history.replaceState({}, '', '/settings/sandbox');
    render(<App />);

    expect(await screen.findByRole('spinbutton', { name: /retention period/i })).toBeTruthy();
    expect(window.location.pathname).toBe('/settings/system');
    expect(window.location.search).toBe('?tab=sandbox');
    expect(screen.getAllByRole('link', { name: 'System Configuration' })).toHaveLength(1);
    expect(screen.queryByRole('link', { name: 'Sandbox' })).toBeNull();
    expect(screen.queryByText('Memory Extraction')).toBeNull();
  });

  it.each([
    { path: '/settings/system', permissions: ['dashboard.view'] },
    { path: '/settings/system', permissions: ['dashboard.view', 'system.manage'] },
    { path: '/settings/sandbox', permissions: ['dashboard.view'] },
  ])('blocks unauthorized access to $path with $permissions before any settings request', async ({ path, permissions }) => {
    signIn('user', permissions);
    window.history.replaceState({}, '', path);
    render(<App />);

    expect(await screen.findByText('Personal dashboard')).toBeTruthy();
    expect(window.location.pathname).toBe('/for-you');
    expect(api.sandboxSnapshotSettings.get).not.toHaveBeenCalled();
    expect(api.systemSettings.get).not.toHaveBeenCalled();
    expect(api.gateway.listModels).not.toHaveBeenCalled();
  });

  it.each([
    { permissions: ['dashboard.view'] },
    { permissions: ['dashboard.view', 'system.manage'] },
  ])('hides the configuration link from a nonadmin with $permissions', async ({ permissions }) => {
    signIn('user', permissions);
    window.history.replaceState({}, '', '/settings');
    render(<App />);

    await waitFor(() => expect(screen.getByRole('link', { name: 'Security' })).toBeTruthy());
    expect(screen.queryByRole('link', { name: 'Sandbox' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'System Configuration' })).toBeNull();
  });

  it('lets scoped users recover a failed retention load without fetching admin data', async () => {
    signIn('snapshot-manager', ['sandbox.snapshot.manage']);
    vi.mocked(api.sandboxSnapshotSettings.get).mockRejectedValueOnce(new Error('Settings unavailable'));
    render(<App />);

    expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Settings unavailable');
    expect(screen.queryByRole('button', { name: /save retention/i })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: /refresh retention/i }));

    expect((await screen.findByRole<HTMLInputElement>('spinbutton', { name: /retention period/i })).value).toBe('14');
    expect(api.sandboxSnapshotSettings.get).toHaveBeenCalledTimes(2);
    expect(api.sandboxSnapshotSettings.update).not.toHaveBeenCalled();
    expect(api.systemSettings.get).not.toHaveBeenCalled();
    expect(api.gateway.listModels).not.toHaveBeenCalled();
  });
});
