import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { skillQualityApi } from '../../../api/quality';
import SkillQualityList from './SkillQualityList';

function renderList() {
  render(
    <MemoryRouter initialEntries={['/quality?tab=skill']}>
      <Routes>
        <Route path="/quality" element={<SkillQualityList />} />
        <Route path="/quality/skill/:ruleId" element={<div>skill detail</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Skill quality list', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.sessionStorage.clear();
    vi.spyOn(skillQualityApi, 'rules').mockResolvedValue({
      policy_revision: 1,
      rules: [{
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
      }],
    });
    vi.spyOn(skillQualityApi, 'policies').mockResolvedValue({
      active: { revision: 1, generation: 1, bindings: [] },
      history: [],
    });
  });

  it('navigates to the rule detail page', async () => {
    renderList();
    await userEvent.click(await screen.findByText('SKILL.NAME_IDENTITY'));
    expect(screen.getByText('skill detail')).toBeTruthy();
  });

  it('offers the new decision rule action', async () => {
    renderList();
    expect(await screen.findByRole('button', { name: /new decision rule/i })).toBeTruthy();
  });
});
