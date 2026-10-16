import type { ComparisonVariant } from '../../api/comparison';

interface Props {
  variantKey: 'a' | 'b';
  variant?: ComparisonVariant;
  /** Where this arm's snapshot came from (a saved version, the live config, or a copy of A). */
  sourceNote: string;
  /** Saved version backing this arm, when there is one — offers a jump to the editable version. */
  versionId?: string;
  onOpenVersion: () => void;
  onClose: () => void;
}

const labelStyle: React.CSSProperties = { color: 'var(--color-text-secondary)' };

function Row({ label, children }: { label: string; children?: React.ReactNode }) {
  if (children == null || children === '') return null;
  return (
    <div className="flex gap-3 text-xs">
      <span className="w-32 shrink-0" style={labelStyle}>{label}</span>
      <div className="min-w-0 flex-1" style={{ color: 'var(--color-text)' }}>{children}</div>
    </div>
  );
}

function Block({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="mt-4 flex flex-col gap-1.5">
      <div className="text-[11px] font-medium" style={labelStyle}>{title}</div>
      {children}
    </div>
  );
}

function JsonBlock({ value }: { value: unknown }) {
  if (value == null) return null;
  return (
    <pre className="max-h-40 overflow-auto whitespace-pre-wrap rounded-md border p-2 text-[11px]"
      style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}

function text(value?: string | number | boolean | null): React.ReactNode {
  if (value == null || value === '') return <span style={labelStyle}>(默认)</span>;
  if (typeof value === 'boolean') return value ? '是' : '否';
  return String(value);
}

/**
 * Read-only view of one comparison arm's frozen config: everything the experiment actually runs with
 * (prompt, model, tools, skills, sandbox), so looking at a version never requires clicking an action —
 * least of all "end & adopt".
 */
export default function VariantConfigModal({ variantKey, variant, sourceNote, versionId, onOpenVersion, onClose }: Props) {
  const config = variant?.config;
  const effective = new Set((variant?.effective_tools || []).map(ref => ref.id));
  const tools = config?.tools || [];
  const skillIds = config?.skill_ids || [];
  const subAgentIds = config?.subagent_ids || [];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto"
      style={{ background: 'rgba(0,0,0,0.45)' }} onClick={onClose}>
      <div className="my-8 w-[640px] max-w-[94vw] rounded-xl border p-5"
        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)' }}
        onClick={event => event.stopPropagation()}>
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="text-sm font-semibold" style={{ color: 'var(--color-text)' }}>
            {variant?.label || `版本 ${variantKey.toUpperCase()}`} · 完整配置
          </h2>
          <span className="rounded px-1.5 py-0.5 text-[10px]" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
            {sourceNote}
          </span>
          <span className="rounded px-1.5 py-0.5 text-[10px]" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
            实验快照{versionId ? '（与版本当前内容可能不同）' : ''}
          </span>
          <button type="button" onClick={onClose}
            className="ml-auto rounded-md px-2 py-1 text-xs cursor-pointer"
            style={labelStyle}>
            关闭
          </button>
        </div>

        <div className="mt-4 max-h-[64vh] overflow-y-auto pr-1">
          <Block title="基础参数">
            <Row label="model">{text(variant?.model || config?.model)}</Row>
            <Row label="multi_modal_model">{text(config?.multi_modal_model)}</Row>
            <Row label="temperature">{text(config?.temperature)}</Row>
            <Row label="thinking_effort">{text(config?.thinking_effort)}</Row>
            <Row label="max_turns">{text(config?.max_turns)}</Row>
            <Row label="timeout_seconds">{text(config?.timeout_seconds)}</Row>
            <Row label="enable_memory">{text(config?.enable_memory)}</Row>
            <Row label="prefer_caption_path">{text(config?.prefer_caption_path)}</Row>
          </Block>

          <Block title={`系统提示词${config?.system_prompt_id ? '（引用 system prompt，发布时解析）' : ''}`}>
            {config?.system_prompt_id && (
              <Row label="system_prompt_id"><code className="font-mono">{config.system_prompt_id}</code></Row>
            )}
            {config?.system_prompt ? (
              <pre className="max-h-72 overflow-auto whitespace-pre-wrap rounded-md border p-2 text-[11px] leading-relaxed"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                {config.system_prompt}
              </pre>
            ) : (
              <p className="text-xs" style={labelStyle}>未设置</p>
            )}
          </Block>

          {config?.input_template && (
            <Block title="输入模板">
              <pre className="max-h-40 overflow-auto whitespace-pre-wrap rounded-md border p-2 text-[11px]"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                {config.input_template}
              </pre>
            </Block>
          )}

          {config?.variables && Object.keys(config.variables).length > 0 && (
            <Block title="变量">
              <JsonBlock value={config.variables} />
            </Block>
          )}

          <Block title={`工具（${effective.size} 个生效 / 共 ${tools.length} 个配置）`}>
            {tools.length === 0 && <p className="text-xs" style={labelStyle}>该版本未配置工具。</p>}
            <div className="flex flex-col gap-1">
              {tools.map(ref => {
                const on = effective.has(ref.id);
                return (
                  <div key={ref.id} className="flex items-center gap-2 text-xs">
                    <span style={{ color: on ? 'var(--color-success)' : 'var(--color-text-muted)' }}>{on ? '●' : '○'}</span>
                    <code className="font-mono" style={{ color: on ? 'var(--color-text)' : 'var(--color-text-muted)' }}>{ref.id}</code>
                    <span className="rounded px-1.5 py-0.5 text-[10px]"
                      style={{ background: 'var(--color-bg-tertiary)', color: ref.type === 'BUILTIN' ? 'var(--color-success)' : 'var(--color-warning)' }}>
                      {ref.type || '?'}
                    </span>
                    <span className="text-[10px]" style={{ color: 'var(--color-text-muted)' }}>{on ? '生效' : '未生效'}</span>
                  </div>
                );
              })}
            </div>
            <p className="text-[10px]" style={{ color: 'var(--color-text-muted)' }}>只有「生效」的工具会被装配，白名单外的工具不会暴露给这个版本。</p>
          </Block>

          <Block title="技能 / 子 agent">
            <Row label="skill_ids">
              {skillIds.length === 0 ? <span style={labelStyle}>无</span> : (
                <div className="flex flex-wrap gap-1">
                  {skillIds.map(id => <code key={id} className="rounded px-1.5 py-0.5 font-mono text-[10px]" style={{ background: 'var(--color-bg-tertiary)' }}>{id}</code>)}
                </div>
              )}
            </Row>
            <Row label="subagent_ids">
              {subAgentIds.length === 0 ? <span style={labelStyle}>无</span> : (
                <div className="flex flex-wrap gap-1">
                  {subAgentIds.map(id => <code key={id} className="rounded px-1.5 py-0.5 font-mono text-[10px]" style={{ background: 'var(--color-bg-tertiary)' }}>{id}</code>)}
                </div>
              )}
            </Row>
          </Block>

          {config?.response_schema && (
            <Block title="响应结构（response_schema）">
              <pre className="max-h-40 overflow-auto whitespace-pre-wrap rounded-md border p-2 text-[11px]"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
                {config.response_schema}
              </pre>
            </Block>
          )}

          {config?.sandbox_config && (
            <Block title="沙箱配置">
              <JsonBlock value={config.sandbox_config} />
            </Block>
          )}

          {config?.dataset_config && config.dataset_config.length > 0 && (
            <Block title="数据集配置">
              <JsonBlock value={config.dataset_config} />
            </Block>
          )}
        </div>

        <div className="mt-5 flex items-center justify-end gap-2">
          {versionId && (
            <button type="button" onClick={onOpenVersion}
              className="rounded-lg border px-3 py-1.5 text-xs cursor-pointer"
              style={{ borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
              打开版本编辑器
            </button>
          )}
          <button type="button" onClick={onClose}
            className="rounded-lg px-3 py-1.5 text-xs text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            关闭
          </button>
        </div>
      </div>
    </div>
  );
}
