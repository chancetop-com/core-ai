// Route one OCG channel to the desktop mailbox: config write, transport-specific restart.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import routingModule from '../dist/test/ocgRouting.js';

const { routeChannelToDesktop } = routingModule;

function harness({ agentType = 'http', run, restart } = {}) {
  const commands = [];
  const restarts = [];
  const deps = {
    channels: async () => ({
      ok: true,
      data: { channels: [{ channelId: 'wechat', agentType, enabled: true, accounts: [] }] },
    }),
    run: async (args, timeoutMs) => {
      commands.push({ args, timeoutMs });
      return run ? run(args) : { code: 0, stdout: '', stderr: '' };
    },
    restartChannel: async (channelId) => {
      restarts.push(channelId);
      return restart ? restart(channelId) : { ok: true };
    },
  };
  return { deps, commands, restarts };
}

test('an http channel is switched to mailbox and restarted', async () => {
  const { deps, commands, restarts } = harness();
  const result = await routeChannelToDesktop('wechat', deps);
  assert.deepEqual(result, { ok: true, previous: 'http' });
  assert.deepEqual(commands.map((entry) => entry.args), [['config', 'set', 'channels.wechat.agentType', 'mailbox']]);
  assert.deepEqual(restarts, ['wechat']);
});

test('an acp channel restarts the daemon so the old ACP process exits', async () => {
  const { deps, commands, restarts } = harness({ agentType: 'acp' });
  const result = await routeChannelToDesktop('wechat', deps);
  assert.deepEqual(result, { ok: true, previous: 'acp', daemonRestarted: true });
  assert.deepEqual(commands.map((entry) => entry.args), [
    ['config', 'set', 'channels.wechat.agentType', 'mailbox'],
    ['daemon', 'stop', '--json'],
    ['daemon', 'start', '--json'],
  ]);
  assert.deepEqual(restarts, []);
});

test('an invalid channel id is rejected before touching OCG', async () => {
  const { deps, commands } = harness();
  for (const id of ['../wechat', 'open.claw', '']) {
    const result = await routeChannelToDesktop(id, deps);
    assert.equal(result.ok, false);
    assert.match(result.error, /invalid channel id/);
  }
  assert.deepEqual(commands, []);
});

test('a channel that is not configured is rejected', async () => {
  const { deps, commands } = harness();
  const result = await routeChannelToDesktop('qqbot', deps);
  assert.equal(result.ok, false);
  assert.match(result.error, /channel not found/);
  assert.deepEqual(commands, []);
});

test('a failed config write does not restart anything', async () => {
  const { deps, restarts } = harness({
    run: () => ({ code: 1, stdout: '', stderr: 'unknown config path' }),
  });
  const result = await routeChannelToDesktop('wechat', deps);
  assert.equal(result.ok, false);
  assert.match(result.error, /unknown config path/);
  assert.deepEqual(restarts, []);
});

test('a failed daemon restart keeps the saved route and reports a warning', async () => {
  const { deps } = harness({
    agentType: 'acp',
    run: (args) => (args[0] === 'daemon' && args[1] === 'start'
      ? { code: 1, stdout: '', stderr: 'port is in use' }
      : { code: 0, stdout: '', stderr: '' }),
  });
  const result = await routeChannelToDesktop('wechat', deps);
  assert.equal(result.ok, true);
  assert.equal(result.previous, 'acp');
  assert.match(result.warning, /daemon restart failed: port is in use/);
  assert.equal(result.daemonRestarted, undefined);
});
