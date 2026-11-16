// Runtime probes used by the built-in skills panel. The npm shim on Windows is the interesting
// case: npm writes an extensionless sh script next to npm.cmd, and CreateProcess needs the .cmd.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import runtimeTools from '../dist/test/runtimeTools.js';

const {
  resolveExecutable,
  probeCommand,
  withExecutableOnPath,
  compareVersions,
  isNewerVersion,
  parsePypiVersion,
  parseNpmVersion,
  parseCuaManifestVersion,
} = runtimeTools;

test('resolveExecutable finds npm and prefers the Windows shim', () => {
  const npm = resolveExecutable('npm');
  assert.ok(npm, 'npm should be on PATH');
  if (process.platform === 'win32') {
    assert.match(npm, /\.(cmd|exe)$/i);
  }
});

test('probeCommand reports npm as present with a version', async () => {
  const status = await probeCommand('npm');
  assert.equal(status.present, true);
  assert.ok(status.version, `expected a version from ${status.detail}`);
});

test('withExecutableOnPath prepends the bundled engine directory once', () => {
  const directory = path.dirname(process.execPath);
  const first = withExecutableOnPath({}, directory);
  const pathKey = Object.keys(first).find((key) => key.toUpperCase() === 'PATH');
  assert.ok(pathKey);
  assert.equal(first[pathKey].split(path.delimiter)[0], directory);
  const second = withExecutableOnPath({ [pathKey]: first[pathKey] }, directory);
  assert.equal(second[pathKey].split(path.delimiter).filter((part) => part === directory).length, 1);

  // npm puts node_modules/.bin first and node's own bin later; the bundled directory must still
  // win even when it is already somewhere on PATH.
  const seeded = [path.join(directory, '..', 'somewhere'), directory, path.join(directory, '..', 'elsewhere')].join(path.delimiter);
  const moved = withExecutableOnPath({ [pathKey]: seeded }, directory);
  assert.equal(moved[pathKey].split(path.delimiter)[0], directory);
  assert.equal(moved[pathKey].split(path.delimiter).filter((part) => part === directory).length, 1);
});

test('compareVersions orders dotted versions and ignores a v prefix', () => {
  assert.equal(compareVersions('1.0.5', '0.1.9'), 1);
  assert.equal(compareVersions('v1.3.7', '1.3.6'), 1);
  assert.equal(compareVersions('1.2', '1.2.0'), 0);
  assert.equal(compareVersions('0.27.7', '0.4.1'), 1);
});

test('isNewerVersion only reports an update when both versions are known', () => {
  assert.equal(isNewerVersion('1.0.5', '0.1.9'), true);
  assert.equal(isNewerVersion('1.3.6', '1.3.6'), false);
  assert.equal(isNewerVersion(null, '1.3.6'), false);
  assert.equal(isNewerVersion('1.0.0', null), false);
});

test('latest-version parsers read PyPI, npm and the Cua manifest shapes', () => {
  assert.equal(parsePypiVersion({ info: { version: '1.0.5' } }), '1.0.5');
  assert.equal(parsePypiVersion({ info: {} }), null);
  assert.equal(parseNpmVersion({ version: '1.3.7' }), '1.3.7');
  assert.equal(parseNpmVersion({}), null);
  assert.equal(parseCuaManifestVersion({ versions: { cli: '0.27.7', app: '0.36.24' } }), '0.27.7');
  assert.equal(parseCuaManifestVersion({ versions: {} }), null);
});
