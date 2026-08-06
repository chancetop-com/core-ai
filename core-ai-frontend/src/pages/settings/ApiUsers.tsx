import { useCallback, useEffect, useState } from 'react';
import { Copy, KeyRound, Pause, Play, Plus } from 'lucide-react';
import { apiUsersAdminApi, type AdminApiUser, type CreateApiUserResponse } from '../../api/client';

export default function ApiUsers() {
  const [users, setUsers] = useState<AdminApiUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  const [issuedKey, setIssuedKey] = useState<CreateApiUserResponse | null>(null);
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  const fetchUsers = useCallback(() => {
    setLoading(true);
    setError('');
    apiUsersAdminApi.list()
      .then(response => setUsers(response.users))
      .catch(err => setError(err instanceof Error ? err.message : 'Failed to load API users'))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { fetchUsers(); }, [fetchUsers]);

  const handleCreate = async () => {
    if (!newName.trim()) return;
    setCreating(true);
    setError('');
    try {
      const response = await apiUsersAdminApi.create(newName.trim());
      setIssuedKey(response);
      setNewName('');
      fetchUsers();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create API user');
    } finally {
      setCreating(false);
    }
  };

  const handleRotateKey = async (user: AdminApiUser) => {
    setActionLoading(user.user_id);
    setError('');
    try {
      const response = await apiUsersAdminApi.rotateKey(user.user_id);
      setIssuedKey(response);
      fetchUsers();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to rotate key');
    } finally {
      setActionLoading(null);
    }
  };

  const handleUpdateStatus = async (user: AdminApiUser) => {
    setActionLoading(user.user_id);
    setError('');
    try {
      const nextStatus = user.status === 'active' ? 'disabled' : 'active';
      await apiUsersAdminApi.updateStatus(user.user_id, nextStatus);
      fetchUsers();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update status');
    } finally {
      setActionLoading(null);
    }
  };

  const copyKey = () => {
    if (!issuedKey?.api_key) return;
    navigator.clipboard.writeText(issuedKey.api_key);
  };

  return (
    <div className="p-6 max-w-3xl">
      <h2 className="text-lg font-semibold mb-1">API Users</h2>
      <p className="text-sm mb-6" style={{ color: 'var(--color-text-secondary)' }}>
        Manage business systems (manager API users). The management key (cmk_) is shown once at creation
        and must be handed to the business system offline.
      </p>

      {error && (
        <div className="mb-4 px-4 py-3 rounded-lg text-sm" style={{ background: '#ef444420', color: 'var(--color-error)' }}>
          {error}
        </div>
      )}

      {issuedKey && (
        <div className="mb-6 p-4 rounded-lg border" style={{ background: '#22c55e10', borderColor: '#22c55e40' }}>
          <p className="text-sm font-medium mb-2" style={{ color: '#16a34a' }}>
            Management key generated for {issuedKey.user_id}. Copy it now — it won't be shown again.
          </p>
          {issuedKey.api_key && (
            <div className="flex items-center gap-2">
              <code className="flex-1 px-3 py-2 rounded text-sm font-mono break-all" style={{ background: 'var(--color-bg-tertiary)' }}>
                {issuedKey.api_key}
              </code>
              <button
                onClick={copyKey}
                className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer"
                style={{ background: 'var(--color-primary)', color: '#fff' }}>
                <Copy size={14} />
                Copy
              </button>
            </div>
          )}
        </div>
      )}

      {/* Create */}
      <div className="rounded-xl border p-5 mb-6" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="flex items-center gap-3">
          <input
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') handleCreate(); }}
            placeholder="Business system name (e.g. Acme Merchant Platform)"
            className="flex-1 px-3 py-2 rounded-lg text-sm border-0 outline-none"
            style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}
          />
          <button
            onClick={handleCreate}
            disabled={creating || !newName.trim()}
            className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer transition-colors disabled:opacity-50"
            style={{ background: 'var(--color-primary)' }}>
            <Plus size={14} />
            {creating ? 'Creating...' : 'Create'}
          </button>
        </div>
      </div>

      {/* List */}
      {loading ? (
        <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
      ) : users.length === 0 ? (
        <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>No API users yet.</div>
      ) : (
        <div className="rounded-xl border divide-y" style={{ borderColor: 'var(--color-border)' }}>
          {users.map((user) => (
            <div key={user.user_id} className="p-4 flex items-center justify-between gap-4"
              style={{ background: 'var(--color-bg-secondary)' }}>
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <span className="font-medium text-sm truncate">{user.name}</span>
                  <span className={`px-2 py-0.5 rounded-full text-xs ${user.status === 'active' ? '' : ''}`}
                    style={{
                      background: user.status === 'active' ? '#22c55e20' : '#ef444420',
                      color: user.status === 'active' ? '#16a34a' : 'var(--color-error)',
                    }}>
                    {user.status}
                  </span>
                </div>
                <div className="text-xs mt-1 font-mono" style={{ color: 'var(--color-text-secondary)' }}>
                  {user.user_id}
                  {user.created_at && ` · created ${new Date(user.created_at).toLocaleString()}`}
                </div>
              </div>
              <div className="flex items-center gap-2 shrink-0">
                <button
                  onClick={() => handleRotateKey(user)}
                  disabled={actionLoading === user.user_id}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium cursor-pointer disabled:opacity-50"
                  style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}
                  title="Rotate management key (old key invalidated immediately)">
                  <KeyRound size={14} />
                  {actionLoading === user.user_id ? '...' : 'Rotate Key'}
                </button>
                <button
                  onClick={() => handleUpdateStatus(user)}
                  disabled={actionLoading === user.user_id}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium cursor-pointer disabled:opacity-50"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}
                  title={user.status === 'active' ? 'Disable (all temp keys fail auth)' : 'Enable'}>
                  {user.status === 'active' ? <Pause size={14} /> : <Play size={14} />}
                  {user.status === 'active' ? 'Disable' : 'Enable'}
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
