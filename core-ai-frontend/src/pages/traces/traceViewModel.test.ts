import { describe, expect, it } from 'vitest';
import {
  extractAssistantContent,
  extractMessages,
  extractTracePreview,
  isReplayableRequest,
} from './traceViewModel';

describe('trace span payload extraction', () => {
  it('extracts chat-style messages', () => {
    const messages = extractMessages(JSON.stringify({
      messages: [
        { role: 'system', content: 'be brief' },
        { role: 'user', content: [{ type: 'text', text: 'hello' }] },
        {
          role: 'assistant',
          content: '',
          tool_calls: [{ id: 'call-1', function: { name: 'exec_command', arguments: '{"cmd":"ls"}' } }],
        },
        { role: 'tool', tool_call_id: 'call-1', content: 'a.txt' },
      ],
    }));

    expect(messages).toEqual([
      { role: 'system', content: 'be brief' },
      { role: 'user', content: 'hello' },
      {
        role: 'assistant',
        content: '',
        tool_calls: [{ id: 'call-1', function: { name: 'exec_command', arguments: '{"cmd":"ls"}' } }],
      },
      { role: 'tool', content: 'a.txt', tool_call_id: 'call-1' },
    ]);
  });

  it('extracts responses-style instructions and typed input items', () => {
    const messages = extractMessages(JSON.stringify({
      model: 'deepseek-flash',
      instructions: 'You are Codex, a coding agent.',
      input: [
        { type: 'message', id: 'msg_1', role: 'developer', content: [{ type: 'input_text', text: 'follow the repo rules' }] },
        { type: 'message', id: 'msg_2', role: 'user', content: [{ type: 'input_text', text: 'fix the bug' }] },
        { type: 'function_call', call_id: 'call_1', name: 'exec_command', arguments: '{"cmd":"ls"}' },
        { type: 'function_call_output', call_id: 'call_1', output: 'a.txt' },
        { type: 'reasoning', id: 'rs_1', summary: [{ type: 'summary_text', text: 'thinking' }] },
      ],
    }));

    expect(messages).toEqual([
      { role: 'system', content: 'You are Codex, a coding agent.' },
      { role: 'system', content: 'follow the repo rules' },
      { role: 'user', content: 'fix the bug' },
      {
        role: 'assistant',
        content: '',
        tool_calls: [{ id: 'call_1', function: { name: 'exec_command', arguments: '{"cmd":"ls"}' } }],
      },
      { role: 'tool', content: 'a.txt', tool_call_id: 'call_1' },
    ]);
  });

  it('supports a plain string responses input', () => {
    expect(extractMessages(JSON.stringify({ model: 'gpt-5', input: 'hi' })))
      .toEqual([{ role: 'user', content: 'hi' }]);
  });

  it('returns no messages for payloads without a conversation', () => {
    expect(extractMessages(JSON.stringify({ model: 'gpt-5', tools: [] }))).toEqual([]);
    expect(extractMessages('not json')).toEqual([]);
    expect(extractMessages(null)).toEqual([]);
  });

  it('extracts the responses output items as an assistant response', () => {
    const output = extractAssistantContent(JSON.stringify({
      id: 'resp_1',
      status: 'completed',
      output: [
        { type: 'reasoning', id: 'rs_1', summary: [{ type: 'summary_text', text: 'checked the repo' }] },
        { type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'done' }] },
        { type: 'function_call', call_id: 'call_1', name: 'exec_command', arguments: '{"cmd":"ls"}' },
      ],
    }));

    expect(output).toEqual({
      content: 'done',
      reasoning: 'checked the repo',
      tool_calls: [{ id: 'call_1', function: { name: 'exec_command', arguments: '{"cmd":"ls"}' } }],
    });
  });

  it('keeps plain streaming text as the assistant response', () => {
    expect(extractAssistantContent('hello world')).toEqual({ content: 'hello world' });
  });

  it('previews the last responses user input instead of the instructions', () => {
    const trace = {
      input: JSON.stringify({
        instructions: 'You are Codex, a coding agent.',
        input: [
          { type: 'message', role: 'user', content: [{ type: 'input_text', text: 'first question' }] },
          { type: 'message', role: 'user', content: [{ type: 'input_text', text: 'second question' }] },
        ],
      }),
    } as unknown as Parameters<typeof extractTracePreview>[0];

    expect(extractTracePreview(trace)).toBe('second question');
  });

  it('marks only chat-style requests as replayable', () => {
    expect(isReplayableRequest(JSON.stringify({ messages: [{ role: 'user', content: 'hi' }] }))).toBe(true);
    expect(isReplayableRequest(JSON.stringify({ instructions: 'x', input: [{ type: 'message', role: 'user' }] }))).toBe(false);
    expect(isReplayableRequest(null)).toBe(false);
  });
});
