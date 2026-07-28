import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import React, { createRef } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { createServer } from 'vite';

const ATTACHMENT_URL = 'https://example.test/menu.png';
const TEXT_BUBBLE_CLASS = 'rounded-xl px-4 py-3 text-sm overflow-x-auto';

let vite;
let ChatMessagesPanel;

before(async () => {
  vite = await createServer({
    appType: 'custom',
    logLevel: 'silent',
    server: { middlewareMode: true },
  });
  ({ default: ChatMessagesPanel } = await vite.ssrLoadModule(
    '/src/pages/chat/components/ChatMessagesPanel.tsx',
  ));
});

after(async () => {
  await vite?.close();
});

function renderMessage(message) {
  return renderToStaticMarkup(React.createElement(ChatMessagesPanel, {
    messages: [message],
    status: 'idle',
    isThinking: false,
    planTodos: null,
    compressionInfo: null,
    sessionArtifacts: [],
    agentVariableEntries: [],
    variableValues: {},
    variablesExpanded: false,
    variablesPanelRef: createRef(),
    messagesContainerRef: createRef(),
    bottomRef: createRef(),
    showJumpToBottom: false,
    visibleMessageLimit: 40,
    onMessagesScroll: () => {},
    onToggleVariables: () => {},
    onVariableChange: () => {},
    onShowEarlierMessages: () => {},
    onScrollToBottom: () => {},
    onOpenArtifact: () => {},
    onOpenSandboxTerminal: () => {},
    onApproval: () => {},
  }));
}

function imageAttachment() {
  return {
    url: ATTACHMENT_URL,
    type: 'IMAGE',
    file_name: 'menu.png',
  };
}

test('renders an attachment-only user message without an empty text bubble', () => {
  const html = renderMessage({
    role: 'user',
    segments: [{ type: 'text', content: '' }],
    attachments: [imageAttachment()],
    timestamp: '2026-07-28T13:53:00.000Z',
  });

  assert.match(html, new RegExp(`src="${ATTACHMENT_URL}"`));
  assert.ok(!html.includes(TEXT_BUBBLE_CLASS), 'empty text bubble should not be rendered');
});

test('renders attachments independently when there is no text segment', () => {
  const html = renderMessage({
    role: 'user',
    segments: [],
    attachments: [imageAttachment()],
    timestamp: '2026-07-28T13:53:00.000Z',
  });

  assert.match(html, new RegExp(`src="${ATTACHMENT_URL}"`));
  assert.ok(!html.includes(TEXT_BUBBLE_CLASS), 'attachment-only messages should not contain a text bubble');
});

test('keeps the text bubble when an attachment has a caption', () => {
  const html = renderMessage({
    role: 'user',
    segments: [{ type: 'text', content: '翻译这张菜单' }],
    attachments: [imageAttachment()],
    timestamp: '2026-07-28T13:55:00.000Z',
  });

  assert.match(html, new RegExp(`src="${ATTACHMENT_URL}"`));
  assert.match(html, /翻译这张菜单/);
  assert.ok(html.includes(TEXT_BUBBLE_CLASS), 'caption text bubble should be rendered');
});
