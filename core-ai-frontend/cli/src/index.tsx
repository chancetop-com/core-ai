#!/usr/bin/env node
import React from 'react';
import { render } from 'ink';
import { parseArgs } from 'node:util';
import { startBackend } from './backend.js';
import { A2AClient } from './client.js';
import { AppContainer } from './app/AppContainer.js';

const VERSION = '0.1.0';

async function main() {
  const { values: opts } = parseArgs({
    options: {
      port:               { type: 'string', default: '0' },
      server:             { type: 'string' },
      model:              { type: 'string' },
      continue:           { type: 'boolean', short: 'c', default: false },
      help:               { type: 'boolean', short: 'h', default: false },
      version:            { type: 'boolean', short: 'V', default: false },
      'skip-permissions': { type: 'boolean', default: false },
    },
    strict: false,
  });

  if (opts.help) {
    process.stdout.write(`core-ai - AI coding assistant (CLI)

Usage: core-ai [options]

Options:
  --server <url>          Connect to existing A2A backend
  --port <port>           Backend port when spawning (default: random)
  --model <name>          Model to use
  -c, --continue          Resume most recent session
  --skip-permissions      Auto-approve all tool calls
  -h, --help              Show help
  -V, --version           Show version

Environment:
  CORE_AI_BACKEND         Path to core-ai-backend binary
  CORE_AI_DEBUG           Enable backend stderr output
`);
    process.exit(0);
  }

  if (opts.version) {
    process.stdout.write(`${VERSION}\n`);
    process.exit(0);
  }

  let baseUrl = opts.server as string | undefined;
  if (!baseUrl) {
    const port = parseInt(opts.port as string) || 0;
    baseUrl = await startBackend({
      port,
      continueSession: opts.continue as boolean,
      skipPermissions: opts['skip-permissions'] as boolean,
      model: opts.model as string,
    });
  }

  // fetch agent info
  const client = new A2AClient(baseUrl);
  let agentName = 'core-ai';
  let modelName = (opts.model as string) || 'default';
  try {
    const card = await client.getAgentCard();
    if (card.name) agentName = card.name;
  } catch {}

  const { unmount, waitUntilExit } = render(
    React.createElement(AppContainer, {
      baseUrl,
      modelName,
      agentName,
      version: VERSION,
      skipPermissions: !!(opts['skip-permissions'] as boolean),
      onExit: () => {
        unmount();
      },
    }),
    { exitOnCtrlC: false }
  );

  await waitUntilExit();
  process.exit(0);
}

main().catch(err => {
  console.error(err.message);
  process.exit(1);
});
