import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { api, type AgentDefinition } from '../../../api/client';
import AgentSelector from './AgentSelector';

function agent(overrides: Partial<AgentDefinition> = {}): AgentDefinition {
  return {
    id: 'shared-agent',
    name: 'Restaurant Local SEO agent',
    description: 'Shared SEO assistant',
    system_prompt: '',
    system_prompt_id: '',
    model: 'default',
    temperature: 0,
    max_turns: 10,
    timeout_seconds: 60,
    tools: [],
    input_template: '',
    variables: {},
    system_default: false,
    type: 'AGENT',
    response_schema: null,
    created_by: 'another-user',
    status: 'PUBLISHED',
    published_at: '',
    created_at: '',
    updated_at: '',
    ...overrides,
  };
}

describe('AgentSelector', () => {
  it('returns the shared agent definition so its name remains visible after selection', async () => {
    const sharedAgent = agent();
    vi.spyOn(api.agents, 'list').mockResolvedValue({ agents: [sharedAgent], total: 1 });
    const onSelectAgent = vi.fn();

    render(
      <AgentSelector
        status="idle"
        myAgents={[]}
        favoriteAgents={[]}
        selectedAgentId=""
        onSelectAgent={onSelectAgent}
        onToggleFavorite={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /select agent/i }));
    fireEvent.change(screen.getByPlaceholderText('Search shared agents...'), {
      target: { value: 'Restaurant Local SEO' },
    });
    const name = await screen.findByText(sharedAgent.name);
    fireEvent.click(name.closest('button')!);

    expect(onSelectAgent).toHaveBeenCalledWith(sharedAgent.id, sharedAgent);
  });
});
