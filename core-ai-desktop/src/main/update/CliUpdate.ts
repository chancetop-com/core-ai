// Keeps standalone core-ai-cli installs in step with the engine bundled in this app.
//
// A desktop update replaces resources/engine with the CLI release pinned by the new desktop
// version, but a CLI installed separately (`core-ai-cli --upgrade` writes into ~/.core-ai/bin)
// keeps its old binary. On the first start after such an update the app copies the bundled
// engine over every standalone copy a terminal would resolve — never downgrading a copy that
// is already newer, and only touching the CLI's own install locations.
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmodSync, copyFileSync, createReadStream, existsSync, readFileSync, renameSync, rmSync } from 'node:fs';
import path from 'node:path';
import { compareVersions } from './UpdateService';

const VERSION_TIMEOUT_MS = 10_000;

export interface EngineManifest {
  version: string;
  /** Recorded when the engine was staged (scripts/stage-engine.mjs); null for dev builds. */
  sha256: string | null;
}

export type CliSyncOutcome =
  | { status: 'updated'; path: string; from: string; to: string }
  | { status: 'current'; path: string; version: string }
  | { status: 'skipped'; path: string; reason: string }
  | { status: 'failed'; path: string; from: string; reason: string };

export function cliBinaryName(platform: NodeJS.Platform = process.platform): string {
  return platform === 'win32' ? 'core-ai-cli.exe' : 'core-ai-cli';
}

/** `core-ai-cli --version` prints the bare version; tolerate a banner or a leading `v`. */
export function parseCliVersion(output: string): string | null {
  const match = /(?:^|\s)v?(\d+(?:\.\d+)+)(?=\s|$)/.exec(output.trim());
  return match ? match[1] : null;
}

/** The engine manifest written next to the bundled binary ({version, sha256}). */
export function readEngineManifest(engineBinary: string): EngineManifest | null {
  try {
    const raw = JSON.parse(readFileSync(path.join(path.dirname(engineBinary), 'engine.json'), 'utf8')) as Record<string, unknown>;
    if (typeof raw?.version !== 'string' || !raw.version.trim()) return null;
    return {
      version: raw.version.trim(),
      sha256: typeof raw.sha256 === 'string' && raw.sha256.trim() ? raw.sha256.trim().toLowerCase() : null,
    };
  } catch {
    return null;
  }
}

export interface CliTargetOptions {
  homeDir: string;
  /** The PATH the user's terminal inherits (not the engine's augmented PATH). */
  searchPath: string;
  platform?: NodeJS.Platform;
  /** Directories that belong to this app (its bundled engine) and must never be rewritten. */
  excludeDirs?: string[];
}

/**
 * The standalone CLI binaries to keep in step: the canonical `~/.core-ai/bin` install plus any
 * copy the user's PATH resolves, minus this app's own bundled engine.
 */
export function standaloneCliTargets(options: CliTargetOptions): string[] {
  const platform = options.platform ?? process.platform;
  const name = cliBinaryName(platform);
  const excluded = new Set((options.excludeDirs ?? []).map((dir) => normalizeDir(dir, platform)));
  const dirs = [path.join(options.homeDir, '.core-ai', 'bin')];
  for (const entry of options.searchPath.split(path.delimiter)) {
    const dir = entry.trim().replace(/^"(.*)"$/, '$1');
    if (dir) dirs.push(dir);
  }
  const seen = new Set<string>();
  const targets: string[] = [];
  for (const dir of dirs) {
    if (excluded.has(normalizeDir(dir, platform)) || isBundledEngineDir(dir)) continue;
    const candidate = path.join(dir, name);
    const key = normalizeDir(candidate, platform);
    if (seen.has(key) || !existsSync(candidate)) continue;
    seen.add(key);
    targets.push(candidate);
  }
  return targets;
}

/** `<app>/resources/engine` belongs to a desktop package, never to a standalone CLI install. */
function isBundledEngineDir(dir: string): boolean {
  return /\/resources\/engine$/i.test(dir.replace(/\\/g, '/').replace(/\/+$/, ''));
}

function normalizeDir(dir: string, platform: NodeJS.Platform): string {
  const resolved = path.resolve(dir).replace(/[\\/]+$/, '');
  return platform === 'win32' ? resolved.toLowerCase() : resolved;
}

export function readCliVersion(binary: string): Promise<string | null> {
  return new Promise((resolve) => {
    execFile(binary, ['--version'], { timeout: VERSION_TIMEOUT_MS, windowsHide: true }, (error, stdout, stderr) => {
      if (error) {
        resolve(null);
        return;
      }
      resolve(parseCliVersion(String(stdout)) ?? parseCliVersion(String(stderr)));
    });
  });
}

async function sha256File(file: string): Promise<string> {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(file)) hash.update(chunk as Buffer);
  return hash.digest('hex');
}

/**
 * Stage next to the target and rename over it, so a failed copy never leaves a half-written
 * binary in place. Windows refuses the rename while the CLI is running; the caller retries on
 * the next launch.
 */
function replaceBinary(source: string, target: string): void {
  const staged = `${target}.new`;
  copyFileSync(source, staged);
  if (process.platform !== 'win32') chmodSync(staged, 0o755);
  try {
    renameSync(staged, target);
  } catch (error) {
    rmSync(staged, { force: true });
    throw error;
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export interface CliSyncOptions {
  bundledBinary: string;
  bundledVersion: string;
  bundledSha256?: string | null;
  targets: string[];
  readVersion?: (binary: string) => Promise<string | null>;
  hashFile?: (file: string) => Promise<string>;
}

export async function syncStandaloneClis(options: CliSyncOptions): Promise<CliSyncOutcome[]> {
  const targets = [...new Set(options.targets)];
  if (!targets.length) return [];
  if (!existsSync(options.bundledBinary)) {
    return targets.map((target) => ({ status: 'skipped' as const, path: target, reason: 'the bundled engine is missing' }));
  }
  const hashFile = options.hashFile ?? sha256File;
  if (options.bundledSha256 && await hashFile(options.bundledBinary) !== options.bundledSha256) {
    return targets.map((target) => ({ status: 'skipped' as const, path: target, reason: 'the bundled engine failed its checksum' }));
  }
  const readVersion = options.readVersion ?? readCliVersion;
  const outcomes: CliSyncOutcome[] = [];
  for (const target of targets) {
    const current = await readVersion(target);
    if (!current) {
      outcomes.push({ status: 'skipped', path: target, reason: 'its version could not be read' });
      continue;
    }
    if (compareVersions(current, options.bundledVersion) >= 0) {
      outcomes.push({ status: 'current', path: target, version: current });
      continue;
    }
    try {
      replaceBinary(options.bundledBinary, target);
      outcomes.push({ status: 'updated', path: target, from: current, to: options.bundledVersion });
    } catch (error) {
      outcomes.push({ status: 'failed', path: target, from: current, reason: errorMessage(error) });
    }
  }
  return outcomes;
}
