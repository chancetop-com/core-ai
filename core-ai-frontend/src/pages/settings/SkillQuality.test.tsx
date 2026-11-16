import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { skillQualityApi, type SkillQualityRule } from '../../api/quality';
import SkillQuality from './SkillQuality';

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

describe('Skill quality rules', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.sessionStorage.clear();
  });

  it('shows the code-defined rules and states that edits need a release', async () => {
    vi.spyOn(skillQualityApi, 'rules').mockResolvedValue({
      policy_revision: 1,
      rules: Array.from({ length: 10 }, (_, i) => rule(i + 1)),
    });

    render(<SkillQuality />);

    expect(await screen.findByText('SKILL.RULE_1')).toBeTruthy();
    expect(screen.getByText('规则由代码定义，修改需发版。')).toBeTruthy();
    expect(screen.getByText(/本页只读/)).toBeTruthy();
    expect(screen.getByText('Showing 1-10 of 10')).toBeTruthy();
    expect(screen.getByText('Policy revision 1')).toBeTruthy();
  });

  it('pages client-side without dropping the read-only contract', async () => {
    vi.spyOn(skillQualityApi, 'rules').mockResolvedValue({
      policy_revision: 1,
      rules: Array.from({ length: 25 }, (_, i) => rule(i + 1)),
    });

    render(<SkillQuality />);

    expect(await screen.findByText('SKILL.RULE_20')).toBeTruthy();
    expect(screen.queryByText('SKILL.RULE_21')).toBeNull();
    expect(screen.getByText('Showing 1-20 of 25')).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: /next/i }));

    expect(screen.getByText('SKILL.RULE_21')).toBeTruthy();
    expect(screen.queryByText('SKILL.RULE_20')).toBeNull();
    expect(screen.getByText('Showing 21-25 of 25')).toBeTruthy();
  });
});
