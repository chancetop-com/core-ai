import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import os from 'node:os';

const READY_TIMEOUT_MS = 30_000;
const POLL_INTERVAL_MS = 200;

async function findFreePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const srv = createServer();
    srv.listen(0, '127.0.0.1', () => {
      const port = (srv.address() as { port: number }).port;
      srv.close(() => resolve(port));
    });
    srv.on('error', reject);
  });
}

function getScriptDir(): string {
  try {
    const url = import.meta.url;
    if (url) return dirname(new URL(url).pathname);
  } catch { /* bundled CJS fallback */ }
  if (process.argv[1]) return dirname(process.argv[1]);
  return process.cwd();
}

function findBinary(): string {
  if (process.env.CORE_AI_BACKEND) return process.env.CORE_AI_BACKEND;

  const execDir = dirname(process.execPath);
  const scriptDir = getScriptDir();
  const searchDirs = [execDir, scriptDir];
  const names = ['core-ai-backend', 'core-ai-cli'];

  for (const dir of searchDirs) {
    for (const name of names) {
      for (const rel of [name, `../${name}`, `../bin/${name}`]) {
        const p = join(dir, rel);
        if (existsSync(p)) return p;
      }
    }
  }

  const homeInstall = join(os.homedir(), '.core-ai', 'bin', 'core-ai-backend');
  if (existsSync(homeInstall)) return homeInstall;

  return 'core-ai-backend';
}

async function waitForReady(url: string): Promise<void> {
  const deadline = Date.now() + READY_TIMEOUT_MS;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`${url}/.well-known/agent-card.json`);
      if (res.ok) return;
    } catch { /* not ready */ }
    await new Promise(r => setTimeout(r, POLL_INTERVAL_MS));
  }
  throw new Error(`Backend did not become ready within ${READY_TIMEOUT_MS / 1000}s`);
}

export interface BackendOptions {
  port?: number;
  continueSession?: boolean;
  skipPermissions?: boolean;
  model?: string;
}

export async function startBackend(opts: BackendOptions): Promise<string> {
  const resolvedPort = opts.port || await findFreePort();
  const binary = findBinary();

  const args = ['--serve', '--headless', '--port', String(resolvedPort)];
  if (opts.continueSession) args.push('--continue');
  if (opts.skipPermissions) args.push('--dangerously-skip-permissions');
  if (opts.model) args.push('--model', opts.model);

  process.stderr.write('\x1b[2m  Starting backend...\x1b[0m\r');

  const child = spawn(binary, args, { stdio: ['ignore', 'pipe', 'pipe'] });

  child.on('error', err => {
    process.stderr.write(`\r\x1b[K`);
    process.stderr.write(`\x1b[31m\nFailed to start backend: ${err.message}\x1b[0m\n`);
    process.stderr.write(`\x1b[2m  Binary: ${binary}\x1b[0m\n`);
    process.stderr.write(`\x1b[2m  Set CORE_AI_BACKEND env var to override\x1b[0m\n`);
    process.exit(1);
  });

  child.on('exit', code => {
    if (code !== 0 && code !== null) {
      process.stderr.write(`\nBackend exited unexpectedly (code ${code})\n`);
      process.exit(1);
    }
  });

  if (process.env.CORE_AI_DEBUG) {
    child.stdout.on('data', d => process.stderr.write(d));
    child.stderr.on('data', d => process.stderr.write(d));
  }

  const cleanup = () => { try { child.kill(); } catch {} };
  process.on('exit', cleanup);
  process.on('SIGTERM', () => { cleanup(); process.exit(0); });

  const baseUrl = `http://localhost:${resolvedPort}`;
  await waitForReady(baseUrl);
  process.stderr.write('\r\x1b[K');
  return baseUrl;
}
