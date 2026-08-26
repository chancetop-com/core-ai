import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AuthContext, type AuthUser } from '../../api/auth';
import { api, type Trace } from '../../api/client';
import TraceInspector from './TraceInspector';

function trace(status: Trace['status']): Trace {
  return {
    id: 'id-1',
    traceId: 'trace-1',
    name: 'audit agent',
    type: 'agent',
    source: 'chat',
    sessionId: 'sess-1',
    userId: 'user-1',
    status,
    metadata: {},
    inputTokens: 0,
    outputTokens: 0,
    totalTokens: 0,
    durationMs: 0,
    startedAt: '2026-08-26T12:00:00Z',
    completedAt: '2026-08-26T12:00:00Z',
    createdAt: '2026-08-26T12:00:00Z',
  };
}

function renderInspector(role: string | undefined, status: Trace['status']) {
  const user: AuthUser = { apiKey: 'key', userId: 'admin-1', name: 'Admin', role };
  return render(
    <AuthContext.Provider value={{ user, login: () => {}, logout: () => {} }}>
      <TraceInspector trace={trace(status)} spans={[]} mode="page" />
    </AuthContext.Provider>,
  );
}

describe('TraceInspector stop control', () => {
  it('shows Stop to admins on a running trace', () => {
    renderInspector('admin', 'RUNNING');

    expect(screen.getByRole('button', { name: /stop trace/i })).toBeTruthy();
  });

  it('hides Stop from non-admin users', () => {
    renderInspector('user', 'RUNNING');

    expect(screen.queryByRole('button', { name: /stop trace/i })).toBeNull();
  });

  it('hides Stop when the trace is not running', () => {
    renderInspector('admin', 'COMPLETED');

    expect(screen.queryByRole('button', { name: /stop trace/i })).toBeNull();
  });

  it('stops the trace after confirmation and reflects the cancelled status', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true));
    const stop = vi.spyOn(api.traces, 'stop').mockResolvedValue({
      trace_id: 'trace-1',
      status: 'CANCELLED',
      target: 'session',
      signalled: true,
    });
    renderInspector('admin', 'RUNNING');

    await userEvent.click(screen.getByRole('button', { name: /stop trace/i }));

    expect(stop).toHaveBeenCalledWith('trace-1');
    await waitFor(() => expect(screen.getByText('CANCELLED')).toBeTruthy());
    expect(screen.queryByRole('button', { name: /stop trace/i })).toBeNull();
  });

  it('does nothing when the admin dismisses the confirmation', async () => {
    vi.stubGlobal('confirm', vi.fn(() => false));
    const stop = vi.spyOn(api.traces, 'stop').mockResolvedValue({
      trace_id: 'trace-1',
      status: 'CANCELLED',
      target: 'session',
      signalled: true,
    });
    renderInspector('admin', 'RUNNING');

    await userEvent.click(screen.getByRole('button', { name: /stop trace/i }));

    expect(stop).not.toHaveBeenCalled();
    expect(screen.getByText('RUNNING')).toBeTruthy();
  });
});
