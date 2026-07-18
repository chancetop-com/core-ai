import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { CheckCircle2, CircleAlert, KeyRound, Pencil, PlugZap, Plus, RefreshCw, Save, Star, Trash2, X } from 'lucide-react';
import { api, type GatewayDiscoveredModel, type GatewayModel, type GatewayModelRequest, type GatewayProvider, type GatewayProviderRequest } from '../../api/client';

const PROVIDER_TYPES = [
  { value: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', prefix: 'openai/' },
  { value: 'azure', label: 'Azure OpenAI', baseUrl: '', prefix: 'azure/' },
  { value: 'litellm', label: 'LiteLLM', baseUrl: '', prefix: 'litellm/' },
  { value: 'deepseek', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', prefix: 'deepseek/' },
  { value: 'qwen', label: 'Qwen', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', prefix: 'qwen/' },
  { value: 'openrouter', label: 'OpenRouter', baseUrl: 'https://openrouter.ai/api/v1', prefix: 'openrouter/' },
  { value: 'openai-compatible', label: 'OpenAI Compatible', baseUrl: '', prefix: '' },
  { value: 'gemini', label: 'Google Gemini', baseUrl: 'https://generativelanguage.googleapis.com/v1beta', prefix: 'google/' },
];

const MEDIA_PROTOCOLS = [
  { value: '', label: 'Automatic' },
  { value: 'OPENAI_IMAGES', label: 'OpenAI Images' },
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI Compatible' },
  { value: 'GEMINI_GENERATE_CONTENT', label: 'Gemini generateContent' },
  { value: 'VERTEX_GEMINI_GENERATE_CONTENT', label: 'Vertex Gemini generateContent' },
  { value: 'VERTEX_GEMINI_INTERACTIONS', label: 'Vertex Gemini Interactions (video)' },
];

const GOOGLE_AUTH_TYPES = [
  { value: 'API_KEY', label: 'API Key' },
  { value: 'GOOGLE_APPLICATION_DEFAULT_CREDENTIALS', label: 'Application Default Credentials' },
  { value: 'GOOGLE_SERVICE_ACCOUNT_JSON', label: 'Service Account JSON' },
];

const ENDPOINT_TYPES = [
  { value: 'chat.completions', label: 'Chat' },
  { value: 'responses', label: 'Responses' },
  { value: 'image.generations', label: 'Image Generation' },
  { value: 'image.edits', label: 'Image Edit' },
  { value: 'video.generations', label: 'Video' },
];

type Tab = 'providers' | 'models';

type ProviderFormState = {
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
  defaultImageModel: string;
  defaultVideoModel: string;
  mediaProtocol: string;
  mediaAuthType: string;
  googleCredentialsJson: string;
  hasGoogleCredentials: boolean;
  vertexProjectId: string;
  vertexLocation: string;
  requestExtraBody: string;
  timeoutSeconds: string;
  connectTimeoutSeconds: string;
};

type ModelFormState = {
  id?: string;
  modelId: string;
  displayName: string;
  providerId: string;
  upstreamModel: string;
  endpointTypes: string[];
  enabled: boolean;
  priority: string;
  contextWindow: string;
  supportsStream: boolean;
  supportsTools: boolean;
  supportsVision: boolean;
  inputPricePer1MTokens: string;
  outputPricePer1MTokens: string;
};

const emptyProviderForm: ProviderFormState = {
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
  defaultImageModel: '',
  defaultVideoModel: '',
  mediaProtocol: '',
  mediaAuthType: 'API_KEY',
  googleCredentialsJson: '',
  hasGoogleCredentials: false,
  vertexProjectId: '',
  vertexLocation: 'us-central1',
  requestExtraBody: '',
  timeoutSeconds: '30',
  connectTimeoutSeconds: '10',
};

const emptyModelForm: ModelFormState = {
  modelId: '',
  displayName: '',
  providerId: '',
  upstreamModel: '',
  endpointTypes: ['chat.completions'],
  enabled: true,
  priority: '100',
  contextWindow: '',
  supportsStream: true,
  supportsTools: false,
  supportsVision: false,
  inputPricePer1MTokens: '',
  outputPricePer1MTokens: '',
};

export default function GatewayProviders() {
  const [activeTab, setActiveTab] = useState<Tab>('providers');
  const [providers, setProviders] = useState<GatewayProvider[]>([]);
  const [models, setModels] = useState<GatewayModel[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [providerPanelOpen, setProviderPanelOpen] = useState(false);
  const [modelPanelOpen, setModelPanelOpen] = useState(false);
  const [discoveryPanelOpen, setDiscoveryPanelOpen] = useState(false);
  const [discovering, setDiscovering] = useState(false);
  const [discoveryProviderId, setDiscoveryProviderId] = useState('');
  const [discoveredModels, setDiscoveredModels] = useState<GatewayDiscoveredModel[]>([]);
  const [selectedDiscoveredModels, setSelectedDiscoveredModels] = useState<Set<string>>(new Set());
  const [providerForm, setProviderForm] = useState<ProviderFormState>(emptyProviderForm);
  const [modelForm, setModelForm] = useState<ModelFormState>(emptyModelForm);

  const selectedType = useMemo(
    () => PROVIDER_TYPES.find(type => type.value === providerForm.type) || PROVIDER_TYPES[0],
    [providerForm.type],
  );

  const providerById = useMemo(() => {
    const map = new Map<string, GatewayProvider>();
    providers.forEach(provider => map.set(provider.id, provider));
    return map;
  }, [providers]);

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const [providerResponse, modelResponse] = await Promise.all([
        api.gateway.listProviders(),
        api.gateway.listModels(),
      ]);
      setProviders(providerResponse.providers || []);
      setModels(modelResponse.models || []);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load gateway config');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const openCreateProvider = () => {
    setProviderForm(emptyProviderForm);
    setProviderPanelOpen(true);
  };

  const openEditProvider = (provider: GatewayProvider) => {
    setProviderForm({
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
       defaultImageModel: provider.defaultImageModel || '',
       defaultVideoModel: provider.defaultVideoModel || '',
       mediaProtocol: provider.mediaProtocol || '',
       mediaAuthType: provider.mediaAuthType || 'API_KEY',
       googleCredentialsJson: '',
       hasGoogleCredentials: provider.hasGoogleCredentials === true,
       vertexProjectId: provider.vertexProjectId || '',
       vertexLocation: provider.vertexLocation || 'us-central1',
       requestExtraBody: provider.requestExtraBody || '',
      timeoutSeconds: String(provider.timeoutSeconds || 30),
      connectTimeoutSeconds: String(provider.connectTimeoutSeconds || 10),
    });
    setProviderPanelOpen(true);
  };

  const changeType = (type: string) => {
    const preset = PROVIDER_TYPES.find(item => item.value === type) || PROVIDER_TYPES[0];
    setProviderForm(current => ({
      ...current,
      type,
      baseUrl: current.id ? current.baseUrl : preset.baseUrl,
      modelPrefix: current.id ? current.modelPrefix : preset.prefix,
    }));
  };

  const saveProvider = async () => {
    setSaving(true);
    setError('');
    try {
      const payload: GatewayProviderRequest = {
        name: providerForm.name,
        type: providerForm.type,
        baseUrl: providerForm.baseUrl,
        apiKey: providerForm.apiKey,
        apiVersion: providerForm.apiVersion,
        enabled: providerForm.enabled,
        allowPrivateNetwork: providerForm.allowPrivateNetwork,
        modelPrefix: providerForm.modelPrefix,
        defaultChatModel: providerForm.defaultChatModel,
         defaultResponsesModel: providerForm.defaultResponsesModel,
         defaultImageModel: providerForm.defaultImageModel,
         defaultVideoModel: providerForm.defaultVideoModel,
         mediaProtocol: providerForm.mediaProtocol,
         mediaAuthType: providerForm.mediaAuthType,
         googleCredentialsJson: providerForm.googleCredentialsJson,
         vertexProjectId: providerForm.vertexProjectId,
         vertexLocation: providerForm.vertexLocation,
         requestExtraBody: providerForm.requestExtraBody,
        timeoutSeconds: Number(providerForm.timeoutSeconds || 30),
        connectTimeoutSeconds: Number(providerForm.connectTimeoutSeconds || 10),
      };
      if (providerForm.id) {
        await api.gateway.updateProvider(providerForm.id, payload);
      } else {
        await api.gateway.createProvider(payload);
      }
      setProviderPanelOpen(false);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save gateway provider');
    } finally {
      setSaving(false);
    }
  };

  const removeProvider = async (provider: GatewayProvider) => {
    const modelCount = models.filter(model => model.providerId === provider.id).length;
    const confirmMessage = modelCount > 0
      ? `Delete ${provider.name}? This will also delete ${modelCount} model${modelCount === 1 ? '' : 's'} configured under this provider.`
      : `Delete ${provider.name}?`;
    if (!window.confirm(confirmMessage)) return;
    setError('');
    try {
      await api.gateway.deleteProvider(provider.id, modelCount > 0);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete gateway provider');
    }
  };

  const testProvider = async (provider: GatewayProvider) => {
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

  const openDiscoverModels = () => {
    const providerId = providers[0]?.id || '';
    setDiscoveryProviderId(providerId);
    setDiscoveredModels([]);
    setSelectedDiscoveredModels(new Set());
    setDiscoveryPanelOpen(true);
    if (providerId) void discoverModels(providerId);
  };

  const openEditModel = (model: GatewayModel) => {
    setModelForm({
      id: model.id,
      modelId: model.modelId || '',
      displayName: model.displayName || '',
      providerId: model.providerId || '',
      upstreamModel: model.upstreamModel || '',
      endpointTypes: model.endpointTypes?.length ? model.endpointTypes : ['chat.completions'],
      enabled: model.enabled !== false,
      priority: String(model.priority ?? 100),
      contextWindow: model.contextWindow ? String(model.contextWindow) : '',
      supportsStream: model.supportsStream === true,
      supportsTools: model.supportsTools === true,
      supportsVision: model.supportsVision === true,
      inputPricePer1MTokens: model.inputPricePer1MTokens == null ? '' : String(model.inputPricePer1MTokens),
      outputPricePer1MTokens: model.outputPricePer1MTokens == null ? '' : String(model.outputPricePer1MTokens),
    });
    setModelPanelOpen(true);
  };

  const openCreateModel = () => {
    setModelForm({
      ...emptyModelForm,
      providerId: providers[0]?.id || '',
    });
    setModelPanelOpen(true);
  };

  const saveModel = async () => {
    setSaving(true);
    setError('');
    try {
      const payload: GatewayModelRequest = {
        modelId: modelForm.modelId,
        displayName: modelForm.displayName,
        providerId: modelForm.providerId,
        upstreamModel: modelForm.upstreamModel,
        endpointTypes: modelForm.endpointTypes,
        enabled: modelForm.enabled,
        priority: optionalNumber(modelForm.priority, 'Priority'),
      };
      if (modelForm.id) {
        await api.gateway.updateModel(modelForm.id, payload);
      } else {
        await api.gateway.createModel(payload);
      }
      setModelPanelOpen(false);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save gateway model');
    } finally {
      setSaving(false);
    }
  };

  const discoverModels = async (providerId = discoveryProviderId) => {
    if (!providerId) return;
    setDiscovering(true);
    setError('');
    try {
      const response = await api.gateway.discoverModels(providerId);
      const rows = response.models || [];
      setDiscoveredModels(rows);
      setSelectedDiscoveredModels(new Set(rows.filter(model => !model.imported).map(model => model.id)));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to sync provider models');
    } finally {
      setDiscovering(false);
    }
  };

  const changeDiscoveryProvider = (providerId: string) => {
    setDiscoveryProviderId(providerId);
    setDiscoveredModels([]);
    setSelectedDiscoveredModels(new Set());
    if (providerId) void discoverModels(providerId);
  };

  const toggleDiscoveredModel = (modelId: string, checked: boolean) => {
    setSelectedDiscoveredModels(current => {
      const next = new Set(current);
      if (checked) {
        next.add(modelId);
      } else {
        next.delete(modelId);
      }
      return next;
    });
  };

  const toggleSelectAllDiscoveredModels = () => {
    const nonImported = discoveredModels.filter(model => !model.imported);
    if (nonImported.length === 0) return;
    if (selectedDiscoveredModels.size === nonImported.length) {
      setSelectedDiscoveredModels(new Set());
    } else {
      setSelectedDiscoveredModels(new Set(nonImported.map(model => model.id)));
    }
  };

  const importDiscoveredModels = async () => {
    if (!discoveryProviderId || selectedDiscoveredModels.size === 0) return;
    setSaving(true);
    setError('');
    try {
      await api.gateway.importModels(discoveryProviderId, {
        models: Array.from(selectedDiscoveredModels).map(upstreamModel => ({ upstreamModel })),
      });
      setDiscoveryPanelOpen(false);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to import provider models');
    } finally {
      setSaving(false);
    }
  };

  const removeModel = async (model: GatewayModel) => {
    if (!window.confirm(`Delete ${model.modelId}?`)) return;
    setError('');
    try {
      await api.gateway.deleteModel(model.id);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete gateway model');
    }
  };

  const setDefaultModel = async (model: GatewayModel) => {
    setError('');
    try {
      await api.gateway.setDefaultModel(model.id);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to set default model');
    }
  };

  const toggleEndpoint = (endpoint: string, checked: boolean) => {
    setModelForm(current => {
      const next = checked
        ? [...current.endpointTypes.filter(value => value !== endpoint), endpoint]
        : current.endpointTypes.filter(value => value !== endpoint);
      return { ...current, endpointTypes: next.length ? next : [endpoint] };
    });
  };

  return (
    <div className="h-full overflow-auto">
      <div className="px-6 py-5 border-b" style={{ borderColor: 'var(--color-border)' }}>
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <h2 className="text-lg font-semibold">Gateway</h2>
            <div className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
              {providers.length} providers · {models.length} models
            </div>
          </div>
          <div className="flex items-center gap-2">
            <SegmentedButton active={activeTab === 'providers'} onClick={() => setActiveTab('providers')}>Providers</SegmentedButton>
            <SegmentedButton active={activeTab === 'models'} onClick={() => setActiveTab('models')}>Models</SegmentedButton>
            {activeTab === 'providers' ? (
              <button
                onClick={openCreateProvider}
                className="inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
                style={{ background: 'var(--color-primary)' }}>
                <Plus size={16} />
                Provider
              </button>
            ) : (
              <>
                <button
                  onClick={openCreateModel}
                  className="inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
                  style={{ background: 'var(--color-primary)' }}>
                  <Plus size={16} />
                  Model
                </button>
                <button
                  onClick={openDiscoverModels}
                  disabled={providers.length === 0}
                  className="inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                  <RefreshCw size={16} />
                  Sync
                </button>
              </>
            )}
          </div>
        </div>
      </div>

      <div className="p-6">
        {error && (
          <div className="mb-4 px-4 py-3 rounded-lg text-sm" style={{ background: '#ef444420', color: 'var(--color-error)' }}>
            {error}
          </div>
        )}

        {activeTab === 'providers' ? renderProvidersTable({
          providers,
          loading,
          testingId,
          testProvider,
          openEditProvider,
          removeProvider,
        }) : renderModelsTable({
          models,
          loading,
          providerById,
          openEditModel,
          removeModel,
          setDefaultModel,
        })}
      </div>

      {providerPanelOpen && renderProviderPanel({
        form: providerForm,
        selectedType,
        saving,
        setForm: setProviderForm,
        changeType,
        close: () => setProviderPanelOpen(false),
        save: saveProvider,
      })}

      {modelPanelOpen && renderModelPanel({
        form: modelForm,
        provider: providerById.get(modelForm.providerId),
        providers,
        saving,
        setForm: setModelForm,
        toggleEndpoint,
        close: () => setModelPanelOpen(false),
        save: saveModel,
      })}

      {discoveryPanelOpen && renderDiscoverModelsPanel({
        providers,
        providerId: discoveryProviderId,
        models: discoveredModels,
        selectedModels: selectedDiscoveredModels,
        discovering,
        saving,
        changeProvider: changeDiscoveryProvider,
        discover: discoverModels,
        toggleModel: toggleDiscoveredModel,
        toggleSelectAll: toggleSelectAllDiscoveredModels,
        close: () => setDiscoveryPanelOpen(false),
        importModels: importDiscoveredModels,
      })}
    </div>
  );
}

function renderProvidersTable(props: {
  providers: GatewayProvider[];
  loading: boolean;
  testingId: string | null;
  testProvider: (provider: GatewayProvider) => void;
  openEditProvider: (provider: GatewayProvider) => void;
  removeProvider: (provider: GatewayProvider) => void;
}) {
  const { providers, loading, testingId, testProvider, openEditProvider, removeProvider } = props;
  return (
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
            <EmptyRow colSpan={7}>Loading...</EmptyRow>
          ) : providers.length === 0 ? (
            <EmptyRow colSpan={7}>No providers configured</EmptyRow>
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
                  <IconButton title="Test" onClick={() => testProvider(provider)} disabled={testingId === provider.id}>
                    <RefreshCw size={15} className={testingId === provider.id ? 'animate-spin' : ''} />
                  </IconButton>
                  <IconButton title="Edit" onClick={() => openEditProvider(provider)}>
                    <Pencil size={15} />
                  </IconButton>
                  <IconButton title="Delete" onClick={() => removeProvider(provider)}>
                    <Trash2 size={15} />
                  </IconButton>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function renderModelsTable(props: {
  models: GatewayModel[];
  loading: boolean;
  providerById: Map<string, GatewayProvider>;
  openEditModel: (model: GatewayModel) => void;
  removeModel: (model: GatewayModel) => void;
  setDefaultModel: (model: GatewayModel) => void;
}) {
  const { models, loading, providerById, openEditModel, removeModel, setDefaultModel } = props;
  return (
    <div className="rounded-lg border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
      <table className="w-full text-sm">
        <thead style={{ background: 'var(--color-bg-secondary)' }}>
          <tr className="text-left" style={{ color: 'var(--color-text-secondary)' }}>
            <th className="px-4 py-3 font-medium">Alias</th>
            <th className="px-4 py-3 font-medium">Display Name</th>
            <th className="px-4 py-3 font-medium">Provider</th>
            <th className="px-4 py-3 font-medium">Official Model</th>
            <th className="px-4 py-3 font-medium">Endpoints</th>
            <th className="px-4 py-3 font-medium">Priority</th>
            <th className="px-4 py-3 font-medium">Capabilities</th>
            <th className="px-4 py-3 font-medium text-right">Actions</th>
          </tr>
        </thead>
        <tbody>
          {loading ? (
            <EmptyRow colSpan={8}>Loading...</EmptyRow>
          ) : models.length === 0 ? (
            <EmptyRow colSpan={8}>No models configured</EmptyRow>
          ) : models.map(model => {
            const provider = providerById.get(model.providerId);
            return (
              <tr key={model.id} className="border-t" style={{ borderColor: 'var(--color-border)' }}>
                <td className="px-4 py-3">
                  <span className="font-medium font-mono">
                    {model.modelId}
                  </span>
                  {model.enabled === false && <span className="text-xs ml-1" style={{ color: 'var(--color-text-secondary)' }}>· disabled</span>}
                </td>
                <td className="px-4 py-3" style={{ color: 'var(--color-text-secondary)' }}>
                  {model.displayName || '-'}
                </td>
                <td className="px-4 py-3">
                  <div>{model.providerName || provider?.name || model.providerId}</div>
                  {provider?.enabled === false && <div className="text-xs" style={{ color: 'var(--color-warning)' }}>provider disabled</div>}
                </td>
                <td className="px-4 py-3 font-mono text-xs">{model.upstreamModel}</td>
                <td className="px-4 py-3">{(model.endpointTypes || []).map(labelEndpoint).join(', ') || '-'}</td>
                <td className="px-4 py-3">{model.priority ?? 100}</td>
                <td className="px-4 py-3 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                  {[
                    model.supportsStream ? 'stream' : null,
                    model.supportsTools ? 'tools' : null,
                    model.supportsVision ? 'vision' : null,
                    model.contextWindow ? `${model.contextWindow} ctx` : null,
                  ].filter(Boolean).join(', ') || '-'}
                </td>
                <td className="px-4 py-3">
                  <div className="flex items-center justify-end gap-1">
                    <IconButton title={model.isDefault ? 'Default model' : 'Set as default'} onClick={() => { if (!model.isDefault) setDefaultModel(model); }}>
                      <Star size={15} style={model.isDefault ? { color: 'var(--color-warning)', fill: 'var(--color-warning)' } : undefined} />
                    </IconButton>
                    <IconButton title="Edit" onClick={() => openEditModel(model)}>
                      <Pencil size={15} />
                    </IconButton>
                    <IconButton title="Delete" onClick={() => removeModel(model)}>
                      <Trash2 size={15} />
                    </IconButton>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function renderProviderPanel(props: {
  form: ProviderFormState;
  selectedType: { baseUrl: string };
  saving: boolean;
  setForm: (form: ProviderFormState) => void;
  changeType: (type: string) => void;
  close: () => void;
  save: () => void;
}) {
  const { form, selectedType, saving, setForm, changeType, close, save } = props;
  return (
    <Panel title={form.id ? 'Edit Provider' : 'New Provider'} close={close}>
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
            <Checkbox checked={form.enabled} onChange={checked => setForm({ ...form, enabled: checked })}>Accept traffic</Checkbox>
          </Field>
        </div>

        <Field label="Network">
          <Checkbox checked={form.allowPrivateNetwork} onChange={checked => setForm({ ...form, allowPrivateNetwork: checked })}>Allow private hosts</Checkbox>
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
           <Field label="Media Protocol">
             <select className={inputClass} style={inputStyle} value={form.mediaProtocol} onChange={e => setForm({ ...form, mediaProtocol: e.target.value })}>
               {MEDIA_PROTOCOLS.map(protocol => <option key={protocol.value} value={protocol.value}>{protocol.label}</option>)}
             </select>
           </Field>
            {isVertexMediaProtocol(form.mediaProtocol) && (
              <Field label="Google Credentials">
               <select className={inputClass} style={inputStyle} value={form.mediaAuthType} onChange={e => setForm({ ...form, mediaAuthType: e.target.value })}>
                 {GOOGLE_AUTH_TYPES.slice(1).map(authType => <option key={authType.value} value={authType.value}>{authType.label}</option>)}
               </select>
             </Field>
           )}
         </div>

          {isVertexMediaProtocol(form.mediaProtocol) && (
            <>
             <div className="grid grid-cols-2 gap-4">
               <Field label="GCP Project ID">
                 <input className={inputClass} style={inputStyle} value={form.vertexProjectId} onChange={e => setForm({ ...form, vertexProjectId: e.target.value })} />
               </Field>
               <Field label="GCP Location">
                 <input className={inputClass} style={inputStyle} value={form.vertexLocation} onChange={e => setForm({ ...form, vertexLocation: e.target.value })} />
               </Field>
             </div>
             {form.mediaAuthType === 'GOOGLE_SERVICE_ACCOUNT_JSON' && (
               <Field label={form.hasGoogleCredentials ? 'Service Account JSON (leave empty to keep current)' : 'Service Account JSON'}>
                 <textarea className={`${inputClass} min-h-36 font-mono`} style={inputStyle} value={form.googleCredentialsJson} onChange={e => setForm({ ...form, googleCredentialsJson: e.target.value })} />
               </Field>
             )}
           </>
         )}

          <div className="grid grid-cols-2 gap-4">
            <Field label="Chat Model">
             <input className={inputClass} style={inputStyle} value={form.defaultChatModel} onChange={e => setForm({ ...form, defaultChatModel: e.target.value })} />
           </Field>
           <Field label="Responses Model">
             <input className={inputClass} style={inputStyle} value={form.defaultResponsesModel} onChange={e => setForm({ ...form, defaultResponsesModel: e.target.value })} />
           </Field>
           <Field label="Image Model">
             <input className={inputClass} style={inputStyle} value={form.defaultImageModel} onChange={e => setForm({ ...form, defaultImageModel: e.target.value })} />
           </Field>
           <Field label="Video Model">
             <input className={inputClass} style={inputStyle} value={form.defaultVideoModel} onChange={e => setForm({ ...form, defaultVideoModel: e.target.value })} />
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

        <PanelActions close={close} save={save} saving={saving} disabled={!form.name || !form.type || !form.baseUrl} />
      </div>
    </Panel>
  );
}

function renderModelPanel(props: {
  form: ModelFormState;
  provider?: GatewayProvider;
  providers: GatewayProvider[];
  saving: boolean;
  setForm: (form: ModelFormState) => void;
  toggleEndpoint: (endpoint: string, checked: boolean) => void;
  close: () => void;
  save: () => void;
}) {
  const { form, provider, providers, saving, setForm, toggleEndpoint, close, save } = props;
  const isCreate = !form.id;
  return (
    <Panel title={isCreate ? 'New Model' : 'Edit Model'} close={close}>
      <div className="p-5 space-y-4">
        {isCreate && (
          <Field label="Provider">
            <select
              className={inputClass}
              style={inputStyle}
              value={form.providerId}
              onChange={e => setForm({ ...form, providerId: e.target.value })}>
              <option value="">Select provider</option>
              {providers.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </Field>
        )}

        <div className="grid grid-cols-2 gap-4">
          <Field label="Alias">
            <input className={inputClass} style={inputStyle} value={form.modelId} onChange={e => setForm({ ...form, modelId: e.target.value })} />
          </Field>
          <Field label="Display Name">
            <input className={inputClass} style={inputStyle} value={form.displayName} onChange={e => setForm({ ...form, displayName: e.target.value })} />
          </Field>
        </div>

        {!isCreate && <ReadOnlyField label="Provider">{provider?.name || form.providerId}</ReadOnlyField>}

        <Field label="Official Model">
          <input
            className={inputClass}
            style={inputStyle}
            value={form.upstreamModel}
            onChange={e => setForm({ ...form, upstreamModel: e.target.value })}
            placeholder="e.g. gpt-4o"
          />
        </Field>

        <div className="grid grid-cols-2 gap-4">
          <Field label="Enabled">
            <Checkbox checked={form.enabled} onChange={checked => setForm({ ...form, enabled: checked })}>Accept traffic</Checkbox>
          </Field>
          <Field label="Priority">
            <input className={inputClass} style={inputStyle} type="number" min={0} value={form.priority} onChange={e => setForm({ ...form, priority: e.target.value })} />
          </Field>
        </div>

        <Field label="Endpoints">
          <div className="grid grid-cols-2 gap-2">
            {ENDPOINT_TYPES.map(endpoint => (
              <Checkbox
                key={endpoint.value}
                checked={form.endpointTypes.includes(endpoint.value)}
                onChange={checked => toggleEndpoint(endpoint.value, checked)}>
                {endpoint.label}
              </Checkbox>
            ))}
          </div>
        </Field>

        <div className="grid grid-cols-2 gap-4">
          <ReadOnlyField label="Capabilities">
            {formatCapabilities({
              supportsStream: form.supportsStream,
              supportsTools: form.supportsTools,
              supportsVision: form.supportsVision,
              contextWindow: optionalDisplayNumber(form.contextWindow),
            })}
          </ReadOnlyField>
          <ReadOnlyField label="Pricing">
            {formatPricing(optionalDisplayNumber(form.inputPricePer1MTokens), optionalDisplayNumber(form.outputPricePer1MTokens))}
          </ReadOnlyField>
        </div>

        <PanelActions close={close} save={save} saving={saving} disabled={!form.modelId || !form.providerId || !form.upstreamModel} />
      </div>
    </Panel>
  );
}

function renderDiscoverModelsPanel(props: {
  providers: GatewayProvider[];
  providerId: string;
  models: GatewayDiscoveredModel[];
  selectedModels: Set<string>;
  discovering: boolean;
  saving: boolean;
  changeProvider: (providerId: string) => void;
  discover: (providerId?: string) => void;
  toggleModel: (modelId: string, checked: boolean) => void;
  toggleSelectAll: () => void;
  close: () => void;
  importModels: () => void;
}) {
  const { providers, providerId, models, selectedModels, discovering, saving, changeProvider, discover, toggleModel, toggleSelectAll, close, importModels } = props;
  const nonImportedCount = models.filter(model => !model.imported).length;
  const allSelected = nonImportedCount > 0 && selectedModels.size === nonImportedCount;
  return (
    <Panel title="Sync Models" close={close}>
      <div className="p-5 space-y-4">
        <div className="grid grid-cols-[1fr_auto] gap-2">
          <Field label="Provider">
            <select className={inputClass} style={inputStyle} value={providerId} onChange={e => changeProvider(e.target.value)}>
              <option value="">Select provider</option>
              {providers.map(provider => <option key={provider.id} value={provider.id}>{provider.name}</option>)}
            </select>
          </Field>
          <div className="pt-5">
            <button
              onClick={() => discover(providerId)}
              disabled={!providerId || discovering}
              className="inline-flex items-center gap-2 h-10 px-3 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50"
              style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
              <RefreshCw size={16} className={discovering ? 'animate-spin' : ''} />
              Sync
            </button>
          </div>
        </div>

        {models.length > 0 && (
          <div className="flex items-center justify-between">
            <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {models.length} model{models.length === 1 ? '' : 's'} found · {selectedModels.size} selected
            </span>
            <button
              onClick={toggleSelectAll}
              disabled={nonImportedCount === 0}
              className="text-xs font-medium cursor-pointer disabled:opacity-50"
              style={{ color: 'var(--color-primary)' }}>
              {allSelected ? 'Deselect All' : 'Select All'}
            </button>
          </div>
        )}
        <div className="rounded-lg border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
          <table className="w-full text-sm">
            <thead style={{ background: 'var(--color-bg-secondary)' }}>
              <tr className="text-left" style={{ color: 'var(--color-text-secondary)' }}>
                <th className="px-3 py-2 font-medium w-10"></th>
                <th className="px-3 py-2 font-medium">Official Model</th>
                <th className="px-3 py-2 font-medium">Endpoints</th>
                <th className="px-3 py-2 font-medium">Metadata</th>
              </tr>
            </thead>
            <tbody>
              {discovering ? (
                <EmptyRow colSpan={4}>Syncing models...</EmptyRow>
              ) : models.length === 0 ? (
                <EmptyRow colSpan={4}>No provider models synced</EmptyRow>
              ) : models.map(model => (
                <tr key={model.id} className="border-t" style={{ borderColor: 'var(--color-border)' }}>
                  <td className="px-3 py-2">
                    <input
                      type="checkbox"
                      checked={selectedModels.has(model.id)}
                      onChange={e => toggleModel(model.id, e.target.checked)}
                    />
                  </td>
                  <td className="px-3 py-2">
                    <div className="font-mono font-medium">{model.id}</div>
                    <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>
                      {model.imported ? 'already imported' : model.displayName || 'alias defaults to official ID'}
                    </div>
                  </td>
                  <td className="px-3 py-2">{(model.endpointTypes || []).map(labelEndpoint).join(', ') || '-'}</td>
                  <td className="px-3 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                    {formatCapabilities(model)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <PanelActions close={close} save={importModels} saving={saving} disabled={!providerId || selectedModels.size === 0} saveLabel="Import" />
      </div>
    </Panel>
  );
}

function Panel({ title, close, children }: { title: string; close: () => void; children: ReactNode }) {
  return (
    <div className="fixed inset-0 z-40 flex justify-end" style={{ background: 'rgba(0,0,0,0.28)' }}>
      <div className="h-full w-full max-w-xl overflow-auto border-l" style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}>
        <div className="sticky top-0 z-10 flex items-center justify-between px-5 py-4 border-b" style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}>
          <div className="flex items-center gap-2">
            <PlugZap size={18} style={{ color: 'var(--color-primary)' }} />
            <h3 className="font-semibold">{title}</h3>
          </div>
          <button className="p-2 rounded-lg cursor-pointer" onClick={close} title="Close">
            <X size={18} />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

function PanelActions({ close, save, saving, disabled, saveLabel = 'Save' }: { close: () => void; save: () => void; saving: boolean; disabled: boolean; saveLabel?: string }) {
  return (
    <div className="flex justify-end gap-2 pt-2">
      <button
        onClick={close}
        className="px-4 py-2 rounded-lg text-sm font-medium cursor-pointer"
        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
        Cancel
      </button>
      <button
        onClick={save}
        disabled={saving || disabled}
        className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
        style={{ background: 'var(--color-primary)' }}>
        <Save size={16} />
        {saving ? 'Saving...' : saveLabel}
      </button>
    </div>
  );
}

function SegmentedButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      onClick={onClick}
      className="px-3 py-2 rounded-lg text-sm font-medium cursor-pointer"
      style={{
        background: active ? 'var(--color-bg-tertiary)' : 'transparent',
        color: active ? 'var(--color-text)' : 'var(--color-text-secondary)',
      }}>
      {children}
    </button>
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

function Checkbox({ checked, onChange, children }: { checked: boolean; onChange: (checked: boolean) => void; children: ReactNode }) {
  return (
    <label className="h-10 flex items-center gap-2 px-3 rounded-lg" style={{ background: 'var(--color-bg-tertiary)' }}>
      <input type="checkbox" checked={checked} onChange={e => onChange(e.target.checked)} />
      <span className="text-sm">{children}</span>
    </label>
  );
}

function EmptyRow({ colSpan, children }: { colSpan: number; children: ReactNode }) {
  return (
    <tr>
      <td colSpan={colSpan} className="px-4 py-10 text-center" style={{ color: 'var(--color-text-secondary)' }}>
        {children}
      </td>
    </tr>
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

function ReadOnlyField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <span className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
      <div className="min-h-10 flex items-center px-3 py-2 rounded-lg text-sm border" style={inputStyle}>
        {children || '-'}
      </div>
    </div>
  );
}

function isVertexMediaProtocol(protocol: string) {
  return protocol === 'VERTEX_GEMINI_GENERATE_CONTENT' || protocol === 'VERTEX_GEMINI_INTERACTIONS';
}

function labelEndpoint(value: string) {
  return ENDPOINT_TYPES.find(endpoint => endpoint.value === value)?.label || value;
}

function optionalNumber(value: string, label: string) {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const number = Number(trimmed);
  if (!Number.isFinite(number)) throw new Error(`${label} must be a number`);
  return number;
}

function optionalDisplayNumber(value: string) {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const number = Number(trimmed);
  return Number.isFinite(number) ? number : null;
}

function formatCapabilities(model: {
  supportsStream?: boolean | null;
  supportsTools?: boolean | null;
  supportsVision?: boolean | null;
  contextWindow?: number | null;
}) {
  return [
    model.supportsStream ? 'stream' : null,
    model.supportsTools ? 'tools' : null,
    model.supportsVision ? 'vision' : null,
    model.contextWindow ? `${model.contextWindow} ctx` : null,
  ].filter(Boolean).join(', ') || '-';
}

function formatPricing(inputPrice: number | null, outputPrice: number | null) {
  if (inputPrice == null && outputPrice == null) return '-';
  return `in ${inputPrice ?? '-'} / out ${outputPrice ?? '-'} per 1M`;
}

const inputClass = 'w-full h-10 px-3 py-2 rounded-lg text-sm border outline-none';
const inputStyle = {
  background: 'var(--color-bg-tertiary)',
  borderColor: 'var(--color-border)',
  color: 'var(--color-text)',
};
