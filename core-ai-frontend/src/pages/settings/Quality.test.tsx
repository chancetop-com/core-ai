import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AuthContext, type AuthUser } from '../../api/auth';
import Quality from './Quality';

vi.mock('./quality/AgentQualityList', () => ({ default: () => <div>agent rules</div> }));
vi.mock('./quality/SkillQualityList', () => ({ default: () => <div>skill rules</div> }));
vi.mock('./quality/QualityExemptionsPage', () => ({ default: () => <div>exemption list</div> }));

function renderQuality(user: AuthUser, entry = '/quality') {
  render(
    <AuthContext.Provider value={{ user, login: vi.fn(), logout: vi.fn() }}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/quality" element={<Quality />} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('Quality page tabs', () => {
  it('switches between agent, skill rules and exemptions for an admin', async () => {
    renderQuality({ apiKey: 'k', userId: 'admin-1', name: 'Admin', role: 'admin', permissions: ['*'] });

    expect(screen.getByText('agent rules')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: /skill quality/i }));

    expect(screen.getByText('skill rules')).toBeTruthy();
    expect(screen.queryByText('agent rules')).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: /exemptions/i }));

    expect(screen.getByText('exemption list')).toBeTruthy();
    expect(screen.queryByText('skill rules')).toBeNull();
  });

  it('hides the skill and exemptions tabs from a non-admin and falls back to agent rules', () => {
    renderQuality(
      { apiKey: 'k', userId: 'qa-1', name: 'QA', role: 'user', permissions: ['agent.quality.manage'] },
      '/quality?tab=skill',
    );

    expect(screen.queryByRole('button', { name: /skill quality/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /exemptions/i })).toBeNull();
    expect(screen.getByText('agent rules')).toBeTruthy();
  });
});
