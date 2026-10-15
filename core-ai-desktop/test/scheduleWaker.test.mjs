import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { ScheduleWaker } from '../dist/test/scheduleWaker.js';

function fixture(t, tasks) {
  const root = mkdtempSync(path.join(os.tmpdir(), 'schedule-waker-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const workspace = path.join(root, 'workspace');
  mkdirSync(workspace);
  const file = path.join(root, 'schedules.json');
  writeFileSync(file, JSON.stringify(tasks));
  return { root, workspace, file };
}

test('wakes only known, stopped workspaces with tasks due in five minutes', async (t) => {
  const now = Date.now();
  const { workspace, file } = fixture(t, []);
  writeFileSync(file, JSON.stringify([
    { id: 'soon', sessionId: 's1', workspace, enabled: true, nextRunAt: new Date(now + 60_000).toISOString() },
    { id: 'later', sessionId: 's2', workspace, enabled: true, nextRunAt: new Date(now + 7 * 60_000).toISOString() },
  ]));
  const woken = [];
  const waker = new ScheduleWaker({ schedulesFile: file, knownWorkspaces: () => [workspace], isRunning: () => false, ensureRunning: async (value) => woken.push(value), now: () => now });
  await waker.tick();
  assert.deepEqual(woken, [workspace]);
});

test('stamped workspace tasks wake even after the workspace leaves the recent-workspace list', async (t) => {
  const now = Date.now();
  const { workspace, file } = fixture(t, []);
  writeFileSync(file, JSON.stringify([
    { id: 'stamped', sessionId: 's1', workspace, enabled: true, nextRunAt: new Date(now + 30_000).toISOString() },
  ]));
  const woken = [];
  const waker = new ScheduleWaker({ schedulesFile: file, knownWorkspaces: () => [], isRunning: () => false, ensureRunning: async (value) => woken.push(value), now: () => now });

  await waker.tick();

  assert.deepEqual(woken, [workspace]);
});

test('legacy task ownership is inferred from the session file', async (t) => {
  const now = Date.now();
  const { workspace, file, root } = fixture(t, [
    { id: 'legacy', sessionId: 'old-session', enabled: true, nextRunAt: new Date(now + 30_000).toISOString() },
  ]);
  const sessions = path.join(root, '.core-ai', 'sessions', path.basename(workspace));
  mkdirSync(sessions, { recursive: true });
  writeFileSync(path.join(sessions, 'old-session.data'), '{}');
  const woken = [];
  const waker = new ScheduleWaker({ schedulesFile: file, knownWorkspaces: () => [workspace], sessionFileExists: (candidate, id) => candidate === workspace && id === 'old-session', isRunning: () => false, ensureRunning: async (value) => woken.push(value), now: () => now });
  await waker.tick();
  assert.deepEqual(woken, [workspace]);
});

test('malformed schedule file is ignored without waking engines', async (t) => {
  const { workspace, file } = fixture(t, []);
  writeFileSync(file, '{broken');
  let wakes = 0;
  const waker = new ScheduleWaker({ schedulesFile: file, knownWorkspaces: () => [workspace], isRunning: () => false, ensureRunning: async () => { wakes += 1; } });
  await waker.tick();
  assert.equal(wakes, 0);
});
