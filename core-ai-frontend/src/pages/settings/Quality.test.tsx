import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AuthContext, type AuthUser } from '../../api/auth';
import Quality from './Quality';

vi.mock('./quality/AgentQualityTab', () => ({ default: () => <div>agent rules</div> }));
vi.mock('./quality/SkillQualityTab', () => ({ default: () => <div>skill rules</div> }));

function renderQuality(user: AuthUser, entry = '/settings/quality') {
  render(
    <AuthContext.Provider value={{ user, login: vi.fn(), logout: vi.fn() }}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/settings/quality" element={<Quality />} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('Quality page tabs', () => {
  it('switches between agent and skill rules for an admin', async () => {
    renderQuality({ apiKey: 'k', userId: 'admin-1', name: 'Admin', role: 'admin', permissions: ['*'] });

    expect(screen.getByText('agent rules')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: /skill quality/i }));

    expect(screen.getByText('skill rules')).toBeTruthy();
    expect(screen.queryByText('agent rules')).toBeNull();
  });

  it('hides the skill tab from a non-admin and falls back to agent rules', () => {
    renderQuality(
      { apiKey: 'k', userId: 'qa-1', name: 'QA', role: 'user', permissions: ['agent.quality.manage'] },
      '/settings/quality?tab=skill',
    );

    expect(screen.queryByRole('button', { name: /skill quality/i })).toBeNull();
    expect(screen.getByText('agent rules')).toBeTruthy();
  });
});
