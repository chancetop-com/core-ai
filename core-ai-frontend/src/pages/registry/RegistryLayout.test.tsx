import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { CapabilitiesContext, defaultCapabilities } from '../../api/capabilities';
import type { AuthUser } from '../../api/auth';
import RegistryLayout, { RegistryHome } from './RegistryLayout';

const mockedUser = vi.hoisted(() => ({ current: null as AuthUser | null }));

vi.mock('../../api/auth', async importOriginal => {
  const actual = await importOriginal<typeof import('../../api/auth')>();
  return {
    ...actual,
    useAuth: () => ({ user: mockedUser.current, login: vi.fn(), logout: vi.fn() }),
  };
});

const admin: AuthUser = { apiKey: 'test-key', userId: 'test-user', name: 'Test User', role: 'admin', permissions: ['*'] };

function withCapabilities(children: ReactNode) {
  return (
    <CapabilitiesContext.Provider value={{ ...defaultCapabilities, datasetReports: true, systemPrompts: true }}>
      {children}
    </CapabilitiesContext.Provider>
  );
}

function renderRegistry(user: AuthUser = admin) {
  mockedUser.current = user;
  render(withCapabilities(
    <MemoryRouter initialEntries={['/agents']}>
      <RegistryLayout />
    </MemoryRouter>,
  ));
}

describe('Registry sub navigation', () => {
  it('lists every registry resource under Registry', () => {
    renderRegistry();

    const expected: [string, string][] = [
      ['Agents', '/agents'],
      ['Workflows', '/workflows'],
      ['System Prompts', '/system-prompts'],
      ['Webhook', '/triggers/webhook'],
      ['Channels', '/triggers/channels'],
      ['OpenClaw', '/triggers/openclaw'],
      ['Schedule', '/triggers/schedule'],
      ['Builtin Tools', '/tools/builtin'],
      ['MCP Servers', '/mcp'],
      ['API Tools', '/api-tools'],
      ['Skills', '/skills'],
      ['Datasets', '/datasets'],
      ['Reports', '/datasets/reports'],
      ['Plugins', '/plugins'],
      ['Quality', '/quality'],
    ];
    for (const [label, href] of expected) {
      expect(screen.getByRole('link', { name: label }).getAttribute('href')).toBe(href);
    }
    expect(screen.getByText('Registry')).toBeTruthy();
  });

  it('keeps projects at the top level instead of duplicating them in Registry', () => {
    renderRegistry();
    expect(screen.queryByRole('link', { name: 'Projects' })).toBeNull();
  });

  it('lands on the first page the user may open', () => {
    mockedUser.current = { apiKey: 'k', userId: 'u', name: 'User', role: 'user', permissions: ['workflow.view'] };
    render(withCapabilities(
      <MemoryRouter initialEntries={['/registry']}>
        <Routes>
          <Route path="/registry" element={<RegistryHome />} />
          <Route path="/workflows" element={<div>workflow list</div>} />
        </Routes>
      </MemoryRouter>,
    ));
    expect(screen.getByText('workflow list')).toBeTruthy();
  });
});
