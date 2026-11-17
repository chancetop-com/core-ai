import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { AlertTriangle, ArrowLeft, FlaskConical, Trash2 } from 'lucide-react';
import { api } from '../../../api/client';
import type { AgentDefinition } from '../../../api/client';
import { qualityApi, type QualityPolicy, type QualityRule, type RuleTemplate, type RuleTestResponse } from '../../../api/quality';
import ToggleSwitch from '../../../components/ToggleSwitch';
import EnforcementBadge from './EnforcementBadge';

const EMPTY_RULE = {
  key: '',
  name: '',
  category: 'CUSTOM',
  description: '',
  rationale: '',
  input_selector: ['SYSTEM_PROMPT'],
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

export default function AgentQualityDetail() {
  const { ruleId } = useParams<{ ruleId: string }>();
  const navigate = useNavigate();
  const creating = ruleId === 'new';
  const [rule, setRule] = useState<QualityRule | null>(null);
  const [templates, setTemplates] = useState<RuleTemplate[]>([]);
  const [inputOptions, setInputOptions] = useState<string[]>([]);
  const [policy, setPolicy] = useState<QualityPolicy | null>(null);
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [draft, setDraft] = useState<Record<string, unknown>>(EMPTY_RULE);
  const [changeReason, setChangeReason] = useState('');
  const [testAgentId, setTestAgentId] = useState('');
  const [testSample, setTestSample] = useState('');
  const [testResult, setTestResult] = useState<RuleTestResponse | null>(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [enforceEnabled, setEnforceEnabled] = useState(false);
  const [enforceLevel, setEnforceLevel] = useState<EnforcementLevel>('BLOCK');
  const initializedRef = useRef<string | null>(null);

  const setDraftFromRule = (value: QualityRule) => {
    setDraft({
      name: value.name,
      description: value.description || '',
      rationale: value.rationale || '',
      input_selector: value.input_selector || ['SYSTEM_PROMPT'],
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
      const [rulesRes, templatesRes, policiesRes, agentsRes] = await Promise.all([
        qualityApi.rules(),
        qualityApi.templates(),
        qualityApi.policies(),
        api.agents.list(false, undefined, 100),
      ]);
      setTemplates(templatesRes.templates || []);
      setInputOptions(templatesRes.input_options || []);
      setPolicy(policiesRes.active);
      setAgents(agentsRes.agents || []);
      if (!testAgentId && agentsRes.agents?.length) setTestAgentId(agentsRes.agents[0].id);

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

  const save = async () => {
    if (!rule) return;
    if (!changeReason.trim()) { setMessage('请填写变更原因（change reason）。'); return; }
    setBusy(true);
    try {
      const updated = await qualityApi.updateRule(rule.id, { ...draft, change_reason: changeReason });
      setRule(updated);
      setMessage('已保存为新 revision，尚未生效；测试后可激活。');
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setBusy(false);
    }
  };

  const create = async () => {
    setBusy(true);
    try {
      const created = await qualityApi.createRule({ ...draft, name: draft.name || draft.key });
      navigate(`/settings/quality/agent/${encodeURIComponent(created.id)}`, { replace: true });
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Create failed');
    } finally {
      setBusy(false);
    }
  };

  const applyPolicy = async () => {
    if (!rule) return;
    if (!changeReason.trim()) { setMessage('激活策略必须填写变更原因。'); return; }
    const enforcement = enforceEnabled ? enforceLevel : 'SHADOW';
    setBusy(true);
    try {
      await qualityApi.activate({
        rule_id: rule.id,
        rule_revision: rule.revision,
        enforcement,
        expected_generation: policy?.generation,
        change_reason: changeReason,
      });
      setMessage(`已应用 ${enforcement}；新检查将使用该策略。`);
      await load();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Activate failed');
    } finally {
      setBusy(false);
    }
  };

  const test = async () => {
    if (!rule) return;
    if (!testAgentId) { setMessage('请选择一个 Agent 作为测试样本。'); return; }
    setBusy(true);
    setTestResult(null);
    try {
      const result = await qualityApi.testRule(rule.id, {
        agent_id: testAgentId,
        sample_name: testSample || undefined,
        system_prompt: testSample || undefined,
      });
      setTestResult(result);
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
      await qualityApi.retireRule(rule.id);
      navigate('/settings/quality');
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
      <button onClick={() => navigate('/settings/quality')}
        className="flex items-center gap-1 text-sm mb-4 cursor-pointer"
        style={{ color: 'var(--color-primary)' }}>
        <ArrowLeft size={16} /> Back to Quality
      </button>

      <div className="flex flex-wrap items-start justify-between gap-4 mb-6">
        <div>
          <h1 className="text-xl font-semibold">{creating ? 'New decision rule' : rule?.key ?? 'Quality rule'}</h1>
          <div className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            {creating ? 'Create a DECISION rule draft, test it, then activate it.' : rule?.name}
          </div>
        </div>
        <div className="flex gap-2">
          {creating ? (
            <button onClick={create} disabled={busy} className="px-4 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50" style={{ background: 'var(--color-primary)', color: 'white' }}>
              Create draft
            </button>
          ) : (
            <>
              <button onClick={save} disabled={busy || !rule} className="px-4 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50" style={{ background: 'var(--color-primary)', color: 'white' }}>
                Save new revision
              </button>
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
              保存 revision、应用策略或 Retire 都需要填写变更原因。
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
            <div className="grid gap-3 md:grid-cols-2">
              {creating && (
                <label className="block text-xs md:col-span-2">Key
                  <input value={String(draft.key || '')} onChange={e => setDraft({ ...draft, key: e.target.value })}
                    placeholder="PROMPT.OUTPUT_REQUIREMENT_PRESENT"
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
            </div>
          </div>

          {!creating && rule && (
            <div className="rounded-xl border p-4" style={{ borderColor: 'var(--color-border)' }}>
              <div className="font-medium mb-3 flex items-center gap-1"><FlaskConical size={14} /> Test against an agent</div>
              <div className="grid gap-3 md:grid-cols-2">
                <label className="block text-xs">Agent
                  <select value={testAgentId} onChange={e => setTestAgentId(e.target.value)}
                    className="mt-1 w-full px-2 py-1.5 rounded border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }}>
                    <option value="">Select agent...</option>
                    {agents.map(agent => <option key={agent.id} value={agent.id}>{agent.name} ({agent.id})</option>)}
                  </select>
                </label>
                <div className="flex items-end">
                  <button onClick={test} disabled={busy} className="px-3 py-2 rounded-lg text-sm border cursor-pointer disabled:opacity-50" style={{ borderColor: 'var(--color-border)' }}>
                    Run test
                  </button>
                </div>
                <label className="block text-xs md:col-span-2">Sample fixture / system prompt override
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
            测试通过只代表该 revision 在当前样本上可用；BLOCK 激活需要正反样例与预算审查。未激活的 revision 不影响发布。
          </div>
        </>
      )}
    </div>
  );
}
