import { execFileSync } from 'node:child_process';
import { accessSync, constants, readFileSync, statSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const timeoutMs = 30_000;
const versionPattern = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/;

try {
  verify();
} catch (error) {
  console.error(`Mac package verification failed: ${error.message}`);
  process.exitCode = 1;
}

function verify() {
  if (process.platform !== 'darwin') throw new Error('Run this verification on macOS.');
  if (process.argv.length > 3) throw new Error('Usage: node scripts/verify-mac-package.mjs [path/to/app.app]');

  const appPath = process.argv[2]
    ? path.resolve(process.argv[2])
    : path.join(root, 'release', 'mac-arm64', 'core-ai-desktop.app');
  if (!appPath.endsWith('.app') || !statSync(appPath).isDirectory()) {
    throw new Error(`Expected a macOS app bundle: ${appPath}`);
  }

  run('/usr/bin/codesign', ['--verify', '--deep', '--strict', '--verbose=2', appPath]);
  console.log('App bundle signature verified.');

  const engineDir = path.join(appPath, 'Contents', 'Resources', 'engine');
  const enginePath = path.join(engineDir, 'core-ai-cli');
  assertExecutable(enginePath);
  const manifest = JSON.parse(readFileSync(path.join(engineDir, 'engine.json'), 'utf8'));
  if (typeof manifest.version !== 'string' || !versionPattern.test(manifest.version)) {
    throw new Error('Bundled engine.json must contain a valid version.');
  }
  run('/usr/bin/codesign', ['--verify', '--strict', '--verbose=2', enginePath]);
  console.log('Bundled engine signature verified.');

  const executableName = run('/usr/bin/plutil', [
    '-extract', 'CFBundleExecutable', 'raw', '-o', '-', path.join(appPath, 'Contents', 'Info.plist'),
  ]);
  if (!executableName || path.basename(executableName) !== executableName) {
    throw new Error('App Info.plist must contain a valid CFBundleExecutable.');
  }
  const executablePath = path.join(appPath, 'Contents', 'MacOS', executableName);
  assertExecutable(executablePath);
  const electronVersion = run(executablePath, ['-e', 'process.stdout.write(process.versions.electron || "")'], {
    ...process.env,
    ELECTRON_RUN_AS_NODE: '1',
  });
  if (!versionPattern.test(electronVersion)) {
    throw new Error(`Electron runtime returned an invalid version: ${JSON.stringify(electronVersion)}`);
  }
  console.log(`Electron runtime started: ${electronVersion}.`);

  const engineVersion = run(enginePath, ['--version']);
  if (engineVersion !== manifest.version) {
    throw new Error(`Bundled engine version mismatch: expected ${manifest.version}, received ${JSON.stringify(engineVersion)}.`);
  }
  console.log(`Bundled engine started: ${engineVersion}.`);
  console.log(`Mac package verified: ${appPath}`);
}

function assertExecutable(filePath) {
  if (!statSync(filePath).isFile()) throw new Error(`Expected an executable file: ${filePath}`);
  accessSync(filePath, constants.X_OK);
}

function run(executable, args, env = process.env) {
  try {
    return execFileSync(executable, args, {
      env,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      timeout: timeoutMs,
      killSignal: 'SIGKILL',
      maxBuffer: 1024 * 1024,
    }).trim();
  } catch (error) {
    const details = [error.stderr, error.stdout].filter(Boolean).join('\n').trim();
    const reason = error.code === 'ETIMEDOUT'
      ? `timed out after ${timeoutMs / 1000}s`
      : `failed (${error.signal || error.status || error.code || error.message})`;
    throw new Error(`${executable} ${args.map((arg) => JSON.stringify(arg)).join(' ')} ${reason}${details ? `:\n${details}` : '.'}`);
  }
}
