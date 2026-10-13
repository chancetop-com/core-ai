import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft, Loader2, Save } from 'lucide-react';
import { api } from '../../api/client';
import type { AgentDefinition, DatasetView, GatewayAvailableModel, SkillDefinition, ToolRegistryView } from '../../api/client';
import { agentVersionApi } from '../../api/comparison';
import type { AgentVersion, ComparisonVariantConfig } from '../../api/comparison';

const inputStyle: React.CSSProperties = {
  background: 'var(--color-bg-tertiary)',
  borderColor: 'var(--color-border)',
  color: 'var(--color-text)',
};

function parseKeyValueLines(text: string): Record<string, string> {
  const result: Record<string, string> = {};
  for (const line of text.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const idx = trimmed.indexOf('=');
    if (idx <= 0) continue;
    result[trimmed.slice(0, idx).trim()] = trimmed.slice(idx + 1).trim();
  }
  return result;
}

function formatKeyValueLines(map?: Record<string, string>): string {
  if (!map) return '';
  return Object.entries(map).map(([k, v]) => `${k}=${v}`).join('\n');
}

interface EditorForm {
  label: string;
  systemPrompt: string;
  model: string;
  multiModalModel: string;
  preferCaptionPath: boolean;
  temperature: string;
  thinkingEffort: string;
  maxTurns: string;
  timeoutSeconds: string;
  enableMemory: boolean;
  inputTemplate: string;
  responseSchema: string;
  variablesText: string;
  toolIds: Set<string>;
  skillIds: Set<string>;
  subAgentIds: Set<string>;
  sandboxEnabled: boolean;
  sandboxImage: string;
  sandboxMemoryMb: string;
  sandboxCpuMillicores: string;
  sandboxTimeoutSeconds: string;
  sandboxNetwork: boolean;
  envVarsText: string;
  datasets: { datasetId: string; permission: string; isOutput: boolean }[];
}

function formFromVersion(version: AgentVersion): EditorForm {
  const config = version.config || {};
  const sandbox = (config.sandbox_config || {}) as Record<string, unknown>;
  const num = (value: unknown) => (value != null ? String(value) : '');
  return {
    label: version.label || '',
    systemPrompt: config.system_prompt || '',
    model: config.model || '',
    multiModalModel: config.multi_modal_model || '',
    preferCaptionPath: config.prefer_caption_path === true,
    temperature: num(config.temperature),
    thinkingEffort: config.thinking_effort || '',
    maxTurns: num(config.max_turns),
    timeoutSeconds: num(config.timeout_seconds),
    enableMemory: config.enable_memory === true,
    inputTemplate: config.input_template || '',
    responseSchema: config.response_schema || '',
    variablesText: formatKeyValueLines(config.variables),
    toolIds: new Set((config.tools || []).map(ref => ref.id)),
    skillIds: new Set(config.skill_ids || []),
    subAgentIds: new Set(config.subagent_ids || []),
    sandboxEnabled: sandbox.enabled === true,
    sandboxImage: typeof sandbox.image === 'string' ? sandbox.image : '',
    sandboxMemoryMb: num(sandbox.memory_limit_mb),
    sandboxCpuMillicores: num(sandbox.cpu_limit_millicores),
    sandboxTimeoutSeconds: num(sandbox.timeout_seconds),
    sandboxNetwork: sandbox.network_enabled === true,
    envVarsText: formatKeyValueLines(sandbox.env_vars as Record<string, string> | undefined),
    datasets: (config.dataset_config || []).map(entry => {
      const row = entry as Record<string, unknown>;
      return {
        datasetId: String(row.dataset_id || ''),
        permission: String(row.permission || 'READ'),
        isOutput: row.is_output === true,
      };
    }),
  };
}

function configFromForm(form: EditorForm, toolRegistry: Map<string, ToolRegistryView>): ComparisonVariantConfig {
  const num = (value: string): number | undefined => (value.trim() ? Number(value) : undefined);
  return {
    system_prompt: form.systemPrompt,
    model: form.model,
    multi_modal_model: form.multiModalModel,
    prefer_caption_path: form.preferCaptionPath,
    temperature: num(form.temperature),
    thinking_effort: form.thinkingEffort || undefined,
    max_turns: num(form.maxTurns),
    timeout_seconds: num(form.timeoutSeconds),
    enable_memory: form.enableMemory,
    input_template: form.inputTemplate,
    response_schema: form.responseSchema,
    variables: parseKeyValueLines(form.variablesText),
    tools: [...form.toolIds].map(id => {
      const registered = toolRegistry.get(id);
      return { id, type: registered?.type, source: registered?.category };
    }),
    skill_ids: [...form.skillIds],
    subagent_ids: [...form.subAgentIds],
    sandbox_config: {
      enabled: form.sandboxEnabled,
      image: form.sandboxImage || undefined,
      memory_limit_mb: num(form.sandboxMemoryMb),
      cpu_limit_millicores: num(form.sandboxCpuMillicores),
      timeout_seconds: num(form.sandboxTimeoutSeconds),
      network_enabled: form.sandboxNetwork,
      env_vars: parseKeyValueLines(form.envVarsText),
    },
    dataset_config: form.datasets
      .filter(row => row.datasetId)
      .map(row => ({ dataset_id: row.datasetId, permission: row.permission, is_output: row.isOutput })),
  };
}

export default function AgentVersionEditor() {
  const { versionId: versionIdParam } = useParams<{ versionId: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const creatingRef = useRef(false);
  const [versionId, setVersionId] = useState<string | null>(versionIdParam && versionIdParam !== 'new' ? versionIdParam : null);
  const [form, setForm] = useState<EditorForm | null>(null);
  const [tools, setTools] = useState<ToolRegistryView[]>([]);
  const [skills, setSkills] = useState<SkillDefinition[]>([]);
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [datasets, setDatasets] = useState<DatasetView[]>([]);
  const [models, setModels] = useState<GatewayAvailableModel[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void Promise.all([
      api.tools.list().then(res => setTools(res.tools || [])),
      api.skills.list(undefined, undefined, undefined, undefined, 0, 200).then(res => setSkills(res.skills || [])),
      api.agents.list(true).then(res => setAgents((res.agents || []).filter(a => a.type !== 'LLM_CALL'))),
      api.datasets.list(undefined, 0, 200).then(res => setDatasets(res.datasets || [])),
      api.gateway.listAvailableModels().then(res => setModels(res.models || [])),
    ]).catch(err => setError(err instanceof Error ? err.message : String(err)));
  }, []);

  useEffect(() => {
    if (versionIdParam !== 'new') {
      if (versionIdParam) setVersionId(versionIdParam);
      return;
    }
    if (creatingRef.current) return;
    creatingRef.current = true;
    const agentId = searchParams.get('agent');
    if (!agentId) {
      setError('缺少 agent 参数');
      return;
    }
    agentVersionApi.create({ agent_id: agentId })
      .then(version => {
        setVersionId(version.id);
        setForm(formFromVersion(version));
        navigate(`/experiments/agent-comparison/versions/${version.id}`, { replace: true });
      })
      .catch(err => setError(err instanceof Error ? err.message : String(err)));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [versionIdParam]);

  useEffect(() => {
    if (!versionId) return;
    let alive = true;
    agentVersionApi.get(versionId)
      .then(version => { if (alive) setForm(formFromVersion(version)); })
      .catch(err => { if (alive) setError(err instanceof Error ? err.message : String(err)); });
    return () => { alive = false; };
  }, [versionId]);

  const toolRegistry = useMemo(() => new Map(tools.map(tool => [tool.id, tool])), [tools]);

  const handleSave = useCallback(async () => {
    if (!versionId || !form) return;
    setBusy(true);
    setError(null);
    try {
      await agentVersionApi.update(versionId, { label: form.label || undefined, config: configFromForm(form, toolRegistry) });
      navigate('/experiments/agent-comparison');
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }, [form, navigate, toolRegistry, versionId]);

  const toggleSet = (key: 'toolIds' | 'skillIds' | 'subAgentIds', id: string) => {
    setForm(prev => {
      if (!prev) return prev;
      const next = new Set(prev[key]);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return { ...prev, [key]: next };
    });
  };

  if (!form) {
    return (
      <div className="mx-auto max-w-4xl p-6">
        {error
          ? <div className="rounded-xl border px-4 py-3 text-sm" style={{ borderColor: 'var(--color-error)', color: 'var(--color-error)' }}>{error}</div>
          : <div className="py-12 text-center text-sm" style={{ color: 'var(--color-text-secondary)' }}><Loader2 className="animate-spin" size={18} /></div>}
      </div>
    );
  }

  const section = 'rounded-xl border p-4';
  const sectionStyle = { borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' } as const;
  const labelStyle = { color: 'var(--color-text-secondary)' } as const;

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-5 p-6">
      <div className="flex flex-wrap items-center gap-3">
        <button type="button" onClick={() => navigate('/experiments/agent-comparison')}
          className="inline-flex items-center gap-1 rounded-lg border px-3 py-1.5 text-xs cursor-pointer"
          style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
          <ArrowLeft size={12} /> 返回
        </button>
        <h1 className="text-lg font-semibold" style={{ color: 'var(--color-text)' }}>Version Editor</h1>
        <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          完整配置一个 agent 版本；保存不影响线上（发布由实验胜出后执行）
        </span>
        <button type="button" onClick={() => void handleSave()} disabled={busy}
          className="ml-auto inline-flex items-center gap-1.5 rounded-lg px-4 py-2 text-sm text-white cursor-pointer disabled:opacity-40"
          style={{ background: 'var(--color-primary)' }}>
          {busy ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />} 保存版本
        </button>
      </div>

      {error && (
        <div className="rounded-xl border px-4 py-3 text-sm" style={{ borderColor: 'var(--color-error)', color: 'var(--color-error)' }}>{error}</div>
      )}

      <section className={section} style={sectionStyle}>
        <div className="grid grid-cols-2 gap-4">
          <label className="flex flex-col gap-1 text-xs">
            <span style={labelStyle}>版本名（label）</span>
            <input value={form.label} onChange={e => setForm(f => f && ({ ...f, label: e.target.value }))}
              className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            <span style={labelStyle}>模型（留空 = 用默认）</span>
            <select value={form.model} onChange={e => setForm(f => f && ({ ...f, model: e.target.value }))}
              className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle}>
              <option value="">default</option>
              {models.map(m => (
                <option key={m.modelId} value={m.modelId}>{m.modelId}{m.providerName ? ` · ${m.providerName}` : ''}</option>
              ))}
              {form.model && !models.some(m => m.modelId === form.model) && (
                <option value={form.model}>{form.model}（未在目录中）</option>
              )}
            </select>
          </label>
        </div>
        <label className="mt-3 flex flex-col gap-1 text-xs">
          <span style={labelStyle}>System prompt</span>
          <textarea value={form.systemPrompt} onChange={e => setForm(f => f && ({ ...f, systemPrompt: e.target.value }))}
            rows={8} className="rounded-md border px-2 py-1 text-xs outline-none resize-y font-mono" style={inputStyle} />
        </label>
      </section>

      <section className={section} style={sectionStyle}>
        <h2 className="mb-3 text-sm font-semibold" style={{ color: 'var(--color-text)' }}>Model config</h2>
        <div className="grid grid-cols-3 gap-3">
          <label className="flex flex-col gap-1 text-xs">
            <span style={labelStyle}>Multimodal model</span>
            <input value={form.multiModalModel} onChange={e => setForm(f => f && ({ ...f, multiModalModel: e.target.value }))}
              className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            <span style={labelStyle}>Temperature</span>
            <input value={form.temperature} onChange={e => setForm(f => f && ({ ...f, temperature: e.target.value }))}
              className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            <span style={labelStyle}>Thinking effort</span>
            <select value={form.thinkingEffort} onChange={e => setForm(f => f && ({ ...f, thinkingEffort: e.target.value }))}
              className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle}>
              <option value="">default</option>
              <option value="none">none</option>
              <option value="low">low</option>
              <option value="high">high</option>
              <option value="max">max</option>
            </select>
          </label>
          <label className="flex flex-col gap-1 text-xs">
            <span style={labelStyle}>Max turns</span>
            <input value={form.maxTurns} onChange={e => setForm(f => f && ({ ...f, maxTurns: e.target.value }))}
              className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            <span style={labelStyle}>Timeout (s)</span>
            <input value={form.timeoutSeconds} onChange={e => setForm(f => f && ({ ...f, timeoutSeconds: e.target.value }))}
              className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
          </label>
          <div className="flex flex-col justify-end gap-1 text-xs">
            <label className="flex items-center gap-2 cursor-pointer">
              <input type="checkbox" checked={form.enableMemory} onChange={e => setForm(f => f && ({ ...f, enableMemory: e.target.checked }))} />
              enable memory
            </label>
            <label className="flex items-center gap-2 cursor-pointer">
              <input type="checkbox" checked={form.preferCaptionPath} onChange={e => setForm(f => f && ({ ...f, preferCaptionPath: e.target.checked }))} />
              prefer caption path
            </label>
          </div>
        </div>
      </section>

      <section className={section} style={sectionStyle}>
        <h2 className="mb-3 text-sm font-semibold" style={{ color: 'var(--color-text)' }}>Tools</h2>
        {tools.length === 0 && <p className="text-xs" style={labelStyle}>工具目录加载中或为空。</p>}
        <div className="flex max-h-72 flex-col gap-1 overflow-y-auto">
          {tools.map(tool => (
            <label key={tool.id} className="flex items-center gap-2 text-xs cursor-pointer">
              <input type="checkbox" checked={form.toolIds.has(tool.id)} onChange={() => toggleSet('toolIds', tool.id)} />
              <code className="font-mono" style={{ color: 'var(--color-text)' }}>{tool.id}</code>
              <span className="rounded px-1.5 py-0.5 text-[10px]"
                style={{ background: 'var(--color-bg-tertiary)', color: tool.type === 'BUILTIN' ? 'var(--color-success)' : 'var(--color-warning)' }}>
                {tool.type}
              </span>
              <span className="truncate" style={labelStyle}>{tool.name}</span>
            </label>
          ))}
        </div>
      </section>

      <section className={section} style={sectionStyle}>
        <h2 className="mb-3 text-sm font-semibold" style={{ color: 'var(--color-text)' }}>Skills & sub-agents</h2>
        <div className="grid grid-cols-2 gap-4">
          <div className="flex max-h-60 flex-col gap-1 overflow-y-auto">
            {skills.map(skill => (
              <label key={skill.id} className="flex items-center gap-2 text-xs cursor-pointer">
                <input type="checkbox" checked={form.skillIds.has(skill.id)} onChange={() => toggleSet('skillIds', skill.id)} />
                <span style={{ color: 'var(--color-text)' }}>{skill.qualified_name}</span>
              </label>
            ))}
          </div>
          <div className="flex max-h-60 flex-col gap-1 overflow-y-auto">
            {agents.map(agent => (
              <label key={agent.id} className="flex items-center gap-2 text-xs cursor-pointer">
                <input type="checkbox" checked={form.subAgentIds.has(agent.id)} onChange={() => toggleSet('subAgentIds', agent.id)} />
                <span style={{ color: 'var(--color-text)' }}>{agent.name}</span>
              </label>
            ))}
          </div>
        </div>
      </section>

      <section className={section} style={sectionStyle}>
        <h2 className="mb-3 text-sm font-semibold" style={{ color: 'var(--color-text)' }}>Sandbox</h2>
        <label className="mb-3 flex items-center gap-2 text-xs cursor-pointer">
          <input type="checkbox" checked={form.sandboxEnabled} onChange={e => setForm(f => f && ({ ...f, sandboxEnabled: e.target.checked }))} />
          enabled
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label className="flex flex-col gap-1 text-xs">
            <span style={labelStyle}>Image</span>
            <input value={form.sandboxImage} onChange={e => setForm(f => f && ({ ...f, sandboxImage: e.target.value }))}
              className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            <span style={labelStyle}>Memory limit MB</span>
            <input value={form.sandboxMemoryMb} onChange={e => setForm(f => f && ({ ...f, sandboxMemoryMb: e.target.value }))}
              className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            <span style={labelStyle}>CPU millicores</span>
            <input value={form.sandboxCpuMillicores} onChange={e => setForm(f => f && ({ ...f, sandboxCpuMillicores: e.target.value }))}
              className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            <span style={labelStyle}>Timeout (s)</span>
            <input value={form.sandboxTimeoutSeconds} onChange={e => setForm(f => f && ({ ...f, sandboxTimeoutSeconds: e.target.value }))}
              className="rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle} />
          </label>
        </div>
        <label className="mt-3 flex items-center gap-2 text-xs cursor-pointer">
          <input type="checkbox" checked={form.sandboxNetwork} onChange={e => setForm(f => f && ({ ...f, sandboxNetwork: e.target.checked }))} />
          network enabled
        </label>
        <label className="mt-3 flex flex-col gap-1 text-xs">
          <span style={labelStyle}>Env vars（每行 k=v）</span>
          <textarea value={form.envVarsText} onChange={e => setForm(f => f && ({ ...f, envVarsText: e.target.value }))}
            rows={3} className="rounded-md border px-2 py-1 text-xs outline-none resize-y font-mono" style={inputStyle} />
        </label>
      </section>

      <section className={section} style={sectionStyle}>
        <h2 className="mb-3 text-sm font-semibold" style={{ color: 'var(--color-text)' }}>Dataset & IO</h2>
        <div className="flex flex-col gap-2">
          {form.datasets.map((row, idx) => (
            <div key={idx} className="flex items-center gap-2">
              <select value={row.datasetId}
                onChange={e => setForm(f => f && ({ ...f, datasets: f.datasets.map((r, i) => i === idx ? { ...r, datasetId: e.target.value } : r) }))}
                className="min-w-0 flex-1 rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle}>
                <option value="">选择 dataset…</option>
                {datasets.map(ds => <option key={ds.id} value={ds.id}>{ds.name}</option>)}
              </select>
              <select value={row.permission}
                onChange={e => setForm(f => f && ({ ...f, datasets: f.datasets.map((r, i) => i === idx ? { ...r, permission: e.target.value } : r) }))}
                className="w-28 rounded-md border px-2 py-1 text-xs outline-none" style={inputStyle}>
                <option value="READ">READ</option>
                <option value="WRITE">WRITE</option>
              </select>
              <label className="flex items-center gap-1 text-xs cursor-pointer">
                <input type="checkbox" checked={row.isOutput}
                  onChange={e => setForm(f => f && ({ ...f, datasets: f.datasets.map((r, i) => i === idx ? { ...r, isOutput: e.target.checked } : r) }))} />
                output
              </label>
              <button type="button"
                onClick={() => setForm(f => f && ({ ...f, datasets: f.datasets.filter((_, i) => i !== idx) }))}
                className="rounded-lg border px-2 py-1 text-xs cursor-pointer" style={{ borderColor: 'var(--color-border)', color: 'var(--color-error)' }}>
                移除
              </button>
            </div>
          ))}
          <button type="button"
            onClick={() => setForm(f => f && ({ ...f, datasets: [...f.datasets, { datasetId: '', permission: 'READ', isOutput: false }] }))}
            className="w-fit rounded-lg border px-3 py-1.5 text-xs cursor-pointer" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            + 添加 dataset
          </button>
        </div>
        <div className="mt-4 grid grid-cols-2 gap-4">
          <label className="flex flex-col gap-1 text-xs">
            <span style={labelStyle}>Input template</span>
            <textarea value={form.inputTemplate} onChange={e => setForm(f => f && ({ ...f, inputTemplate: e.target.value }))}
              rows={3} className="rounded-md border px-2 py-1 text-xs outline-none resize-y font-mono" style={inputStyle} />
          </label>
          <label className="flex flex-col gap-1 text-xs">
            <span style={labelStyle}>Response schema</span>
            <textarea value={form.responseSchema} onChange={e => setForm(f => f && ({ ...f, responseSchema: e.target.value }))}
              rows={3} className="rounded-md border px-2 py-1 text-xs outline-none resize-y font-mono" style={inputStyle} />
          </label>
        </div>
        <label className="mt-4 flex flex-col gap-1 text-xs">
          <span style={labelStyle}>Variables（每行 k=v）</span>
          <textarea value={form.variablesText} onChange={e => setForm(f => f && ({ ...f, variablesText: e.target.value }))}
            rows={3} className="rounded-md border px-2 py-1 text-xs outline-none resize-y font-mono" style={inputStyle} />
        </label>
      </section>
    </div>
  );
}
