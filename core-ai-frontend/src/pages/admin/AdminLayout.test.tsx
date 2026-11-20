import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import type { AuthUser } from '../../api/auth';
import AdminLayout from './AdminLayout';

const mockedUser = vi.hoisted(() => ({ current: null as AuthUser | null }));

vi.mock('../../api/auth', async importOriginal => {
  const actual = await importOriginal<typeof import('../../api/auth')>();
  return {
    ...actual,
    useAuth: () => ({ user: mockedUser.current, login: vi.fn(), logout: vi.fn() }),
  };
});

function renderAdmin(user: AuthUser) {
  mockedUser.current = user;
  render(
    <MemoryRouter initialEntries={['/admin']}>
      <AdminLayout />
    </MemoryRouter>,
  );
}

describe('Admin sub navigation', () => {
  it('shows the admin dashboard, management pages and cost alerts to an admin', () => {
    renderAdmin({ apiKey: 'k', userId: 'a', name: 'Admin', role: 'admin', permissions: ['*'] });

    expect(screen.getByRole('link', { name: 'Dashboard' }).getAttribute('href')).toBe('/admin/dashboard');
    expect(screen.getByRole('link', { name: 'User Management' }).getAttribute('href')).toBe('/admin/users');
    expect(screen.getByRole('link', { name: 'System Tasks' }).getAttribute('href')).toBe('/admin/tasks');
    expect(screen.getByRole('link', { name: 'System Configuration' }).getAttribute('href')).toBe('/admin/system');
    expect(screen.getByRole('link', { name: 'Cost Alerts' }).getAttribute('href')).toBe('/admin/cost-alerts');
  });

  it('limits a snapshot manager to system configuration', () => {
    renderAdmin({ apiKey: 'k', userId: 's', name: 'Snapshot', role: 'user', permissions: ['sandbox.snapshot.manage'] });

    expect(screen.getByRole('link', { name: 'System Configuration' })).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Dashboard' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'User Management' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'System Tasks' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Cost Alerts' })).toBeNull();
  });
});
