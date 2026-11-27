import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { api, type AgentDefinition } from '../../../api/client';
import AgentSelector from './AgentSelector';

const GIB = 1024 ** 3;

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

  it('lists a pinned agent under Favorites only, never twice', () => {
    const assistant = agent({ id: 'assistant:user-1', name: "Alice's Assistant", system_default: true });
    const pinned = agent({ id: 'pinned-agent', name: 'Pinned Agent' });

    render(
      <AgentSelector
        status="idle"
        myAgents={[assistant, pinned]}
        favoriteAgents={[pinned]}
        selectedAgentId=""
        onSelectAgent={vi.fn()}
        onToggleFavorite={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /select agent/i }));

    expect(screen.getAllByText('Pinned Agent')).toHaveLength(1);
    expect(screen.getByText('Favorites')).toBeTruthy();
    expect(screen.getByText("Alice's Assistant")).toBeTruthy();
  });

  it('renders clickable workspace chip with name, status, and usage summary for persistent workspace sessions', () => {
    render(
      <MemoryRouter>
        <AgentSelector
          status="idle"
          myAgents={[]}
          favoriteAgents={[]}
          selectedAgentId="default-assistant"
          onSelectAgent={vi.fn()}
          onToggleFavorite={vi.fn()}
          workspace={{
            id: 'ws-99',
            name: 'default',
            runtimeStatus: 'READY',
            volumeStatus: 'READY',
            usedBytes: 12.5 * GIB,
            capacityBytes: 32 * GIB,
            sizeGb: 32,
          }}
        />
      </MemoryRouter>,
    );

    const chip = screen.getByRole('link', { name: 'Workspace default' });
    expect(chip.getAttribute('href')).toBe('/settings/workspaces/ws-99');
    expect(screen.getByText('default')).toBeTruthy();
    expect(screen.getByText('runtime ready')).toBeTruthy();
    expect(screen.getByTestId('workspace-chip-usage').textContent).toContain('12.5 GiB / 32.0 GiB');
  });

  it('does not render any workspace chip for non-workspace sessions', () => {
    render(
      <MemoryRouter>
        <AgentSelector
          status="idle"
          myAgents={[]}
          favoriteAgents={[]}
          selectedAgentId="default-assistant"
          onSelectAgent={vi.fn()}
          onToggleFavorite={vi.fn()}
          workspace={null}
        />
      </MemoryRouter>,
    );

    expect(screen.queryByRole('link', { name: /Workspace/i })).toBeNull();
    expect(screen.queryByTestId('workspace-chip-usage')).toBeNull();
  });
});
