import { useState, useMemo } from 'react';
import { Star, X, ThumbsUp, ThumbsDown, AlertTriangle, ChevronLeft } from 'lucide-react';
import type { SessionFeedback } from '../../../api/session';

const OUTCOMES = [
  { key: 'COMPLETED', label: 'Fully completed', icon: ThumbsUp, color: '#22c55e' },
  { key: 'PARTIAL', label: 'Partially completed', icon: AlertTriangle, color: '#f59e0b' },
  { key: 'FAILED', label: 'Not completed', icon: ThumbsDown, color: '#ef4444' },
] as const;

const FAILURE_OPTIONS = [
  { key: 'UNDERSTANDING', label: "Didn't understand my needs" },
  { key: 'PLANNING', label: 'Poor planning / skipped steps' },
  { key: 'EXECUTION', label: "Didn't finish / stopped midway" },
  { key: 'TOOL_USAGE', label: 'Used wrong tools / tool errors' },
  { key: 'OUTPUT', label: 'Incorrect or low-quality output' },
  { key: 'EFFICIENCY', label: 'Too slow / too many steps' },
  { key: 'COLLABORATION', label: 'Poor communication' },
  { key: 'RELIABILITY', label: 'Made up facts / contradicted itself' },
  { key: 'OTHER', label: 'Other' },
] as const;

const RATING_DIMENSIONS = [
  { key: 'understanding_rating', label: 'Understanding needs' },
  { key: 'problem_solving_rating', label: 'Problem solving' },
  { key: 'tool_usage_rating', label: 'Tool usage' },
  { key: 'communication_rating', label: 'Communication' },
  { key: 'outcome_rating', label: 'Final outcome' },
] as const;

const PROACTIVITY_OPTIONS = [
  { key: 'TOO_ACTIVE', label: 'Too proactive' },
  { key: 'JUST_RIGHT', label: 'Just right' },
  { key: 'TOO_CONSERVATIVE', label: 'Too conservative' },
] as const;

const DECISION_OPTIONS = [
  { key: 'SHOULD_DECIDE', label: 'Should decide more' },
  { key: 'JUST_RIGHT', label: 'Decision balance was right' },
  { key: 'SHOULD_ASK', label: 'Should ask me more' },
] as const;

const TRUST_OPTIONS = [
  { key: 'FULLY_TRUST', label: "I'd fully hand it over" },
  { key: 'MOSTLY_TRUST', label: "I'd delegate most of it" },
  { key: 'NEED_CONFIRM', label: "I'd need to confirm everything" },
  { key: 'WOULD_NOT_USE', label: "I wouldn't use it again" },
] as const;

interface FeedbackModalProps {
  sessionId: string;
  agentId?: string;
  messageCount: number;
  toolCallCount: number;
  toolErrorCount: number;
  sessionDurationMs: number;
  source?: string;
  onSubmit: (feedback: SessionFeedback) => Promise<void>;
  onClose: () => void;
}

// Steps: 0=outcome, 1=failureReasons, 2=ratings, 3=workStyle&trust, 4=comment&submit
type Step = 0 | 1 | 2 | 3 | 4;

const STEP_LABELS = ['Outcome', 'Issues', 'Ratings', 'Style', 'Done'];

export default function FeedbackModal({
  sessionId: _sessionId,
  agentId: _agentId,
  messageCount,
  toolCallCount,
  toolErrorCount,
  sessionDurationMs,
  source,
  onSubmit,
  onClose,
}: FeedbackModalProps) {
  const [step, setStep] = useState<Step>(0);
  const [outcome, setOutcome] = useState<'COMPLETED' | 'PARTIAL' | 'FAILED' | null>(null);
  const [failureReasons, setFailureReasons] = useState<Set<string>>(new Set());
  const [failureDetail, setFailureDetail] = useState('');
  const [ratings, setRatings] = useState<Record<string, number>>({});
  const [proactivityFit, setProactivityFit] = useState<string | null>(null);
  const [decisionFit, setDecisionFit] = useState<string | null>(null);
  const [trustLevel, setTrustLevel] = useState<string | null>(null);
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);

  const showFailureStep = outcome === 'PARTIAL' || outcome === 'FAILED';
  // step indices shift if failure step is absent
  const totalSteps = 3 + (showFailureStep ? 1 : 0); // outcome + (failures?) + ratings + style-trust + done
  const currentStepLabel = () => {
    if (step === 0) return 0;
    if (step === 1 && showFailureStep) return 1;
    if (step === 1 && !showFailureStep) return 1; // ratings is step index 1 when no failure
    if (step === 2 && showFailureStep) return 2;
    if (step === 2 && !showFailureStep) return 2;
    if (step === 3 && showFailureStep) return 3;
    if (step === 3 && !showFailureStep) return 3;
    return 4;
  };

  const handleToggleFailure = (key: string) => {
    setFailureReasons(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const handleRating = (dimKey: string, value: number) => {
    setRatings(prev => ({ ...prev, [dimKey]: prev[dimKey] === value ? 0 : value }));
  };

  const handleOutcomeSelect = (val: typeof outcome) => {
    setOutcome(val);
    if (val === 'PARTIAL' || val === 'FAILED') {
      setStep(1);
    } else {
      setStep(1); // skip failure, go to ratings
    }
  };

  const handleNext = () => {
    setStep(prev => {
      const next = (prev + 1) as Step;
      return next > (4 as Step) ? (4 as Step) : next;
    });
  };

  const handleBack = () => {
    setStep(prev => {
      const prevStep = (prev - 1) as Step;
      return prevStep < 0 ? 0 : prevStep;
    });
  };

  const handleSubmit = async () => {
    if (!outcome || submitting) return;
    setSubmitting(true);
    try {
      const feedback: SessionFeedback = {
        outcome,
        failure_reasons: failureReasons.size > 0 ? Array.from(failureReasons) : undefined,
        failure_detail: failureDetail || undefined,
        understanding_rating: ratings.understanding_rating,
        problem_solving_rating: ratings.problem_solving_rating,
        tool_usage_rating: ratings.tool_usage_rating,
        communication_rating: ratings.communication_rating,
        outcome_rating: ratings.outcome_rating,
        proactivity_fit: (proactivityFit as SessionFeedback['proactivity_fit']) || undefined,
        decision_fit: (decisionFit as SessionFeedback['decision_fit']) || undefined,
        trust_level: (trustLevel as SessionFeedback['trust_level']) || undefined,
        comment: comment || undefined,
        message_count: messageCount,
        tool_call_count: toolCallCount,
        tool_error_count: toolErrorCount,
        session_duration_ms: sessionDurationMs,
        source: source || undefined,
      };
      await onSubmit(feedback);
      setSubmitted(true);
      setTimeout(() => onClose(), 1500);
    } catch {
      setSubmitting(false);
    }
  };

  const durationText = useMemo(() => {
    const secs = Math.round(sessionDurationMs / 1000);
    if (secs < 60) return `${secs}s`;
    return `${Math.floor(secs / 60)}m ${secs % 60}s`;
  }, [sessionDurationMs]);

  const renderStars = (dimKey: string, current: number) => (
    <div className="flex gap-0.5">
      {[1, 2, 3, 4, 5].map(i => (
        <button
          key={i}
          type="button"
          onClick={() => handleRating(dimKey, i)}
          className="p-0.5 cursor-pointer transition-transform hover:scale-110"
          style={{ background: 'none', border: 'none' }}
        >
          <Star
            size={20}
            fill={i <= (current || 0) ? '#f59e0b' : 'none'}
            color={i <= (current || 0) ? '#f59e0b' : 'var(--color-text-muted)'}
          />
        </button>
      ))}
    </div>
  );

  if (submitted) {
    return (
      <div className="fixed inset-0 z-50 flex items-center justify-center"
        style={{ background: 'rgba(0,0,0,0.4)' }}>
        <div className="rounded-2xl p-8 text-center max-w-sm mx-4"
          style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}>
          <div className="text-4xl mb-3">🎉</div>
          <h3 className="text-lg font-semibold mb-1" style={{ color: 'var(--color-text)' }}>
            Thank you!
          </h3>
          <p style={{ color: 'var(--color-text-secondary)' }} className="text-sm">
            Your feedback helps improve the agent experience.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ background: 'rgba(0,0,0,0.4)' }}>
      <div className="rounded-2xl w-full max-w-md"
        style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}>

        {/* Header */}
        <div className="flex items-center justify-between p-5 pb-0">
          <h2 className="text-base font-semibold" style={{ color: 'var(--color-text)' }}>
            Session Feedback
          </h2>
          <button onClick={onClose}
            className="p-1 rounded-lg cursor-pointer hover:opacity-70"
            style={{ background: 'none', border: 'none', color: 'var(--color-text-secondary)' }}>
            <X size={18} />
          </button>
        </div>

        {/* Step indicator */}
        {step > 0 && (
          <div className="px-5 pt-3 flex items-center gap-2">
            {Array.from({ length: totalSteps }, (_, i) => (
              <div key={i} className="flex items-center gap-2 flex-1">
                <div className="rounded-full transition-all"
                  style={{
                    width: 8, height: 8,
                    background: i <= currentStepLabel() ? 'var(--color-primary)' : 'var(--color-border)',
                  }}
                />
                {i < totalSteps - 1 && (
                  <div className="flex-1 h-px"
                    style={{ background: i < currentStepLabel() ? 'var(--color-primary)' : 'var(--color-border)' }}
                  />
                )}
              </div>
            ))}
          </div>
        )}

        <div className="p-5">
          {/* Step 0: Outcome — only 3 buttons */}
          {step === 0 && (
            <div>
              <p className="text-sm mb-4" style={{ color: 'var(--color-text-secondary)' }}>
                How did the agent do on this task?
              </p>
              <div className="flex gap-2">
                {OUTCOMES.map(o => {
                  const Icon = o.icon;
                  return (
                    <button
                      key={o.key}
                      type="button"
                      onClick={() => handleOutcomeSelect(o.key)}
                      className="flex-1 flex flex-col items-center gap-2 p-4 rounded-xl text-sm font-medium transition-all cursor-pointer hover:-translate-y-0.5"
                      style={{
                        background: 'var(--color-bg-secondary)',
                        border: `1.5px solid var(--color-border)`,
                        color: 'var(--color-text-secondary)',
                      }}
                    >
                      <Icon size={24} color={o.color} />
                      {o.label}
                    </button>
                  );
                })}
              </div>

              {/* Session summary */}
              <div className="mt-4 rounded-lg px-3 py-2 text-xs"
                style={{ background: 'var(--color-bg-secondary)', color: 'var(--color-text-muted)' }}>
                {durationText} · {messageCount} msgs · {toolCallCount} tools{toolErrorCount > 0 ? ` · ${toolErrorCount} errors` : ''}
              </div>
            </div>
          )}

          {/* Step 1: Failure Reasons (only when PARTIAL or FAILED) */}
          {step === 1 && showFailureStep && (
            <div>
              <p className="text-sm mb-3" style={{ color: 'var(--color-text-secondary)' }}>
                What went wrong? <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>(optional — pick any that apply)</span>
              </p>
              <div className="flex flex-wrap gap-1.5">
                {FAILURE_OPTIONS.map(opt => {
                  const selected = failureReasons.has(opt.key);
                  return (
                    <button
                      key={opt.key}
                      type="button"
                      onClick={() => handleToggleFailure(opt.key)}
                      className="px-3 py-1.5 rounded-lg text-xs font-medium transition-all cursor-pointer"
                      style={{
                        background: selected ? 'var(--color-primary)' : 'var(--color-bg-secondary)',
                        color: selected ? 'white' : 'var(--color-text-secondary)',
                        border: `1px solid ${selected ? 'var(--color-primary)' : 'var(--color-border)'}`,
                      }}
                    >
                      {opt.label}
                    </button>
                  );
                })}
              </div>
              {failureReasons.has('OTHER') && (
                <textarea
                  value={failureDetail}
                  onChange={e => setFailureDetail(e.target.value)}
                  placeholder="Tell us more..."
                  className="mt-2 w-full px-3 py-2 rounded-lg text-xs resize-none"
                  rows={2}
                  style={{
                    background: 'var(--color-bg-secondary)',
                    border: '1px solid var(--color-border)',
                    color: 'var(--color-text)',
                  }}
                />
              )}
            </div>
          )}

          {/* Step 2 (or 1 when no failure): Star Ratings */}
          {(step === 1 && !showFailureStep) || (step === 2 && showFailureStep) ? (
            <div>
              <p className="text-sm mb-3" style={{ color: 'var(--color-text-secondary)' }}>
                Rate the agent <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>(optional)</span>
              </p>
              <div className="space-y-3">
                {RATING_DIMENSIONS.map(dim => (
                  <div key={dim.key} className="flex items-center justify-between">
                    <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                      {dim.label}
                    </span>
                    {renderStars(dim.key, ratings[dim.key] || 0)}
                  </div>
                ))}
              </div>
            </div>
          ) : null}

          {/* Step 3 (or 2 when no failure): Work Style & Trust */}
          {(step === 2 && !showFailureStep) || (step === 3 && showFailureStep) ? (
            <div className="space-y-4">
              {/* Proactivity */}
              <div>
                <p className="text-sm mb-2" style={{ color: 'var(--color-text-secondary)' }}>
                  Proactivity <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>(optional)</span>
                </p>
                <div className="flex gap-2">
                  {PROACTIVITY_OPTIONS.map(opt => {
                    const selected = proactivityFit === opt.key;
                    return (
                      <button
                        key={opt.key}
                        type="button"
                        onClick={() => setProactivityFit(prev => prev === opt.key ? null : opt.key)}
                        className="flex-1 px-3 py-2 rounded-lg text-xs font-medium transition-all cursor-pointer"
                        style={{
                          background: selected ? 'var(--color-primary)' : 'var(--color-bg-secondary)',
                          color: selected ? 'white' : 'var(--color-text-secondary)',
                          border: `1px solid ${selected ? 'var(--color-primary)' : 'var(--color-border)'}`,
                        }}
                      >
                        {opt.label}
                      </button>
                    );
                  })}
                </div>
              </div>

              {/* Decision */}
              <div>
                <p className="text-sm mb-2" style={{ color: 'var(--color-text-secondary)' }}>
                  Decision making <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>(optional)</span>
                </p>
                <div className="flex gap-2">
                  {DECISION_OPTIONS.map(opt => {
                    const selected = decisionFit === opt.key;
                    return (
                      <button
                        key={opt.key}
                        type="button"
                        onClick={() => setDecisionFit(prev => prev === opt.key ? null : opt.key)}
                        className="flex-1 px-3 py-2 rounded-lg text-xs font-medium transition-all cursor-pointer"
                        style={{
                          background: selected ? 'var(--color-primary)' : 'var(--color-bg-secondary)',
                          color: selected ? 'white' : 'var(--color-text-secondary)',
                          border: `1px solid ${selected ? 'var(--color-primary)' : 'var(--color-border)'}`,
                        }}
                      >
                        {opt.label}
                      </button>
                    );
                  })}
                </div>
              </div>

              {/* Trust */}
              <div>
                <p className="text-sm mb-2" style={{ color: 'var(--color-text-secondary)' }}>
                  For a similar task next time <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>(optional)</span>
                </p>
                <div className="space-y-1.5">
                  {TRUST_OPTIONS.map(opt => {
                    const selected = trustLevel === opt.key;
                    return (
                      <button
                        key={opt.key}
                        type="button"
                        onClick={() => setTrustLevel(prev => prev === opt.key ? null : opt.key)}
                        className="w-full px-4 py-2 rounded-lg text-sm text-left transition-all cursor-pointer"
                        style={{
                          background: selected ? 'var(--color-primary)' : 'var(--color-bg-secondary)',
                          color: selected ? 'white' : 'var(--color-text-secondary)',
                          border: `1px solid ${selected ? 'var(--color-primary)' : 'var(--color-border)'}`,
                        }}
                      >
                        {opt.label}
                      </button>
                    );
                  })}
                </div>
              </div>
            </div>
          ) : null}

          {/* Step 4 (or 3): Comment + Submit */}
          {(step === 3 && !showFailureStep) || (step === 4 && showFailureStep) ? (
            <div>
              <p className="text-sm mb-3" style={{ color: 'var(--color-text-secondary)' }}>
                Anything else? <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>(optional)</span>
              </p>
              <textarea
                value={comment}
                onChange={e => setComment(e.target.value)}
                placeholder="Any other thoughts..."
                className="w-full px-3 py-3 rounded-lg text-sm resize-none"
                rows={3}
                style={{
                  background: 'var(--color-bg-secondary)',
                  border: '1px solid var(--color-border)',
                  color: 'var(--color-text)',
                }}
              />
            </div>
          ) : null}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between px-5 pb-5 pt-1">
          <div>
            {step > 0 && (
              <button
                type="button"
                onClick={handleBack}
                className="inline-flex items-center gap-1 px-3 py-2 rounded-lg text-xs font-medium cursor-pointer"
                style={{
                  background: 'none',
                  color: 'var(--color-text-secondary)',
                  border: '1px solid var(--color-border)',
                }}
              >
                <ChevronLeft size={14} />
                Back
              </button>
            )}
          </div>
          <div className="flex gap-2">
            {step > 0 && (
              <button
                type="button"
                onClick={onClose}
                className="px-3 py-2 rounded-lg text-xs cursor-pointer"
                style={{
                  background: 'none',
                  color: 'var(--color-text-muted)',
                  border: 'none',
                }}
              >
                Skip & close
              </button>
            )}
            {isLastStep(step, showFailureStep) ? (
              <button
                type="button"
                onClick={handleSubmit}
                disabled={submitting}
                className="px-4 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-40"
                style={{ background: 'var(--color-primary)', color: 'white', border: 'none' }}
              >
                {submitting ? 'Submitting...' : 'Submit'}
              </button>
            ) : (
              <button
                type="button"
                onClick={handleNext}
                className="px-4 py-2 rounded-lg text-sm font-medium cursor-pointer"
                style={{ background: 'var(--color-primary)', color: 'white', border: 'none' }}
              >
                Next
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function isLastStep(step: Step, showFailure: boolean): boolean {
  if (showFailure) return step === 4;
  return step === 3;
}
