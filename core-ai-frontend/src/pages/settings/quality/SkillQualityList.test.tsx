import { render, screen, waitFor } from '@testing-library/react';
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
    vi.spyOn(skillQualityApi, 'rules').mockImplementation(async scope => ({
      policy_revision: scope === 'skill:tier:core' ? 3 : 2,
      rules: [{
        id: 'sqrule:SKILL.EVAL_DATASET',
        key: 'SKILL.EVAL_DATASET',
        revision: 1,
        name: 'Eval dataset',
        category: 'EVAL',
        kind: 'CODE',
        lifecycle: 'ENFORCING',
        built_in: true,
        enforcement: scope === 'skill:tier:core' ? 'BLOCK' : 'WARN',
        description: 'eval/eval.json must be published.',
        requirement: 'eval/eval.json must be published.',
      }],
    }));
    vi.spyOn(skillQualityApi, 'policies').mockImplementation(async scope => ({
      active: {
        revision: scope === 'skill:tier:core' ? 3 : 2,
        generation: scope === 'skill:tier:core' ? 4 : 1,
        scope: scope || 'skill:tier:domain',
        bindings: [{
          rule_id: 'sqrule:SKILL.EVAL_DATASET',
          rule_revision: 1,
          enforcement: scope === 'skill:tier:core' ? 'BLOCK' : 'WARN',
        }],
      },
      history: [{
        revision: scope === 'skill:tier:core' ? 3 : 2,
        generation: scope === 'skill:tier:core' ? 4 : 1,
        scope: scope || 'skill:tier:domain',
        active: true,
        change_reason: 'seed',
      }],
    }));
  });

  it('renders four tier scope tabs and reloads per scope', async () => {
    renderList();
    expect(await screen.findByText('SKILL.EVAL_DATASET')).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'DOMAIN' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'CORE' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'ALWAYS_ON' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'IMPORTED' })).toBeTruthy();
    expect(screen.getByText(/revision 2 · generation 1/i)).toBeTruthy();

    await userEvent.click(screen.getByRole('tab', { name: 'CORE' }));

    await waitFor(() => {
      expect(skillQualityApi.rules).toHaveBeenCalledWith('skill:tier:core');
      expect(skillQualityApi.policies).toHaveBeenCalledWith('skill:tier:core');
      expect(screen.getByText(/revision 3 · generation 4/i)).toBeTruthy();
    });
  });

  it('saves inline enforcement change with scope and expected_generation', async () => {
    const activateSpy = vi.spyOn(skillQualityApi, 'activate').mockResolvedValue({
      revision: 5,
      generation: 2,
      scope: 'skill:tier:domain',
    });
    renderList();
    const select = await screen.findByLabelText('Enforcement for SKILL.EVAL_DATASET');
    await userEvent.selectOptions(select, 'BLOCK');
    await userEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => {
      expect(activateSpy).toHaveBeenCalledWith(expect.objectContaining({
        scope: 'skill:tier:domain',
        rule_id: 'sqrule:SKILL.EVAL_DATASET',
        enforcement: 'BLOCK',
        expected_generation: 1,
      }));
    });
  });

  it('navigates to the rule detail page', async () => {
    renderList();
    await userEvent.click(await screen.findByText('SKILL.EVAL_DATASET'));
    expect(screen.getByText('skill detail')).toBeTruthy();
  });

  it('offers the new decision rule action', async () => {
    renderList();
    expect(await screen.findByRole('button', { name: /new decision rule/i })).toBeTruthy();
  });
});
