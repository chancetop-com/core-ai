import type { AgentHealth, AgentReferenceIssue } from '../../api/client';

export function needsAttention(health?: AgentHealth | null): boolean {
  return Boolean(health?.needs_attention);
}

export function healthIssueCount(health?: AgentHealth | null): number {
  if (!health) return 0;
  const referenceCount = health.reference_issues?.length ?? 0;
  const qualityFailed = health.quality
    && (health.quality.state === 'FAILED' || health.quality.state === 'UNAVAILABLE');
  return referenceCount + (qualityFailed ? 1 : 0);
}

export function healthTooltip(health?: AgentHealth | null): string {
  if (!health) return '';
  const parts: string[] = [];
  const issues = health.reference_issues ?? [];
  for (const issue of issues.slice(0, 3)) {
    parts.push(`${referenceLabel(issue)} ${referenceReason(issue)}.`);
  }
  if (issues.length > 3) parts.push(`and ${issues.length - 3} more reference issue${issues.length - 3 === 1 ? '' : 's'}.`);

  const quality = health.quality;
  if (quality && (quality.state === 'FAILED' || quality.state === 'UNAVAILABLE')) {
    if (quality.state === 'UNAVAILABLE') {
      parts.push('Last quality check could not complete.');
    } else if (quality.block_fail_count != null) {
      parts.push(`Last quality check failed: ${quality.block_fail_count} blocking issue${quality.block_fail_count === 1 ? '' : 's'}.`);
    } else {
      parts.push('Last quality check failed.');
    }
    if (quality.content_changed || quality.policy_changed) {
      parts.push('Content or policy changed since the check; recheck before publishing.');
    }
  } else if (health.status === 'UNKNOWN') {
    parts.push('Some references could not be verified.');
  }
  return parts.join(' ');
}

export function findReferenceIssue(
  health: AgentHealth | null | undefined,
  refType: string,
  refId: string,
): AgentReferenceIssue | undefined {
  return health?.reference_issues?.find(issue => issue.ref_type === refType && issue.ref_id === refId);
}

export function referenceLabel(issue: AgentReferenceIssue): string {
  return issue.ref_name || issue.ref_id;
}

export function referenceReason(issue: AgentReferenceIssue): string {
  switch (issue.reason) {
    case 'SKILL_NOT_FOUND':
      return 'no longer exists';
    case 'API_TOOL_NOT_FOUND':
      return 'is no longer available';
    case 'MALFORMED_REFERENCE':
      return 'is malformed';
    case 'SKILL_PROBE_UNAVAILABLE':
    case 'API_TOOL_UNVERIFIED':
      return 'could not be verified';
    default:
      return issue.state === 'UNVERIFIED' ? 'could not be verified' : 'is unavailable';
  }
}

export function qualityMessage(health?: AgentHealth | null): string | null {
  const quality = health?.quality;
  if (!quality) return null;
  if (quality.state === 'FAILED') {
    if (quality.block_fail_count != null) {
      return `Last quality check failed: ${quality.block_fail_count} blocking issue${quality.block_fail_count === 1 ? '' : 's'}.`;
    }
    return 'Last quality check failed.';
  }
  if (quality.state === 'UNAVAILABLE') return 'Last quality check could not complete.';
  if (quality.state === 'PASSED' && (quality.content_changed || quality.policy_changed)) {
    return 'Content or policy changed since the last quality check; recheck before publishing.';
  }
  return null;
}
