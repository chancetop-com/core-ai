// Artifact list bookkeeping for the right dock. The engine scan is the authority on what is still on
// disk; live `artifact` events only cover what this window has seen. A refresh therefore keeps a live
// entry only while the workspace still has the file, so one the user deleted after submit_artifacts
// drops out of the gallery instead of leaving a broken thumbnail and an unreadable preview behind.

export interface ArtifactPathEntry {
  path: string;
}

/** Entries the scan did not return: either they appeared after the scan or they were deleted. */
export function unscannedArtifactPaths<T extends ArtifactPathEntry>(existing: T[], baseline: T[]): string[] {
  const scanned = new Set(baseline.map((entry) => entry.path));
  return existing.filter((entry) => !scanned.has(entry.path)).map((entry) => entry.path);
}

/**
 * Baseline plus the live entries the workspace still has. `gone` holds the paths the existence check
 * confirmed missing; an empty set (the check could not run) keeps every live entry, as before.
 */
export function mergeArtifactEntries<T extends ArtifactPathEntry>(
  existing: T[],
  baseline: T[],
  gone: ReadonlySet<string>,
): T[] {
  const scanned = new Set(baseline.map((entry) => entry.path));
  return [...existing.filter((entry) => !scanned.has(entry.path) && !gone.has(entry.path)), ...baseline];
}
