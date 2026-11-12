import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import Layout from './Layout';

vi.mock('../api/auth', async importOriginal => {
  const actual = await importOriginal<typeof import('../api/auth')>();
  return {
    ...actual,
    useAuth: () => ({
      user: { apiKey: 'test-key', userId: 'test-user', name: 'Test User', permissions: ['*'] },
      login: vi.fn(),
      logout: vi.fn(),
    }),
  };
});

vi.mock('../hooks/useTheme', () => ({
  useTheme: () => ({ dark: false, toggle: vi.fn() }),
}));

vi.mock('./QuickActionDialog', () => ({ default: () => null }));

function renderLayout() {
  render(
    <MemoryRouter initialEntries={['/for-you']}>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/for-you" element={<div>Personal dashboard</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

describe('Layout desktop download entry', () => {
  it('links to the desktop download route and opens it outside the console', () => {
    renderLayout();

    const link = screen.getByRole('link', { name: 'Download core-ai-desktop' });
    expect(link.getAttribute('href')).toBe('/downloads/desktop');
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    expect(screen.getByText('Download Desktop')).toBeTruthy();
  });

  it('keeps the download entry reachable when the sidebar is collapsed', async () => {
    renderLayout();

    await userEvent.click(screen.getByTitle('Collapse'));

    expect(screen.queryByText('Download Desktop')).toBeNull();
    expect(screen.getByRole('link', { name: 'Download core-ai-desktop' })).toBeTruthy();
  });
});
