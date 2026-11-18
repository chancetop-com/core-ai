import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { skillQualityApi, type SkillQualityRule } from '../../../api/quality';
import SkillQualityDetail from './SkillQualityDetail';

const RULE: SkillQualityRule = {
  key: 'SKILL.NAME_IDENTITY',
  revision: 1,
  name: 'Name matches directory',
  category: 'IDENTITY',
  enforcement: 'BLOCK',
  requirement: 'The SKILL.md name must follow the naming rules and match the uploaded directory name.',
};

function renderDetail() {
  render(
    <MemoryRouter initialEntries={[`/settings/quality/skill/${encodeURIComponent(RULE.key)}`]}>
      <Routes>
        <Route path="/settings/quality/skill/:ruleKey" element={<SkillQualityDetail />} />
        <Route path="/settings/quality" element={<div>quality list</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Skill quality detail', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(skillQualityApi, 'rules').mockResolvedValue({ policy_revision: 1, rules: [RULE] });
  });

  it('shows the code-defined rule with a locked enabled switch', async () => {
    renderDetail();

    expect(await screen.findByText('Name matches directory')).toBeTruthy();
    expect(screen.getByText('SKILL.NAME_IDENTITY')).toBeTruthy();
    expect(screen.getByText('Code-defined')).toBeTruthy();
    expect(screen.getByText(RULE.requirement!)).toBeTruthy();
    expect(screen.getByText(/cannot be changed at runtime/)).toBeTruthy();
    const toggle = screen.getByRole<HTMLButtonElement>('switch', { name: /enabled/i });
    expect(toggle.disabled).toBe(true);
    expect(toggle.getAttribute('aria-checked')).toBe('true');
  });

  it('returns to the quality list', async () => {
    renderDetail();
    await userEvent.click(await screen.findByRole('button', { name: /back to quality/i }));
    expect(screen.getByText('quality list')).toBeTruthy();
  });
});
