// Bundles the desktop app with esbuild: main + preload (Electron, cjs), renderer (browser, iife)
// and the engine client for the node:test suite. No watch mode: `npm start` rebuilds first.
import { build } from 'esbuild';
import { copyFileSync, mkdirSync, readFileSync, rmSync } from 'node:fs';

// VERSION is the release's single source of truth (packaging reads it too, see electron-builder.cjs).
const version = readFileSync('VERSION', 'utf8').trim();

rmSync('dist', { recursive: true, force: true });
mkdirSync('dist/renderer', { recursive: true });
mkdirSync('dist/test', { recursive: true });

const shared = {
  bundle: true,
  sourcemap: true,
  logLevel: 'warning',
  define: { __APP_VERSION__: JSON.stringify(version) },
};

const targets = [
  { entryPoints: ['src/main/main.ts'], outfile: 'dist/main.js', platform: 'node', target: 'node20', format: 'cjs', external: ['electron'] },
  { entryPoints: ['src/preload/preload.ts'], outfile: 'dist/preload.js', platform: 'node', target: 'node20', format: 'cjs', external: ['electron'] },
  { entryPoints: ['src/main/engine/RpcClient.ts'], outfile: 'dist/test/rpcClient.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/browser/CdpBridge.ts'], outfile: 'dist/test/cdpBridge.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/computer/managedSkill.ts'], outfile: 'dist/test/managedSkill.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/computer/CuaManager.ts'], outfile: 'dist/test/cuaManager.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/attachments/AttachmentStager.ts'], outfile: 'dist/test/attachmentStager.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/main/update/UpdateService.ts'], outfile: 'dist/test/updateService.js', platform: 'node', target: 'node20', format: 'cjs' },
  { entryPoints: ['src/renderer/app.ts'], outfile: 'dist/renderer/app.js', platform: 'browser', target: 'chrome120', format: 'iife' },
];

for (const target of targets) {
  await build({ ...shared, ...target });
}

copyFileSync('src/renderer/index.html', 'dist/renderer/index.html');
copyFileSync('src/renderer/styles.css', 'dist/renderer/styles.css');
copyFileSync('src/assets/logo.svg', 'dist/renderer/logo.svg');
copyFileSync('src/assets/logo-light.svg', 'dist/renderer/logo-light.svg');
copyFileSync('src/assets/logo.png', 'dist/logo.png');
