/**
 * Runs a plugin-declared host operation with a fixed argv. The manifest selects an allowlisted
 * operation; it cannot pass arguments or name an executable. There is no shell and inherited
 * credentials are stripped before the child starts.
 */
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import type { PluginTerminalCommandDescriptor } from './pluginStore';

export interface PluginTerminalResult {
  commandId: string;
  exitCode: number | null;
  signal: string | null;
  stdout: string;
  stderr: string;
  truncated: boolean;
  durationMs: number;
}

export interface PluginTerminalOptions {
  workspace: string;
  timeoutMs?: number;
  maxOutputBytes?: number;
  env?: NodeJS.ProcessEnv;
}

const DEFAULT_TIMEOUT_MS = 60_000;
const DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;

const OPERATIONS: Record<PluginTerminalCommandDescriptor['operation'], string[]> = {
  'git.status': ['git', 'status', '--short'],
  'git.log': ['git', 'log', '--oneline', '-20'],
  'git.diff': ['git', 'diff', '--stat'],
  'node.version': ['node', '--version'],
};

export async function runPluginTerminal(
  command: PluginTerminalCommandDescriptor,
  options: PluginTerminalOptions,
): Promise<PluginTerminalResult> {
  const argv = OPERATIONS[command.operation];
  if (!argv) throw new Error(`unsupported terminal operation: ${command.operation}`);
  const started = Date.now();
  const timeoutMs = Math.max(1_000, Math.min(options.timeoutMs ?? DEFAULT_TIMEOUT_MS, 120_000));
  const maxOutput = Math.max(1_024, Math.min(options.maxOutputBytes ?? DEFAULT_MAX_OUTPUT_BYTES, 256 * 1024));
  const [executable, ...args] = argv;
  const resolved = resolveExecutable(executable, options.workspace);
  if (!resolved) throw new Error(`executable is not available outside the workspace: ${executable}`);
  return new Promise((resolve, reject) => {
    let stdout = '';
    let stderr = '';
    let stdoutBytes = 0;
    let stderrBytes = 0;
    let truncated = false;
    let settled = false;
    const child = spawn(resolved, args, {
      cwd: options.workspace,
      shell: false,
      windowsHide: true,
      env: sanitizedEnv(options.env ?? process.env),
    });
    const timer = setTimeout(() => {
      truncated = true;
      child.kill();
    }, timeoutMs);
    const append = (chunk: Buffer, current: string, bytes: number, limit: number): [string, number] => {
      if (bytes >= limit) {
        truncated = true;
        return [current, bytes];
      }
      const remaining = limit - bytes;
      const slice = chunk.length > remaining ? chunk.subarray(0, remaining) : chunk;
      if (slice.length < chunk.length) truncated = true;
      return [current + slice.toString('utf8'), bytes + slice.length];
    };
    child.stdout?.on('data', (chunk: Buffer) => {
      [stdout, stdoutBytes] = append(chunk, stdout, stdoutBytes, maxOutput);
    });
    child.stderr?.on('data', (chunk: Buffer) => {
      [stderr, stderrBytes] = append(chunk, stderr, stderrBytes, maxOutput);
    });
    child.on('error', (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(error);
    });
    child.on('close', (exitCode, signal) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({
        commandId: command.commandId,
        exitCode,
        signal,
        stdout,
        stderr,
        truncated,
        durationMs: Date.now() - started,
      });
    });
  });
}

export function resolveExecutable(name: string, workspace: string): string | null {
  if (path.isAbsolute(name) || name.includes('/') || name.includes('\\')) return null;
  const extensions = process.platform === 'win32' ? ['.exe'] : [''];
  const root = path.resolve(workspace);
  for (const dir of (process.env.PATH ?? '').split(path.delimiter)) {
    if (!dir || !path.isAbsolute(dir)) continue;
    for (const extension of extensions) {
      const candidate = path.join(dir, `${name}${extension}`);
      if (!existsSync(candidate)) continue;
      const relative = path.relative(root, candidate);
      if (relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))) continue;
      return candidate;
    }
  }
  return null;
}

export function sanitizedEnv(source: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = {};
  for (const [key, value] of Object.entries(source)) {
    if (value === undefined) continue;
    if (/(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|AUTH)/i.test(key)) continue;
    if (/^(GIT_|SSH_|AWS_|AZURE_|GOOGLE_|OPENAI_|ANTHROPIC_|CORE_AI_)/i.test(key)) continue;
    env[key] = value;
  }
  return env;
}
