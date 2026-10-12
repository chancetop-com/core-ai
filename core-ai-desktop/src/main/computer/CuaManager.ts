// CUA CLI integration behind Settings -> Computer use (design §4.10, CLI route instead of the
// cua-driver MCP plan): detect the cua CLI, drive the one-time host enablement (desktop consent ->
// local cua-spacesd service -> `host` target -> access probe) and install the vendored
// gui-automation skill. The app itself never drives the desktop: agents do, through the cua CLI,
// once engineEnv() advertises the ready state to the engine (CORE_AI_COMPUTER_USE).
import { execFile } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describeManagedSkill, installManagedSkill, type ManagedSkillState } from './managedSkill';

/** Pinned upstream revision of the vendored skill (desktop/skills/gui-automation/SOURCE.md). */
export const SKILL_SOURCE = 'trycua/cua@0f921000ba7721ffc7be8b563dbb96ef8b3d543c';
const HOST_SERVICE_URL = '127.0.0.1:3211';
const HOST_SERVICE_PORT = 3211;

const CLI_INSTALL_TIMEOUT = 10 * 60 * 1000;
const HOST_SETUP_TIMEOUT = 5 * 60 * 1000;
const SHORT_TIMEOUT = 60 * 1000;

export interface CuaStep {
  step: string;
  ok: boolean;
  detail: string;
}

export interface HostInfo {
  configured: boolean;
  installed: boolean;
  running: boolean;
  mode: string | null;
  directUrl: string | null;
  envTokenPath: string | null;
}

export interface ComputerStatus {
  enabled: boolean;
  ready: boolean;
  problems: string[];
  cli: { present: boolean; path: string | null; version: string | null };
  consent: boolean;
  host: HostInfo;
  target: { host: boolean };
  token: boolean;
  skill: ManagedSkillState & { targetDir: string; sourceDir: string };
}

export interface CuaManagerOptions {
  /** Vendored skill directory shipped with the app (dev: <repo>/skills/gui-automation). */
  skillSourceDir: string;
  desktopVersion: string;
  /** Overridable for tests; defaults to the real user home. */
  homeDir?: string;
}

interface CommandResult {
  code: number;
  stdout: string;
  stderr: string;
}

function runCommand(file: string, args: string[], timeoutMs: number): Promise<CommandResult> {
  return new Promise((resolve) => {
    execFile(file, args, { timeout: timeoutMs, windowsHide: true, maxBuffer: 8 * 1024 * 1024 }, (error, stdout, stderr) => {
      const code = error === null ? 0 : typeof (error as { code?: unknown }).code === 'number' ? (error as { code: number }).code : 1;
      resolve({ code, stdout: String(stdout ?? ''), stderr: String(stderr ?? '') });
    });
  });
}

/** Last non-empty output line, for compact step details. */
function lastLine(...texts: Array<string | undefined>): string {
  for (const text of texts) {
    const lines = (text ?? '').split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
    if (lines.length > 0) return lines[lines.length - 1];
  }
  return '';
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export class CuaManager {
  constructor(private readonly options: CuaManagerOptions) {}

  private get home(): string {
    return this.options.homeDir ?? os.homedir();
  }

  private get cuaDir(): string {
    return path.join(this.home, '.cua');
  }

  private get skillDir(): string {
    return path.join(this.home, '.core-ai', 'skills', 'gui-automation');
  }

  /** The `cua` binary: explicit override, the installer's default locations, then PATH. */
  resolveBin(): string | null {
    const override = process.env.CORE_AI_CUA_BIN;
    if (override) return existsSync(override) ? override : null;
    const exe = process.platform === 'win32' ? 'cua.exe' : 'cua';
    const candidates = process.platform === 'win32'
      ? [path.join(process.env.LOCALAPPDATA ?? path.join(this.home, 'AppData', 'Local'), 'Programs', 'cua', 'bin', exe)]
      : [path.join(this.home, '.local', 'bin', exe), `/usr/local/bin/${exe}`, `/opt/homebrew/bin/${exe}`];
    for (const candidate of candidates) {
      if (existsSync(candidate)) return candidate;
    }
    for (const dir of (process.env.PATH ?? '').split(path.delimiter)) {
      if (dir && existsSync(path.join(dir, exe))) return path.join(dir, exe);
    }
    return null;
  }

  private hostTargetSelected(): boolean {
    try {
      const data = JSON.parse(readFileSync(path.join(this.cuaDir, 'do_target.json'), 'utf8')) as { provider?: unknown };
      return data?.provider === 'host';
    } catch {
      return false;
    }
  }

  private encryptedTokenStored(): boolean {
    try {
      const data = JSON.parse(readFileSync(path.join(this.cuaDir, 'env-tokens.json'), 'utf8')) as { host?: unknown };
      return typeof data?.host === 'string' && data.host.length > 0;
    } catch {
      return false;
    }
  }

  private tokenFileExists(): boolean {
    return existsSync(path.join(this.cuaDir, 'host', 'env-token'));
  }

  /** Everything `cua do` needs to work in this session, from cheap file checks (no subprocess). */
  private syncProblems(): string[] {
    const problems: string[] = [];
    if (!this.resolveBin()) problems.push('the cua CLI is not installed');
    if (!existsSync(path.join(this.cuaDir, 'host_consented'))) problems.push('desktop consent is not granted');
    if (!this.hostTargetSelected()) problems.push('the host target is not selected');
    if (this.tokenFileExists() && !this.encryptedTokenStored()) problems.push('the host access token is not stored');
    if (!existsSync(path.join(this.skillDir, 'SKILL.md'))) problems.push('the gui-automation skill is not installed');
    return problems;
  }

  /**
   * Environment for engine spawns. The flag drives the CLI's computer-use prompt section; the cua
   * bin directory goes on PATH so the agent's shell can run `cua do` even when the app inherited a
   * stale user PATH. Empty until computer use is enabled and every ready check passes.
   */
  engineEnv(enabled: boolean): Record<string, string> {
    if (!enabled) return {};
    const bin = this.resolveBin();
    if (!bin || this.syncProblems().length > 0) return {};
    const env: Record<string, string> = { CORE_AI_COMPUTER_USE: '1' };
    const pathKey = Object.keys(process.env).find((key) => key.toUpperCase() === 'PATH');
    if (pathKey) {
      const dir = path.dirname(bin);
      const separator = process.platform === 'win32' ? ';' : ':';
      const current = process.env[pathKey] ?? '';
      if (!current.split(separator).includes(dir)) {
        env[pathKey] = current ? `${dir}${separator}${current}` : dir;
      }
    }
    return env;
  }

  private async cliVersion(bin: string): Promise<string | null> {
    const result = await runCommand(bin, ['--version'], SHORT_TIMEOUT);
    const match = /(\d+\.\d+\.\d+[\w.+-]*)/.exec(result.stdout);
    return match ? match[1] : null;
  }

  private async hostInfo(bin: string): Promise<HostInfo> {
    const result = await runCommand(bin, ['host', 'status', '--json'], SHORT_TIMEOUT);
    try {
      const data = JSON.parse(result.stdout.trim()) as {
        configured?: unknown;
        mode?: unknown;
        directUrl?: unknown;
        envTokenPath?: unknown;
        service?: { installed?: unknown; running?: unknown };
      };
      return {
        configured: data.configured === true,
        installed: data.service?.installed === true,
        running: data.service?.running === true,
        mode: typeof data.mode === 'string' ? data.mode : null,
        directUrl: typeof data.directUrl === 'string' ? data.directUrl : null,
        envTokenPath: typeof data.envTokenPath === 'string' ? data.envTokenPath : null,
      };
    } catch {
      return { configured: false, installed: false, running: false, mode: null, directUrl: null, envTokenPath: null };
    }
  }

  async status(enabled: boolean): Promise<ComputerStatus> {
    const bin = this.resolveBin();
    const problems = this.syncProblems();
    return {
      enabled,
      ready: problems.length === 0,
      problems,
      cli: { present: Boolean(bin), path: bin, version: bin ? await this.cliVersion(bin) : null },
      consent: existsSync(path.join(this.cuaDir, 'host_consented')),
      host: bin ? await this.hostInfo(bin) : { configured: false, installed: false, running: false, mode: null, directUrl: null, envTokenPath: null },
      target: { host: this.hostTargetSelected() },
      token: this.encryptedTokenStored(),
      skill: { ...describeManagedSkill(this.options.skillSourceDir, this.skillDir), targetDir: this.skillDir, sourceDir: this.options.skillSourceDir },
    };
  }

  /** Runs the official installer, CLI only (no sandbox runtimes, no sign-in onboarding). */
  async installCli(): Promise<{ ok: boolean; detail: string }> {
    if (process.platform !== 'win32') {
      return { ok: false, detail: 'install the cua CLI first: curl -fsSL https://cua.ai/install.sh | sh' };
    }
    const script = '& ([scriptblock]::Create((irm https://cua.ai/install.ps1))) -CliOnly -Yes -ModifyPath -NoOnboarding';
    const result = await runCommand('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', script], CLI_INSTALL_TIMEOUT);
    const detail = lastLine(result.stdout) || lastLine(result.stderr) || `installer exited with ${result.code}`;
    return { ok: result.code === 0, detail };
  }

  /**
   * Idempotent enablement: every step checks first and only acts when something is missing, so it
   * doubles as a repair action. Step details feed the Settings panel.
   */
  async enable(): Promise<{ ok: boolean; steps: CuaStep[] }> {
    const steps: CuaStep[] = [];
    let bin = this.resolveBin();
    if (!bin) {
      const installed = await this.installCli();
      steps.push({ step: 'Install the cua CLI', ok: installed.ok, detail: installed.detail });
      bin = this.resolveBin();
      if (!bin) return { ok: false, steps };
    } else {
      steps.push({ step: 'cua CLI', ok: true, detail: bin });
    }

    if (existsSync(path.join(this.cuaDir, 'host_consented'))) {
      steps.push({ step: 'Desktop consent', ok: true, detail: 'already granted' });
    } else {
      const consent = await runCommand(bin, ['do-host-consent'], SHORT_TIMEOUT);
      steps.push({ step: 'Grant desktop consent', ok: consent.code === 0, detail: lastLine(consent.stdout, consent.stderr) || `exit ${consent.code}` });
    }

    let host = await this.hostInfo(bin);
    if (!host.configured) {
      const setup = await runCommand(bin, ['host', 'setup', '--direct', HOST_SERVICE_URL, '--name', os.hostname() || 'core-ai-desktop'], HOST_SETUP_TIMEOUT);
      host = await this.waitForService(bin, 3);
      steps.push({
        step: 'Install the desktop service',
        ok: setup.code === 0 && host.running,
        detail: host.running ? `cua-spacesd service running (${HOST_SERVICE_URL})` : lastLine(setup.stdout, setup.stderr) || `exit ${setup.code}`,
      });
    } else if (!host.running) {
      const start = await runCommand(bin, ['host', 'start'], SHORT_TIMEOUT);
      host = await this.waitForService(bin, 3);
      steps.push({ step: 'Start the desktop service', ok: host.running, detail: lastLine(start.stdout, start.stderr) || `exit ${start.code}` });
    } else {
      steps.push({ step: 'Desktop service', ok: true, detail: `already running (${host.mode ?? 'unknown'} mode, port ${HOST_SERVICE_PORT})` });
    }

    const token = this.readHostToken(host);
    const switched = await runCommand(bin, ['do', 'switch', 'host', ...(token ? ['--token', token] : [])], SHORT_TIMEOUT);
    steps.push({ step: 'Select the host target', ok: switched.code === 0, detail: lastLine(switched.stdout, switched.stderr) || `exit ${switched.code}` });

    if (switched.code === 0) {
      const probe = await this.runProbe(bin);
      steps.push({ step: 'Verify desktop access', ok: probe.code === 0, detail: lastLine(probe.stdout, probe.stderr) || `exit ${probe.code}` });
    }

    const skillInstall = installManagedSkill(this.options.skillSourceDir, this.skillDir, {
      skill: 'gui-automation',
      source: SKILL_SOURCE,
      desktopVersion: this.options.desktopVersion,
    });
    const skill = describeManagedSkill(this.options.skillSourceDir, this.skillDir);
    steps.push({ step: 'Install the gui-automation skill', ok: skill.installed, detail: skillInstall.detail });

    return { ok: steps.every((step) => step.ok), steps };
  }

  async removeService(): Promise<{ ok: boolean; detail: string }> {
    const bin = this.resolveBin();
    if (!bin) return { ok: false, detail: 'the cua CLI is not installed' };
    const result = await runCommand(bin, ['host', 'remove', '--force'], SHORT_TIMEOUT * 2);
    return { ok: result.code === 0, detail: lastLine(result.stdout, result.stderr) || `exit ${result.code}` };
  }

  private readHostToken(host: HostInfo): string | null {
    const tokenPath = host.envTokenPath ?? path.join(this.cuaDir, 'host', 'env-token');
    try {
      const token = readFileSync(tokenPath, 'utf8').trim();
      return token || null;
    } catch {
      return null;
    }
  }

  private async waitForService(bin: string, tries: number): Promise<HostInfo> {
    let host = await this.hostInfo(bin);
    for (let attempt = 1; attempt < tries && !host.running; attempt++) {
      await sleep(2000);
      host = await this.hostInfo(bin);
    }
    return host;
  }

  /** `cua do cursor` — cheap round trip proving the loopback service answers for the host target. */
  private async runProbe(bin: string): Promise<CommandResult> {
    let result = await runCommand(bin, ['do', 'cursor'], SHORT_TIMEOUT);
    for (let attempt = 0; attempt < 2 && result.code !== 0; attempt++) {
      await sleep(2000);
      result = await runCommand(bin, ['do', 'cursor'], SHORT_TIMEOUT);
    }
    return result;
  }
}
