// Local tests for computer use: the managed skill install lifecycle and the engine env gate.
// Run with `npm test` (builds dist/test/{managedSkill,cuaManager}.js first, then node --test).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import managedSkillModule from '../dist/test/managedSkill.js';
import cuaManagerModule from '../dist/test/cuaManager.js';

const { describeManagedSkill, installManagedSkill } = managedSkillModule;
const { CuaManager } = cuaManagerModule;
const META = { skill: 'gui-automation', source: 'test@0', desktopVersion: '0.0.0' };
const temps = [];

function tempDir(prefix) {
  const dir = mkdtempSync(path.join(tmpdir(), prefix));
  temps.push(dir);
  return dir;
}

function makeSourceDir() {
  const dir = tempDir('coreai-skill-src-');
  mkdirSync(path.join(dir, 'references'));
  writeFileSync(path.join(dir, 'SKILL.md'), '# gui automation\n');
  writeFileSync(path.join(dir, 'references', 'command-reference.md'), 'commands\n');
  return dir;
}

function makeInstalledHome(sourceDir) {
  const home = tempDir('coreai-cua-home-');
  mkdirSync(path.join(home, '.cua', 'host'), { recursive: true });
  writeFileSync(path.join(home, '.cua', 'host_consented'), 'consented');
  writeFileSync(path.join(home, '.cua', 'do_target.json'), JSON.stringify({ provider: 'host', name: 'host' }));
  writeFileSync(path.join(home, '.cua', 'env-tokens.json'), JSON.stringify({ host: 'token' }));
  writeFileSync(path.join(home, '.cua', 'host', 'env-token'), 'token');
  installManagedSkill(sourceDir, path.join(home, '.core-ai', 'skills', 'gui-automation'), META);
  return home;
}

test.after(() => {
  for (const dir of temps) rmSync(dir, { recursive: true, force: true });
});

test('installManagedSkill installs a fresh copy with a marker', () => {
  const source = makeSourceDir();
  const target = path.join(tempDir('coreai-skill-dst-'), 'gui-automation');
  const result = installManagedSkill(source, target, META);
  assert.equal(result.ok, true);
  assert.equal(result.changed, true);
  assert.equal(result.files, 2);
  assert.ok(existsSync(path.join(target, 'SKILL.md')));
  assert.ok(existsSync(path.join(target, 'references', 'command-reference.md')));
  const marker = JSON.parse(readFileSync(path.join(target, '.core-ai-desktop.json'), 'utf8'));
  assert.equal(marker.skill, 'gui-automation');
  assert.equal(marker.source, 'test@0');
  assert.deepEqual(Object.keys(marker.files).sort(), ['SKILL.md', 'references/command-reference.md']);
  const state = describeManagedSkill(source, target);
  assert.deepEqual([state.installed, state.managed, state.upToDate], [true, true, true]);
});

test('installManagedSkill is a no-op when the copy is current', () => {
  const source = makeSourceDir();
  const target = path.join(tempDir('coreai-skill-dst-'), 'gui-automation');
  installManagedSkill(source, target, META);
  const again = installManagedSkill(source, target, META);
  assert.equal(again.ok, true);
  assert.equal(again.changed, false);
  assert.equal(again.detail, 'up to date');
});

test('installManagedSkill refreshes when the bundled copy changes', () => {
  const source = makeSourceDir();
  const target = path.join(tempDir('coreai-skill-dst-'), 'gui-automation');
  installManagedSkill(source, target, META);
  writeFileSync(path.join(source, 'SKILL.md'), '# gui automation v2\n');
  const updated = installManagedSkill(source, target, META);
  assert.equal(updated.ok, true);
  assert.equal(updated.changed, true);
  assert.equal(readFileSync(path.join(target, 'SKILL.md'), 'utf8'), '# gui automation v2\n');
  assert.equal(describeManagedSkill(source, target).upToDate, true);
});

test('a locally modified copy is kept, not overwritten', () => {
  const source = makeSourceDir();
  const target = path.join(tempDir('coreai-skill-dst-'), 'gui-automation');
  installManagedSkill(source, target, META);
  writeFileSync(path.join(target, 'SKILL.md'), '# locally edited\n');
  writeFileSync(path.join(source, 'SKILL.md'), '# v2\n');
  const result = installManagedSkill(source, target, META);
  assert.equal(result.ok, false);
  assert.match(result.detail, /modified locally/);
  assert.equal(readFileSync(path.join(target, 'SKILL.md'), 'utf8'), '# locally edited\n');
  const state = describeManagedSkill(source, target);
  assert.deepEqual([state.installed, state.managed, state.upToDate], [true, true, false]);
});

test('an unmanaged copy is never overwritten', () => {
  const source = makeSourceDir();
  const target = path.join(tempDir('coreai-skill-dst-'), 'gui-automation');
  mkdirSync(target, { recursive: true });
  writeFileSync(path.join(target, 'SKILL.md'), '# user copy\n');
  const result = installManagedSkill(source, target, META);
  assert.equal(result.ok, false);
  assert.match(result.detail, /outside desktop management/);
  assert.equal(readFileSync(path.join(target, 'SKILL.md'), 'utf8'), '# user copy\n');
});

test('engineEnv gates on enabled plus every ready file check', () => {
  const source = makeSourceDir();
  const home = makeInstalledHome(source);
  const previous = process.env.CORE_AI_CUA_BIN;
  process.env.CORE_AI_CUA_BIN = process.execPath;
  try {
    const manager = new CuaManager({ skillSourceDir: source, desktopVersion: '0.0.0', homeDir: home });
    assert.deepEqual(manager.engineEnv(false), {});
    const env = manager.engineEnv(true);
    assert.equal(env.CORE_AI_COMPUTER_USE, '1');
    const pathKey = Object.keys(env).find((key) => key.toUpperCase() === 'PATH');
    if (pathKey) assert.ok(env[pathKey].startsWith(path.dirname(process.execPath)));

    rmSync(path.join(home, '.cua', 'host_consented'));
    assert.deepEqual(manager.engineEnv(true), {});
  } finally {
    if (previous === undefined) delete process.env.CORE_AI_CUA_BIN;
    else process.env.CORE_AI_CUA_BIN = previous;
  }
});

test('status reports file-derived state and problems', async () => {
  const source = makeSourceDir();
  const home = makeInstalledHome(source);
  const previous = process.env.CORE_AI_CUA_BIN;
  process.env.CORE_AI_CUA_BIN = process.execPath;
  try {
    const manager = new CuaManager({ skillSourceDir: source, desktopVersion: '0.0.0', homeDir: home });
    const status = await manager.status(true);
    assert.equal(status.ready, true);
    assert.deepEqual(status.problems, []);
    assert.equal(status.cli.present, true);
    assert.equal(status.consent, true);
    assert.equal(status.target.host, true);
    assert.equal(status.token, true);
    assert.equal(status.skill.installed, true);
    assert.equal(status.enabled, true);
  } finally {
    if (previous === undefined) delete process.env.CORE_AI_CUA_BIN;
    else process.env.CORE_AI_CUA_BIN = previous;
  }
});
