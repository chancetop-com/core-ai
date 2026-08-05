import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { api } from '../../api/client';
import WorkflowEditor from './WorkflowEditor';

vi.mock('@xyflow/react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@xyflow/react')>();
  return {
    ...actual,
    ReactFlow: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
    Background: () => null,
    Controls: () => null,
  };
});

const graph = JSON.stringify({
  nodes: [
    { id: 'start', type: 'START', name: 'Start', config: {}, position: { x: 0, y: 0 } },
    { id: 'agent_1', type: 'AGENT', name: 'First Agent', config: {}, position: { x: 100, y: 0 } },
    { id: 'agent_2', type: 'AGENT', name: 'Second Agent', config: {}, position: { x: 200, y: 0 } },
    { id: 'end', type: 'END', name: 'End', config: {}, position: { x: 300, y: 0 } },
  ],
  edges: [
    { id: 'e1', source: 'start', target: 'agent_1' },
    { id: 'e2', source: 'agent_1', target: 'agent_2' },
    { id: 'e3', source: 'agent_2', target: 'end' },
  ],
});

describe('WorkflowEditor validation focus', () => {
  it('closes the Test panel and shows the first failed node issues', async () => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false })));
    vi.stubGlobal('localStorage', { getItem: vi.fn(() => null), setItem: vi.fn(), removeItem: vi.fn() });
    vi.spyOn(api.workflows, 'get').mockResolvedValue({
      id: 'workflow-1', name: 'Validation workflow', status: 'PRIVATE', visibility: 'PRIVATE', editable: true,
      draft_graph: graph,
    });
    vi.spyOn(api.workflows, 'versions').mockResolvedValue({ versions: [] });
    vi.spyOn(api.workflows, 'update').mockResolvedValue({
      id: 'workflow-1', name: 'Validation workflow', status: 'PRIVATE', visibility: 'PRIVATE', editable: true,
      draft_graph: graph,
    });
    vi.spyOn(api.workflows, 'validate').mockResolvedValue({
      valid: false,
      errors: [
        'node agent_2 references an unavailable agent',
        'node agent_1 references an unavailable agent',
      ],
    });

    render(
      <MemoryRouter initialEntries={['/workflows/workflow-1']}>
        <Routes>
          <Route path="/workflows/:id" element={<WorkflowEditor />} />
        </Routes>
      </MemoryRouter>,
    );

    await screen.findByDisplayValue('Validation workflow');
    await userEvent.click(screen.getByRole('button', { name: 'Test' }));
    await userEvent.click(screen.getByRole('button', { name: 'Test draft' }));

    expect(await screen.findByDisplayValue('Second Agent')).toBeTruthy();
    expect(screen.getByText('node agent_2 references an unavailable agent')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Test draft' })).toBeNull();
  });
});
