// Stages the pinned CLI engine for packaging: downloads the release asset for the current platform
// and writes engine/engine.json ({version, sha256}) next to it. electron-builder ships the directory
// as extraResources and the packaged app resolves the binary from resources/engine (design §4.2).
// The pin lives in package.json coreAi.pinnedEngine — bump it to move the engine.
import { createHash } from 'node:crypto';
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const pkg = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8'));
const version = pkg.coreAi?.pinnedEngine;
if (!version) throw new Error('package.json is missing coreAi.pinnedEngine');

// The CLI releases one engine per platform; the darwin asset is arm64 only.
const assets = {
  win32: ['core-ai-cli-windows.exe', 'core-ai-cli.exe'],
  darwin: ['core-ai-cli-darwin', 'core-ai-cli'],
  linux: ['core-ai-cli-linux', 'core-ai-cli'],
};
const asset = assets[process.platform];
if (!asset) throw new Error(`unsupported platform: ${process.platform}`);
const [assetName, binaryName] = asset;

const engineDir = path.join(root, 'engine');
const binaryPath = path.join(engineDir, binaryName);
const manifestPath = path.join(engineDir, 'engine.json');
if (existsSync(binaryPath) && stagedVersion() === version) {
  console.log(`engine ${version} is already staged`);
  process.exit(0);
}

const url = `https://github.com/chancetop-com/core-ai/releases/download/v${version}/${assetName}`;
console.log(`downloading ${url}`);
const response = await fetch(url);
if (!response.ok) throw new Error(`engine download failed: HTTP ${response.status} (${url})`);
const bytes = Buffer.from(await response.arrayBuffer());
const sha256 = createHash('sha256').update(bytes).digest('hex');

mkdirSync(engineDir, { recursive: true });
writeFileSync(binaryPath, bytes);
chmodSync(binaryPath, 0o755);
writeFileSync(manifestPath, `${JSON.stringify({ version, sha256 }, null, 2)}\n`);
console.log(`staged engine ${version}: ${binaryName} (${bytes.length} bytes, sha256 ${sha256})`);

function stagedVersion() {
  try {
    return JSON.parse(readFileSync(manifestPath, 'utf8')).version;
  } catch {
    return null;
  }
}
