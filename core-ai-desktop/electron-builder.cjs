// Electron packaging config (electron-builder auto-loads electron-builder.cjs).
//
// Keep the .cjs extension: cmd.exe resolves a bare `electron-builder` command against the project
// directory first, and a file named electron-builder.js would be "executed" through the .js file
// association (Notepad++, WScript) instead of node_modules/.bin/electron-builder.cmd. `.cjs` is not
// in PATHEXT, so the collision cannot happen.
//
// Version policy: core-ai-desktop/VERSION is the single source of truth; package.json keeps a 0.0.0
// placeholder. The real version is injected via extraMetadata, which lands in the packed app's
// package.json (app.getVersion), the artifact/installer metadata and the update feed files — so a
// release can never disagree with its VERSION.
const { existsSync, readFileSync } = require('node:fs');
const path = require('node:path');

const version = readFileSync(path.join(__dirname, 'VERSION'), 'utf8').trim();
// The pinned engine (scripts/stage-engine.mjs) is staged per release build; local dev builds may skip it.
const engineDir = path.join(__dirname, 'engine');

module.exports = {
  appId: 'com.chancetop.core-ai',
  productName: 'core-ai',
  artifactName: 'core-ai-desktop-${os}-${arch}.${ext}',
  directories: { output: 'release' },
  files: ['dist/**/*', '!dist/test/**'],
  extraMetadata: { version },
  // Generates latest.yml / latest-mac.yml for the update feed; CI passes --publish never and the
  // release job uploads the files itself (the repo latest slot stays with the CLI).
  publish: [{ provider: 'github', owner: 'chancetop-com', repo: 'core-ai' }],
  ...(existsSync(engineDir) ? { extraResources: [{ from: 'engine', to: 'engine' }] } : {}),
  win: {
    target: [{ target: 'nsis', arch: ['x64'] }],
    icon: 'src/assets/logo.png',
  },
  nsis: {
    oneClick: false,
    allowToChangeInstallationDirectory: true,
  },
  mac: {
    // The CLI ships a single darwin (arm64) engine today, so the mac package is arm64 only.
    target: [
      { target: 'dmg', arch: ['arm64'] },
      { target: 'zip', arch: ['arm64'] },
    ],
    icon: 'src/assets/logo.png',
    category: 'public.app-category.developer-tools',
  },
};
