// Local tests for the CLI/engine sync that runs after a desktop update: version parsing, target
// discovery, and the upgrade-only copy against stubbed version reads.
// Run with `npm test` (builds dist/test/cliUpdate.js first, then node --test).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import cliModule from '../dist/test/cliUpdate.js';

const { cliBinaryName, parseCliVersion, readCliVersion, readEngineManifest, standaloneCliTargets, syncStandaloneClis } = cliModule;

const temps = [];

function tempDir() {
  const dir = mkdtempSync(path.join(tmpdir(), 'coreai-cli-'));
  temps.push(dir);
  return dir;
}

function writeFile(file, content) {
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, content);
  return file;
}

test.after(() => {
  for (const dir of temps) rmSync(dir, { recursive: true, force: true });
});

test('cliBinaryName follows the platform naming', () => {
  assert.equal(cliBinaryName('win32'), 'core-ai-cli.exe');
  assert.equal(cliBinaryName('darwin'), 'core-ai-cli');
  assert.equal(cliBinaryName('linux'), 'core-ai-cli');
});

test('parseCliVersion reads the bare version out of CLI output', () => {
  assert.equal(parseCliVersion('2.0.53'), '2.0.53');
  assert.equal(parseCliVersion('v2.0.53\n'), '2.0.53');
  assert.equal(parseCliVersion('core-ai-cli 2.0.53 (native)'), '2.0.53');
  assert.equal(parseCliVersion('unknown'), null);
  assert.equal(parseCliVersion(''), null);
});

test('readEngineManifest reads version and sha256 next to the binary', () => {
  const dir = tempDir();
  const binary = writeFile(path.join(dir, 'core-ai-cli.exe'), 'engine');
  writeFileSync(path.join(dir, 'engine.json'), JSON.stringify({ version: '2.0.53', sha256: 'ABC123' }));
  assert.deepEqual(readEngineManifest(binary), { version: '2.0.53', sha256: 'abc123' });
  writeFileSync(path.join(dir, 'engine.json'), JSON.stringify({ sha256: 'abc' }));
  assert.equal(readEngineManifest(binary), null);
  assert.equal(readEngineManifest(path.join(tempDir(), 'missing', 'core-ai-cli.exe')), null);
});

test('standaloneCliTargets finds the canonical install and PATH copies, never the bundled engine', () => {
  const root = tempDir();
  const home = path.join(root, 'home');
  const pathDir = path.join(root, 'tools');
  const engineDir = path.join(root, 'app', 'resources', 'engine');
  const name = cliBinaryName(process.platform);
  const canonical = writeFile(path.join(home, '.core-ai', 'bin', name), 'canonical');
  const onPath = writeFile(path.join(pathDir, name), 'path');
  writeFile(path.join(engineDir, name), 'bundled');
  const searchPath = [pathDir, engineDir, pathDir, path.join(root, 'missing')].join(path.delimiter);

  const targets = standaloneCliTargets({ homeDir: home, searchPath, excludeDirs: [engineDir] });
  assert.deepEqual(targets, [canonical, onPath]);
});

test('standaloneCliTargets never adopts a desktop package engine directory on PATH', () => {
  const root = tempDir();
  const name = cliBinaryName(process.platform);
  const otherAppEngine = path.join(root, 'other-app', 'resources', 'engine');
  writeFile(path.join(otherAppEngine, name), 'bundled');
  const targets = standaloneCliTargets({ homeDir: path.join(root, 'home'), searchPath: otherAppEngine });
  assert.deepEqual(targets, []);
});

test('syncStandaloneClis upgrades older copies, keeps newer ones and reports failures', async () => {
  const dir = tempDir();
  const bundled = writeFile(path.join(dir, 'engine', 'core-ai-cli'), 'NEW ENGINE');
  const older = writeFile(path.join(dir, 'bin', 'core-ai-cli'), 'old 2.0.52');
  const current = writeFile(path.join(dir, 'bin2', 'core-ai-cli'), 'current 2.0.53');
  const newer = writeFile(path.join(dir, 'bin3', 'core-ai-cli'), 'newer 2.0.54');
  const unreadable = writeFile(path.join(dir, 'bin4', 'core-ai-cli'), 'broken');
  const versions = new Map([[older, '2.0.52'], [current, '2.0.53'], [newer, '2.0.54'], [unreadable, null]]);

  const outcomes = await syncStandaloneClis({
    bundledBinary: bundled,
    bundledVersion: '2.0.53',
    targets: [older, current, newer, unreadable, older],
    readVersion: async (binary) => versions.get(binary) ?? null,
  });

  assert.deepEqual(outcomes, [
    { status: 'updated', path: older, from: '2.0.52', to: '2.0.53' },
    { status: 'current', path: current, version: '2.0.53' },
    { status: 'current', path: newer, version: '2.0.54' },
    { status: 'skipped', path: unreadable, reason: 'its version could not be read' },
  ]);
  assert.equal(readFileSync(older, 'utf8'), 'NEW ENGINE');
  assert.equal(readFileSync(current, 'utf8'), 'current 2.0.53');
  assert.equal(readFileSync(newer, 'utf8'), 'newer 2.0.54');
  assert.equal(readFileSync(unreadable, 'utf8'), 'broken');
  assert.equal(existsSync(`${older}.new`), false);
});

test('a checksum mismatch leaves every install untouched', async () => {
  const dir = tempDir();
  const bundled = writeFile(path.join(dir, 'engine', 'core-ai-cli'), 'NEW ENGINE');
  const target = writeFile(path.join(dir, 'bin', 'core-ai-cli'), 'old');
  const outcomes = await syncStandaloneClis({
    bundledBinary: bundled,
    bundledVersion: '2.0.53',
    bundledSha256: 'expected',
    targets: [target],
    hashFile: async () => 'actual',
    readVersion: async () => '2.0.52',
  });
  assert.equal(outcomes[0].status, 'skipped');
  assert.match(outcomes[0].reason, /checksum/);
  assert.equal(readFileSync(target, 'utf8'), 'old');
});

test('a missing bundled engine or an empty target list is a no-op', async () => {
  const dir = tempDir();
  const target = writeFile(path.join(dir, 'bin', 'core-ai-cli'), 'old');
  const missing = await syncStandaloneClis({
    bundledBinary: path.join(dir, 'engine', 'core-ai-cli'),
    bundledVersion: '2.0.53',
    targets: [target],
    readVersion: async () => '2.0.52',
  });
  assert.equal(missing[0].status, 'skipped');
  assert.match(missing[0].reason, /bundled engine is missing/);
  assert.equal(readFileSync(target, 'utf8'), 'old');
  assert.deepEqual(await syncStandaloneClis({ bundledBinary: target, bundledVersion: '2.0.53', targets: [] }), []);
});

test('readCliVersion reports null for a binary it cannot run', async () => {
  assert.equal(await readCliVersion(path.join(tempDir(), 'nope', 'core-ai-cli')), null);
});
