/**
 * View state that belongs to one conversation rather than to the window: the unsent composer text,
 * which right-dock tab is open, and the artifact selection/unread marker. Resources with a single
 * physical instance (the embedded browser, the workspace artifact list) stay app-level.
 *
 * @author stephen
 */

export type DockKind = 'none' | 'browser' | 'artifacts';

export interface SessionSurface {
  /** Composer text not sent yet. */
  draft: string;
  dock: DockKind;
  dockFullscreen: boolean;
  /** Selection inside the workspace-wide artifact list. */
  activeArtifactPath: string | null;
  artifactSource: boolean;
  artifactUnread: boolean;
  /** The user closed the artifact pane during this turn; auto-expand stays off until the next one. */
  artifactDismissedForTurn: boolean;
}

export function createSurface(): SessionSurface {
  return {
    draft: '',
    dock: 'none',
    dockFullscreen: false,
    activeArtifactPath: null,
    artifactSource: false,
    artifactUnread: false,
    artifactDismissedForTurn: false,
  };
}

/**
 * Sessions keep their surface for as long as the app runs (a view may be swept and rebuilt while
 * its surface stays). The +New window has no session id yet: it owns a transient surface that moves
 * onto the session its first message creates.
 */
export class SessionSurfaces {
  private readonly bySession = new Map<string, SessionSurface>();
  private draft: SessionSurface = createSurface();

  /** `null` is the blank +New window; creating an entry for a real session is cheap. */
  get(sessionId: string | null): SessionSurface {
    if (sessionId == null) return this.draft;
    let surface = this.bySession.get(sessionId);
    if (!surface) {
      surface = createSurface();
      this.bySession.set(sessionId, surface);
    }
    return surface;
  }

  /** Read-only lookup for UI that must not create state (session list badges). */
  peek(sessionId: string): SessionSurface | undefined {
    return this.bySession.get(sessionId);
  }

  /** +New starts a fresh window; the previous blank window's surface is discarded. */
  resetDraft(): SessionSurface {
    this.draft = createSurface();
    return this.draft;
  }

  /** The blank window becomes a session: its dock/selection/draft move onto the new id. */
  adopt(sessionId: string): SessionSurface {
    const adopted = this.draft;
    this.bySession.set(sessionId, adopted);
    this.draft = createSurface();
    return adopted;
  }

  delete(sessionId: string): void {
    this.bySession.delete(sessionId);
  }
}
