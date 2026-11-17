import { useEffect, useMemo, useState } from 'react';
import { AlertTriangle, CheckCircle2, FlaskConical, History, Plus, RefreshCw, Trash2 } from 'lucide-react';
import { api } from '../../../api/client';
import type { AgentDefinition } from '../../../api/client';
import { qualityApi, type QualityPolicy, type QualityRule, type RuleTemplate, type RuleTestResponse } from '../../../api/quality';
import Pagination from '../../../components/Pagination';
import ToggleSwitch from '../../../components/ToggleSwitch';
import { useListState } from '../../../hooks/useListState';
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

export default function AgentQualityTab() {
  const [rules, setRules] = useState<QualityRule[]>([]);
  const [templates, setTemplates] = useState<RuleTemplate[]>([]);
  const [inputOptions, setInputOptions] = useState<string[]>([]);
  const [policy, setPolicy] = useState<QualityPolicy | null>(null);
  const [history, setHistory] = useState<QualityPolicy[]>([]);
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [selected, setSelected] = useState<QualityRule | null>(null);
  const [draft, setDraft] = useState<Record<string, unknown>>(EMPTY_RULE);
  const [changeReason, setChangeReason] = useState('');
  const [testAgentId, setTestAgentId] = useState('');
  const [testSample, setTestSample] = useState('');
  const [testResult, setTestResult] = useState<RuleTestResponse | null>(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [creating, setCreating] = useState(false);
  const [enforceEnabled, setEnforceEnabled] = useState(false);
  const [enforceLevel, setEnforceLevel] = useState<EnforcementLevel>('BLOCK');
  const [offset, setOffset] = useListState('agent-quality.offset', 0);
  const [limit, setLimit] = useListState('agent-quality.limit', 20);

  const load = async () => {
    setBusy(true);
    try {
      const [rulesRes, templatesRes, policiesRes, agentsRes] = await Promise.all([
        qualityApi.rules(),
        qualityApi.templates(),
        qualityApi.policies(),
        api.agents.list(false, undefined, 100),
      ]);
      setRules(rulesRes.rules || []);
      setTemplates(templatesRes.templates || []);
      setInputOptions(templatesRes.input_options || []);
      setPolicy(policiesRes.active);
      setHistory(policiesRes.history || []);
      setAgents(agentsRes.agents || []);
      if (!testAgentId && agentsRes.agents?.length) setTestAgentId(agentsRes.agents[0].id);
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Failed to load quality policy');
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => { load(); }, []);

  useEffect(() => {
    if (rules.length === 0) {
      if (offset !== 0) setOffset(0);
    } else if (offset >= rules.length) {
      setOffset(Math.floor((rules.length - 1) / limit) * limit);
    }
  }, [rules.length, offset, limit, setOffset]);

  const pageRules = rules.slice(offset, offset + limit);

  const select = (rule: QualityRule) => {
    const activeLevel = rule.enforcement === 'BLOCK' || rule.enforcement === 'WARN' ? rule.enforcement : null;
    setSelected(rule);
    setDraft({
      name: rule.name,
      description: rule.description || '',
      rationale: rule.rationale || '',
      input_selector: rule.input_selector || ['SYSTEM_PROMPT'],
      decision_type: rule.decision_type || 'noul',
      decision_question: rule.decision_question || '',
      decision_instruction: rule.decision_instruction || '',
      affirmative_threshold: rule.affirmative_threshold ?? 0.8,
      negative_threshold: rule.negative_threshold ?? 0.2,
      message_template: rule.message_template || '',
      remediation_template: rule.remediation_template || '',
      parameters: rule.parameters || {},
    });
    setEnforceEnabled(Boolean(activeLevel));
    setEnforceLevel(activeLevel === 'WARN' ? 'WARN' : 'BLOCK');
    setCreating(false);
    setTestResult(null);
    setMessage('');
  };

  const template = useMemo(
    () => templates.find(t => t.evaluator_key === selected?.evaluator_key),
    [templates, selected],
  );

  const editableParams = template?.parameters || [];

  const save = async () => {
    if (!selected) return;
    if (!changeReason.trim()) { setMessage('请填写变更原因（change reason）。'); return; }
    setBusy(true);
    try {
      const updated = await qualityApi.updateRule(selected.id, { ...draft, change_reason: changeReason });
      setSelected(updated);
      setMessage('已保存为新 revision，尚未生效；测试后可激活。');
      await load();
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
      setCreating(false);
      setMessage('已创建规则（DRAFT）；请先测试，再激活。');
      await load();
      select(created);
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Create failed');
    } finally {
      setBusy(false);
    }
  };

  const test = async () => {
    if (!selected) return;
    if (!testAgentId) { setMessage('请选择一个 Agent 作为测试样本。'); return; }
    setBusy(true);
    setTestResult(null);
    try {
      const result = await qualityApi.testRule(selected.id, {
        agent_id: testAgentId,
        sample_name: testSample || undefined,
        system_prompt: testSample || undefined,
      });
      setTestResult(result);
      await load();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Test failed');
    } finally {
      setBusy(false);
    }
  };

  const applyPolicy = async () => {
    if (!selected) return;
    if (!changeReason.trim()) { setMessage('激活策略必须填写变更原因。'); return; }
    const enforcement = enforceEnabled ? enforceLevel : 'SHADOW';
    setBusy(true);
    try {
      await qualityApi.activate({
        rule_id: selected.id,
        rule_revision: selected.revision,
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

  const retire = async () => {
    if (!selected || !confirm(`Retire ${selected.key}?`)) return;
    setBusy(true);
    try {
      await qualityApi.retireRule(selected.id);
      setSelected(null);
      await load();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Retire failed');
    } finally {
      setBusy(false);
    }
  };

  const rollback = async (revision: number) => {
    if (!changeReason.trim()) { setMessage('回滚策略必须填写变更原因。'); return; }
    setBusy(true);
    try {
      await qualityApi.activate({
        policy_revision: revision,
        expected_generation: policy?.generation,
        change_reason: changeReason,
      });
      setMessage(`已回滚到 policy revision ${revision}。`);
      await load();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Rollback failed');
    } finally {
      setBusy(false);
    }
  };

  const policyDirty = !selected
    ? false
    : selected.enforcement == null
      ? enforceEnabled
      : enforceEnabled ? selected.enforcement !== enforceLevel : selected.enforcement !== 'SHADOW';

  return (
    <div>
      {message && (
        <div className="rounded-lg px-3 py-2 mb-4 text-sm" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
          {message}
        </div>
      )}

      <div className="flex flex-wrap items-center gap-3 mb-4 text-sm">
        <span className="px-2 py-1 rounded" style={{ background: 'var(--color-bg-tertiary)' }}>
          Active policy revision {policy?.revision ?? '-'} · generation {policy?.generation ?? '-'}
        </span>
        <span style={{ color: 'var(--color-text-secondary)' }}>
          {policy?.bindings?.filter(b => b.enforcement === 'BLOCK').length ?? 0} blocking · {policy?.bindings?.filter(b => b.enforcement === 'SHADOW').length ?? 0} shadow
        </span>
        <div className="ml-auto flex items-center gap-2">
          <input value={changeReason} onChange={e => setChangeReason(e.target.value)}
            placeholder="Change reason (required for policy changes)"
            className="px-2 py-1 rounded border text-sm w-72"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
          <button onClick={load} disabled={busy} className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer disabled:opacity-50" style={{ borderColor: 'var(--color-border)' }}>
            <RefreshCw size={14} /> Refresh
          </button>
          <button onClick={() => { setCreating(true); setSelected(null); setDraft(EMPTY_RULE); setEnforceEnabled(false); setEnforceLevel('BLOCK'); }} className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm cursor-pointer" style={{ background: 'var(--color-primary)', color: 'white' }}>
            <Plus size={14} /> New decision rule
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 min-[1400px]:grid-cols-[minmax(0,1fr)_460px]">
        <div>
          <div className="rounded-xl border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
              <thead style={{ background: 'var(--color-bg-tertiary)' }}>
                <tr>
                  <th className="text-left px-3 py-2">Rule</th>
                  <th className="text-left px-3 py-2">Kind</th>
                  <th className="text-left px-3 py-2">Lifecycle</th>
                  <th className="text-left px-3 py-2">Enforcement</th>
                  <th className="text-left px-3 py-2">Rev</th>
                  <th className="text-left px-3 py-2">Tested</th>
                </tr>
              </thead>
              <tbody>
                {pageRules.map(rule => (
                  <tr key={rule.id} onClick={() => select(rule)} className="border-t cursor-pointer"
                    style={{ borderColor: 'var(--color-border)', background: selected?.id === rule.id ? 'var(--color-primary-bg)' : undefined }}>
                    <td className="px-3 py-2">
                      <div className="font-mono text-xs">{rule.key}</div>
                      <div style={{ color: 'var(--color-text-secondary)' }}>{rule.name}</div>
                    </td>
                    <td className="px-3 py-2 text-xs">{rule.kind}</td>
                    <td className="px-3 py-2 text-xs">{rule.lifecycle}</td>
                    <td className="px-3 py-2 text-xs"><EnforcementBadge enforcement={rule.enforcement} /></td>
                    <td className="px-3 py-2 text-xs">{rule.revision}</td>
                    <td className="px-3 py-2 text-xs">{rule.tested ? <CheckCircle2 size={14} style={{ color: 'var(--color-success)' }} /> : '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
          </div>

          {rules.length > 0 && (
            <Pagination
              offset={offset}
              limit={limit}
              total={rules.length}
              shown={pageRules.length}
              onOffsetChange={setOffset}
              onLimitChange={nextLimit => { setLimit(nextLimit); setOffset(0); }}
            />
          )}
        </div>

        <div className="rounded-xl border p-4" style={{ borderColor: 'var(--color-border)' }}>
          {creating || selected ? (
            <>
              <div className="flex items-start justify-between gap-3 mb-3">
                <div>
                  <h3 className="font-medium">{creating ? 'New decision rule' : selected?.key}</h3>
                  {!creating && <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{selected?.name}</div>}
                </div>
                {!creating && (
                  <label className="flex items-center gap-2 text-xs shrink-0" style={{ color: 'var(--color-text-secondary)' }}>
                    Enforce
                    <ToggleSwitch checked={enforceEnabled} onChange={setEnforceEnabled} label="Enforce rule" />
                  </label>
                )}
              </div>

              {!creating && (
                <div className="flex items-center gap-2 mb-3 text-xs">
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
                    {selected?.enforcement == null
                      ? (enforceEnabled ? `${enforceLevel} on publish` : 'Not in active policy')
                      : enforceEnabled ? `${enforceLevel} on publish` : 'SHADOW — observe only'}
                  </span>
                  {policyDirty && <span style={{ color: 'var(--color-primary)' }}>Unsaved policy change</span>}
                </div>
              )}

              {creating && (
                <label className="block text-xs mb-2">Key
                  <input value={String(draft.key || '')} onChange={e => setDraft({ ...draft, key: e.target.value })}
                    placeholder="PROMPT.OUTPUT_REQUIREMENT_PRESENT"
                    className="mt-1 w-full px-2 py-1 rounded border text-sm font-mono"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                </label>
              )}
              <label className="block text-xs mb-2">Name
                <input value={String(draft.name || '')} onChange={e => setDraft({ ...draft, name: e.target.value })}
                  className="mt-1 w-full px-2 py-1 rounded border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
              </label>
              <label className="block text-xs mb-2">Rule requirement (description)
                <textarea value={String(draft.description || '')} onChange={e => setDraft({ ...draft, description: e.target.value })}
                  rows={2} className="mt-1 w-full px-2 py-1 rounded border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
              </label>
              <label className="block text-xs mb-2">Decision question
                <textarea value={String(draft.decision_question || '')} onChange={e => setDraft({ ...draft, decision_question: e.target.value })}
                  rows={2} className="mt-1 w-full px-2 py-1 rounded border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
              </label>
              <label className="block text-xs mb-2">Instructions
                <textarea value={String(draft.decision_instruction || '')} onChange={e => setDraft({ ...draft, decision_instruction: e.target.value })}
                  rows={2} className="mt-1 w-full px-2 py-1 rounded border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
              </label>
              <div className="grid grid-cols-2 gap-2 mb-2">
                <label className="block text-xs">Pass threshold
                  <input type="number" step="0.01" value={Number(draft.affirmative_threshold ?? 0.8)}
                    onChange={e => setDraft({ ...draft, affirmative_threshold: Number(e.target.value) })}
                    className="mt-1 w-full px-2 py-1 rounded border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                </label>
                <label className="block text-xs">Fail threshold
                  <input type="number" step="0.01" value={Number(draft.negative_threshold ?? 0.2)}
                    onChange={e => setDraft({ ...draft, negative_threshold: Number(e.target.value) })}
                    className="mt-1 w-full px-2 py-1 rounded border text-sm"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                </label>
              </div>
              <label className="block text-xs mb-2">Failure message
                <textarea value={String(draft.message_template || '')} onChange={e => setDraft({ ...draft, message_template: e.target.value })}
                  rows={2} className="mt-1 w-full px-2 py-1 rounded border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
              </label>
              <label className="block text-xs mb-2">Suggestion
                <textarea value={String(draft.remediation_template || '')} onChange={e => setDraft({ ...draft, remediation_template: e.target.value })}
                  rows={2} className="mt-1 w-full px-2 py-1 rounded border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
              </label>

              {editableParams.length > 0 && (
                <div className="mb-3">
                  <div className="text-xs font-medium mb-1">Parameters</div>
                  {editableParams.map(param => (
                    <label key={param.name} className="block text-xs mb-1" title={param.description}>
                      {param.name}
                      <input value={String((draft.parameters as Record<string, string> | undefined)?.[param.name] ?? '')}
                        onChange={e => setDraft({
                          ...draft,
                          parameters: { ...((draft.parameters as Record<string, string>) || {}), [param.name]: e.target.value },
                        })}
                        placeholder={param.default_value || ''}
                        className="mt-1 w-full px-2 py-1 rounded border text-sm"
                        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                    </label>
                  ))}
                </div>
              )}

              <div className="text-xs mb-2">Inputs: {inputOptions.map(option => (
                <label key={option} className="inline-flex items-center gap-1 mr-2">
                  <input type="checkbox"
                    checked={((draft.input_selector as string[]) || []).includes(option)}
                    onChange={e => {
                      const current = (draft.input_selector as string[]) || [];
                      setDraft({ ...draft, input_selector: e.target.checked ? [...current, option] : current.filter(v => v !== option) });
                    }} />
                  {option}
                </label>
              ))}</div>

              <div className="flex flex-wrap gap-2 mt-3">
                {creating
                  ? <button onClick={create} disabled={busy} className="px-3 py-1.5 rounded text-sm cursor-pointer" style={{ background: 'var(--color-primary)', color: 'white' }}>Create draft</button>
                  : (
                    <>
                      <button onClick={save} disabled={busy} className="px-3 py-1.5 rounded text-sm cursor-pointer" style={{ background: 'var(--color-primary)', color: 'white' }}>Save new revision</button>
                      <button onClick={applyPolicy} disabled={busy} className="px-3 py-1.5 rounded text-sm border cursor-pointer" style={{ borderColor: policyDirty ? 'var(--color-primary)' : 'var(--color-border)', color: policyDirty ? 'var(--color-primary)' : 'var(--color-text-secondary)' }}>
                        Apply policy
                      </button>
                      <button onClick={retire} disabled={busy} className="px-3 py-1.5 rounded text-sm border cursor-pointer" style={{ borderColor: 'var(--color-border)', color: 'var(--color-error)' }}><Trash2 size={12} className="inline" /> Retire</button>
                    </>
                  )}
              </div>

              {!creating && (
                <div className="mt-4 pt-3 border-t" style={{ borderColor: 'var(--color-border)' }}>
                  <div className="text-xs font-medium mb-1 flex items-center gap-1"><FlaskConical size={12} /> Test against an agent</div>
                  <select value={testAgentId} onChange={e => setTestAgentId(e.target.value)}
                    className="w-full px-2 py-1 rounded border text-sm mb-1"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }}>
                    <option value="">Select agent...</option>
                    {agents.map(agent => <option key={agent.id} value={agent.id}>{agent.name} ({agent.id})</option>)}
                  </select>
                  <textarea value={testSample} onChange={e => setTestSample(e.target.value)} rows={2}
                    placeholder="Optional: fixture description/system prompt override"
                    className="w-full px-2 py-1 rounded border text-sm mb-1"
                    style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text-primary)' }} />
                  <button onClick={test} disabled={busy} className="px-3 py-1.5 rounded text-sm border cursor-pointer" style={{ borderColor: 'var(--color-border)' }}>Run test</button>
                  {testResult && (
                    <div className="mt-2 text-xs rounded p-2" style={{ background: 'var(--color-bg-tertiary)' }}>
                      Result: <strong>{testResult.result}</strong> · {testResult.duration_ms}ms · lifecycle {testResult.lifecycle}
                      {testResult.issues?.map((issue, i) => (
                        <div key={i} className="mt-1">{issue.message} — {issue.suggestion}</div>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </>
          ) : (
            <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>Select a rule to view or edit it.</div>
          )}
        </div>
      </div>

      <div className="mt-6 rounded-xl border p-4" style={{ borderColor: 'var(--color-border)' }}>
        <div className="flex items-center gap-2 mb-2"><History size={14} /><span className="font-medium">Policy history</span></div>
        <div className="space-y-1 text-sm">
          {history.slice(0, 10).map(item => (
            <div key={item.revision} className="flex items-center gap-3">
              <span className="font-mono text-xs">#{item.revision}</span>
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{item.change_reason}</span>
              <span className="text-xs" style={{ color: 'var(--color-text-tertiary)' }}>{item.activated_by} {item.activated_at ? new Date(item.activated_at).toLocaleString() : ''}</span>
              {item.active && <span className="text-xs px-1.5 py-0.5 rounded" style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}>active</span>}
              {!item.active && (
                <button onClick={() => rollback(item.revision)} disabled={busy}
                  className="text-xs px-2 py-0.5 rounded border cursor-pointer" style={{ borderColor: 'var(--color-border)' }}>
                  Roll back
                </button>
              )}
            </div>
          ))}
        </div>
      </div>

      <div className="mt-4 text-xs flex items-center gap-2" style={{ color: 'var(--color-text-secondary)' }}>
        <AlertTriangle size={12} />
        测试通过只代表该 revision 在当前样本上可用；BLOCK 激活需要正反样例与预算审查。未激活的 revision 不影响发布。
      </div>
    </div>
  );
}
