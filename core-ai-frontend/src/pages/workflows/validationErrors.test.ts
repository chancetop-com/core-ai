import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { api, type WorkflowAgentOption } from '../../api/client';
import type { WorkflowRFNode } from './graph';
import NodeConfigPanel from './NodeConfigPanel';
import { firstNodeErrorId, groupNodeErrors, parseWorkflowValidationErrors } from './validation';

function agentNode(nodeType: 'AGENT' | 'LLM', config: Record<string, unknown> = {}): WorkflowRFNode {
  return {
    id: 'agent_1',
    type: 'workflowNode',
    position: { x: 0, y: 0 },
    data: { nodeType, name: 'Agent node', config },
  };
}

function pickerOption(
  id: string,
  name: string,
  type: 'AGENT' | 'LLM_CALL',
): WorkflowAgentOption {
  return { id, name, type, status: 'DRAFT', ownership: 'MINE' };
}

describe('workflow node validation errors', () => {
  it('parses trimmed semicolon-delimited workflow validation errors', () => {
    expect(parseWorkflowValidationErrors(
      'workflow validation failed: node agent_2 is invalid; node agent_1 is invalid; ',
    )).toEqual([
      'node agent_2 is invalid',
      'node agent_1 is invalid',
    ]);
  });

  it('does not reinterpret a generic failure as workflow validation', () => {
    expect(parseWorkflowValidationErrors('database offline')).toEqual([]);
  });

  it('groups multiple node-prefixed errors and returns the first node', () => {
    const errors = [
      'node agent_1 references an unavailable agent',
      'node agent_1 contains private environment variables',
      'workflow must have an END node',
    ];

    expect(firstNodeErrorId(errors)).toBe('agent_1');
    expect(groupNodeErrors(errors)).toEqual({
      agent_1: [errors[0], errors[1]],
    });
  });

  it('ignores global errors before and between node errors', () => {
    const errors = [
      'workflow has a cycle',
      'node first-node needs configuration',
      'workflow must have a START node',
      'node second.node references an unavailable agent',
    ];

    expect(firstNodeErrorId(errors)).toBe('first-node');
    expect(groupNodeErrors(errors)).toEqual({
      'first-node': [errors[1]],
      'second.node': [errors[3]],
    });
  });

  it('accepts non-whitespace node ids supported by the server prefix', () => {
    const errors = [
      'node team:agent-7/private references an unavailable agent',
      'node team:agent-7/private contains private environment variables',
    ];

    expect(firstNodeErrorId(errors)).toBe('team:agent-7/private');
    expect(groupNodeErrors(errors)).toEqual({
      'team:agent-7/private': errors,
    });
  });

  it('returns no node when every error is global', () => {
    const errors = ['workflow must have an END node', 'a node is not a prefixed error'];

    expect(firstNodeErrorId(errors)).toBeUndefined();
    expect(groupNodeErrors(errors)).toEqual({});
  });
});

describe('node config agent picker integration', () => {
  it('merges node and external issues without rendering duplicates', () => {
    const node = agentNode('AGENT');
    const props = {
      node,
      nodes: [node],
      edges: [],
      externalIssues: ['Select an agent.', 'node agent_1 references an unavailable agent'],
      onChange: vi.fn(),
      onDelete: vi.fn(),
      onClose: vi.fn(),
    };

    render(createElement(NodeConfigPanel, props));

    expect(screen.getAllByText('Select an agent.')).toHaveLength(1);
    expect(screen.getByText('node agent_1 references an unavailable agent')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Select agent' })).toBeTruthy();
  });

  it('maps an LLM node to LLM_CALL and persists the picked id and name', async () => {
    const current = pickerOption('old-id', 'Saved LLM', 'LLM_CALL');
    const replacement = pickerOption('new-id', 'Draft LLM', 'LLM_CALL');
    vi.spyOn(api.workflows, 'agentOptions').mockResolvedValue({
      items: [replacement], selected: current, total: 1, page: 1, limit: 20,
    });
    const onChange = vi.fn();
    const node = agentNode('LLM', { agent_id: current.id, agent_name: current.name, input: 'hello' });
    const props = {
      node,
      nodes: [node],
      edges: [],
      onChange,
      onDelete: vi.fn(),
      onClose: vi.fn(),
    };

    render(createElement(NodeConfigPanel, props));
    await userEvent.click(screen.getByRole('button', { name: current.name }));
    await userEvent.click(await screen.findByRole('button', { name: /Draft LLM.*draft/i }));

    expect(api.workflows.agentOptions).toHaveBeenCalledWith('mine', 'LLM_CALL', '', 1, 20, current.id);
    expect(onChange).toHaveBeenCalledWith({
      config: { agent_id: replacement.id, agent_name: replacement.name, input: 'hello' },
    });
  });
});
