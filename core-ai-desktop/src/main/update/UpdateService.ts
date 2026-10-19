// Update checks and one-click upgrade for the desktop app (design §4.7).
//
// Desktop releases share the core-ai GitHub repo with the CLI (`v*` tags) and the sandbox runtime
// (`sandbox-v*`), and the repo-wide "latest" release follows whichever stream published last —
// which is why electron-updater's GitHub provider cannot resolve a desktop update here. The check
// filters the release list down to the `desktop-v*` stream itself; the install step then speaks
// electron-updater's exact NSIS contract (`--updated /S --force-run`): silent install, forced
// relaunch of the new version.
import { createHash } from 'node:crypto';
import { createReadStream, createWriteStream, existsSync, mkdirSync, renameSync, rmSync, statSync } from 'node:fs';
import path from 'node:path';
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const RELEASES_API = 'https://api.github.com/repos/chancetop-com/core-ai/releases?per_page=100';
const DESKTOP_TAG_PATTERN = /^desktop-v(\d+(?:\.\d+)*)$/;
const PLATFORM_ASSET_KEY: Partial<Record<NodeJS.Platform, string>> = { win32: 'win', darwin: 'mac', linux: 'linux' };
const PLATFORM_FEED_ASSET: Partial<Record<NodeJS.Platform, string>> = { win32: 'latest.yml', darwin: 'latest-mac.yml' };

export type UpdatePhase = 'idle' | 'checking' | 'up-to-date' | 'available' | 'downloading' | 'downloaded' | 'installing' | 'ready' | 'error';

/** install = silent NSIS install + relaunch (Windows); download = fetch and open the artifact (macOS dmg). */
export type UpdateInstallMode = 'install' | 'download' | 'manual';

export interface UpdateState {
  phase: UpdatePhase;
  currentVersion: string;
  latestVersion: string | null;
  releaseUrl: string | null;
  installMode: UpdateInstallMode;
  assetName: string | null;
  /** Bytes downloaded so far / expected, while phase = downloading. */
  transferred: number;
  total: number;
  error: string | null;
  devMode: boolean;
}

export interface ReleaseAsset {
  name: string;
  url: string;
  size: number;
}

export interface DesktopRelease {
  version: string;
  url: string;
  assets: ReleaseAsset[];
}

export function compareVersions(left: string, right: string): number {
  const a = left.split('.').map(Number);
  const b = right.split('.').map(Number);
  for (let index = 0; index < Math.max(a.length, b.length); index += 1) {
    const diff = (a[index] ?? 0) - (b[index] ?? 0);
    if (diff !== 0) return Math.sign(diff);
  }
  return 0;
}

function normalizeAssets(raw: unknown): ReleaseAsset[] {
  if (!Array.isArray(raw)) return [];
  const assets: ReleaseAsset[] = [];
  for (const entry of raw) {
    if (!entry || typeof entry !== 'object') continue;
    const item = entry as Record<string, unknown>;
    if (typeof item.name !== 'string' || typeof item.browser_download_url !== 'string') continue;
    assets.push({ name: item.name, url: item.browser_download_url, size: typeof item.size === 'number' ? item.size : 0 });
  }
  return assets;
}

/**
 * Picks the newest `desktop-v*` release from a GitHub releases payload. The prerelease flag is
 * deliberately ignored: desktop releases stay marked prerelease until signing/notarization ships.
 */
export function pickLatestDesktopRelease(payload: unknown): DesktopRelease | null {
  if (!Array.isArray(payload)) return null;
  let best: DesktopRelease | null = null;
  for (const entry of payload) {
    if (!entry || typeof entry !== 'object') continue;
    const release = entry as Record<string, unknown>;
    if (release.draft === true) continue;
    const match = typeof release.tag_name === 'string' ? DESKTOP_TAG_PATTERN.exec(release.tag_name) : null;
    if (!match) continue;
    if (best && compareVersions(match[1], best.version) <= 0) continue;
    best = {
      version: match[1],
      url: typeof release.html_url === 'string' ? release.html_url : '',
      assets: normalizeAssets(release.assets),
    };
  }
  return best;
}

/** The installer for this platform, preferring the exact arch (e.g. core-ai-desktop-win-x64.exe). */
export function pickReleaseAsset(release: DesktopRelease, platform: NodeJS.Platform, arch: string): ReleaseAsset | null {
  const key = PLATFORM_ASSET_KEY[platform];
  if (!key) return null;
  const prefix = `core-ai-desktop-${key}-`;
  return release.assets.find((asset) => asset.name.startsWith(`${prefix}${arch}.`))
    ?? release.assets.find((asset) => asset.name.startsWith(prefix))
    ?? null;
}

/** electron-builder's latest.yml carries the artifact checksum (base64 sha512) and size. */
export function parseLatestYml(text: string): { sha512: string; size: number } | null {
  const sha = /(?:^|\n)\s*sha512:\s*([A-Za-z0-9+/=]+)/.exec(text);
  if (!sha) return null;
  const size = /(?:^|\n)\s*size:\s*(\d+)/.exec(text);
  return { sha512: sha[1], size: size ? Number(size[1]) : 0 };
}

/** The exact arguments electron-updater passes to an NSIS update (NsisUpdater.doInstall). */
export function installArgs(): string[] {
  return ['--updated', '/S', '--force-run'];
}

export function installModeFor(platform: NodeJS.Platform, packaged: boolean, hasAsset: boolean): UpdateInstallMode {
  if (!packaged || !hasAsset) return 'manual';
  if (platform === 'win32') return 'install';
  if (platform === 'darwin') return 'download';
  return 'manual';
}

export interface UpdateServiceOptions {
  currentVersion: string;
  isPackaged: boolean;
  /** Where installer downloads are cached between attempts. */
  downloadDir: string;
  platform?: NodeJS.Platform;
  arch?: string;
  fetchImpl?: typeof fetch;
  onState?: (state: UpdateState) => void;
  /** Windows: run the verified installer (main shuts down engines, spawns it and exits). */
  install?: (installerPath: string) => Promise<void>;
  /** macOS: open the downloaded dmg (mounts it; the user finishes the drag). */
  openFile?: (filePath: string) => Promise<void> | void;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function httpMessage(status: number): string {
  if (status === 403) return 'GitHub rate limit reached (HTTP 403) — try again later';
  if (status === 404) return 'GitHub release asset not found (HTTP 404)';
  return `HTTP ${status}`;
}

async function fileSha512(file: string): Promise<string> {
  const hash = createHash('sha512');
  for await (const chunk of createReadStream(file)) hash.update(chunk as Buffer);
  return hash.digest('base64');
}

export class UpdateService {
  private readonly options: UpdateServiceOptions;
  private state: UpdateState;
  private release: DesktopRelease | null = null;
  private checkInFlight: Promise<UpdateState> | null = null;
  private downloadInFlight: Promise<UpdateState> | null = null;
  private installInFlight: Promise<UpdateState> | null = null;
  private downloaded: { version: string; path: string; sha512: string; size: number } | null = null;
  private lastProgressAt = 0;

  constructor(options: UpdateServiceOptions) {
    this.options = options;
    this.state = {
      phase: 'idle',
      currentVersion: options.currentVersion,
      latestVersion: null,
      releaseUrl: null,
      installMode: 'manual',
      assetName: null,
      transferred: 0,
      total: 0,
      error: null,
      devMode: !options.isPackaged,
    };
  }

  snapshot(): UpdateState {
    return { ...this.state };
  }

  /** One in-flight check at a time: the About panel may ask again while a check is running. */
  check(): Promise<UpdateState> {
    this.checkInFlight ??= this.runCheck().finally(() => {
      this.checkInFlight = null;
    });
    return this.checkInFlight;
  }

  /** Fetches and verifies the installer, then stops at `downloaded` — installing is the user's call. */
  download(): Promise<UpdateState> {
    this.downloadInFlight ??= this.runDownload().finally(() => {
      this.downloadInFlight = null;
    });
    return this.downloadInFlight;
  }

  /** Runs the already downloaded installer; the app hands over and exits. */
  install(): Promise<UpdateState> {
    this.installInFlight ??= this.runInstall().finally(() => {
      this.installInFlight = null;
    });
    return this.installInFlight;
  }

  private platform(): NodeJS.Platform {
    return this.options.platform ?? process.platform;
  }

  private arch(): string {
    return this.options.arch ?? process.arch;
  }

  private setState(patch: Partial<UpdateState>): void {
    this.state = { ...this.state, ...patch };
    this.options.onState?.(this.snapshot());
  }

  private request(url: string): Promise<Response> {
    const impl = this.options.fetchImpl ?? fetch;
    return impl(url, {
      headers: { accept: 'application/vnd.github+json', 'user-agent': `core-ai-desktop/${this.options.currentVersion}` },
    });
  }

  private async fetchJson(url: string): Promise<unknown> {
    const response = await this.request(url);
    if (!response.ok) throw new Error(httpMessage(response.status));
    return response.json();
  }

  private async fetchText(url: string): Promise<string> {
    const response = await this.request(url);
    if (!response.ok) throw new Error(httpMessage(response.status));
    return response.text();
  }

  private async runCheck(): Promise<UpdateState> {
    const wasDownloaded = this.state.phase === 'downloaded';
    this.setState({ phase: 'checking', error: null });
    try {
      const release = pickLatestDesktopRelease(await this.fetchReleases());
      this.release = release;
      if (!release) {
        this.setState({ phase: 'error', latestVersion: null, error: 'No desktop release found on GitHub' });
        return this.snapshot();
      }
      const asset = pickReleaseAsset(release, this.platform(), this.arch());
      const hasUpdate = compareVersions(release.version, this.state.currentVersion) > 0;
      if (!hasUpdate) this.downloaded = null;
      // A re-check must not hide an installer the user already downloaded: the update is still
      // pending, so keep the ready-to-install state for the same release.
      const keepDownloaded = hasUpdate && wasDownloaded && this.downloaded?.version === release.version;
      this.setState({
        phase: !hasUpdate ? 'up-to-date' : keepDownloaded ? 'downloaded' : 'available',
        latestVersion: release.version,
        releaseUrl: release.url,
        assetName: asset?.name ?? null,
        installMode: installModeFor(this.platform(), this.options.isPackaged, Boolean(asset)),
      });
    } catch (error) {
      // A failed re-check keeps the downloaded installer usable.
      if (wasDownloaded) {
        this.setState({ phase: 'downloaded', error: null });
      } else {
        this.setState({ phase: 'error', error: `Update check failed: ${errorMessage(error)}` });
      }
    }
    return this.snapshot();
  }

  /** One retry on transient network blips (TypeError: fetch failed); HTTP errors rethrow as-is. */
  private async fetchReleases(): Promise<unknown> {
    try {
      return await this.fetchJson(RELEASES_API);
    } catch (error) {
      if (!(error instanceof TypeError)) throw error;
      return this.fetchJson(RELEASES_API);
    }
  }

  private async runDownload(): Promise<UpdateState> {
    const release = this.release;
    const asset = release ? pickReleaseAsset(release, this.platform(), this.arch()) : null;
    if ((this.state.phase !== 'available' && this.state.phase !== 'downloaded')
      || !release || !asset || this.state.installMode === 'manual') {
      return this.snapshot();
    }
    if (this.state.phase === 'downloaded' && this.downloaded?.version === release.version) return this.snapshot();
    try {
      this.setState({ phase: 'downloading', transferred: 0, total: asset.size, error: null });
      const target = path.join(this.options.downloadDir, asset.name);
      const checksum = await this.downloadAndVerify(release, asset, target);
      if (this.state.installMode === 'install') {
        this.downloaded = {
          version: release.version,
          path: target,
          sha512: checksum.sha512,
          size: checksum.size,
        };
        this.setState({ phase: 'downloaded' });
      } else {
        await this.options.openFile?.(target);
        this.setState({ phase: 'ready' });
      }
    } catch (error) {
      this.setState({ phase: 'error', error: `Update failed: ${errorMessage(error)}` });
    }
    return this.snapshot();
  }

  private async runInstall(): Promise<UpdateState> {
    const downloaded = this.downloaded;
    if (this.state.phase !== 'downloaded' || !downloaded) return this.snapshot();
    try {
      // The installer waited on disk for the user's decision — re-check it before handing over.
      const intact = existsSync(downloaded.path)
        && statSync(downloaded.path).size === downloaded.size
        && await fileSha512(downloaded.path) === downloaded.sha512;
      if (!intact) {
        this.downloaded = null;
        this.setState({ phase: 'error', error: 'The downloaded installer changed on disk — download the update again' });
        return this.snapshot();
      }
      this.setState({ phase: 'installing' });
      await this.options.install?.(downloaded.path);
    } catch (error) {
      this.setState({ phase: 'error', error: `Update failed: ${errorMessage(error)}` });
    }
    return this.snapshot();
  }

  private async downloadAndVerify(release: DesktopRelease, asset: ReleaseAsset, target: string): Promise<{ sha512: string; size: number }> {
    const feedName = PLATFORM_FEED_ASSET[this.platform()];
    const feed = release.assets.find((entry) => entry.name === feedName);
    if (!feed) throw new Error(`release ${release.version} has no ${feedName} checksum file`);
    const checksum = parseLatestYml(await this.fetchText(feed.url));
    if (!checksum) throw new Error(`could not parse ${feedName}`);
    const size = checksum.size || asset.size;

    if (existsSync(target) && statSync(target).size === size && await fileSha512(target) === checksum.sha512) {
      return { sha512: checksum.sha512, size };
    }
    await this.fetchAsset(asset, target);
    if (await fileSha512(target) !== checksum.sha512) {
      rmSync(target, { force: true });
      throw new Error('checksum mismatch — the downloaded installer was discarded');
    }
    return { sha512: checksum.sha512, size };
  }

  private async fetchAsset(asset: ReleaseAsset, target: string): Promise<void> {
    const part = `${target}.part`;
    const response = await this.request(asset.url);
    if (!response.ok || !response.body) throw new Error(`installer download failed (${httpMessage(response.status)})`);
    const total = Number(response.headers.get('content-length')) || asset.size;
    let transferred = 0;
    const counter = new Transform({
      transform: (chunk: Buffer, _encoding, callback) => {
        transferred += chunk.length;
        this.reportProgress(transferred, total);
        callback(null, chunk);
      },
    });
    mkdirSync(path.dirname(target), { recursive: true });
    try {
      await pipeline(Readable.fromWeb(response.body as Parameters<typeof Readable.fromWeb>[0]), counter, createWriteStream(part));
    } catch (error) {
      rmSync(part, { force: true });
      throw error;
    }
    rmSync(target, { force: true });
    renameSync(part, target);
  }

  private reportProgress(transferred: number, total: number): void {
    const now = Date.now();
    if (now - this.lastProgressAt < 120 && transferred < total) return;
    this.lastProgressAt = now;
    this.setState({ transferred, total });
  }
}
