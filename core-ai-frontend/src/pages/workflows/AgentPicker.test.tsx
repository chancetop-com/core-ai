import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { api, type ListWorkflowAgentOptionsResponse, type WorkflowAgentOption } from '../../api/client';
import AgentPicker from './AgentPicker';

function option(
  id: string,
  name: string,
  overrides: Partial<WorkflowAgentOption> = {},
): WorkflowAgentOption {
  return {
    id,
    name,
    type: 'AGENT',
    status: 'DRAFT',
    ownership: 'MINE',
    ...overrides,
  };
}

function page(...items: WorkflowAgentOption[]): ListWorkflowAgentOptionsResponse {
  return { items, total: items.length, page: 1, limit: 20 };
}

function emptyPage(): ListWorkflowAgentOptionsResponse {
  return page();
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe('AgentPicker', () => {
  it('loads mine first, shows status, and selects a draft agent', async () => {
    const draft = option('draft-1', 'Alpha Reviewer');
    vi.spyOn(api.workflows, 'agentOptions').mockResolvedValue(page(draft));
    const onChange = vi.fn();

    render(<AgentPicker value="" type="AGENT" onChange={onChange} />);
    await userEvent.click(screen.getByRole('button', { name: /select agent/i }));
    await userEvent.click(await screen.findByRole('button', { name: /alpha reviewer.*draft/i }));

    expect(onChange).toHaveBeenCalledWith(draft);
    expect(screen.queryByRole('listbox', { name: /agent results/i })).toBeNull();
  });

  it('switches to shared and clears the current search before requesting it', async () => {
    vi.useFakeTimers();
    const request = vi.spyOn(api.workflows, 'agentOptions').mockResolvedValue(emptyPage());

    render(<AgentPicker value="" type="AGENT" onChange={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: /select agent/i }));
    await vi.advanceTimersByTimeAsync(0);
    fireEvent.change(screen.getByRole('textbox', { name: /search agents/i }), { target: { value: 'reviewer' } });
    await vi.advanceTimersByTimeAsync(250);
    fireEvent.click(screen.getByRole('button', { name: /shared agents/i }));
    await vi.advanceTimersByTimeAsync(0);

    expect(screen.getByRole<HTMLInputElement>('textbox', { name: /search agents/i }).value).toBe('');
    expect(request).toHaveBeenLastCalledWith('shared', 'AGENT', '', 1, 20, undefined);
  });

  it('waits 250ms before searching and trims the query', async () => {
    vi.useFakeTimers();
    const request = vi.spyOn(api.workflows, 'agentOptions').mockResolvedValue(emptyPage());

    render(<AgentPicker value="" type="AGENT" onChange={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: /select agent/i }));
    await vi.advanceTimersByTimeAsync(0);
    expect(request).toHaveBeenCalledTimes(1);

    fireEvent.change(screen.getByRole('textbox', { name: /search agents/i }), { target: { value: '  alpha  ' } });
    await vi.advanceTimersByTimeAsync(249);
    expect(request).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);

    expect(request).toHaveBeenLastCalledWith('mine', 'AGENT', 'alpha', 1, 20, undefined);
  });

  it('ignores an older search response and keeps the newest results', async () => {
    vi.useFakeTimers();
    const oldRequest = deferred<ListWorkflowAgentOptionsResponse>();
    const newRequest = deferred<ListWorkflowAgentOptionsResponse>();
    vi.spyOn(api.workflows, 'agentOptions')
      .mockResolvedValueOnce(emptyPage())
      .mockReturnValueOnce(oldRequest.promise)
      .mockReturnValueOnce(newRequest.promise);

    render(<AgentPicker value="" type="AGENT" onChange={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: /select agent/i }));
    await vi.advanceTimersByTimeAsync(0);
    const search = screen.getByRole('textbox', { name: /search agents/i });
    fireEvent.change(search, { target: { value: 'old' } });
    await vi.advanceTimersByTimeAsync(250);
    fireEvent.change(search, { target: { value: 'new' } });
    await vi.advanceTimersByTimeAsync(250);
    await act(async () => {
      newRequest.resolve(page(option('new-id', 'New Result')));
      oldRequest.resolve(page(option('old-id', 'Old Result')));
      await Promise.resolve();
    });

    expect(screen.getByText('New Result')).toBeTruthy();
    expect(screen.queryByText('Old Result')).toBeNull();
  });

  it('appends page two once and removes duplicate IDs', async () => {
    const first = option('a1', 'Agent 1');
    const duplicate = option('a1', 'Agent 1 updated');
    const second = option('a2', 'Agent 2');
    const request = vi.spyOn(api.workflows, 'agentOptions')
      .mockResolvedValueOnce({ ...page(first), total: 21 })
      .mockResolvedValueOnce({ ...page(duplicate, second), page: 2, total: 21 });

    render(<AgentPicker value="" type="AGENT" onChange={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: /select agent/i }));
    const list = await screen.findByRole('listbox', { name: /agent results/i });
    Object.defineProperties(list, {
      scrollTop: { value: 180, configurable: true },
      clientHeight: { value: 100, configurable: true },
      scrollHeight: { value: 280, configurable: true },
    });
    fireEvent.scroll(list);
    fireEvent.scroll(list);

    expect(await screen.findByText('Agent 2')).toBeTruthy();
    expect(screen.queryByText('Agent 1')).toBeNull();
    expect(screen.getAllByText('Agent 1 updated')).toHaveLength(1);
    expect(request).toHaveBeenCalledTimes(2);
    expect(request).toHaveBeenLastCalledWith('mine', 'AGENT', '', 2, 20, undefined);
  });

  it('pins an off-page selected response and removes it from result rows', async () => {
    const selected = option('selected-1', 'Pinned Agent', { status: 'PUBLISHED' });
    vi.spyOn(api.workflows, 'agentOptions').mockResolvedValue({
      ...page(option('row-1', 'Other Agent'), selected),
      selected,
    });

    render(<AgentPicker value="selected-1" selectedName="Saved Agent" type="AGENT" onChange={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: /saved agent/i }));
    const options = await screen.findAllByRole('button', { name: /pinned agent.*published/i });

    expect(options).toHaveLength(1);
    expect(options[0].parentElement?.previousElementSibling).toBeNull();
    expect(screen.getByRole('button', { name: /other agent.*draft/i })).toBeTruthy();
  });

  it('only marks a selected value unavailable after a successful check', async () => {
    const request = deferred<ListWorkflowAgentOptionsResponse>();
    vi.spyOn(api.workflows, 'agentOptions').mockReturnValue(request.promise);

    render(<AgentPicker value="missing-id" selectedName="Old Agent" type="AGENT" onChange={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: /old agent/i }));
    expect(screen.queryByText(/unavailable.*replace this agent/i)).toBeNull();

    await act(async () => {
      request.resolve(emptyPage());
      await Promise.resolve();
    });
    expect(await screen.findByText(/unavailable.*replace this agent/i)).toBeTruthy();
  });

  it('retries a failure without clearing the selected value', async () => {
    const request = vi.spyOn(api.workflows, 'agentOptions')
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValue(emptyPage());

    render(<AgentPicker value="missing-id" selectedName="Old Agent" type="AGENT" onChange={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: /old agent/i }));
    expect(await screen.findByText('network down')).toBeTruthy();
    expect(screen.queryByText(/unavailable.*replace this agent/i)).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(await screen.findByText(/unavailable.*replace this agent/i)).toBeTruthy();
    expect(screen.getByRole('button', { name: /old agent/i })).toBeTruthy();
    expect(request).toHaveBeenLastCalledWith('mine', 'AGENT', '', 1, 20, 'missing-id');
  });

  it('closes on an outside pointer without clearing the selected value', async () => {
    vi.spyOn(api.workflows, 'agentOptions').mockResolvedValue(emptyPage());
    const onChange = vi.fn();

    render(
      <div>
        <AgentPicker value="saved-id" selectedName="Saved Agent" type="AGENT" onChange={onChange} />
        <button type="button">Outside</button>
      </div>,
    );
    await userEvent.click(screen.getByRole('button', { name: /saved agent/i }));
    expect(await screen.findByRole('listbox', { name: /agent results/i })).toBeTruthy();
    fireEvent.pointerDown(screen.getByRole('button', { name: /outside/i }));

    expect(screen.queryByRole('listbox', { name: /agent results/i })).toBeNull();
    expect(screen.getByRole('button', { name: /saved agent/i })).toBeTruthy();
    expect(onChange).not.toHaveBeenCalled();
  });

  it('refreshes from page one when the type or selected value changes', async () => {
    const request = vi.spyOn(api.workflows, 'agentOptions')
      .mockResolvedValueOnce({ ...page(option('agent-1', 'Agent One')), total: 21 })
      .mockResolvedValueOnce({ ...page(option('agent-2', 'Agent Two')), page: 2, total: 21 })
      .mockResolvedValue(emptyPage());
    const { rerender } = render(<AgentPicker value="agent-1" selectedName="Agent One" type="AGENT" onChange={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: /agent one/i }));
    await waitFor(() => expect(request).toHaveBeenLastCalledWith('mine', 'AGENT', '', 1, 20, 'agent-1'));
    const list = screen.getByRole('listbox', { name: /agent results/i });
    Object.defineProperties(list, {
      scrollTop: { value: 180, configurable: true },
      clientHeight: { value: 100, configurable: true },
      scrollHeight: { value: 280, configurable: true },
    });
    fireEvent.scroll(list);
    expect(await screen.findByText('Agent Two')).toBeTruthy();

    rerender(<AgentPicker value="llm-2" selectedName="LLM Two" type="LLM_CALL" onChange={vi.fn()} />);

    await waitFor(() => expect(request).toHaveBeenLastCalledWith('mine', 'LLM_CALL', '', 1, 20, 'llm-2'));
    expect(screen.getByRole('button', { name: /llm two/i })).toBeTruthy();
    expect(screen.queryByText('Agent Two')).toBeNull();
  });
});

describe('workflow agent options API', () => {
  it('encodes required parameters, a trimmed query, and an optional selected ID', async () => {
    vi.stubGlobal('localStorage', {
      getItem: vi.fn().mockReturnValue(null),
      removeItem: vi.fn(),
    });
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => (
      new Response(JSON.stringify(emptyPage()), { status: 200 })
    ));

    await api.workflows.agentOptions('shared', 'LLM_CALL', '  alpha & beta  ', 3, 40, 'selected/id');
    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/workflows/agent-options?scope=shared&type=LLM_CALL&page=3&limit=40&query=alpha+%26+beta&selected_id=selected%2Fid',
    );

    await api.workflows.agentOptions('mine', 'AGENT', '   ', 1, 20);
    expect(fetchMock.mock.calls[1][0]).toBe('/api/workflows/agent-options?scope=mine&type=AGENT&page=1&limit=20');
  });
});
