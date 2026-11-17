import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { skillQualityApi } from '../../../api/quality';
import SkillQualityList from './SkillQualityList';

function renderList() {
  render(
    <MemoryRouter initialEntries={['/settings/quality?tab=skill']}>
      <Routes>
        <Route path="/settings/quality" element={<SkillQualityList />} />
        <Route path="/settings/quality/skill/:ruleKey" element={<div>skill detail</div>} />
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
        key: 'SKILL.NAME_IDENTITY',
        revision: 1,
        name: '名称与目录一致',
        category: 'IDENTITY',
        enforcement: 'BLOCK',
        requirement: 'SKILL.md 的 name 必须符合命名规范，并与上传目录名一致。',
      }],
    });
  });

  it('navigates to the rule detail page', async () => {
    renderList();
    await userEvent.click(await screen.findByText('SKILL.NAME_IDENTITY'));
    expect(screen.getByText('skill detail')).toBeTruthy();
  });
});
