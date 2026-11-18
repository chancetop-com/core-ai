// Unit tests for the allowlisted plugin terminal operations.
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { runPluginTerminal, sanitizedEnv } from '../dist/test/pluginTerminal.js';

function command(operation) {
  return { pluginId: 'test.plugin', pluginName: 'Test', commandId: 'run', name: 'run', description: 'run', operation };
}

test('runs an allowlisted operation without a shell', async () => {
  const workspace = mkdtempSync(path.join(os.tmpdir(), 'coreai-terminal-'));
  const result = await runPluginTerminal(command('node.version'), { workspace });
  assert.equal(result.exitCode, 0);
  assert.match(result.stdout, /^v\d+/);
  assert.equal(result.truncated, false);
});

test('strips credential-shaped environment variables', () => {
  const env = sanitizedEnv({ PATH: 'p', CORE_AI_TEST_SECRET: 'leaked', GIT_CONFIG_VALUE_0: 'x' });
  assert.equal(env.PATH, 'p');
  assert.equal(env.CORE_AI_TEST_SECRET, undefined);
  assert.equal(env.GIT_CONFIG_VALUE_0, undefined);
});

test('rejects an operation outside the allowlist', async () => {
  const workspace = mkdtempSync(path.join(os.tmpdir(), 'coreai-terminal-'));
  await assert.rejects(
    runPluginTerminal(command('shell.exec'), { workspace }),
    /unsupported terminal operation/,
  );
});
