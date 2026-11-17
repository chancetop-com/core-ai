import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { qualityApi } from '../../../api/quality';
import AgentQualityList from './AgentQualityList';

function renderList() {
  render(
    <MemoryRouter initialEntries={['/settings/quality']}>
      <Routes>
        <Route path="/settings/quality" element={<AgentQualityList />} />
        <Route path="/settings/quality/agent/:ruleId" element={<div>agent detail</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Agent quality list', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.sessionStorage.clear();
    vi.spyOn(qualityApi, 'rules').mockResolvedValue({
      policy_revision: 1,
      rules: [{
        id: 'qrule:1',
        key: 'PROMPT.OUTPUT_REQUIREMENT_PRESENT',
        name: 'Output requirement present',
        lifecycle: 'TESTED',
        kind: 'DECISION',
        revision: 1,
        tested: true,
        enforcement: 'BLOCK',
      }],
    });
    vi.spyOn(qualityApi, 'policies').mockResolvedValue({
      active: { revision: 1, generation: 2, bindings: [] },
      history: [],
    });
  });

  it('navigates to the rule detail page instead of opening a side panel', async () => {
    renderList();
    await userEvent.click(await screen.findByText('PROMPT.OUTPUT_REQUIREMENT_PRESENT'));
    expect(screen.getByText('agent detail')).toBeTruthy();
  });
});
