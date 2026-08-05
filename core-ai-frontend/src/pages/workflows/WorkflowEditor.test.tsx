import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
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

function setupEditorApi() {
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
}

function renderEditor() {
  render(
    <MemoryRouter initialEntries={['/workflows/workflow-1']}>
      <Routes>
        <Route path="/workflows/:id" element={<WorkflowEditor />} />
      </Routes>
    </MemoryRouter>,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('WorkflowEditor validation focus', () => {
  it('closes the Test panel and shows the first failed node issues', async () => {
    setupEditorApi();
    vi.spyOn(api.workflows, 'validate').mockResolvedValue({
      valid: false,
      errors: [
        'node agent_2 references an unavailable agent',
        'node agent_1 references an unavailable agent',
      ],
    });

    renderEditor();

    await screen.findByDisplayValue('Validation workflow');
    await userEvent.click(screen.getByRole('button', { name: 'Test' }));
    await userEvent.click(screen.getByRole('button', { name: 'Test draft' }));

    expect(await screen.findByDisplayValue('Second Agent')).toBeTruthy();
    expect(screen.getByText('node agent_2 references an unavailable agent')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Test draft' })).toBeNull();
  });

  it('focuses the first failed node when Save races with server validation', async () => {
    setupEditorApi();
    vi.spyOn(api.workflows, 'validate').mockResolvedValue({ valid: true, errors: [] });
    vi.spyOn(api.workflows, 'saveVersion').mockRejectedValue(new Error(
      'workflow validation failed: node agent_2 changed during save; node agent_1 is also invalid',
    ));
    renderEditor();

    await screen.findByDisplayValue('Validation workflow');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByDisplayValue('Second Agent')).toBeTruthy();
    expect(screen.getByText('node agent_2 changed during save')).toBeTruthy();
    expect(screen.getByText(/Save failed: workflow validation failed:/)).toBeTruthy();
  });

  it('focuses the first referenced node that still exists on the canvas', async () => {
    setupEditorApi();
    vi.spyOn(api.workflows, 'validate').mockResolvedValue({ valid: true, errors: [] });
    vi.spyOn(api.workflows, 'saveVersion').mockRejectedValue(new Error(
      'workflow validation failed: node deleted_node changed during save; node agent_2 is invalid',
    ));
    renderEditor();

    await screen.findByDisplayValue('Validation workflow');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByDisplayValue('Second Agent')).toBeTruthy();
    expect(screen.getByText('node agent_2 is invalid')).toBeTruthy();
  });

  it('focuses the first failed node and closes Test when Preview races with server validation', async () => {
    setupEditorApi();
    vi.spyOn(api.workflows, 'validate').mockResolvedValue({ valid: true, errors: [] });
    vi.spyOn(api.workflows, 'previewRun').mockRejectedValue(new Error(
      'workflow validation failed: node agent_2 changed during preview; node agent_1 is also invalid',
    ));
    renderEditor();

    await screen.findByDisplayValue('Validation workflow');
    await userEvent.click(screen.getByRole('button', { name: 'Test' }));
    await userEvent.click(screen.getByRole('button', { name: 'Test draft' }));

    expect(await screen.findByDisplayValue('Second Agent')).toBeTruthy();
    expect(screen.getByText('node agent_2 changed during preview')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Test draft' })).toBeNull();
  });

  it('keeps generic Save failures generic without focusing a node', async () => {
    setupEditorApi();
    vi.spyOn(api.workflows, 'validate').mockResolvedValue({ valid: true, errors: [] });
    vi.spyOn(api.workflows, 'saveVersion').mockRejectedValue(new Error('database offline'));
    renderEditor();

    await screen.findByDisplayValue('Validation workflow');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Save failed: database offline')).toBeTruthy();
    expect(screen.queryByDisplayValue('Second Agent')).toBeNull();
  });

  it('keeps generic Preview failures in the Test panel without focusing a node', async () => {
    setupEditorApi();
    vi.spyOn(api.workflows, 'validate').mockResolvedValue({ valid: true, errors: [] });
    vi.spyOn(api.workflows, 'previewRun').mockRejectedValue(new Error('runner unavailable'));
    renderEditor();

    await screen.findByDisplayValue('Validation workflow');
    await userEvent.click(screen.getByRole('button', { name: 'Test' }));
    await userEvent.click(screen.getByRole('button', { name: 'Test draft' }));

    expect(await screen.findByText('Run failed: runner unavailable')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Test draft' })).toBeTruthy();
    expect(screen.queryByDisplayValue('Second Agent')).toBeNull();
  });
});
