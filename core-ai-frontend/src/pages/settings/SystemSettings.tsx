import { useEffect, useMemo, useRef, useState } from 'react';
import { ChevronDown, RefreshCw, Save, Settings } from 'lucide-react';
import { api, type GatewayModel, type SystemSettings as SystemSettingsData } from '../../api/client';

export default function SystemSettings() {
  const [settings, setSettings] = useState<SystemSettingsData | null>(null);
  const [models, setModels] = useState<GatewayModel[]>([]);
  const [memoryExtractionModel, setMemoryExtractionModel] = useState('');
  const [llmModel, setLlmModel] = useState('');
  const [llmMultiModalModel, setLlmMultiModalModel] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');

  const chatModels = useMemo(
    () => models.filter(model => model.enabled !== false && (model.endpointTypes || []).includes('chat.completions')),
    [models],
  );

  const effectiveModel = memoryExtractionModel || settings?.default_memory_extraction_model || '';

  const load = async () => {
    setLoading(true);
    setError('');
    setMessage('');
    try {
      const [settingsResponse, modelsResponse] = await Promise.all([
        api.systemSettings.get(),
        api.gateway.listModels(),
      ]);
      setSettings(settingsResponse);
      setModels(modelsResponse.models || []);
      setMemoryExtractionModel(settingsResponse.memory_extraction_model || '');
      setLlmModel(settingsResponse.llm_model || '');
      setLlmMultiModalModel(settingsResponse.llm_model_multimodal || '');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load system settings');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const save = async () => {
    setSaving(true);
    setError('');
    setMessage('');
    try {
      const response = await api.systemSettings.update({
        memory_extraction_model: memoryExtractionModel.trim() || null,
        llm_model: llmModel.trim() || null,
        llm_model_multimodal: llmMultiModalModel.trim() || null,
      });
      setSettings(response);
      setMemoryExtractionModel(response.memory_extraction_model || '');
      setLlmModel(response.llm_model || '');
      setLlmMultiModalModel(response.llm_model_multimodal || '');
      setMessage('System settings saved. Memory extraction will use the new model on the next consolidation run.');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save system settings');
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="p-8 text-sm" style={{ color: 'var(--color-text-secondary)' }}>Loading system settings...</div>;
  }

  return (
    <div className="p-8 max-w-4xl">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold flex items-center gap-2">
            <Settings size={22} />
            System Configuration
          </h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Configure server-level behavior for background jobs and system services.
          </p>
        </div>
        <button onClick={load} className="btn-secondary flex items-center gap-2" disabled={saving}>
          <RefreshCw size={16} />
          Refresh
        </button>
      </div>

      {error && (
        <div className="mb-4 rounded-lg border px-4 py-3 text-sm" style={{ borderColor: '#ef4444', color: '#ef4444' }}>
          {error}
        </div>
      )}
      {message && (
        <div className="mb-4 rounded-lg border px-4 py-3 text-sm" style={{ borderColor: '#22c55e', color: '#22c55e' }}>
          {message}
        </div>
      )}

      <section className="rounded-xl border" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="p-5 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <h2 className="font-semibold">Memory Extraction</h2>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Select the gateway chat model used by the hourly agent memory consolidation job.
          </p>
        </div>
        <div className="p-5 space-y-5">
          <label className="block">
            <span className="block text-sm font-medium mb-2">Extraction model</span>
            <ModelSelect
              value={memoryExtractionModel}
              models={chatModels}
              defaultModel={settings?.default_memory_extraction_model}
              onChange={setMemoryExtractionModel}
            />
            <span className="block text-xs mt-2" style={{ color: 'var(--color-text-secondary)' }}>
              Only enabled gateway models that support chat completions can be selected.
            </span>
          </label>

          <div className="rounded-lg p-4 text-sm" style={{ background: 'var(--color-bg-tertiary)' }}>
            <div style={{ color: 'var(--color-text-secondary)' }}>Effective model</div>
            <div className="font-mono mt-1">{effectiveModel || 'Not configured'}</div>
          </div>
        </div>
      </section>

      <section className="rounded-xl border mt-6" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="p-5 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <h2 className="font-semibold">Default LLM Model</h2>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            The default text model used by agents when no model is specified in the agent definition.
            Falls back to the value from agent.properties if not set here.
          </p>
        </div>
        <div className="p-5 space-y-5">
          <label className="block">
            <span className="block text-sm font-medium mb-2">Text model</span>
            <ModelSelect
              value={llmModel}
              models={chatModels}
              defaultModel={settings?.default_llm_model}
              onChange={setLlmModel}
            />
          </label>
          <div className="rounded-lg p-4 text-sm" style={{ background: 'var(--color-bg-tertiary)' }}>
            <div style={{ color: 'var(--color-text-secondary)' }}>Effective model</div>
            <div className="font-mono mt-1">{llmModel || settings?.default_llm_model || 'Not configured'}</div>
          </div>
        </div>
      </section>

      <section className="rounded-xl border mt-6" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="p-5 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <h2 className="font-semibold">Default Multimodal Model</h2>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            The default model used for vision/multimodal requests (images, files) when no model is specified in the agent definition.
            Falls back to the value from agent.properties if not set here.
          </p>
        </div>
        <div className="p-5 space-y-5">
          <label className="block">
            <span className="block text-sm font-medium mb-2">Multimodal model</span>
            <ModelSelect
              value={llmMultiModalModel}
              models={chatModels}
              defaultModel={settings?.default_llm_model_multimodal}
              onChange={setLlmMultiModalModel}
            />
          </label>
          <div className="rounded-lg p-4 text-sm" style={{ background: 'var(--color-bg-tertiary)' }}>
            <div style={{ color: 'var(--color-text-secondary)' }}>Effective model</div>
            <div className="font-mono mt-1">{llmMultiModalModel || settings?.default_llm_model_multimodal || 'Not configured'}</div>
          </div>
        </div>
      </section>

      {chatModels.length === 0 && (
        <div className="mt-6 rounded-lg border px-4 py-3 text-sm" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
          No enabled chat gateway models are available. Add or enable a chat model under Settings → Gateway first.
        </div>
      )}

      <div className="flex justify-end mt-6">
        <button onClick={save} className="btn-primary flex items-center gap-2" disabled={saving}>
          <Save size={16} />
          {saving ? 'Saving...' : 'Save Settings'}
        </button>
      </div>
    </div>
  );
}

function ModelSelect({ value, models, defaultModel, onChange }: {
  value: string;
  models: GatewayModel[];
  defaultModel?: string;
  onChange: (value: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handlePointerDown = (event: PointerEvent) => {
      if (!ref.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('pointerdown', handlePointerDown);
    return () => document.removeEventListener('pointerdown', handlePointerDown);
  }, [open]);

  const options = [
    { value: '', label: `Default (${defaultModel || 'not configured'})` },
    ...models.map(model => ({
      value: model.modelId,
      label: `${model.displayName || model.modelId}${model.providerName ? ` · ${model.providerName}` : ''}`,
    })),
  ];

  const selectedLabel = options.find(o => o.value === value)?.label || 'Select model...';

  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen(v => !v)}
        className="w-full h-10 px-3 py-2 rounded-lg text-sm border outline-none flex items-center justify-between gap-2 cursor-pointer"
        style={{
          background: 'var(--color-bg-tertiary)',
          borderColor: 'var(--color-border)',
          color: value ? 'var(--color-text)' : 'var(--color-text-secondary)',
        }}>
        <span className="truncate">{selectedLabel}</span>
        <ChevronDown size={14} className={`shrink-0 transition-transform ${open ? 'rotate-180' : ''}`}
          style={{ color: 'var(--color-text-secondary)' }} />
      </button>
      {open && (
        <div className="absolute left-0 top-full mt-1 z-50 w-full rounded-lg border shadow-lg py-1 max-h-60 overflow-auto"
          style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}>
          {options.map(option => (
            <button key={option.value}
              onClick={() => { onChange(option.value); setOpen(false); }}
              className="w-full text-left px-3 py-2 text-sm cursor-pointer transition-colors"
              style={{
                color: option.value === value ? 'var(--color-primary)' : 'var(--color-text)',
                fontWeight: option.value === value ? 600 : 400,
                background: option.value === value ? 'var(--color-primary-bg)' : 'transparent',
              }}
              onMouseEnter={e => { if (option.value !== value) e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
              onMouseLeave={e => { if (option.value !== value) e.currentTarget.style.background = 'transparent'; }}>
              {option.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
