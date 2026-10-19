import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ChatMessageRow } from './ChatMessagesPanel';
import type { ChatMessage } from '../types';

const message: ChatMessage = {
  role: 'agent',
  segments: [
    { type: 'tools', tools: [{ type: 'result', tool: 'present_choices', callId: 'call-1' }] },
    { type: 'quick_replies', question: 'Pick one', options: [{ label: 'Yes', value: 'yes' }, { label: 'No', value: 'no' }] },
    { type: 'text', content: 'A long reply body' },
  ],
};

function renderRow(msg: ChatMessage = message) {
  render(
    <ChatMessageRow
      msg={msg}
      msgIndex={0}
      messagesLength={1}
      isStreamingLast={false}
      isThinking={false}
      planTodos={null}
      sessionArtifacts={[]}
      agentMarkdownComponents={{}}
      onOpenArtifact={vi.fn()}
      terminalEnabled={false}
      onOpenSandboxTerminal={vi.fn()}
      onApproval={vi.fn()}
      showFeedback={false}
      onQuickReply={vi.fn()}
      interactiveDisabled={false}
    />,
  );
}

describe('ChatMessageRow', () => {
  it('renders quick replies below the reply body so long answers keep the choices reachable', () => {
    renderRow();

    const body = screen.getByText('A long reply body');
    const choice = screen.getByRole('button', { name: 'Yes' });

    expect(body.compareDocumentPosition(choice) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});
