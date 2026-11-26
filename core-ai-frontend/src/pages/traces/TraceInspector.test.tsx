import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import type { Span, Trace } from '../../api/client';
import TraceInspector from './TraceInspector';

vi.mock('../../api/permissions', () => ({ usePermission: () => true }));

const TRACE = {
  id: 'fb649a0d',
  traceId: 'fb649a0d0839e1dedec6051af35e877b',
  name: 'Main-Meta-Ads-Agent',
  type: 'agent',
  source: 'chat',
  agentName: 'Main-Meta-Ads-Agent',
  model: 'deepseek-flash',
  sessionId: '04909181-8cb8-46e3-9f55-8c4e59475316',
  userId: 'jonathanguo@chancetop.com',
  status: 'CANCELLED',
  metadata: {},
  inputTokens: 0,
  outputTokens: 0,
  totalTokens: 0,
  durationMs: 39814,
  startedAt: '2026-11-26T09:43:18.265Z',
  completedAt: '2026-11-26T09:43:58.079Z',
  createdAt: '2026-11-26T09:43:18.266Z',
} as Trace;

const LLM_SPAN = {
  id: 'a42b27d3',
  traceId: TRACE.traceId,
  spanId: 'a42b27d3e9cdf041',
  parentSpanId: null,
  name: 'Main-Meta-Ads-Agent',
  type: 'LLM',
  model: 'deepseek-flash',
  input: JSON.stringify({
    truncated: true,
    messages: [
      { role: 'system', content: 'you are an agent' },
      { role: 'assistant', content: 'delegating', tool_calls: ['Jonathan_Meta_Datasets_Agent'] },
    ],
  }),
  output: null,
  inputTokens: 100,
  outputTokens: 0,
  durationMs: 39684,
  status: 'CANCELLED',
  attributes: {},
  startedAt: '2026-11-26T09:43:18.300Z',
  completedAt: '2026-11-26T09:43:57.984Z',
} as Span;

describe('TraceInspector', () => {
  it('renders a summarized payload whose tool_calls keep only the tool name', async () => {
    const loadSpan = async () => LLM_SPAN;
    render(
      <MemoryRouter>
        <TraceInspector trace={TRACE} spans={[LLM_SPAN]} loadSpan={loadSpan} />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Messages' }));

    expect(await screen.findByText('Jonathan_Meta_Datasets_Agent')).toBeTruthy();
    expect(screen.getByText(/arguments not recorded/)).toBeTruthy();
    expect(screen.getByText(/summarized to fit the trace size limit/)).toBeTruthy();
  }, 30000);
});
