import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { QualityReport } from '../../api/quality';
import QualityReportPanel from './QualityReportPanel';

const REPORT: QualityReport = {
  run_id: 'run-1',
  subject_type: 'SKILL',
  qualified_name: 'demo/skill',
  policy_revision: 2,
  gate_result: 'REJECTED',
  checks_complete: true,
  warn_count: 0,
  issues: [{
    rule_id: 'SKILL.CUSTOM.OUTPUT',
    rule_revision: 1,
    rule_name: 'Output artifact is named',
    rule_requirement: 'The skill must name a concrete output artifact.',
    status: 'FAIL',
    enforcement: 'BLOCK',
    field_path: '/SKILL.md',
    evidence_kind: 'SEMANTIC_ASSESSMENT',
    message: 'The skill does not name a concrete output artifact.',
    suggestion: 'Add an output specification naming the deliverable and its format.',
    decision_type: 'noul',
    question_id: 'Does the skill name an output artifact?',
    affirmative_probability: 0.01,
    affirmative_threshold: 0.8,
    negative_threshold: 0.2,
    category: 'CUSTOM',
  }],
};

describe('QualityReportPanel', () => {
  it('shows rule, message, location and suggestion instead of only the summary', () => {
    render(<QualityReportPanel report={REPORT} />);

    expect(screen.getByText(/quality checks failed/i)).toBeTruthy();
    expect(screen.getByText('Output artifact is named')).toBeTruthy();
    expect(screen.getByText('The skill does not name a concrete output artifact.')).toBeTruthy();
    expect(screen.getByText('/SKILL.md')).toBeTruthy();
    expect(screen.getByText(/Add an output specification naming the deliverable/)).toBeTruthy();
    expect(screen.getByText(/affirmative probability 0.01/)).toBeTruthy();
  });

  it('scrolls the long issue list inside the panel and shows the precheck note', () => {
    const { container } = render(<QualityReportPanel report={REPORT} note="precheck — nothing saved" />);

    expect(screen.getByText(/precheck — nothing saved/)).toBeTruthy();
    const body = container.querySelector('.overflow-y-auto');
    expect(body).toBeTruthy();
    expect(body!.textContent).toContain('Output artifact is named');
  });
});
