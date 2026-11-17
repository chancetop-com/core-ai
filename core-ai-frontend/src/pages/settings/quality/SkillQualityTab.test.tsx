import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { skillQualityApi, type SkillQualityRule } from '../../../api/quality';
import SkillQualityTab from './SkillQualityTab';

function rule(index: number): SkillQualityRule {
  return {
    key: `SKILL.RULE_${index}`,
    revision: 1,
    name: `Rule ${index}`,
    category: index % 2 === 0 ? 'CONTENT' : 'METADATA',
    enforcement: index % 3 === 0 ? 'BLOCK' : 'WARN',
    requirement: `Requirement ${index}`,
  };
}

describe('Skill quality tab', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.sessionStorage.clear();
  });

  it('shows the code-defined rules and states that edits need a release', async () => {
    vi.spyOn(skillQualityApi, 'rules').mockResolvedValue({
      policy_revision: 1,
      rules: Array.from({ length: 10 }, (_, i) => rule(i + 1)),
    });

    render(<SkillQualityTab />);

    expect(await screen.findByText('SKILL.RULE_1')).toBeTruthy();
    expect(screen.getByText('规则由代码定义，修改需发版。')).toBeTruthy();
    expect(screen.getByText('Showing 1-10 of 10')).toBeTruthy();
    expect(screen.getByText('Policy revision 1')).toBeTruthy();
  });

  it('opens a read-only detail panel with a locked enabled switch', async () => {
    vi.spyOn(skillQualityApi, 'rules').mockResolvedValue({
      policy_revision: 1,
      rules: [rule(1)],
    });

    render(<SkillQualityTab />);
    await userEvent.click(await screen.findByText('SKILL.RULE_1'));

    expect(screen.getByText('Code-defined')).toBeTruthy();
    expect(screen.getAllByText('Requirement 1').length).toBeGreaterThan(1);
    expect(screen.getByText(/不可在运行时修改/)).toBeTruthy();
    const toggle = screen.getByRole<HTMLButtonElement>('switch', { name: /enabled/i });
    expect(toggle.disabled).toBe(true);
    expect(toggle.getAttribute('aria-checked')).toBe('true');
  });

  it('pages client-side without dropping the read-only contract', async () => {
    vi.spyOn(skillQualityApi, 'rules').mockResolvedValue({
      policy_revision: 1,
      rules: Array.from({ length: 25 }, (_, i) => rule(i + 1)),
    });

    render(<SkillQualityTab />);

    expect(await screen.findByText('SKILL.RULE_20')).toBeTruthy();
    expect(screen.queryByText('SKILL.RULE_21')).toBeNull();
    expect(screen.getByText('Showing 1-20 of 25')).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: /next/i }));

    expect(screen.getByText('SKILL.RULE_21')).toBeTruthy();
    expect(screen.queryByText('SKILL.RULE_20')).toBeNull();
    expect(screen.getByText('Showing 21-25 of 25')).toBeTruthy();
  });
});
