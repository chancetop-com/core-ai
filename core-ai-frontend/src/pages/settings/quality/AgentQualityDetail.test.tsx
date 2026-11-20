import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../../../api/client';
import { qualityApi, type QualityPolicy, type QualityRule } from '../../../api/quality';
import AgentQualityDetail from './AgentQualityDetail';

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

function renderDetail() {
  render(
    <MemoryRouter initialEntries={[`/quality/agent/${encodeURIComponent('qrule:1')}`]}>
      <Routes>
        <Route path="/quality/agent/:ruleId" element={<AgentQualityDetail />} />
        <Route path="/quality" element={<div>quality list</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Agent quality detail', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('turns enforcement off into SHADOW through the policy switch', async () => {
    mockLoad('BLOCK');
    const activate = vi.spyOn(qualityApi, 'activate').mockResolvedValue(POLICY);

    renderDetail();
    expect(await screen.findByText('PROMPT.OUTPUT_REQUIREMENT_PRESENT')).toBeTruthy();

    const toggle = screen.getByRole<HTMLButtonElement>('switch', { name: /enforce rule/i });
    expect(toggle.getAttribute('aria-checked')).toBe('true');

    await userEvent.click(toggle);
    expect(toggle.getAttribute('aria-checked')).toBe('false');
    expect(screen.getByText('SHADOW — observe only')).toBeTruthy();
    expect(screen.getByText('Unsaved policy change')).toBeTruthy();

    await userEvent.type(screen.getByPlaceholderText(/required for save/i), 'observe only');
    await userEvent.click(screen.getByRole('button', { name: /apply policy/i }));

    expect(activate).toHaveBeenCalledWith(expect.objectContaining({
      rule_id: 'qrule:1',
      rule_revision: 1,
      enforcement: 'SHADOW',
      change_reason: 'observe only',
    }));
  });

  it('returns to the list from the back link', async () => {
    mockLoad('BLOCK');
    renderDetail();
    await userEvent.click(await screen.findByRole('button', { name: /back to quality/i }));
    expect(screen.getByText('quality list')).toBeTruthy();
  });

  it('shows the decision questions compiled into a built-in rule', async () => {
    vi.spyOn(qualityApi, 'rules').mockResolvedValue({
      rules: [{
        ...rule('SHADOW'),
        built_in: true,
        evaluator_key: 'PROMPT.BUILTIN_CAPABILITY_REQUIRED',
        decision_question: undefined,
      }],
      policy_revision: 1,
    });
    vi.spyOn(qualityApi, 'templates').mockResolvedValue({
      templates: [{
        evaluator_key: 'PROMPT.BUILTIN_CAPABILITY_REQUIRED',
        name: '操作意图必须有 builtin 能力',
        kind: 'HYBRID',
        category: 'PROMPT',
        description: '提示词要求执行文件、网络、代码、图片或视频操作时，必须配置对应 builtin 能力。',
        parameters: [],
        input_options: [],
        supports_decision: true,
        questions: [{
          name: 'intent_FILE_READ',
          type: 'noul',
          instruction: '提示词是否要求 Agent 实际读取本地文件内容？解释如何读取、或只输出文本不算。',
          criteria: { yes: '提示词明确要求 Agent 实际执行该操作', no: '提示词只解释、描述或禁止该操作，不要求实际执行' },
        }],
      }],
      input_options: [],
    });
    vi.spyOn(qualityApi, 'policies').mockResolvedValue({ active: POLICY, history: [] });
    vi.spyOn(api.agents, 'list').mockResolvedValue({ agents: [], total: 0 });

    renderDetail();

    expect(await screen.findByText('Built-in decision questions')).toBeTruthy();
    expect(screen.getByText('intent_FILE_READ · noul')).toBeTruthy();
    expect(screen.getByText(/提示词是否要求 Agent 实际读取本地文件内容/)).toBeTruthy();
    expect(screen.queryByText('Decision question')).toBeNull();
  });
});
