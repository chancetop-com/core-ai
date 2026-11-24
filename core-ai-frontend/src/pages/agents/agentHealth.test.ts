import { describe, expect, it } from 'vitest';
import { healthIssueCount, healthTooltip, needsAttention, qualityMessage, referenceReason } from './agentHealth';
import type { AgentHealth } from '../../api/client';

describe('agentHealth', () => {
  it('counts reference issues and a failed quality check', () => {
    const health: AgentHealth = {
      status: 'ATTENTION',
      needs_attention: true,
      reference_issues: [
        { ref_type: 'SKILL', ref_id: 's1', state: 'BROKEN', reason: 'SKILL_NOT_FOUND' },
        { ref_type: 'API', ref_id: 'api-operation:a:s:o', state: 'BROKEN', reason: 'API_TOOL_NOT_FOUND' },
      ],
      quality: { state: 'FAILED', block_fail_count: 2 },
    };
    expect(needsAttention(health)).toBe(true);
    expect(healthIssueCount(health)).toBe(3);
  });

  it('builds a tooltip with labels, reasons and quality result', () => {
    const health: AgentHealth = {
      status: 'ATTENTION',
      needs_attention: true,
      reference_issues: [
        { ref_type: 'SKILL', ref_id: 's1', ref_name: 'pdf-report', state: 'BROKEN', reason: 'SKILL_NOT_FOUND' },
      ],
      quality: { state: 'FAILED', block_fail_count: 1, content_changed: true },
    };
    const tooltip = healthTooltip(health);
    expect(tooltip).toContain('pdf-report no longer exists');
    expect(tooltip).toContain('Last quality check failed: 1 blocking issue');
    expect(tooltip).toContain('recheck');
  });

  it('does not count an unknown health as attention', () => {
    expect(needsAttention({ status: 'UNKNOWN', needs_attention: false })).toBe(false);
    expect(healthIssueCount({ status: 'UNKNOWN', needs_attention: false })).toBe(0);
  });

  it('renders a content-changed hint for a stale pass', () => {
    expect(qualityMessage({ status: 'OK', quality: { state: 'PASSED', content_changed: true } }))
      .toContain('recheck');
  });

  it('maps unverified references to a readable reason', () => {
    expect(referenceReason({ ref_type: 'API', ref_id: 'x', state: 'UNVERIFIED', reason: 'API_TOOL_UNVERIFIED' }))
      .toBe('could not be verified');
  });
});
