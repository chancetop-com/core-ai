#!/usr/bin/env node
/**
 * Build script: compiles TS → JS then bundles into single file.
 *
 * Usage: node --import tsx cli/build.ts
 * Or:    npx tsx cli/build.ts
 */
import { execSync } from 'node:child_process';
import { mkdirSync, writeFileSync, cpSync, existsSync, chmodSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, '..');
const dist = join(root, 'dist');

mkdirSync(dist, { recursive: true });

const bundlePath = join(dist, 'cli.mjs');
const wrapperPath = join(dist, 'core-ai');

// step 1: esbuild bundle TS directly (esbuild handles TSX natively)
console.log('1. Bundling CLI with esbuild...');
execSync(
  `npx esbuild cli/src/index.tsx --bundle --platform=node --format=esm --jsx=automatic --outfile=${bundlePath} --external:ink --external:ink-spinner --external:react --external:react-dom --external:react-devtools-core --external:yoga-wasm-web --banner:js="import{createRequire}from'module';const require=createRequire(import.meta.url);"`,
  { cwd: root, stdio: 'inherit' }
);

// step 2: copy yoga.wasm needed by ink
const yogaWasm = join(root, 'node_modules', 'yoga-wasm-web', 'dist', 'yoga.wasm');
if (existsSync(yogaWasm)) {
  cpSync(yogaWasm, join(dist, 'yoga.wasm'));
}

// step 3: create shell wrapper
console.log('2. Creating launcher...');
writeFileSync(wrapperPath, `#!/bin/sh
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec node "\${SCRIPT_DIR}/cli.mjs" "$@"
`);
chmodSync(wrapperPath, 0o755);

// step 4: symlink node_modules so ESM imports resolve
const nmLink = join(dist, 'node_modules');
const nmTarget = join(root, 'node_modules');
try {
  const { symlinkSync, lstatSync } = await import('node:fs');
  try { lstatSync(nmLink); } catch { symlinkSync(nmTarget, nmLink); }
} catch { /* ignore */ }

// step 3: copy Java backend binary if available
const nativeBin = join(root, '..', 'build', 'core-ai-cli', 'native', 'nativeCompile', 'core-ai-cli');
const backendDest = join(dist, 'core-ai-backend');
if (existsSync(nativeBin)) {
  console.log('3. Copying Java backend binary...');
  cpSync(nativeBin, backendDest);
  chmodSync(backendDest, 0o755);
} else {
  console.log('3. Java native binary not found, skipping.');
  console.log('   Build it with: ./gradlew :core-ai-cli:nativeCompile');
}

console.log(`\nDone! Output: ${dist}/`);
console.log('  core-ai          <- launcher');
console.log('  cli.mjs          <- bundled JS');
if (existsSync(backendDest)) {
  console.log('  core-ai-backend  <- Java backend');
}
console.log('\nUsage: ./dist/core-ai');
