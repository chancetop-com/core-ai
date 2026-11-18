import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../../../api/client';
import { skillQualityApi, type SkillQualityRule } from '../../../api/quality';
import SkillQualityDetail from './SkillQualityDetail';

const RULE: SkillQualityRule = {
  id: 'sqrule:SKILL.NAME_IDENTITY',
  key: 'SKILL.NAME_IDENTITY',
  revision: 1,
  name: 'Name matches directory',
  category: 'IDENTITY',
  kind: 'CODE',
  lifecycle: 'ENFORCING',
  built_in: true,
  enforcement: 'BLOCK',
  description: 'The SKILL.md name must follow the naming rules and match the uploaded directory name.',
  requirement: 'The SKILL.md name must follow the naming rules and match the uploaded directory name.',
};

function renderDetail() {
  render(
    <MemoryRouter initialEntries={[`/settings/quality/skill/${encodeURIComponent(RULE.id!)}`]}>
      <Routes>
        <Route path="/settings/quality/skill/:ruleId" element={<SkillQualityDetail />} />
        <Route path="/settings/quality" element={<div>quality list</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Skill quality detail', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(skillQualityApi, 'rules').mockResolvedValue({ policy_revision: 1, rules: [RULE] });
    vi.spyOn(skillQualityApi, 'templates').mockResolvedValue({ templates: [], input_options: [] });
    vi.spyOn(skillQualityApi, 'policies').mockResolvedValue({
      active: { revision: 1, generation: 1, bindings: [] },
      history: [],
    });
    vi.spyOn(api.skills, 'list').mockResolvedValue({ skills: [], total: 0 });
  });

  it('shows a code-defined rule as read-only', async () => {
    renderDetail();

    expect(await screen.findByText('Name matches directory')).toBeTruthy();
    expect(screen.getByText('SKILL.NAME_IDENTITY')).toBeTruthy();
    expect(screen.getByText(RULE.requirement!)).toBeTruthy();
    expect(screen.getByText(/defined in code/i)).toBeTruthy();
    expect(screen.queryByRole('button', { name: /save new revision/i })).toBeNull();
  });

  it('returns to the quality list', async () => {
    renderDetail();
    await userEvent.click(await screen.findByRole('button', { name: /back to quality/i }));
    expect(screen.getByText('quality list')).toBeTruthy();
  });
});
