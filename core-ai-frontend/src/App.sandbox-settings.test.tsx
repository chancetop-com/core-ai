import { render, screen, waitFor } from '@testing-library/react';
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

describe('Sandbox settings permissions', () => {
  beforeEach(() => {
    const storage = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    });
    window.history.replaceState({}, '', '/settings/sandbox');
    vi.spyOn(capabilities, 'fetchCapabilities').mockResolvedValue(capabilities.defaultCapabilities);
  });

  it.each([
    { role: 'snapshot-manager', permissions: ['sandbox.snapshot.manage'] },
    { role: 'admin', permissions: ['*'] },
  ])('allows $role to open sandbox settings', async ({ role, permissions }) => {
    signIn(role, permissions);
    vi.spyOn(api.sandboxSnapshotSettings, 'get').mockResolvedValue({ retention_days: 14 });

    render(<App />);

    expect(await screen.findByRole('spinbutton', { name: /retention period/i })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Sandbox' }).getAttribute('href')).toBe('/settings/sandbox');
    if (role !== 'admin') expect(screen.queryByRole('link', { name: 'System Configuration' })).toBeNull();
  });

  it('blocks users without the permission before loading settings', async () => {
    signIn('user', ['dashboard.view']);
    const get = vi.spyOn(api.sandboxSnapshotSettings, 'get');

    render(<App />);

    expect(await screen.findByText('Personal dashboard')).toBeTruthy();
    expect(window.location.pathname).toBe('/for-you');
    expect(get).not.toHaveBeenCalled();
  });

  it('hides the sandbox link from users without the permission', async () => {
    signIn('user', ['dashboard.view']);
    window.history.replaceState({}, '', '/settings');

    render(<App />);

    await waitFor(() => expect(screen.getByRole('link', { name: 'Security' })).toBeTruthy());
    expect(screen.queryByRole('link', { name: 'Sandbox' })).toBeNull();
  });
});
