import { AlertTriangle, CheckCircle2, ChevronDown, ChevronRight, CircleHelp, XCircle } from 'lucide-react';
import { useState } from 'react';
import type { QualityIssue, QualityReport } from '../../api/quality';
import { issueTargetSection } from '../../api/quality';

interface Props {
  report: QualityReport;
  /** Extra context appended to the run summary, e.g. that this was a precheck and nothing was saved. */
  note?: string;
  onLocate?: (section: string) => void;
  onClose?: () => void;
}

function statusIcon(status: QualityIssue['status']) {
  if (status === 'FAIL') return <XCircle size={14} style={{ color: 'var(--color-error)' }} />;
  if (status === 'ERROR') return <AlertTriangle size={14} style={{ color: 'var(--color-warning, #d97706)' }} />;
  if (status === 'INDETERMINATE') return <CircleHelp size={14} style={{ color: 'var(--color-warning, #d97706)' }} />;
  return <CheckCircle2 size={14} style={{ color: 'var(--color-success)' }} />;
}

function statusLabel(status: string) {
  if (status === 'FAIL') return 'Failed';
  if (status === 'ERROR') return 'Check incomplete';
  if (status === 'INDETERMINATE') return 'Indeterminate';
  if (status === 'SKIPPED') return 'Not applicable';
  return 'Passed';
}

function groupLabel(issue: QualityIssue) {
  if (issue.category) return issue.category;
  if (issue.rule_id.startsWith('CFG.')) return 'CONFIG';
  if (issue.rule_id.startsWith('PROMPT.')) return 'PROMPT';
  if (issue.rule_id.startsWith('DESC.')) return 'DESCRIPTION';
  return 'CUSTOM';
}

export default function QualityReportPanel({ report, note, onLocate, onClose }: Props) {
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const issues = report.issues || [];
  const groups = Array.from(new Set(issues.map(groupLabel)));
  const exempted = report.exempted === true;

  return (
    <div className="rounded-xl border mb-4 overflow-hidden shrink-0"
      style={{ borderColor: report.gate_result === 'PASSED' ? 'var(--color-success)' : 'var(--color-error)', background: 'var(--color-bg-secondary)' }}>
      <div className="flex items-center justify-between px-4 py-3"
        style={{ borderBottom: '1px solid var(--color-border)' }}>
        <div className="flex items-center gap-3">
          {report.gate_result === 'PASSED'
            ? <CheckCircle2 size={18} style={{ color: 'var(--color-success)' }} />
            : <XCircle size={18} style={{ color: 'var(--color-error)' }} />}
          <div>
            <div className="text-sm font-medium">
              {exempted ? 'Quality gate exempted' : report.gate_result === 'PASSED' ? 'Quality checks passed' : report.gate_result === 'REJECTED' ? 'Quality checks failed' : 'Checks could not complete'}
            </div>
            <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {report.qualified_name ? `${report.qualified_name} · ` : ''}
              Run {report.run_id} · policy {report.policy_revision ?? '-'} · checks complete {report.checks_complete ? 'yes' : 'no'}
              {!!report.warn_count && ` · ${report.warn_count} warning(s)`}
              {exempted && report.exemption_reason ? ` · ${report.exemption_reason}` : ''}
              {note ? ` · ${note}` : ''}
            </div>
          </div>
        </div>
        <div className="flex items-center gap-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          {onClose && <button onClick={onClose} className="px-2 py-1 rounded cursor-pointer" style={{ background: 'var(--color-bg-tertiary)' }}>Close</button>}
        </div>
      </div>

      {issues.length === 0 ? (
        <div className="px-4 py-3 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          {exempted ? 'This subject skips rule evaluation, so there are no issues to fix.' : 'No issues to fix.'}
        </div>
      ) : (
        <div className="p-3 space-y-3 max-h-[40vh] overflow-y-auto overscroll-contain">
          {groups.map(group => {
            const groupIssues = issues.filter(issue => groupLabel(issue) === group);
            if (groupIssues.length === 0) return null;
            const isCollapsed = collapsed[group];
            return (
              <div key={group}>
                <button onClick={() => setCollapsed(prev => ({ ...prev, [group]: !prev[group] }))}
                  className="flex items-center gap-1 text-xs font-medium mb-2 cursor-pointer"
                  style={{ color: 'var(--color-text-secondary)' }}>
                  {isCollapsed ? <ChevronRight size={12} /> : <ChevronDown size={12} />}
                  {group} ({groupIssues.length})
                </button>
                {!isCollapsed && (
                  <div className="space-y-2">
                    {groupIssues.map((issue, index) => (
                      <div key={`${issue.rule_id}-${issue.rule_revision}-${index}`}
                        className="rounded-lg border p-3"
                        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)' }}>
                        <div className="flex items-start gap-2">
                          {statusIcon(issue.status)}
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2 flex-wrap">
                              <span className="text-sm font-medium">{issue.rule_name}</span>
                              <span className="text-xs px-1.5 py-0.5 rounded" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                                {issue.rule_id}@{issue.rule_revision}
                              </span>
                              <span className="text-xs" style={{ color: issue.status === 'FAIL' ? 'var(--color-error)' : 'var(--color-text-secondary)' }}>
                                {statusLabel(issue.status)}
                              </span>
                              {issue.enforcement && (
                                <span className="text-xs" style={{ color: 'var(--color-text-tertiary)' }}>{issue.enforcement}</span>
                              )}
                            </div>
                            <div className="text-sm mt-1" style={{ color: 'var(--color-text-primary)' }}>{issue.message}</div>
                            {issue.evidence_text && (
                              <div className="text-xs mt-1 break-words" style={{ color: 'var(--color-text-secondary)' }} title={issue.evidence_text}>
                                Evidence ({issue.evidence_kind === 'SEMANTIC_ASSESSMENT' ? 'model assessment' : 'code fact'}): {issue.evidence_text}
                              </div>
                            )}
                            <div className="text-xs mt-1" style={{ color: 'var(--color-primary)' }}>Suggestion: {issue.suggestion}</div>
                            {issue.decision_type && (
                              <div className="text-[11px] mt-1" style={{ color: 'var(--color-text-tertiary)' }}>
                                {issue.question_id} · affirmative probability {issue.affirmative_probability?.toFixed(2) ?? '-'} (threshold {issue.affirmative_threshold ?? '-'} / {issue.negative_threshold ?? '-'})
                              </div>
                            )}
                            <div className="flex items-center gap-2 mt-2">
                              {issue.field_path && (
                                <span className="text-[11px] font-mono" style={{ color: 'var(--color-text-tertiary)' }}>{issue.field_path}</span>
                              )}
                              {onLocate && issueTargetSection(issue.field_path) && (
                                <button onClick={() => onLocate(issueTargetSection(issue.field_path)!)}
                                  className="text-xs px-2 py-0.5 rounded cursor-pointer"
                                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-primary)' }}>
                                  Locate
                                </button>
                              )}
                              {issue.retryable && <span className="text-[11px]" style={{ color: 'var(--color-text-tertiary)' }}>retryable</span>}
                              {issue.recommended_action && (
                                <span className="text-[11px]" style={{ color: 'var(--color-text-tertiary)' }}>{issue.recommended_action}</span>
                              )}
                            </div>
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
