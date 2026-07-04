import { useEffect, useMemo, useState } from 'react';
import { RefreshCw, Save, Settings } from 'lucide-react';
import { api, type GatewayModel, type SystemSettings as SystemSettingsData } from '../../api/client';

export default function SystemSettings() {
  const [settings, setSettings] = useState<SystemSettingsData | null>(null);
  const [models, setModels] = useState<GatewayModel[]>([]);
  const [memoryExtractionModel, setMemoryExtractionModel] = useState('');
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
      });
      setSettings(response);
      setMemoryExtractionModel(response.memory_extraction_model || '');
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
            <select
              value={memoryExtractionModel}
              onChange={event => setMemoryExtractionModel(event.target.value)}
              className="input w-full"
            >
              <option value="">Default ({settings?.default_memory_extraction_model || 'not configured'})</option>
              {chatModels.map(model => (
                <option key={model.id} value={model.modelId}>
                  {model.displayName || model.modelId}{model.providerName ? ` · ${model.providerName}` : ''}
                </option>
              ))}
            </select>
            <span className="block text-xs mt-2" style={{ color: 'var(--color-text-secondary)' }}>
              Only enabled gateway models that support chat completions can be selected.
            </span>
          </label>

          <div className="rounded-lg p-4 text-sm" style={{ background: 'var(--color-bg-tertiary)' }}>
            <div style={{ color: 'var(--color-text-secondary)' }}>Effective model</div>
            <div className="font-mono mt-1">{effectiveModel || 'Not configured'}</div>
          </div>

          {chatModels.length === 0 && (
            <div className="rounded-lg border px-4 py-3 text-sm" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
              No enabled chat gateway models are available. Add or enable a chat model under Settings → Gateway first.
            </div>
          )}

          <div className="flex justify-end">
            <button onClick={save} className="btn-primary flex items-center gap-2" disabled={saving}>
              <Save size={16} />
              {saving ? 'Saving...' : 'Save Settings'}
            </button>
          </div>
        </div>
      </section>
    </div>
  );
}
