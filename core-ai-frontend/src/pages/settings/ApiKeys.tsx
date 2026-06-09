import { useEffect, useState } from 'react';
import { Eye, EyeOff, RefreshCw } from 'lucide-react';
import { userApi, type ApiKeyInfo } from '../../api/client';
import { useAuth } from '../../api/auth';

export default function ApiKeys() {
  const { login } = useAuth();
  const [apiKeyInfo, setApiKeyInfo] = useState<ApiKeyInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [showKey, setShowKey] = useState(false);
  const [regenerating, setRegenerating] = useState(false);
  const [newKey, setNewKey] = useState('');

  const fetchKey = () => {
    setLoading(true);
    setError('');
    userApi.getApiKey()
      .then(setApiKeyInfo)
      .catch(err => setError(err instanceof Error ? err.message : 'Failed to load API key'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    fetchKey();
  }, []);

  const handleRegenerate = async () => {
    setRegenerating(true);
    setError('');
    try {
      const res = await userApi.generateApiKey();
      setNewKey(res.api_key);
      // Update localStorage so current session stays valid
      login(res.api_key, localStorage.getItem('userId') || '', localStorage.getItem('userName') || '', localStorage.getItem('userRole') || undefined);
      // Refresh the key info
      fetchKey();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to regenerate API key');
    } finally {
      setRegenerating(false);
    }
  };

  if (loading) {
    return (
      <div className="p-6 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
        Loading...
      </div>
    );
  }

  if (error && !apiKeyInfo) {
    return (
      <div className="p-6">
        <div className="px-4 py-3 rounded-lg text-sm" style={{ background: '#ef444420', color: 'var(--color-error)' }}>
          {error}
        </div>
      </div>
    );
  }

  return (
    <div className="p-6 max-w-2xl">
      <h2 className="text-lg font-semibold mb-1">API Keys</h2>
      <p className="text-sm mb-6" style={{ color: 'var(--color-text-secondary)' }}>
        Your default API key is used for authentication in the web UI, CLI, and API requests.
      </p>

      {newKey && (
        <div className="mb-6 p-4 rounded-lg border" style={{ background: '#22c55e10', borderColor: '#22c55e40' }}>
          <p className="text-sm font-medium mb-2" style={{ color: '#16a34a' }}>
            New API key generated. Copy it now — it won't be shown again in this view.
          </p>
          <div className="flex items-center gap-2">
            <code className="flex-1 px-3 py-2 rounded text-sm font-mono break-all" style={{ background: 'var(--color-bg-tertiary)' }}>
              {newKey}
            </code>
            <button
              onClick={() => { navigator.clipboard.writeText(newKey); setNewKey(''); }}
              className="px-3 py-2 rounded-lg text-sm font-medium cursor-pointer"
              style={{ background: 'var(--color-primary)', color: '#fff' }}>
              Copy & dismiss
            </button>
          </div>
        </div>
      )}

      {/* Default Key Card */}
      <div className="rounded-xl border p-5" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="flex items-center justify-between mb-4">
          <div>
            <h3 className="font-medium">Default Key</h3>
            <p className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>
              Auto-generated for login and authentication
            </p>
          </div>
          {apiKeyInfo && (
            <button
              onClick={handleRegenerate}
              disabled={regenerating}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium cursor-pointer disabled:opacity-50"
              style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}>
              <RefreshCw size={14} className={regenerating ? 'animate-spin' : ''} />
              {regenerating ? 'Regenerating...' : 'Regenerate'}
            </button>
          )}
        </div>

        {apiKeyInfo ? (
          <>
            <div className="mb-3">
              <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>API Key</label>
              <div className="flex items-center gap-2">
                <code className="flex-1 px-3 py-2 rounded text-sm font-mono break-all" style={{ background: 'var(--color-bg-tertiary)' }}>
                  {showKey ? apiKeyInfo.api_key : apiKeyInfo.api_key.substring(0, 12) + '••••••••••••••••'}
                </code>
                <button
                  onClick={() => setShowKey(!showKey)}
                  className="p-2 rounded-lg cursor-pointer"
                  style={{ color: 'var(--color-text-secondary)' }}
                  title={showKey ? 'Hide' : 'Show'}>
                  {showKey ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
            </div>
            <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              Created: {new Date(apiKeyInfo.created_at).toLocaleString()}
            </div>
          </>
        ) : (
          <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            No API key yet. One will be created on your next login.
          </div>
        )}
      </div>
    </div>
  );
}
