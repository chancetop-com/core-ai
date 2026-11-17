import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../../../api/client';
import { qualityApi, type QualityPolicy, type QualityRule } from '../../../api/quality';
import AgentQualityTab from './AgentQualityTab';

const POLICY: QualityPolicy = { revision: 1, generation: 2, bindings: [] };

function rule(enforcement: string): QualityRule {
  return {
    id: 'qrule:1',
    key: 'PROMPT.OUTPUT_REQUIREMENT_PRESENT',
    name: 'Output requirement present',
    category: 'PROMPT',
    lifecycle: 'TESTED',
    kind: 'DECISION',
    revision: 1,
    tested: true,
    enforcement,
  };
}

function mockLoad(enforcement = 'BLOCK') {
  vi.spyOn(qualityApi, 'rules').mockResolvedValue({ rules: [rule(enforcement)], policy_revision: 1 });
  vi.spyOn(qualityApi, 'templates').mockResolvedValue({ templates: [], input_options: [] });
  vi.spyOn(qualityApi, 'policies').mockResolvedValue({ active: POLICY, history: [] });
  vi.spyOn(api.agents, 'list').mockResolvedValue({ agents: [], total: 0 });
}

describe('Agent quality tab', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.sessionStorage.clear();
  });

  it('turns enforcement off into SHADOW through the detail switch', async () => {
    mockLoad('BLOCK');
    const activate = vi.spyOn(qualityApi, 'activate').mockResolvedValue(POLICY);

    render(<AgentQualityTab />);
    await userEvent.click(await screen.findByText('PROMPT.OUTPUT_REQUIREMENT_PRESENT'));

    const toggle = screen.getByRole<HTMLButtonElement>('switch', { name: /enforce rule/i });
    expect(toggle.getAttribute('aria-checked')).toBe('true');

    await userEvent.click(toggle);
    expect(toggle.getAttribute('aria-checked')).toBe('false');
    expect(screen.getByText('SHADOW — observe only')).toBeTruthy();
    expect(screen.getByText('Unsaved policy change')).toBeTruthy();

    await userEvent.type(screen.getByPlaceholderText(/change reason/i), 'observe only');
    await userEvent.click(screen.getByRole('button', { name: /apply policy/i }));

    expect(activate).toHaveBeenCalledWith(expect.objectContaining({
      rule_id: 'qrule:1',
      rule_revision: 1,
      enforcement: 'SHADOW',
      change_reason: 'observe only',
    }));
  });
});
