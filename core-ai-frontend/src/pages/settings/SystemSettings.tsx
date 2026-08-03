import { useEffect, useMemo, useRef, useState } from 'react';
import { CheckCircle2, ChevronDown, CircleAlert, RefreshCw, Save, Settings } from 'lucide-react';
import { api, type GatewayModel, type SystemSettings as SystemSettingsData } from '../../api/client';

export default function SystemSettings() {
  const [settings, setSettings] = useState<SystemSettingsData | null>(null);
  const [models, setModels] = useState<GatewayModel[]>([]);
  const [memoryExtractionModel, setMemoryExtractionModel] = useState('');
  const [llmModel, setLlmModel] = useState('');
  const [llmMultiModalModel, setLlmMultiModalModel] = useState('');
  const [captionImageModel, setCaptionImageModel] = useState('');
  const [imageGenerationModel, setImageGenerationModel] = useState('');
  const [videoGenerationModel, setVideoGenerationModel] = useState('');
  const [videoUnderstandingModel, setVideoUnderstandingModel] = useState('');
  const [azureBlobAccountName, setAzureBlobAccountName] = useState('');
  const [azureBlobAccountKey, setAzureBlobAccountKey] = useState('');
  const [azureBlobMultimodalContainer, setAzureBlobMultimodalContainer] = useState('');
  const [azureBlobPublicBaseUrl, setAzureBlobPublicBaseUrl] = useState('');
  const [azureSpeechKey, setAzureSpeechKey] = useState('');
  const [azureSpeechRegion, setAzureSpeechRegion] = useState('');
  const [azureSpeechEndpoint, setAzureSpeechEndpoint] = useState('');
  const [githubAppId, setGithubAppId] = useState('');
  const [githubAppInstallationId, setGithubAppInstallationId] = useState('');
  const [githubAppPrivateKey, setGithubAppPrivateKey] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const savedTimer = useRef<number | null>(null);

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
      setCaptionImageModel(settingsResponse.caption_image_model || '');
      setImageGenerationModel(settingsResponse.image_generation_model || '');
      setVideoGenerationModel(settingsResponse.video_generation_model || '');
      setVideoUnderstandingModel(settingsResponse.video_understanding_model || '');
      setAzureBlobAccountName(settingsResponse.azure_blob_account_name || '');
      setAzureBlobAccountKey('');
      setAzureBlobMultimodalContainer(settingsResponse.azure_blob_multimodal_container || '');
      setAzureBlobPublicBaseUrl(settingsResponse.azure_blob_public_base_url || '');
      setAzureSpeechKey('');
      setAzureSpeechRegion(settingsResponse.azure_speech_region || '');
      setAzureSpeechEndpoint(settingsResponse.azure_speech_endpoint || '');
      setGithubAppId(settingsResponse.github_app_id || '');
      setGithubAppInstallationId(settingsResponse.github_app_installation_id || '');
      setGithubAppPrivateKey('');
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
        caption_image_model: captionImageModel.trim() || null,
        image_generation_model: imageGenerationModel.trim() || null,
        video_generation_model: videoGenerationModel.trim() || null,
        video_understanding_model: videoUnderstandingModel.trim() || null,
        azure_blob_account_name: azureBlobAccountName.trim() || null,
        azure_blob_account_key: azureBlobAccountKey.trim() || null,
        azure_blob_multimodal_container: azureBlobMultimodalContainer.trim() || null,
        azure_blob_public_base_url: azureBlobPublicBaseUrl.trim() || null,
        azure_speech_key: azureSpeechKey.trim() || null,
        azure_speech_region: azureSpeechRegion.trim() || null,
        azure_speech_endpoint: azureSpeechEndpoint.trim() || null,
        github_app_id: githubAppId.trim() || null,
        github_app_installation_id: githubAppInstallationId.trim() || null,
        github_app_private_key: githubAppPrivateKey.trim() || null,
      });
      setSettings(response);
      setMemoryExtractionModel(response.memory_extraction_model || '');
      setLlmModel(response.llm_model || '');
      setLlmMultiModalModel(response.llm_model_multimodal || '');
      setCaptionImageModel(response.caption_image_model || '');
      setImageGenerationModel(response.image_generation_model || '');
      setVideoGenerationModel(response.video_generation_model || '');
      setVideoUnderstandingModel(response.video_understanding_model || '');
      setAzureBlobAccountName(response.azure_blob_account_name || '');
      setAzureBlobAccountKey('');
      setAzureBlobMultimodalContainer(response.azure_blob_multimodal_container || '');
      setAzureBlobPublicBaseUrl(response.azure_blob_public_base_url || '');
      setAzureSpeechKey('');
      setAzureSpeechRegion(response.azure_speech_region || '');
      setAzureSpeechEndpoint(response.azure_speech_endpoint || '');
      setGithubAppId(response.github_app_id || '');
      setGithubAppInstallationId(response.github_app_installation_id || '');
      setGithubAppPrivateKey('');
      setMessage('System settings saved. Changes take effect immediately.');
      setSaved(true);
      if (savedTimer.current) window.clearTimeout(savedTimer.current);
      savedTimer.current = window.setTimeout(() => setSaved(false), 2500);
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
        <button
          onClick={load}
          disabled={saving}
          className="inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50"
          style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
          <RefreshCw size={16} />
          Refresh
        </button>
      </div>

      {error && (
        <div className="mb-4 rounded-lg border px-4 py-3 text-sm flex items-center gap-2" style={{ borderColor: '#ef4444', background: 'rgba(239,68,68,0.08)', color: '#ef4444' }}>
          <CircleAlert size={16} className="shrink-0" />
          {error}
        </div>
      )}
      {message && (
        <div className="mb-4 rounded-lg border px-4 py-3 text-sm flex items-start gap-2" style={{ borderColor: '#22c55e', background: 'rgba(34,197,94,0.08)', color: '#22c55e' }}>
          <CheckCircle2 size={16} className="shrink-0 mt-0.5" />
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

      <section className="rounded-xl border mt-6" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="p-5 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <h2 className="font-semibold">Default Media &amp; Tool Models</h2>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Default models used by caption_image, image generation, video generation and video understanding
            when no model is specified on the agent. Leave empty to fall back to gateway routing.
          </p>
        </div>
        <div className="p-5 space-y-5">
          <label className="block">
            <span className="block text-sm font-medium mb-2">Caption image model</span>
            <ModelSelect
              value={captionImageModel}
              models={chatModels}
              defaultModel={settings?.default_caption_image_model}
              onChange={setCaptionImageModel}
            />
          </label>
          <label className="block">
            <span className="block text-sm font-medium mb-2">Image generation model</span>
            <ModelSelect
              value={imageGenerationModel}
              models={chatModels}
              defaultModel={settings?.default_image_generation_model}
              onChange={setImageGenerationModel}
            />
          </label>
          <label className="block">
            <span className="block text-sm font-medium mb-2">Video generation model</span>
            <ModelSelect
              value={videoGenerationModel}
              models={chatModels}
              defaultModel={settings?.default_video_generation_model}
              onChange={setVideoGenerationModel}
            />
          </label>
          <label className="block">
            <span className="block text-sm font-medium mb-2">Video understanding model</span>
            <ModelSelect
              value={videoUnderstandingModel}
              models={chatModels}
              defaultModel={settings?.default_video_understanding_model}
              onChange={setVideoUnderstandingModel}
            />
          </label>
        </div>
      </section>

      {chatModels.length === 0 && (
        <div className="mt-6 rounded-lg border px-4 py-3 text-sm" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
          No enabled chat gateway models are available. Add or enable a chat model under Settings → Gateway first.
        </div>
      )}

      <section className="rounded-xl border mt-6" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="p-5 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <h2 className="font-semibold">Azure Blob Storage</h2>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Credentials for user file uploads. Leave the account key blank to keep the existing value.
            Changes take effect immediately.
          </p>
        </div>
        <div className="p-5 space-y-4">
          <label className="block">
            <span className="block text-sm font-medium mb-2">Account name</span>
            <input
              type="text"
              value={azureBlobAccountName}
              onChange={e => setAzureBlobAccountName(e.target.value)}
              placeholder="e.g. fbrdevbostorage"
              className="w-full h-10 px-3 py-2 rounded-lg text-sm border outline-none"
              style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
            />
          </label>
          <label className="block">
            <span className="block text-sm font-medium mb-2">Account key</span>
            <input
              type="password"
              value={azureBlobAccountKey}
              onChange={e => setAzureBlobAccountKey(e.target.value)}
              placeholder={settings?.has_azure_blob_account_key ? 'Already configured (leave blank to keep)' : 'Not configured'}
              className="w-full h-10 px-3 py-2 rounded-lg text-sm border outline-none"
              style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
            />
          </label>
          <label className="block">
            <span className="block text-sm font-medium mb-2">Multimodal container</span>
            <input
              type="text"
              value={azureBlobMultimodalContainer}
              onChange={e => setAzureBlobMultimodalContainer(e.target.value)}
              placeholder="uploads"
              className="w-full h-10 px-3 py-2 rounded-lg text-sm border outline-none"
              style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
            />
          </label>
          <label className="block">
            <span className="block text-sm font-medium mb-2">Public base URL</span>
            <input
              type="text"
              value={azureBlobPublicBaseUrl}
              onChange={e => setAzureBlobPublicBaseUrl(e.target.value)}
              placeholder="https://<account>.blob.core.windows.net"
              className="w-full h-10 px-3 py-2 rounded-lg text-sm border outline-none"
              style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
            />
          </label>
        </div>
      </section>

      <section className="rounded-xl border mt-6" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="p-5 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <h2 className="font-semibold">Azure Speech</h2>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Credentials for speech token issuance. Leave the key blank to keep the existing value.
            Changes take effect immediately.
          </p>
        </div>
        <div className="p-5 space-y-4">
          <label className="block">
            <span className="block text-sm font-medium mb-2">Endpoint</span>
            <input
              type="text"
              value={azureSpeechEndpoint}
              onChange={e => setAzureSpeechEndpoint(e.target.value)}
              placeholder="https://xxx.cognitiveservices.azure.com/"
              className="w-full h-10 px-3 py-2 rounded-lg text-sm border outline-none"
              style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
            />
          </label>
          <label className="block">
            <span className="block text-sm font-medium mb-2">Key</span>
            <input
              type="password"
              value={azureSpeechKey}
              onChange={e => setAzureSpeechKey(e.target.value)}
              placeholder={settings?.has_azure_speech_key ? 'Already configured (leave blank to keep)' : 'Not configured'}
              className="w-full h-10 px-3 py-2 rounded-lg text-sm border outline-none"
              style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
            />
          </label>
          <label className="block">
            <span className="block text-sm font-medium mb-2">Region</span>
            <input
              type="text"
              value={azureSpeechRegion}
              onChange={e => setAzureSpeechRegion(e.target.value)}
              placeholder="eastus"
              className="w-full h-10 px-3 py-2 rounded-lg text-sm border outline-none"
              style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
            />
          </label>
        </div>
      </section>

      <section className="rounded-xl border mt-6" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="p-5 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <h2 className="font-semibold">GitHub App</h2>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Credentials for GitHub installation token generation. Leave the private key blank to keep the existing value.
            Changes take effect immediately.
          </p>
        </div>
        <div className="p-5 space-y-4">
          <label className="block">
            <span className="block text-sm font-medium mb-2">App ID</span>
            <input
              type="text"
              value={githubAppId}
              onChange={e => setGithubAppId(e.target.value)}
              placeholder="GitHub App ID"
              className="w-full h-10 px-3 py-2 rounded-lg text-sm border outline-none"
              style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
            />
          </label>
          <label className="block">
            <span className="block text-sm font-medium mb-2">Installation ID</span>
            <input
              type="text"
              value={githubAppInstallationId}
              onChange={e => setGithubAppInstallationId(e.target.value)}
              placeholder="GitHub App Installation ID"
              className="w-full h-10 px-3 py-2 rounded-lg text-sm border outline-none"
              style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
            />
          </label>
          <label className="block">
            <span className="block text-sm font-medium mb-2">Private key</span>
            <textarea
              value={githubAppPrivateKey}
              onChange={e => setGithubAppPrivateKey(e.target.value)}
              placeholder={settings?.has_github_app_private_key ? 'Already configured (leave blank to keep)' : '-----BEGIN RSA PRIVATE KEY-----'}
              rows={6}
              className="w-full px-3 py-2 rounded-lg text-sm border outline-none font-mono"
              style={{ background: 'var(--color-bg-tertiary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
            />
          </label>
        </div>
      </section>

      <div className="flex justify-end mt-6">
        <button
          onClick={save}
          disabled={saving}
          className="inline-flex items-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50 transition-colors"
          style={{ background: saved ? '#22c55e' : 'var(--color-primary)' }}>
          {saved ? <CheckCircle2 size={16} /> : <Save size={16} />}
          {saving ? 'Saving...' : saved ? 'Saved' : 'Save Settings'}
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
