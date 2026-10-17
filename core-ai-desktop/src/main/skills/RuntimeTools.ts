// Runtime probes and installers for the built-in skills. Skills are plain files; the external
// executables they drive are optional and owned by the user's machine. Installers run only after an
// explicit UI confirmation and stay on the official package channels.
import { execFile } from 'node:child_process';
import { statSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export interface RuntimeStatus {
  present: boolean;
  version: string | null;
  path: string | null;
  detail: string;
}

export interface InstallOutcome {
  ok: boolean;
  detail: string;
}

const SHORT_TIMEOUT = 20_000;
const DAEMON_TIMEOUT = 5_000;
const INSTALL_TIMEOUT = 10 * 60 * 1000;

interface CommandResult {
  code: number;
  stdout: string;
  stderr: string;
}

function quoteShellArg(value: string): string {
  return /[\s"]/u.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

function runCommand(file: string, args: string[], timeoutMs: number): Promise<CommandResult> {
  // CreateProcess cannot launch .cmd/.bat shims directly; npm and many global CLIs are exactly that.
  // cmd /s /c strips the first and last quote, so the whole command line is wrapped once more.
  const useShell = process.platform === 'win32' && /\.(cmd|bat)$/i.test(file);
  const command = useShell ? (process.env.ComSpec ?? 'cmd.exe') : file;
  const commandArgs = useShell
    ? ['/d', '/s', '/c', `""${file}" ${args.map(quoteShellArg).join(' ')}"`]
    : args;
  return new Promise((resolve) => {
    execFile(command, commandArgs, {
      timeout: timeoutMs,
      windowsHide: true,
      windowsVerbatimArguments: useShell,
      maxBuffer: 8 * 1024 * 1024,
    }, (error, stdout, stderr) => {
      const code = error === null ? 0 : typeof (error as { code?: unknown }).code === 'number' ? (error as { code: number }).code : 1;
      resolve({ code, stdout: String(stdout ?? ''), stderr: String(stderr ?? '') });
    });
  });
}

function lastLine(...texts: Array<string | undefined>): string {
  for (const text of texts) {
    const lines = (text ?? '').split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
    if (lines.length > 0) return lines[lines.length - 1];
  }
  return '';
}

function versionOf(output: string): string | null {
  const match = /(\d+\.\d+\.\d+[\w.+-]*)/.exec(output);
  return match ? match[1] : null;
}

function executableExtensions(): string[] {
  if (process.platform !== 'win32') return [''];
  const raw = process.env.PATHEXT ?? '.COM;.EXE;.BAT;.CMD';
  return raw.split(';').map((extension) => extension.trim().toLowerCase()).filter(Boolean);
}

function candidateNames(name: string): string[] {
  if (process.platform !== 'win32') return [name];
  const extensions = executableExtensions();
  const preferred = ['.exe', '.com', '.cmd', '.bat'];
  const rest = extensions.filter((extension) => !preferred.includes(extension));
  // Never prefer the extensionless sh shim npm writes next to its .cmd wrapper.
  return [...preferred, ...rest].map((extension) => `${name}${extension}`);
}

/** PATH plus the install locations uv/pipx/npm use but the desktop process may not have inherited. */
function searchDirs(): string[] {
  const home = os.homedir();
  const dirs = (process.env.PATH ?? '').split(path.delimiter).filter(Boolean);
  const extra = process.platform === 'win32'
    ? [
        path.join(home, '.local', 'bin'),
        path.join(process.env.APPDATA ?? path.join(home, 'AppData', 'Roaming'), 'npm'),
        path.join(process.env.LOCALAPPDATA ?? path.join(home, 'AppData', 'Local'), 'Programs', 'cua', 'bin'),
      ]
    : [path.join(home, '.local', 'bin'), '/usr/local/bin', '/opt/homebrew/bin'];
  for (const dir of extra) if (!dirs.includes(dir)) dirs.push(dir);
  return dirs;
}

function isFile(file: string): boolean {
  try {
    return statSync(file).isFile();
  } catch {
    return false;
  }
}

export function resolveExecutable(name: string): string | null {
  if (path.isAbsolute(name)) return isFile(name) ? name : null;
  for (const dir of searchDirs()) {
    for (const candidate of candidateNames(name)) {
      const file = path.join(dir, candidate);
      if (isFile(file)) return file;
    }
  }
  return null;
}

export async function probeCommand(name: string, args: string[] = ['--version']): Promise<RuntimeStatus> {
  const file = resolveExecutable(name);
  if (!file) return { present: false, version: null, path: null, detail: `${name} is not installed` };
  const result = await runCommand(file, args, SHORT_TIMEOUT);
  if (result.code !== 0) {
    return { present: true, version: null, path: file, detail: lastLine(result.stderr, result.stdout) || `exited with ${result.code}` };
  }
  return { present: true, version: versionOf(result.stdout), path: file, detail: file };
}

export function browserUseStatus(): Promise<RuntimeStatus> {
  return probeCommand('browser-use');
}

export async function ocgStatus(): Promise<RuntimeStatus & { daemon: string | null }> {
  const status = await probeCommand('ocg');
  if (!status.present || !status.path) return { ...status, daemon: null };
  const result = await runCommand(status.path, ['daemon', 'status', '--json'], DAEMON_TIMEOUT);
  let daemon: string | null = null;
  try {
    const data = JSON.parse(result.stdout.trim()) as { running?: unknown; status?: unknown };
    if (typeof data.running === 'boolean') daemon = data.running ? 'running' : 'stopped';
    else if (typeof data.status === 'string') daemon = data.status;
  } catch {
    // older OCG versions do not support --json; the version probe is enough for the panel
  }
  return { ...status, daemon };
}

/** Installs browser-use through uv/pipx/pip, whichever the machine already has. */
export async function installBrowserUse(): Promise<InstallOutcome> {
  const uv = resolveExecutable('uv');
  if (uv) {
    const result = await runCommand(uv, ['tool', 'install', '--python', '3.12', '--upgrade', '--force', 'browser-use'], INSTALL_TIMEOUT);
    const detail = lastLine(result.stdout, result.stderr) || `uv exited with ${result.code}`;
    return { ok: result.code === 0, detail };
  }
  const pipx = resolveExecutable('pipx');
  if (pipx) {
    const result = await runCommand(pipx, ['install', '--force', 'browser-use'], INSTALL_TIMEOUT);
    return { ok: result.code === 0, detail: lastLine(result.stdout, result.stderr) || `pipx exited with ${result.code}` };
  }
  const python = resolveExecutable('python') ?? resolveExecutable('python3');
  if (python) {
    const result = await runCommand(python, ['-m', 'pip', 'install', '--user', '--upgrade', 'browser-use'], INSTALL_TIMEOUT);
    return { ok: result.code === 0, detail: lastLine(result.stdout, result.stderr) || `pip exited with ${result.code}` };
  }
  return {
    ok: false,
    detail: 'uv/pipx/python not found. Install uv first, then run: uv tool install --python 3.12 browser-use',
  };
}

/** Installs the OCG CLI globally with npm (Node >= 22.12 is a package requirement). */
export async function installOcg(): Promise<InstallOutcome> {
  const npm = resolveExecutable('npm');
  if (!npm) return { ok: false, detail: 'npm not found. Install Node.js 22.12+ first.' };
  const result = await runCommand(npm, ['install', '-g', 'openclaw-channel-gateway'], INSTALL_TIMEOUT);
  return { ok: result.code === 0, detail: lastLine(result.stdout, result.stderr) || `npm exited with ${result.code}` };
}

/** The engine runs `core-ai-cli ...` from the agent's shell; put the bundled binary first. */
export function withExecutableOnPath(env: Record<string, string>, directory: string): Record<string, string> {
  const pathKey = Object.keys(process.env).find((key) => key.toUpperCase() === 'PATH') ?? 'PATH';
  const separator = process.platform === 'win32' ? ';' : ':';
  const current = env[pathKey] ?? process.env[pathKey] ?? '';
  const parts = current.split(separator).filter(Boolean);
  if (!parts.includes(directory)) parts.unshift(directory);
  return { ...env, [pathKey]: parts.join(separator) };
}
