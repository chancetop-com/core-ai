import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../../api/client';
import type { AgentDefinition } from '../../api/client';
import { qualityApi, type AgentQualityRun } from '../../api/quality';
import AgentQualityHistory from './AgentQualityHistory';

function run(index: number): AgentQualityRun {
  return {
    run_id: `run-${index}`,
    mode: index % 2 === 0 ? 'PUBLISH' : 'PRECHECK',
    run_status: 'COMPLETED',
    gate_result: index % 3 === 0 ? 'REJECTED' : 'PASSED',
    policy_revision: 5,
    definition_revision: index,
    warn_count: 1,
    block_fail_count: index % 3 === 0 ? 2 : 0,
    incomplete_count: 0,
    created_at: new Date(2026, 10, 17, 12, index).toISOString(),
  };
}

function renderHistory() {
  render(
    <MemoryRouter initialEntries={['/agents/agent-1/quality-history']}>
      <Routes>
        <Route path="/agents/:id/quality-history" element={<AgentQualityHistory />} />
        <Route path="/agents/:id" element={<div>agent detail</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Agent quality history', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.sessionStorage.clear();
    vi.spyOn(api.agents, 'get').mockResolvedValue({ id: 'agent-1', name: 'Agent One' } as AgentDefinition);
  });

  it('lists only the requested agent runs with their policy revisions', async () => {
    const runs = vi.spyOn(qualityApi, 'runs').mockResolvedValue({ runs: [run(1), run(2)] });

    renderHistory();

    expect(await screen.findByText('Policy history')).toBeTruthy();
    expect(screen.getByText(/Agent One/)).toBeTruthy();
    expect(screen.getByText(/2 quality checks recorded/)).toBeTruthy();
    expect(screen.getByText('PRECHECK')).toBeTruthy();
    expect(screen.getByText('PUBLISH')).toBeTruthy();
    expect(screen.getAllByText('5')).toHaveLength(2);
    expect(runs).toHaveBeenCalledWith('agent-1');
  });

  it('pages the history list', async () => {
    vi.spyOn(qualityApi, 'runs').mockResolvedValue({
      runs: Array.from({ length: 25 }, (_, i) => run(i + 1)),
    });

    renderHistory();

    expect(await screen.findByText('Showing 1-20 of 25')).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: /next/i }));
    expect(screen.getByText('Showing 21-25 of 25')).toBeTruthy();
  });

  it('returns to the agent detail page', async () => {
    vi.spyOn(qualityApi, 'runs').mockResolvedValue({ runs: [] });
    renderHistory();

    await userEvent.click(await screen.findByRole('button', { name: /back to agent/i }));
    expect(screen.getByText('agent detail')).toBeTruthy();
  });
});
