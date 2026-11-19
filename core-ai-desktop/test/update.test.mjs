// Local tests for the desktop updater: release picking, checksum parsing, the NSIS install
// contract, and the check/install flow against a stubbed fetch.
// Run with `npm test` (builds dist/test/updateService.js first, then node --test).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import updateModule from '../dist/test/updateService.js';

const { UpdateService, compareVersions, pickLatestDesktopRelease, parseLatestYml, installArgs, installModeFor,
  versionedDownloadName, versionFromDownloadName } = updateModule;

const INSTALLER_BYTES = Buffer.alloc(64 * 1024, 7);
const INSTALLER_SHA = createHash('sha512').update(INSTALLER_BYTES).digest('base64');
const FEED_TEXT = [
  'version: 0.1.3',
  'files:',
  '  - url: core-ai-desktop-win-x64.exe',
  `    sha512: ${INSTALLER_SHA}`,
  `    size: ${INSTALLER_BYTES.length}`,
  'path: core-ai-desktop-win-x64.exe',
  `sha512: ${INSTALLER_SHA}`,
  "releaseDate: '2026-10-12T07:48:50.576Z'",
  '',
].join('\n');

const temps = [];

function tempDir() {
  const dir = mkdtempSync(path.join(tmpdir(), 'coreai-update-'));
  temps.push(dir);
  return dir;
}

test.after(() => {
  for (const dir of temps) rmSync(dir, { recursive: true, force: true });
});

/** Mirrors the real GitHub releases payload shape, desktop-v* stream only. */
function releaseFixture(version, { draft = false } = {}) {
  return {
    tag_name: `desktop-v${version}`,
    draft,
    prerelease: true,
    html_url: `https://github.com/chancetop-com/core-ai/releases/tag/desktop-v${version}`,
    assets: [
      { name: 'latest.yml', browser_download_url: `https://assets.test/desktop-v${version}/latest.yml`, size: FEED_TEXT.length },
      { name: 'latest-mac.yml', browser_download_url: `https://assets.test/desktop-v${version}/latest-mac.yml`, size: 515 },
      { name: 'core-ai-desktop-win-x64.exe', browser_download_url: `https://assets.test/desktop-v${version}/core-ai-desktop-win-x64.exe`, size: INSTALLER_BYTES.length },
      { name: 'core-ai-desktop-mac-arm64.dmg', browser_download_url: `https://assets.test/desktop-v${version}/core-ai-desktop-mac-arm64.dmg`, size: INSTALLER_BYTES.length },
    ],
  };
}

function stubFetch({ releases = [], feed = FEED_TEXT, blockDownloads = false } = {}) {
  return async (url) => {
    if (url.startsWith('https://api.github.com/')) return new Response(JSON.stringify(releases), { status: 200 });
    if (url.endsWith('latest.yml') || url.endsWith('latest-mac.yml')) return new Response(feed, { status: 200 });
    if (!blockDownloads && (url.endsWith('.exe') || url.endsWith('.dmg'))) {
      return new Response(INSTALLER_BYTES, { status: 200, headers: { 'content-length': String(INSTALLER_BYTES.length) } });
    }
    return new Response('not found', { status: 404 });
  };
}

function makeService(overrides = {}) {
  const dir = tempDir();
  const states = [];
  const installed = [];
  const opened = [];
  const service = new UpdateService({
    currentVersion: '0.1.2',
    isPackaged: true,
    downloadDir: dir,
    platform: 'win32',
    arch: 'x64',
    fetchImpl: stubFetch({ releases: [releaseFixture('0.1.3')] }),
    onState: (state) => states.push(state),
    install: async (installerPath) => {
      installed.push(installerPath);
    },
    openFile: async (filePath) => {
      opened.push(filePath);
    },
    ...overrides,
  });
  return { service, states, installed, opened, dir };
}

test('compareVersions orders dotted versions numerically', () => {
  assert.equal(compareVersions('0.1.3', '0.1.2'), 1);
  assert.equal(compareVersions('0.1.10', '0.1.9'), 1);
  assert.equal(compareVersions('0.2', '0.1.9'), 1);
  assert.equal(compareVersions('0.1.2', '0.1.2'), 0);
  assert.equal(compareVersions('0.1.1', '0.1.2'), -1);
});

test('pickLatestDesktopRelease picks the newest desktop-v* release', () => {
  const payload = [
    { tag_name: 'v2.0.36', draft: false, html_url: 'x', assets: [] },
    { tag_name: 'sandbox-v1.0.49', draft: false, html_url: 'x', assets: [] },
    releaseFixture('0.1.1'),
    releaseFixture('0.1.10'),
    releaseFixture('0.1.9', { draft: true }),
    { tag_name: 'desktop-vNext', draft: false, html_url: 'x', assets: [] },
  ];
  const latest = pickLatestDesktopRelease(payload);
  assert.equal(latest.version, '0.1.10');
  assert.equal(latest.url, 'https://github.com/chancetop-com/core-ai/releases/tag/desktop-v0.1.10');
  assert.equal(pickLatestDesktopRelease([]), null);
  assert.equal(pickLatestDesktopRelease('nope'), null);
});

test('parseLatestYml reads the sha512 and size of the artifact', () => {
  const parsed = parseLatestYml(FEED_TEXT);
  assert.equal(parsed.sha512, INSTALLER_SHA);
  assert.equal(parsed.size, INSTALLER_BYTES.length);
  assert.equal(parseLatestYml('version: 0.1.3\n'), null);
});

test('installArgs is the electron-updater NSIS contract', () => {
  assert.deepEqual(installArgs(), ['--updated', '/S', '--force-run']);
});

test('installModeFor maps platform and packaging to the upgrade flow', () => {
  assert.equal(installModeFor('win32', true, true), 'install');
  assert.equal(installModeFor('darwin', true, true), 'download');
  assert.equal(installModeFor('linux', true, true), 'manual');
  assert.equal(installModeFor('win32', false, true), 'manual');
  assert.equal(installModeFor('win32', true, false), 'manual');
});

test('cached installers carry their release version in the name', () => {
  assert.equal(versionedDownloadName('core-ai-desktop-win-x64.exe', '0.1.3'), 'core-ai-desktop-win-x64-0.1.3.exe');
  assert.equal(versionedDownloadName('core-ai-desktop-mac-arm64.dmg', '0.2.10'), 'core-ai-desktop-mac-arm64-0.2.10.dmg');
  assert.equal(versionFromDownloadName('core-ai-desktop-win-x64-0.1.3.exe'), '0.1.3');
  assert.equal(versionFromDownloadName('core-ai-desktop-win-x64-0.1.3.exe.part'), null);
  assert.equal(versionFromDownloadName('core-ai-desktop-win-x64.exe'), null);
  assert.equal(versionFromDownloadName('notes.txt'), null);
});

test('check reports an available update and its installer', async () => {
  const { service } = makeService();
  const state = await service.check();
  assert.equal(state.phase, 'available');
  assert.equal(state.latestVersion, '0.1.3');
  assert.equal(state.assetName, 'core-ai-desktop-win-x64.exe');
  assert.equal(state.installMode, 'install');
  assert.equal(state.releaseUrl, 'https://github.com/chancetop-com/core-ai/releases/tag/desktop-v0.1.3');
});

test('check reports up to date on the newest version', async () => {
  const { service } = makeService({ currentVersion: '0.1.3' });
  const state = await service.check();
  assert.equal(state.phase, 'up-to-date');
});

test('check surfaces network failures as an error state', async () => {
  const { service } = makeService({
    fetchImpl: async () => {
      throw new Error('offline');
    },
  });
  const state = await service.check();
  assert.equal(state.phase, 'error');
  assert.match(state.error, /offline/);
});

test('check retries once on a transient network blip', async () => {
  let attempts = 0;
  const { service } = makeService({
    fetchImpl: async (url) => {
      if (url.startsWith('https://api.github.com/') && attempts++ === 0) throw new TypeError('fetch failed');
      return new Response(JSON.stringify([releaseFixture('0.1.3')]), { status: 200 });
    },
  });
  const state = await service.check();
  assert.equal(state.phase, 'available');
  assert.equal(attempts, 2);
});

test('download verifies the installer and stops before installing', async () => {
  const { service, states, installed, dir } = makeService();
  await service.check();
  const state = await service.download();
  assert.equal(state.phase, 'downloaded');
  assert.equal(installed.length, 0);
  const target = path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe');
  assert.deepEqual(readFileSync(target), INSTALLER_BYTES);
  const progress = states.filter((entry) => entry.phase === 'downloading');
  assert.ok(progress.length > 0);
  assert.equal(progress[progress.length - 1].transferred, INSTALLER_BYTES.length);
});

test('auto-download fetches the release as soon as the check finds it', async () => {
  const { service, installed, dir } = makeService({ autoDownload: true });
  await service.check();
  const state = await service.download();
  assert.equal(state.phase, 'downloaded');
  assert.equal(installed.length, 0);
  assert.ok(existsSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe')));
});

test('without auto-download a check leaves the installer unfetched', async () => {
  const { service, states, dir } = makeService();
  await service.check();
  assert.equal(service.snapshot().phase, 'available');
  assert.ok(!states.some((entry) => entry.phase === 'downloading'));
  assert.ok(!existsSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe')));
});

test('enabling auto-download while an update is listed starts the download', async () => {
  const { service } = makeService();
  await service.check();
  service.setAutoDownload(true);
  const state = await service.download();
  assert.equal(state.phase, 'downloaded');
});

test('install hands the downloaded installer over', async () => {
  const { service, installed, dir } = makeService();
  await service.check();
  await service.download();
  const state = await service.install();
  assert.equal(state.phase, 'installing');
  assert.equal(installed.length, 1);
  assert.equal(installed[0], path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe'));
});

test('install does nothing before the download completes', async () => {
  const { service, installed } = makeService();
  await service.check();
  const state = await service.install();
  assert.equal(state.phase, 'available');
  assert.equal(installed.length, 0);
});

test('download creates the download directory on first use', async () => {
  const nested = path.join(tempDir(), 'updates');
  const { service, installed } = makeService({ downloadDir: nested });
  await service.check();
  const state = await service.download();
  assert.equal(state.phase, 'downloaded');
  assert.equal(installed.length, 0);
  assert.ok(existsSync(path.join(nested, 'core-ai-desktop-win-x64-0.1.3.exe')));
});

test('download reuses an already verified download', async () => {
  const { service, installed, dir } = makeService({
    fetchImpl: stubFetch({ releases: [releaseFixture('0.1.3')], blockDownloads: true }),
  });
  writeFileSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe'), INSTALLER_BYTES);
  await service.check();
  const state = await service.download();
  assert.equal(state.phase, 'downloaded');
  const installedState = await service.install();
  assert.equal(installedState.phase, 'installing');
  assert.equal(installed.length, 1);
});

test('a re-check keeps the already downloaded update ready to install', async () => {
  const { service } = makeService();
  await service.check();
  await service.download();
  const state = await service.check();
  assert.equal(state.phase, 'downloaded');
});

test('install re-verifies the installer and refuses a file changed after download', async () => {
  const { service, installed, dir } = makeService();
  await service.check();
  await service.download();
  writeFileSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe'), Buffer.alloc(INSTALLER_BYTES.length, 9));
  const state = await service.install();
  assert.equal(state.phase, 'error');
  assert.match(state.error, /changed on disk/);
  assert.equal(installed.length, 0);
});

test('a checksum mismatch discards the download and never installs', async () => {
  const wrongSha = createHash('sha512').update('tampered').digest('base64');
  const { service, installed, dir } = makeService({
    fetchImpl: stubFetch({ releases: [releaseFixture('0.1.3')], feed: FEED_TEXT.split(INSTALLER_SHA).join(wrongSha) }),
  });
  await service.check();
  const state = await service.download();
  assert.equal(state.phase, 'error');
  assert.match(state.error, /checksum mismatch/);
  assert.equal(installed.length, 0);
  assert.ok(!existsSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe')));
  assert.ok(!existsSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe.part')));
});

test('macOS downloads the dmg and opens it instead of installing', async () => {
  const { service, opened } = makeService({ platform: 'darwin', arch: 'arm64' });
  const checked = await service.check();
  assert.equal(checked.installMode, 'download');
  assert.equal(checked.assetName, 'core-ai-desktop-mac-arm64.dmg');
  const state = await service.download();
  assert.equal(state.phase, 'ready');
  assert.equal(opened.length, 1);
});

test('a check keeps only the pending release installer', async () => {
  const { service, dir } = makeService();
  writeFileSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.2.exe'), 'older release');
  writeFileSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe'), INSTALLER_BYTES);
  writeFileSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe.part'), 'half a download');
  await service.check();
  assert.ok(existsSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe')));
  assert.ok(!existsSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.2.exe')));
  assert.ok(!existsSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe.part')));
});

test('a check on the newest version clears the installed release installer', async () => {
  const { service, dir } = makeService({ currentVersion: '0.1.3' });
  writeFileSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe'), INSTALLER_BYTES);
  const state = await service.check();
  assert.equal(state.phase, 'up-to-date');
  assert.ok(!existsSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe')));
  assert.ok(!existsSync(dir));
});

test('cleanupAbandoned removes partials and pre-versioning installers only', () => {
  const { service, dir } = makeService();
  writeFileSync(path.join(dir, 'core-ai-desktop-win-x64.exe'), 'left by an older app version');
  writeFileSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.4.exe.part'), 'interrupted download');
  writeFileSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe'), INSTALLER_BYTES);
  const removed = service.cleanupAbandoned();
  assert.equal(removed.length, 2);
  assert.ok(existsSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe')));
});

test('a pending installer survives a restart and is reused without a second download', async () => {
  const dir = tempDir();
  writeFileSync(path.join(dir, 'core-ai-desktop-win-x64-0.1.3.exe'), INSTALLER_BYTES);
  const { service } = makeService({
    downloadDir: dir,
    fetchImpl: stubFetch({ releases: [releaseFixture('0.1.3')], blockDownloads: true }),
  });
  const checked = await service.check();
  assert.equal(checked.phase, 'available');
  const state = await service.download();
  assert.equal(state.phase, 'downloaded');
});
