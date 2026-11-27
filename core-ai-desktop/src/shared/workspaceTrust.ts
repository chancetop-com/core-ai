/**
 * Workspace trust state reported by the engine's `initialize` / `workspace/trust` result.
 * The engine owns the fingerprint; the desktop only stores the record the user approves.
 */
export interface WorkspaceTrustState {
  state: 'trusted' | 'restricted';
  key: string;
  /** Canonical workspace path; the desktop MCP store keys its per-workspace documents by this. */
  workspaceKey?: string;
  fingerprint: string;
  hasExecutableInputs: boolean;
  reason: string | null;
  engineRestricted: boolean;
}

/** The engine may be starting or restarting; callers render a neutral state until it reports. */
export type WorkspaceTrustView = WorkspaceTrustState | null;
