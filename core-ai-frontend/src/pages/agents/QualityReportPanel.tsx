import { AlertTriangle, CheckCircle2, ChevronDown, ChevronRight, CircleHelp, XCircle } from 'lucide-react';
import { useState } from 'react';
import type { QualityIssue, QualityReport } from '../../api/quality';
import { issueTargetSection } from '../../api/quality';

interface Props {
  report: QualityReport;
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
  if (status === 'FAIL') return '未通过';
  if (status === 'ERROR') return '检查未完成';
  if (status === 'INDETERMINATE') return '无法可靠判断';
  if (status === 'SKIPPED') return '不适用';
  return '通过';
}

function groupLabel(issue: QualityIssue) {
  if (issue.rule_id.startsWith('CFG.')) return '配置';
  if (issue.rule_id.startsWith('PROMPT.')) return '提示词';
  if (issue.rule_id.startsWith('DESC.')) return '描述';
  return '自定义规则';
}

export default function QualityReportPanel({ report, onLocate, onClose }: Props) {
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const issues = report.issues || [];
  const groups = ['配置', '提示词', '描述', '自定义规则'];

  return (
    <div className="rounded-xl border mb-4 overflow-hidden"
      style={{ borderColor: report.gate_result === 'PASSED' ? 'var(--color-success)' : 'var(--color-error)', background: 'var(--color-bg-secondary)' }}>
      <div className="flex items-center justify-between px-4 py-3"
        style={{ borderBottom: '1px solid var(--color-border)' }}>
        <div className="flex items-center gap-3">
          {report.gate_result === 'PASSED'
            ? <CheckCircle2 size={18} style={{ color: 'var(--color-success)' }} />
            : <XCircle size={18} style={{ color: 'var(--color-error)' }} />}
          <div>
            <div className="text-sm font-medium">
              {report.gate_result === 'PASSED' ? '质量检查通过' : report.gate_result === 'REJECTED' ? '质量检查未通过' : '检查未能完成'}
            </div>
            <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              运行 {report.run_id} · 策略 {report.policy_revision ?? '-'} · 检查完成 {report.checks_complete ? '是' : '否'}
              {!!report.warn_count && ` · ${report.warn_count} 条警告`}
            </div>
          </div>
        </div>
        <div className="flex items-center gap-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          {onClose && <button onClick={onClose} className="px-2 py-1 rounded cursor-pointer" style={{ background: 'var(--color-bg-tertiary)' }}>关闭</button>}
        </div>
      </div>

      {issues.length === 0 ? (
        <div className="px-4 py-3 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          没有需要修复的问题。
        </div>
      ) : (
        <div className="p-3 space-y-3">
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
                  {group}（{groupIssues.length}）
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
                              <div className="text-xs mt-1 truncate" style={{ color: 'var(--color-text-secondary)' }} title={issue.evidence_text}>
                                证据（{issue.evidence_kind === 'SEMANTIC_ASSESSMENT' ? '模型判定' : '代码事实'}）：{issue.evidence_text}
                              </div>
                            )}
                            <div className="text-xs mt-1" style={{ color: 'var(--color-primary)' }}>建议：{issue.suggestion}</div>
                            {issue.decision_type && (
                              <div className="text-[11px] mt-1" style={{ color: 'var(--color-text-tertiary)' }}>
                                {issue.question_id} · 肯定概率 {issue.affirmative_probability?.toFixed(2) ?? '-'}（阈值 {issue.affirmative_threshold ?? '-'} / {issue.negative_threshold ?? '-'}）
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
                                  定位
                                </button>
                              )}
                              {issue.retryable && <span className="text-[11px]" style={{ color: 'var(--color-text-tertiary)' }}>可重试</span>}
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
