import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { createServer } from 'vite';

let vite;
let HumanInputConfig;
let RunTrace;
let nodeIssues;

before(async () => {
  vite = await createServer({
    appType: 'custom',
    logLevel: 'silent',
    server: { middlewareMode: true },
  });
  ({ default: HumanInputConfig } = await vite.ssrLoadModule(
    '/src/pages/workflows/HumanInputConfig.tsx',
  ));
  ({ default: RunTrace } = await vite.ssrLoadModule(
    '/src/pages/workflows/RunTrace.tsx',
  ));
  ({ nodeIssues } = await vite.ssrLoadModule(
    '/src/pages/workflows/validation.ts',
  ));
});

after(async () => {
  await vite?.close();
});

function humanInputNode() {
  return {
    id: 'human',
    type: 'workflowNode',
    position: { x: 0, y: 0 },
    data: {
      nodeType: 'HUMAN_INPUT',
      name: 'Human Input',
      config: {
        mode: 'input',
        fields: [{
          name: 'decision',
          type: 'select',
          label: 'Decision',
          required: true,
          options: 'approve, reject',
        }],
      },
    },
  };
}

test('renders an options editor for a Human Input select field', () => {
  const node = humanInputNode();
  const html = renderToStaticMarkup(React.createElement(HumanInputConfig, {
    node,
    nodes: [node],
    edges: [],
    onChange: () => {},
  }));

  assert.match(html, /placeholder="options, comma-separated"/);
  assert.match(html, /value="approve, reject"/);
});

test('renders configured choices when a Human Input select field is waiting', () => {
  const node = humanInputNode();
  const html = renderToStaticMarkup(React.createElement(RunTrace, {
    nodes: [node],
    runStatus: 'PAUSED',
    nodeRuns: {
      human: {
        node_id: 'human',
        status: 'WAITING',
        input: '{"mode":"input","prompt":"Choose a decision"}',
      },
    },
    focusNodeId: 'human',
    onResume: () => {},
  }));

  assert.match(html, /<option value="approve">approve<\/option>/);
  assert.match(html, /<option value="reject">reject<\/option>/);
  assert.ok(!html.includes('type="text"'), 'select fields must not fall back to a text input');
});

test('flags a Human Input select field that has no choices', () => {
  const node = humanInputNode();
  node.data.config.fields[0].options = ' , ';

  assert.ok(
    nodeIssues(node, [node], []).includes('Select field "decision" needs at least one option.'),
  );
});
