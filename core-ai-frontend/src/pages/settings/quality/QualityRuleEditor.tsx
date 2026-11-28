import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { AlertTriangle, ArrowLeft, FlaskConical, Trash2 } from 'lucide-react';
import type { QualityAdminApi, QualityPolicy, QualityRule, RuleQuestion, RuleTemplate, RuleTestResponse } from '../../../api/quality';
import ToggleSwitch from '../../../components/ToggleSwitch';
import EnforcementBadge from './EnforcementBadge';

export interface QualityFixtureOption {
  id: string;
  label: string;
}

interface Props {
  api: QualityAdminApi;
  basePath: string;
  subject: 'agent' | 'skill';
  defaultInput: string;
  createTitle: string;
  createSubtitle: string;
  testTitle: string;
  keyPlaceholder: string;
  note: string;
  loadFixtures: () => Promise<QualityFixtureOption[]>;
  testPayload: (fixtureId: string, sample: string) => Record<string, unknown>;
  canEditDefinition: (rule: QualityRule) => boolean;
}

const EMPTY_RULE = {
  key: '',
  name: '',
  category: 'CUSTOM',
  description: '',
  rationale: '',
  decision_type: 'noul',
  decision_question: '',
  decision_instruction: '',
  affirmative_threshold: 0.8,
  negative_threshold: 0.2,
  message_template: '',
  remediation_template: '',
  parameters: {},
};

type EnforcementLevel = 'BLOCK' | 'WARN';

function DecisionQuestionList({ questions, question }: { questions: RuleQuestion[]; question?: string }) {
  if (!questions.length) return <>{question || '-'}</>;
  return (
    <ul className="space-y-2">
      {questions.map(item => (
        <li key={item.name}>
          <div className="font-mono text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            {item.name}{item.type ? ` · ${item.type}` : ''}
          </div>
          <div>{item.instruction || '-'}</div>
          {item.criteria && Object.keys(item.criteria).length > 0 && (
            <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {Object.entries(item.criteria).map(([key, value]) => `${key}: ${value}`).join(' · ')}
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}

export default function QualityRuleEditor({
  api,
  basePath,
  subject,
  defaultInput,
  createTitle,
  createSubtitle,
  testTitle,
  keyPlaceholder,
  note,
  loadFixtures,
  testPayload,
  canEditDefinition,
}: Props) {
  const params = useParams<{ ruleId?: string; ruleKey?: string }>();
  const [searchParams] = useSearchParams();
  const ruleId = params.ruleId ?? params.ruleKey;
  const scope = subject === 'skill' ? (searchParams.get('scope') || 'skill:tier:domain') : undefined;
  const navigate = useNavigate();
  const creating = ruleId === 'new';
  const [rule, setRule] = useState<QualityRule | null>(null);
  const [templates, setTemplates] = useState<RuleTemplate[]>([]);
  const [inputOptions, setInputOptions] = useState<string[]>([]);
  const [policy, setPolicy] = useState<QualityPolicy | null>(null);
  const [fixtures, setFixtures] = useState<QualityFixtureOption[]>([]);
  const [draft, setDraft] = useState<Record<string, unknown>>({ ...EMPTY_RULE, input_selector: [defaultInput] });
  const [changeReason, setChangeReason] = useState('');
  const [testFixtureId, setTestFixtureId] = useState('');
  const [testSample, setTestSample] = useState('');
  const [testResult, setTestResult] = useState<RuleTestResponse | null>(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [enforceEnabled, setEnforceEnabled] = useState(false);
  const [enforceLevel, setEnforceLevel] = useState<EnforcementLevel>('BLOCK');
  const initializedRef = useRef<string | null>(null);

  const editable = creating || (rule != null && canEditDefinition(rule));

  const setDraftFromRule = (value: QualityRule) => {
    setDraft({
      name: value.name,
      description: value.description || '',
      rationale: value.rationale || '',
      input_selector: value.input_selector || [defaultInput],
      decision_type: value.decision_type || 'noul',
      decision_question: value.decision_question || '',
      decision_instruction: value.decision_instruction || '',
      affirmative_threshold: value.affirmative_threshold ?? 0.8,
      negative_threshold: value.negative_threshold ?? 0.2,
      message_template: value.message_template || '',
      remediation_template: value.remediation_template || '',
      parameters: value.parameters || {},
    });
  };

  const setEnforceFromRule = (value: QualityRule) => {
    const activeLevel = value.enforcement === 'BLOCK' || value.enforcement === 'WARN' ? value.enforcement : null;
    setEnforceEnabled(Boolean(activeLevel));
    setEnforceLevel(activeLevel === 'WARN' ? 'WARN' : 'BLOCK');
  };

  const load = async () => {
    setLoading(true);
    setNotFound(false);
    try {
      const [rulesRes, templatesRes, policiesRes] = await Promise.all([
        api.rules(scope),
        api.templates(),
        api.policies(scope),
      ]);
      setTemplates(templatesRes.templates || []);
      setInputOptions(templatesRes.input_options || []);
      setPolicy(policiesRes.active);
      const loadedFixtures = await loadFixtures().catch(() => []);
      setFixtures(loadedFixtures);
      if (!testFixtureId && loadedFixtures.length) setTestFixtureId(loadedFixtures[0].id);

      if (!creating) {
        const found = (rulesRes.rules || []).find(item => item.id === ruleId) || null;
        setRule(found);
        if (!found) {
          setNotFound(true);
          return;
        }
        if (initializedRef.current !== found.id) {
          initializedRef.current = found.id;
          setDraftFromRule(found);
          setEnforceFromRule(found);
        }
      }
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Failed to load quality rule');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [ruleId]);

  const template = useMemo(
    () => templates.find(t => t.evaluator_key === rule?.evaluator_key),
    [templates, rule],
  );
  const editableParams = template?.parameters || [];
  const builtIn = Boolean(rule?.built_in);
  const codeQuestions = template?.questions || [];

  const save = async () => {
    if (!rule || !canEditDefinition(rule)) return;
    if (!changeReason.trim()) { setMessage('Change reason is required.'); return; }
    setBusy(true);
    try {
      const updated = await api.updateRule(rule.id, { ...draft, change_reason: changeReason });
      setRule(updated);
      setMessage('Saved as a new revision. It is not active until you apply the policy.');
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setBusy(false);
    }
  };

  const create = async () => {
    setBusy(true);
    try {
      const created = await api.createRule({ ...draft, name: draft.name || draft.key });
      navigate(`${basePath}/${encodeURIComponent(created.id)}`, { replace: true });
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Create failed');
    } finally {
      setBusy(false);
    }
  };

  const applyPolicy = async () => {
    if (!rule) return;
    if (!changeReason.trim()) { setMessage('A change reason is required to apply a policy.'); return; }
    const enforcement = enforceEnabled ? enforceLevel : 'SHADOW';
    setBusy(true);
    try {
      await api.activate({
        ...(scope ? { scope } : {}),
        rule_id: rule.id,
        rule_revision: rule.revision,
        enforcement,
        expected_generation: policy?.generation,
        change_reason: changeReason,
      });
      setMessage(`Applied ${enforcement}. New checks will use this policy.`);
      await load();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Activate failed');
    } finally {
      setBusy(false);
    }
  };

  const test = async () => {
    if (!rule) return;
    if (!testFixtureId) { setMessage(`Select a ${subject} to test against.`); return; }
    setBusy(true);
    setTestResult(null);
    try {
      const result = await api.testRule(rule.id, testPayload(testFixtureId, testSample));
      setTestResult(result);
      setMessage('');
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Test failed');
    } finally {
      setBusy(false);
    }
  };

  const retire = async () => {
    if (!rule || !confirm(`Retire ${rule.key}?`)) return;
    setBusy(true);
    try {
      await api.retireRule(rule.id);
      navigate('/quality');
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Retire failed');
    } finally {
      setBusy(false);
    }
  };

  const policyDirty = !rule
    ? false
    : rule.enforcement == null
      ? enforceEnabled
      : enforceEnabled ? rule.enforcement !== enforceLevel : rule.enforcement !== 'SHADOW';

  return (
    <div className="p-6 max-w-5xl">
      <button onClick={() => navigate('/quality')}
        className="flex items-center gap-1 text-sm mb-4 cursor-pointer"
        style={{ color: 'var(--color-primary)' }}>
        <ArrowLeft size={16} /> Back to Quality
      </button>

      <div className="flex flex-wrap items-start justify-between gap-4 mb-6">
        <div>
          <h1 className="text-xl font-semibold">{creating ? createTitle : rule?.key ?? 'Quality rule'}</h1>
          <div className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            {creating ? createSubtitle : rule?.name}
          </div>
        </div>
        <div className="flex gap-2">
          {creating ? (
            <button onClick={create} disabled={busy} className="px-4 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50" style={{ background: 'var(--color-primary)', color: 'white' }}>
              Create draft
            </button>
          ) : (
            <>
              {editable && (
                <button onClick={save} disabled={busy || !rule} className="px-4 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50" style={{ background: 'var(--color-primary)', color: 'white' }}>
                  Save new revision
                </button>
              )}
              <button onClick={retire} disabled={busy || !rule} className="flex items-center gap-1 px-4 py-2 rounded-lg text-sm border cursor-pointer disabled:opacity-50" style={{ borderColor: 'var(--color-border)', color: 'var(--color-error)' }}>
                <Trash2 size={14} /> Retire
              </button>
            </>
          )}
        </div>
      </div>

      {message && (
        <div className="rounded-lg px-3 py-2 mb-4 text-sm" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
          {message}
        </div>
      )}

      {loading ? (
        <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
      ) : notFound ? (
        <div className="rounded-xl border p-6 text-sm" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
          Rule not found.
        </div>
      ) : (
        <>
          <div className="rounded-xl border p-4 mb-4" style={{ borderColor: 'var(--color-border)' }}>
            <label className="block text-xs mb-1">Change reason
              <input value={changeReason} onChange={e => setChangeReason(e.target.value)}
                placeholder="Required for save, apply policy or retire"
                className="mt-1 w-full px-2 py-1.5 rounded border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
            </label>
            <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
              Saving a revision, applying a policy or retiring a rule all require a change reason.
            </div>
          </div>

          {!creating && rule && (
            <div className="rounded-xl border p-4 mb-4" style={{ borderColor: 'var(--color-border)' }}>
              <div className="flex flex-wrap items-center justify-between gap-3 mb-3">
                <div>
                  <div className="font-medium">Policy</div>
                  <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>
                    Current: <EnforcementBadge enforcement={rule.enforcement} />
                  </div>
                </div>
                <label className="flex items-center gap-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  Enforce
                  <ToggleSwitch checked={enforceEnabled} onChange={setEnforceEnabled} label="Enforce rule" />
                </label>
              </div>
              <div className="flex flex-wrap items-center gap-3 text-xs">
                <div className="inline-flex rounded-lg border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
                  {(['BLOCK', 'WARN'] as const).map(level => (
                    <button key={level} type="button" onClick={() => setEnforceLevel(level)} disabled={!enforceEnabled}
                      className="px-3 py-1 cursor-pointer disabled:opacity-40"
                      style={{
                        background: enforceEnabled && enforceLevel === level ? 'var(--color-primary)' : 'transparent',
                        color: enforceEnabled && enforceLevel === level ? 'white' : 'var(--color-text-secondary)',
                      }}>
                      {level}
                    </button>
                  ))}
                </div>
                <span style={{ color: 'var(--color-text-secondary)' }}>
                  {rule.enforcement == null
                    ? (enforceEnabled ? `${enforceLevel} on publish` : 'Not in active policy')
                    : enforceEnabled ? `${enforceLevel} on publish` : 'SHADOW — observe only'}
                </span>
                {policyDirty && <span style={{ color: 'var(--color-primary)' }}>Unsaved policy change</span>}
                <button onClick={applyPolicy} disabled={busy}
                  className="ml-auto px-3 py-1.5 rounded-lg text-sm border cursor-pointer disabled:opacity-50"
                  style={{ borderColor: policyDirty ? 'var(--color-primary)' : 'var(--color-border)', color: policyDirty ? 'var(--color-primary)' : 'var(--color-text-secondary)' }}>
                  Apply policy
                </button>
              </div>
              <div className="text-xs mt-2" style={{ color: 'var(--color-text-secondary)' }}>
                Off = SHADOW: the rule still reports issues but never blocks or warns.
              </div>
            </div>
          )}

          <div className="rounded-xl border p-4 mb-4" style={{ borderColor: 'var(--color-border)' }}>
            <div className="font-medium mb-3">Rule definition</div>
            {!editable && rule ? (
              <dl className="grid gap-3 text-sm md:grid-cols-2">
                <div className="md:col-span-2">
                  <dt className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Requirement</dt>
                  <dd className="mt-0.5">{rule.description || '-'}</dd>
                </div>
                <div className="md:col-span-2">
                  <dt className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                    {codeQuestions.length ? 'Decision questions' : 'Decision question'}
                  </dt>
                  <dd className="mt-0.5">
                    <DecisionQuestionList questions={codeQuestions} question={rule.decision_question} />
                  </dd>
                </div>
                <div>
                  <dt className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Inputs</dt>
                  <dd className="mt-0.5">{rule.input_selector?.join(', ') || '-'}</dd>
                </div>
                <div>
                  <dt className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Enforcement</dt>
                  <dd className="mt-0.5"><EnforcementBadge enforcement={rule.enforcement} /></dd>
                </div>
                <div className="md:col-span-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  This rule is defined in code. Only its enforcement can change without a release.
                </div>
              </dl>
            ) : (
              <div className="grid gap-3 md:grid-cols-2">
                {creating && (
                  <label className="block text-xs md:col-span-2">Key
                    <input value={String(draft.key || '')} onChange={e => setDraft({ ...draft, key: e.target.value })}
                      placeholder={keyPlaceholder}
                      className="mt-1 w-full px-2 py-1.5 rounded border text-sm font-mono"
                      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                  </label>
                )}
                <label className="block text-xs">Name
                  <input value={String(draft.name || '')} onChange={e => setDraft({ ...draft, name: e.target.value })}
                    className="mt-1 w-full px-2 py-1.5 rounded border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                </label>
                <label className="block text-xs md:col-span-2">Rule requirement (description)
                  <textarea value={String(draft.description || '')} onChange={e => setDraft({ ...draft, description: e.target.value })}
                    rows={2} className="mt-1 w-full px-2 py-1.5 rounded border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                </label>
                {builtIn && codeQuestions.length > 0 && (
                  <div className="md:col-span-2 rounded-lg border p-3" style={{ borderColor: 'var(--color-border)' }}>
                    <div className="text-xs font-medium mb-2">Built-in decision questions</div>
                    <DecisionQuestionList questions={codeQuestions} />
                    {template?.supports_instruction_override ? (
                      <>
                        <label className="block text-xs mt-3">Decision instructions override
                          <textarea value={String(draft.decision_instruction || '')}
                            onChange={e => setDraft({ ...draft, decision_instruction: e.target.value })}
                            rows={4} placeholder={codeQuestions[0]?.instruction || ''}
                            className="mt-1 w-full px-2 py-1.5 rounded border text-sm"
                            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                        </label>
                        <div className="text-xs mt-2" style={{ color: 'var(--color-text-secondary)' }}>
                          Leave empty to use the built-in default. The override is versioned with this rule revision;
                          the question name, type and criteria stay in code.
                        </div>
                      </>
                    ) : (
                      <div className="text-xs mt-2" style={{ color: 'var(--color-text-secondary)' }}>
                        Defined in code and read-only. Adjust parameters, thresholds or enforcement to tune this rule.
                      </div>
                    )}
                  </div>
                )}
                {!builtIn && (
                  <>
                    <label className="block text-xs">Decision question
                      <textarea value={String(draft.decision_question || '')} onChange={e => setDraft({ ...draft, decision_question: e.target.value })}
                        rows={3} className="mt-1 w-full px-2 py-1.5 rounded border text-sm"
                        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                    </label>
                    <label className="block text-xs">Instructions
                      <textarea value={String(draft.decision_instruction || '')} onChange={e => setDraft({ ...draft, decision_instruction: e.target.value })}
                        rows={3} className="mt-1 w-full px-2 py-1.5 rounded border text-sm"
                        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                    </label>
                  </>
                )}
                <label className="block text-xs">Pass threshold
                  <input type="number" step="0.01" value={Number(draft.affirmative_threshold ?? 0.8)}
                    onChange={e => setDraft({ ...draft, affirmative_threshold: Number(e.target.value) })}
                    className="mt-1 w-full px-2 py-1.5 rounded border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                </label>
                <label className="block text-xs">Fail threshold
                  <input type="number" step="0.01" value={Number(draft.negative_threshold ?? 0.2)}
                    onChange={e => setDraft({ ...draft, negative_threshold: Number(e.target.value) })}
                    className="mt-1 w-full px-2 py-1.5 rounded border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                </label>
                <label className="block text-xs">Failure message
                  <textarea value={String(draft.message_template || '')} onChange={e => setDraft({ ...draft, message_template: e.target.value })}
                    rows={3} className="mt-1 w-full px-2 py-1.5 rounded border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                </label>
                <label className="block text-xs">Suggestion
                  <textarea value={String(draft.remediation_template || '')} onChange={e => setDraft({ ...draft, remediation_template: e.target.value })}
                    rows={3} className="mt-1 w-full px-2 py-1.5 rounded border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                </label>

                {editableParams.length > 0 && (
                  <div className="md:col-span-2">
                    <div className="text-xs font-medium mb-1">Parameters</div>
                    <div className="grid gap-2 md:grid-cols-2">
                      {editableParams.map(param => (
                        <label key={param.name} className="block text-xs" title={param.description}>
                          {param.name}
                          <input value={String((draft.parameters as Record<string, string> | undefined)?.[param.name] ?? '')}
                            onChange={e => setDraft({
                              ...draft,
                              parameters: { ...((draft.parameters as Record<string, string>) || {}), [param.name]: e.target.value },
                            })}
                            placeholder={param.default_value || ''}
                            className="mt-1 w-full px-2 py-1.5 rounded border text-sm"
                            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                        </label>
                      ))}
                    </div>
                  </div>
                )}

                {!builtIn && (
                  <div className="md:col-span-2 text-xs">
                    Inputs:
                    {inputOptions.map(option => (
                      <label key={option} className="inline-flex items-center gap-1 ml-2">
                        <input type="checkbox"
                          checked={((draft.input_selector as string[]) || []).includes(option)}
                          onChange={e => {
                            const current = (draft.input_selector as string[]) || [];
                            setDraft({ ...draft, input_selector: e.target.checked ? [...current, option] : current.filter(v => v !== option) });
                          }} />
                        {option}
                      </label>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>

          {!creating && rule && (
            <div className="rounded-xl border p-4" style={{ borderColor: 'var(--color-border)' }}>
              <div className="font-medium mb-3 flex items-center gap-1"><FlaskConical size={14} /> {testTitle}</div>
              <div className="grid gap-3 md:grid-cols-2">
                <label className="block text-xs">{subject === 'agent' ? 'Agent' : 'Skill'}
                  <select value={testFixtureId} onChange={e => setTestFixtureId(e.target.value)}
                    className="mt-1 w-full px-2 py-1.5 rounded border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }}>
                    <option value="">{`Select ${subject}...`}</option>
                    {fixtures.map(fixture => <option key={fixture.id} value={fixture.id}>{fixture.label}</option>)}
                  </select>
                </label>
                <div className="flex items-end">
                  <button onClick={test} disabled={busy} className="px-3 py-2 rounded-lg text-sm border cursor-pointer disabled:opacity-50" style={{ borderColor: 'var(--color-border)' }}>
                    Run test
                  </button>
                </div>
                <label className="block text-xs md:col-span-2">
                  {subject === 'agent' ? 'Sample fixture / system prompt override' : 'Sample fixture / SKILL.md override'}
                  <textarea value={testSample} onChange={e => setTestSample(e.target.value)} rows={3}
                    placeholder="Optional"
                    className="mt-1 w-full px-2 py-1.5 rounded border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                </label>
              </div>
              {testResult && (
                <div className="mt-3 text-xs rounded p-2" style={{ background: 'var(--color-bg-tertiary)' }}>
                  Result: <strong>{testResult.result}</strong> · {testResult.duration_ms}ms · lifecycle {testResult.lifecycle}
                  {testResult.issues?.map((issue, i) => (
                    <div key={i} className="mt-1">{issue.message} — {issue.suggestion}</div>
                  ))}
                </div>
              )}
            </div>
          )}

          <div className="mt-4 text-xs flex items-center gap-2" style={{ color: 'var(--color-text-secondary)' }}>
            <AlertTriangle size={12} />
            {note}
          </div>
        </>
      )}
    </div>
  );
}
