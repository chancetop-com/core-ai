// Keeps only workspaces with an imminent scheduled task awake; the engine owns firing and persistence.
import { existsSync, readFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export interface ScheduleWakerOptions {
  schedulesFile?: string;
  knownWorkspaces: () => string[];
  sessionFileExists?: (workspace: string, sessionId: string) => boolean;
  isRunning: (workspace: string) => boolean;
  ensureRunning: (workspace: string) => Promise<void>;
  now?: () => number;
  onError?: (message: string) => void;
}

interface ScheduledTaskRecord {
  id?: string;
  sessionId?: string;
  workspace?: string;
  enabled?: boolean;
  nextRunAt?: string;
}

export class ScheduleWaker {
  private timer: ReturnType<typeof setInterval> | null = null;
  private readonly failures = new Map<string, number>();
  private readonly file: string;
  private readonly now: () => number;

  constructor(private readonly options: ScheduleWakerOptions) {
    this.file = options.schedulesFile ?? path.join(os.homedir(), '.core-ai', 'schedules.json');
    this.now = options.now ?? Date.now;
  }

  start(): void {
    if (this.timer) return;
    void this.tick();
    this.timer = setInterval(() => void this.tick(), 60_000);
    this.timer.unref?.();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  async tick(): Promise<void> {
    const tasks = this.readTasks();
    if (!tasks) return;
    const known = new Set(this.options.knownWorkspaces().map((workspace) => path.resolve(workspace)));
    const wake = new Set<string>();
    const now = this.now();
    for (const task of tasks) {
      if (task.enabled === false) continue;
      const next = Date.parse(task.nextRunAt ?? '');
      if (!Number.isFinite(next) || next < now || next > now + 5 * 60_000) continue;
      const candidates = task.workspace
        ? [path.resolve(task.workspace)]
        : [...known].filter((workspace) => this.legacySessionExists(workspace, task.sessionId));
      for (const workspace of candidates) {
        if ((!task.workspace && !known.has(workspace)) || !existsSync(workspace)) continue;
        if (!this.options.isRunning(workspace)) wake.add(workspace);
      }
    }
    await Promise.all([...wake].map((workspace) => this.wake(workspace)));
  }

  private readTasks(): ScheduledTaskRecord[] | null {
    try {
      const parsed: unknown = JSON.parse(readFileSync(this.file, 'utf8'));
      return Array.isArray(parsed) ? parsed as ScheduledTaskRecord[] : null;
    } catch (error) {
      this.options.onError?.(`schedule scan skipped: ${error instanceof Error ? error.message : String(error)}`);
      return null;
    }
  }

  private legacySessionExists(workspace: string, sessionId: string | undefined): boolean {
    if (!sessionId || !this.options.sessionFileExists) return false;
    return this.options.sessionFileExists(workspace, sessionId);
  }

  private async wake(workspace: string): Promise<void> {
    const retryAt = this.failures.get(workspace) ?? 0;
    if (retryAt > this.now()) return;
    try {
      await this.options.ensureRunning(workspace);
      this.failures.delete(workspace);
    } catch (error) {
      this.failures.set(workspace, this.now() + 5 * 60_000);
      this.options.onError?.(`failed to wake ${workspace}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }
}
