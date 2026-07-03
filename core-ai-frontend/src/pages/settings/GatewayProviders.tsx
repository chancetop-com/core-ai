import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { CheckCircle2, CircleAlert, KeyRound, Pencil, PlugZap, Plus, RefreshCw, Save, Trash2, X } from 'lucide-react';
import { api, type GatewayProvider, type GatewayProviderRequest } from '../../api/client';

const PROVIDER_TYPES = [
  { value: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', prefix: 'openai/' },
  { value: 'azure', label: 'Azure OpenAI', baseUrl: '', prefix: 'azure/' },
  { value: 'litellm', label: 'LiteLLM', baseUrl: '', prefix: 'litellm/' },
  { value: 'deepseek', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', prefix: 'deepseek/' },
  { value: 'qwen', label: 'Qwen', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', prefix: 'qwen/' },
  { value: 'openrouter', label: 'OpenRouter', baseUrl: 'https://openrouter.ai/api/v1', prefix: 'openrouter/' },
  { value: 'openai-compatible', label: 'OpenAI Compatible', baseUrl: '', prefix: '' },
];

type FormState = {
  id?: string;
  name: string;
  type: string;
  baseUrl: string;
  apiKey: string;
  apiVersion: string;
  enabled: boolean;
  allowPrivateNetwork: boolean;
  modelPrefix: string;
  defaultChatModel: string;
  defaultResponsesModel: string;
  requestExtraBody: string;
  timeoutSeconds: string;
  connectTimeoutSeconds: string;
};

const emptyForm: FormState = {
  name: '',
  type: 'openai',
  baseUrl: 'https://api.openai.com/v1',
  apiKey: '',
  apiVersion: '',
  enabled: true,
  allowPrivateNetwork: false,
  modelPrefix: 'openai/',
  defaultChatModel: '',
  defaultResponsesModel: '',
  requestExtraBody: '',
  timeoutSeconds: '30',
  connectTimeoutSeconds: '10',
};

export default function GatewayProviders() {
  const [providers, setProviders] = useState<GatewayProvider[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [panelOpen, setPanelOpen] = useState(false);
  const [form, setForm] = useState<FormState>(emptyForm);

  const selectedType = useMemo(
    () => PROVIDER_TYPES.find(type => type.value === form.type) || PROVIDER_TYPES[0],
    [form.type],
  );

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const response = await api.gateway.listProviders();
      setProviders(response.providers || []);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load gateway providers');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const openCreate = () => {
    setForm(emptyForm);
    setPanelOpen(true);
  };

  const openEdit = (provider: GatewayProvider) => {
    setForm({
      id: provider.id,
      name: provider.name || '',
      type: provider.type || 'openai',
      baseUrl: provider.baseUrl || '',
      apiKey: '',
      apiVersion: provider.apiVersion || '',
      enabled: provider.enabled !== false,
      allowPrivateNetwork: provider.allowPrivateNetwork === true,
      modelPrefix: provider.modelPrefix || '',
      defaultChatModel: provider.defaultChatModel || '',
      defaultResponsesModel: provider.defaultResponsesModel || '',
      requestExtraBody: provider.requestExtraBody || '',
      timeoutSeconds: String(provider.timeoutSeconds || 30),
      connectTimeoutSeconds: String(provider.connectTimeoutSeconds || 10),
    });
    setPanelOpen(true);
  };

  const changeType = (type: string) => {
    const preset = PROVIDER_TYPES.find(item => item.value === type) || PROVIDER_TYPES[0];
    setForm(current => ({
      ...current,
      type,
      baseUrl: current.id ? current.baseUrl : preset.baseUrl,
      modelPrefix: current.id ? current.modelPrefix : preset.prefix,
    }));
  };

  const save = async () => {
    setSaving(true);
    setError('');
    try {
      const payload: GatewayProviderRequest = {
        name: form.name,
        type: form.type,
        baseUrl: form.baseUrl,
        apiKey: form.apiKey,
        apiVersion: form.apiVersion,
        enabled: form.enabled,
        allowPrivateNetwork: form.allowPrivateNetwork,
        modelPrefix: form.modelPrefix,
        defaultChatModel: form.defaultChatModel,
        defaultResponsesModel: form.defaultResponsesModel,
        requestExtraBody: form.requestExtraBody,
        timeoutSeconds: Number(form.timeoutSeconds || 30),
        connectTimeoutSeconds: Number(form.connectTimeoutSeconds || 10),
      };
      if (form.id) {
        await api.gateway.updateProvider(form.id, payload);
      } else {
        await api.gateway.createProvider(payload);
      }
      setPanelOpen(false);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save gateway provider');
    } finally {
      setSaving(false);
    }
  };

  const remove = async (provider: GatewayProvider) => {
    if (!window.confirm(`Delete ${provider.name}?`)) return;
    setError('');
    try {
      await api.gateway.deleteProvider(provider.id);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete gateway provider');
    }
  };

  const test = async (provider: GatewayProvider) => {
    setTestingId(provider.id);
    setError('');
    try {
      await api.gateway.testProvider(provider.id);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to test gateway provider');
    } finally {
      setTestingId(null);
    }
  };

  return (
    <div className="h-full overflow-auto">
      <div className="px-6 py-5 border-b" style={{ borderColor: 'var(--color-border)' }}>
        <div className="flex items-center justify-between gap-4">
          <div>
            <h2 className="text-lg font-semibold">Gateway</h2>
            <div className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
              {providers.length} providers configured
            </div>
          </div>
          <button
            onClick={openCreate}
            className="inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            <Plus size={16} />
            Provider
          </button>
        </div>
      </div>

      <div className="p-6">
        {error && (
          <div className="mb-4 px-4 py-3 rounded-lg text-sm" style={{ background: '#ef444420', color: 'var(--color-error)' }}>
            {error}
          </div>
        )}

        <div className="rounded-lg border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
          <table className="w-full text-sm">
            <thead style={{ background: 'var(--color-bg-secondary)' }}>
              <tr className="text-left" style={{ color: 'var(--color-text-secondary)' }}>
                <th className="px-4 py-3 font-medium">Provider</th>
                <th className="px-4 py-3 font-medium">Route Prefix</th>
                <th className="px-4 py-3 font-medium">Base URL</th>
                <th className="px-4 py-3 font-medium">Key</th>
                <th className="px-4 py-3 font-medium">Network</th>
                <th className="px-4 py-3 font-medium">Status</th>
                <th className="px-4 py-3 font-medium text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={7} className="px-4 py-10 text-center" style={{ color: 'var(--color-text-secondary)' }}>
                    Loading...
                  </td>
                </tr>
              ) : providers.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-4 py-10 text-center" style={{ color: 'var(--color-text-secondary)' }}>
                    No providers configured
                  </td>
                </tr>
              ) : providers.map(provider => (
                <tr key={provider.id} className="border-t" style={{ borderColor: 'var(--color-border)' }}>
                  <td className="px-4 py-3">
                    <div className="font-medium">{provider.name}</div>
                    <div className="text-xs uppercase mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>
                      {provider.type}
                      {provider.enabled === false && ' · disabled'}
                    </div>
                  </td>
                  <td className="px-4 py-3 font-mono text-xs">{provider.modelPrefix || '-'}</td>
                  <td className="px-4 py-3 max-w-sm truncate" title={provider.baseUrl}>{provider.baseUrl}</td>
                  <td className="px-4 py-3">
                    <span className="inline-flex items-center gap-1.5">
                      <KeyRound size={14} style={{ color: provider.hasApiKey ? 'var(--color-success)' : 'var(--color-text-secondary)' }} />
                      {provider.apiKeyMasked || 'none'}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-xs" style={{ color: provider.allowPrivateNetwork ? 'var(--color-warning)' : 'var(--color-text-secondary)' }}>
                    {provider.allowPrivateNetwork ? 'private' : 'public'}
                  </td>
                  <td className="px-4 py-3">
                    {provider.lastTestStatus === 'ok' ? (
                      <span className="inline-flex items-center gap-1.5 text-xs" style={{ color: 'var(--color-success)' }}>
                        <CheckCircle2 size={14} /> OK
                      </span>
                    ) : provider.lastTestStatus === 'failed' ? (
                      <span className="inline-flex items-center gap-1.5 text-xs" style={{ color: 'var(--color-error)' }} title={provider.lastTestMessage}>
                        <CircleAlert size={14} /> Failed
                      </span>
                    ) : (
                      <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Untested</span>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center justify-end gap-1">
                      <IconButton title="Test" onClick={() => test(provider)} disabled={testingId === provider.id}>
                        <RefreshCw size={15} className={testingId === provider.id ? 'animate-spin' : ''} />
                      </IconButton>
                      <IconButton title="Edit" onClick={() => openEdit(provider)}>
                        <Pencil size={15} />
                      </IconButton>
                      <IconButton title="Delete" onClick={() => remove(provider)}>
                        <Trash2 size={15} />
                      </IconButton>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {panelOpen && (
        <div className="fixed inset-0 z-40 flex justify-end" style={{ background: 'rgba(0,0,0,0.28)' }}>
          <div className="h-full w-full max-w-xl overflow-auto border-l" style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}>
            <div className="sticky top-0 z-10 flex items-center justify-between px-5 py-4 border-b" style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}>
              <div className="flex items-center gap-2">
                <PlugZap size={18} style={{ color: 'var(--color-primary)' }} />
                <h3 className="font-semibold">{form.id ? 'Edit Provider' : 'New Provider'}</h3>
              </div>
              <button className="p-2 rounded-lg cursor-pointer" onClick={() => setPanelOpen(false)} title="Close">
                <X size={18} />
              </button>
            </div>

            <div className="p-5 space-y-4">
              <Field label="Name">
                <input className={inputClass} style={inputStyle} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} />
              </Field>

              <div className="grid grid-cols-2 gap-4">
                <Field label="Type">
                  <select className={inputClass} style={inputStyle} value={form.type} onChange={e => changeType(e.target.value)}>
                    {PROVIDER_TYPES.map(type => <option key={type.value} value={type.value}>{type.label}</option>)}
                  </select>
                </Field>
                <Field label="Enabled">
                  <label className="h-10 flex items-center gap-2 px-3 rounded-lg" style={{ background: 'var(--color-bg-tertiary)' }}>
                    <input type="checkbox" checked={form.enabled} onChange={e => setForm({ ...form, enabled: e.target.checked })} />
                    <span className="text-sm">Accept traffic</span>
                  </label>
                </Field>
              </div>

              <Field label="Network">
                <label className="h-10 flex items-center gap-2 px-3 rounded-lg" style={{ background: 'var(--color-bg-tertiary)' }}>
                  <input type="checkbox" checked={form.allowPrivateNetwork} onChange={e => setForm({ ...form, allowPrivateNetwork: e.target.checked })} />
                  <span className="text-sm">Allow private hosts</span>
                </label>
              </Field>

              <Field label="Base URL">
                <input
                  className={inputClass}
                  style={inputStyle}
                  value={form.baseUrl}
                  placeholder={selectedType.baseUrl || 'https://...'}
                  onChange={e => setForm({ ...form, baseUrl: e.target.value })}
                />
              </Field>

              <Field label={form.id ? 'API Key (leave empty to keep current)' : 'API Key'}>
                <input
                  className={inputClass}
                  style={inputStyle}
                  type="password"
                  value={form.apiKey}
                  onChange={e => setForm({ ...form, apiKey: e.target.value })}
                />
              </Field>

              <div className="grid grid-cols-2 gap-4">
                <Field label="Model Prefix">
                  <input className={inputClass} style={inputStyle} value={form.modelPrefix} onChange={e => setForm({ ...form, modelPrefix: e.target.value })} />
                </Field>
                <Field label="API Version">
                  <input className={inputClass} style={inputStyle} value={form.apiVersion} onChange={e => setForm({ ...form, apiVersion: e.target.value })} />
                </Field>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <Field label="Chat Model">
                  <input className={inputClass} style={inputStyle} value={form.defaultChatModel} onChange={e => setForm({ ...form, defaultChatModel: e.target.value })} />
                </Field>
                <Field label="Responses Model">
                  <input className={inputClass} style={inputStyle} value={form.defaultResponsesModel} onChange={e => setForm({ ...form, defaultResponsesModel: e.target.value })} />
                </Field>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <Field label="Timeout Seconds">
                  <input className={inputClass} style={inputStyle} type="number" min={1} value={form.timeoutSeconds} onChange={e => setForm({ ...form, timeoutSeconds: e.target.value })} />
                </Field>
                <Field label="Connect Timeout">
                  <input className={inputClass} style={inputStyle} type="number" min={1} value={form.connectTimeoutSeconds} onChange={e => setForm({ ...form, connectTimeoutSeconds: e.target.value })} />
                </Field>
              </div>

              <Field label="Extra Body JSON">
                <textarea
                  className={`${inputClass} min-h-24 font-mono`}
                  style={inputStyle}
                  value={form.requestExtraBody}
                  onChange={e => setForm({ ...form, requestExtraBody: e.target.value })}
                />
              </Field>

              <div className="flex justify-end gap-2 pt-2">
                <button
                  onClick={() => setPanelOpen(false)}
                  className="px-4 py-2 rounded-lg text-sm font-medium cursor-pointer"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                  Cancel
                </button>
                <button
                  onClick={save}
                  disabled={saving || !form.name || !form.type || !form.baseUrl}
                  className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
                  style={{ background: 'var(--color-primary)' }}>
                  <Save size={16} />
                  {saving ? 'Saving...' : 'Save'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function IconButton({ title, onClick, disabled, children }: { title: string; onClick: () => void; disabled?: boolean; children: ReactNode }) {
  return (
    <button
      title={title}
      onClick={onClick}
      disabled={disabled}
      className="p-2 rounded-lg cursor-pointer disabled:opacity-50"
      style={{ color: 'var(--color-text-secondary)' }}>
      {children}
    </button>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
      {children}
    </label>
  );
}

const inputClass = 'w-full h-10 px-3 py-2 rounded-lg text-sm border outline-none';
const inputStyle = {
  background: 'var(--color-bg-tertiary)',
  borderColor: 'var(--color-border)',
  color: 'var(--color-text)',
};
