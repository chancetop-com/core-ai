import { useMemo, useState, useEffect, useCallback, useRef } from 'react';
import {
  Users, CheckCircle, XCircle, RefreshCw, Trash2, Key, KeyRound, X, ArrowLeft,
  Plus, Copy, Pause, Play, Search,
} from 'lucide-react';
import { api, adminApi, apiUsersAdminApi, type CreateApiUserResponse } from '../../api/client';

interface ManagedUser {
  key: string;
  name: string;
  status: string;
  type: 'internal' | 'api';
  created_at?: string;
  email?: string;
  role?: string;
  has_api_key?: boolean;
  api_key?: string;
  api_key_created_at?: string;
  user_id?: string;
  external_id?: string;
  owner_id?: string;
  owner_name?: string;
  created_by?: string;
  permissions?: { resource_type: string; resource_id: string }[];
  input_token_quota?: number;
  output_token_quota?: number;
  quota_consumed_input_tokens?: number;
  quota_consumed_output_tokens?: number;
  outbound_caller_headers?: { header_name: string; value_source: string }[];
}

export default function UserManagement() {
  const [users, setUsers] = useState<ManagedUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionLoading, setActionLoading] = useState(false);
  const [selectedUser, setSelectedUser] = useState<ManagedUser | null>(null);
  const [newApiKey, setNewApiKey] = useState<string | null>(null);
  const [roleLoading, setRoleLoading] = useState(false);
  const [resetLoading, setResetLoading] = useState(false);
  const [generatedPassword, setGeneratedPassword] = useState('');
  const [search, setSearch] = useState('');
  const [managerFilter, setManagerFilter] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [newName, setNewName] = useState('');
  const [issuedKey, setIssuedKey] = useState<CreateApiUserResponse | null>(null);
  const [copied, setCopied] = useState(false);
  const copyTimer = useRef<number | null>(null);
  const [configDraft, setConfigDraft] = useState<ConfigDraft | null>(null);
  const [configSaving, setConfigSaving] = useState(false);
  const [configSaved, setConfigSaved] = useState(false);
  const [agents, setAgents] = useState<{ id: string; name: string }[]>([]);
  const [callerHeadersDraft, setCallerHeadersDraft] = useState<CallerHeaderDraft[]>([]);
  const [callerHeadersSaving, setCallerHeadersSaving] = useState(false);
  const [callerHeadersSaved, setCallerHeadersSaved] = useState(false);

  useEffect(() => {
    api.agents.list(undefined, undefined, 1000).then(res => setAgents(res.agents)).catch(() => {});
  }, []);

  useEffect(() => {
    return () => { if (copyTimer.current) window.clearTimeout(copyTimer.current); };
  }, []);

  useEffect(() => {
    if (selectedUser) {
      setConfigDraft({
        inputQuota: toM(selectedUser.input_token_quota),
        outputQuota: toM(selectedUser.output_token_quota),
        permissions: (selectedUser.permissions || []).map(p => ({ resourceType: p.resource_type, resourceId: p.resource_id })),
      });
      setConfigSaved(false);
      setCallerHeadersDraft((selectedUser.outbound_caller_headers || []).map(h => {
        const isMetadata = h.value_source.startsWith('metadata.');
        return {
          headerName: h.header_name,
          valueSource: isMetadata ? 'metadata' : h.value_source,
          metadataKey: isMetadata ? h.value_source.substring('metadata.'.length) : '',
        };
      }));
      setCallerHeadersSaved(false);
    }
  }, [selectedUser]);

  const copyText = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopied(true);
    if (copyTimer.current) window.clearTimeout(copyTimer.current);
    copyTimer.current = window.setTimeout(() => setCopied(false), 2500);
  };

  const fetchUsers = useCallback(async () => {
    const res = await adminApi.listUsers();
    return res.users.map(u => {
      const isApi = u.user_type === 'api';
      return {
        key: isApi ? (u.user_id || u.email || '') : (u.email || ''),
        name: u.name,
        status: u.status,
        type: isApi ? 'api' : 'internal',
        created_at: u.created_at,
        email: u.email,
        role: u.role,
        has_api_key: u.has_api_key,
        api_key: u.api_key,
        api_key_created_at: u.api_key_created_at,
        user_id: u.user_id,
        external_id: u.external_id,
        owner_id: u.owner_id,
        owner_name: u.owner_name,
        created_by: u.created_by,
        permissions: u.permissions,
        input_token_quota: u.input_token_quota,
        output_token_quota: u.output_token_quota,
        quota_consumed_input_tokens: u.quota_consumed_input_tokens,
        quota_consumed_output_tokens: u.quota_consumed_output_tokens,
        outbound_caller_headers: u.outbound_caller_headers,
      } as ManagedUser;
    });
  }, []);

  const loadUsers = useCallback(async () => {
    try {
      setError('');
      setLoading(true);
      setUsers(await fetchUsers());
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load users');
    } finally {
      setLoading(false);
    }
  }, [fetchUsers]);

  useEffect(() => { loadUsers(); }, [loadUsers]);

  const refresh = useCallback(async () => {
    const all = await fetchUsers();
    setUsers(all);
    if (selectedUser) {
      const updated = all.find(u => u.key === selectedUser.key);
      if (updated) setSelectedUser(updated);
    }
  }, [fetchUsers, selectedUser]);

  const managers = useMemo(() => users.filter(u => u.type === 'api' && !u.owner_id), [users]);

  const filtered = useMemo(() => {
    let list = users;
    if (managerFilter) {
      list = list.filter(u => u.type === 'api' && (u.user_id === managerFilter || u.owner_id === managerFilter));
    }
    const q = search.trim().toLowerCase();
    if (q) {
      list = list.filter(u =>
        (u.name || '').toLowerCase().includes(q) ||
        (u.email || '').toLowerCase().includes(q) ||
        (u.user_id || '').toLowerCase().includes(q));
    }
    return list;
  }, [users, managerFilter, search]);

  const handleUpdateStatus = async (email: string, status: string) => {
    setActionLoading(true);
    try {
      await adminApi.updateUserStatus(email, status);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update user');
    } finally {
      setActionLoading(false);
    }
  };

  const handleDeleteUser = async (email: string) => {
    if (!confirm(`Delete user "${email}"? This action cannot be undone.`)) return;
    setActionLoading(true);
    try {
      await adminApi.deleteUser(email);
      setSelectedUser(null);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to delete user');
    } finally {
      setActionLoading(false);
    }
  };

  const handleGenerateApiKey = async (email: string) => {
    if (!confirm(`Generate a new API key for "${email}"? Their existing key will be replaced.`)) return;
    setActionLoading(true);
    try {
      const res = await adminApi.generateApiKeyForUser(email);
      setNewApiKey(res.api_key);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to generate API key');
    } finally {
      setActionLoading(false);
    }
  };

  const handleRevokeApiKey = async (email: string) => {
    if (!confirm(`Revoke API key for "${email}"? They will lose API access immediately.`)) return;
    setActionLoading(true);
    try {
      await adminApi.revokeApiKey(email);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to revoke API key');
    } finally {
      setActionLoading(false);
    }
  };

  const handleUpdateRole = async (email: string, newRole: string) => {
    setRoleLoading(true);
    try {
      await adminApi.updateUserRole(email, newRole);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update role');
    } finally {
      setRoleLoading(false);
    }
  };

  const generateRandomPassword = () => {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%';
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    let result = '';
    for (let i = 0; i < 16; i++) {
      result += chars[bytes[i] % chars.length];
    }
    return result;
  };

  const handleResetPassword = async (email: string) => {
    const password = generateRandomPassword();
    setResetLoading(true);
    try {
      await adminApi.resetUserPassword(email, password);
      setGeneratedPassword(password);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to reset password');
    } finally {
      setResetLoading(false);
    }
  };

  const handleRotateManagerKey = async (user: ManagedUser) => {
    if (!user.user_id) return;
    if (!confirm(`Rotate management key for "${user.name}"? The old key is invalidated immediately.`)) return;
    setActionLoading(true);
    try {
      const res = await apiUsersAdminApi.rotateKey(user.user_id);
      setIssuedKey(res);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to rotate key');
    } finally {
      setActionLoading(false);
    }
  };

  const handleToggleApiStatus = async (user: ManagedUser) => {
    if (!user.user_id) return;
    setActionLoading(true);
    try {
      const nextStatus = user.status === 'active' ? 'disabled' : 'active';
      await apiUsersAdminApi.updateStatus(user.user_id, nextStatus);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update status');
    } finally {
      setActionLoading(false);
    }
  };

  const handleCreateApiUser = async () => {
    if (!newName.trim()) return;
    setCreating(true);
    setError('');
    try {
      const res = await apiUsersAdminApi.create(newName.trim());
      setIssuedKey(res);
      setNewName('');
      await loadUsers();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to create API user');
    } finally {
      setCreating(false);
    }
  };

  const handleSaveConfig = async () => {
    if (!selectedUser || !configDraft) return;
    setConfigSaving(true);
    setError('');
    try {
      const permissions = configDraft.permissions
        .filter(p => p.resourceType.trim() && p.resourceId.trim())
        .map(p => ({ resource_type: p.resourceType.trim(), resource_id: p.resourceId.trim() }));
      await adminApi.updateUserConfig(selectedUser.key, {
        permissions,
        input_token_quota: toTokens(configDraft.inputQuota),
        output_token_quota: toTokens(configDraft.outputQuota),
      });
      setConfigSaved(true);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save config');
    } finally {
      setConfigSaving(false);
    }
  };

  const handleSaveCallerHeaders = async () => {
    if (!selectedUser?.user_id) return;
    setCallerHeadersSaving(true);
    setError('');
    try {
      const headers = callerHeadersDraft
        .filter(h => h.headerName.trim() && h.valueSource)
        .map(h => ({
          header_name: h.headerName.trim(),
          value_source: h.valueSource === 'metadata'
            ? `metadata.${h.metadataKey.trim()}`
            : h.valueSource,
        }));
      await adminApi.updateUserConfig(selectedUser.user_id, { outbound_caller_headers: headers });
      setCallerHeadersSaved(true);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save caller headers');
    } finally {
      setCallerHeadersSaving(false);
    }
  };

  const openCreate = () => {
    setCreateOpen(true);
    setIssuedKey(null);
    setNewName('');
    setError('');
  };

  const closePanel = () => {
    setSelectedUser(null);
    setNewApiKey(null);
    setGeneratedPassword('');
    setIssuedKey(null);
  };

  const getStatusBadge = (status: string) => {
    const isActive = status === 'active';
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium"
        style={{
          background: isActive ? '#22c55e20' : '#f59e0b20',
          color: isActive ? '#22c55e' : '#f59e0b',
        }}>
        {isActive ? <CheckCircle size={12} /> : <XCircle size={12} />}
        {status}
      </span>
    );
  };

  return (
    <div className="p-6 max-w-5xl">
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-lg flex items-center justify-center"
            style={{ background: 'var(--color-primary)', opacity: 0.9 }}>
            <Users size={20} color="white" />
          </div>
          <div>
            <h1 className="text-xl font-semibold">User Management</h1>
            <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Manage user accounts and permissions
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={openCreate}
            className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            <Plus size={14} /> New API User
          </button>
          <button onClick={loadUsers}
            className="flex items-center gap-2 px-3 py-2 rounded-lg text-sm cursor-pointer transition-colors"
            style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
            <RefreshCw size={14} />
            Refresh
          </button>
        </div>
      </div>

      {error && (
        <div className="mb-4 px-4 py-3 rounded-lg text-sm" style={{ background: '#ef444420', color: 'var(--color-error)' }}>
          {error}
        </div>
      )}

      {/* Search & Filter */}
      <div className="flex items-center gap-3 mb-4">
        <div className="relative flex-1 max-w-sm">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2"
            style={{ color: 'var(--color-text-secondary)' }} />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search by name..."
            className="w-full pl-9 pr-3 py-2 rounded-lg text-sm border-0 outline-none"
            style={{ background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}
          />
        </div>
        <select
          value={managerFilter}
          onChange={(e) => setManagerFilter(e.target.value)}
          className="px-3 py-2 rounded-lg text-sm border-0 outline-none cursor-pointer"
          style={{ background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
          <option value="">All business accounts</option>
          {managers.map(m => (
            <option key={m.user_id} value={m.user_id}>{m.name}</option>
          ))}
        </select>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-12" style={{ color: 'var(--color-text-secondary)' }}>
          Loading users...
        </div>
      ) : (
        <div className="rounded-xl border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
          <table className="w-full">
            <thead>
              <tr style={{ background: 'var(--color-bg-secondary)' }}>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider"
                  style={{ color: 'var(--color-text-secondary)' }}>Name</th>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider"
                  style={{ color: 'var(--color-text-secondary)' }}>Identifier</th>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider"
                  style={{ color: 'var(--color-text-secondary)' }}>Type</th>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider"
                  style={{ color: 'var(--color-text-secondary)' }}>Status</th>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider"
                  style={{ color: 'var(--color-text-secondary)' }}>Created</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((user, idx) => (
                <tr key={user.key}
                  className="border-t cursor-pointer hover:opacity-80"
                  style={{ borderColor: 'var(--color-border)', background: idx % 2 === 0 ? 'transparent' : 'var(--color-bg-secondary)' }}
                  onClick={() => { setSelectedUser(user); setNewApiKey(null); setGeneratedPassword(''); setIssuedKey(null); }}>
                  <td className="px-4 py-3 text-sm">
                    <span className="font-medium">{user.name}</span>
                    {user.type === 'api' && (
                      <span className="ml-2 px-2 py-0.5 rounded-full text-xs"
                        style={{
                          background: user.owner_id ? 'var(--color-bg-tertiary)' : 'var(--color-primary-bg)',
                          color: user.owner_id ? 'var(--color-text-secondary)' : 'var(--color-primary)',
                        }}>
                        {user.owner_id ? 'Sub user' : 'Manager'}
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-sm font-mono" style={{ color: 'var(--color-text-secondary)' }}>
                    {user.email || user.user_id}
                  </td>
                  <td className="px-4 py-3 text-sm">
                    <span className="px-2 py-0.5 rounded text-xs font-medium"
                      style={{
                        background: user.type === 'api' ? '#8b5cf620' : '#3b82f620',
                        color: user.type === 'api' ? '#8b5cf6' : '#3b82f6',
                      }}>
                      {user.type === 'api' ? 'API' : 'Internal'}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-sm">{getStatusBadge(user.status)}</td>
                  <td className="px-4 py-3 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                    {user.created_at ? new Date(user.created_at).toLocaleDateString() : '-'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {filtered.length === 0 && (
            <div className="py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>
              No users found
            </div>
          )}
        </div>
      )}

      {/* Create API User Modal */}
      {createOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div className="absolute inset-0 bg-black/30" onClick={() => setCreateOpen(false)} />
          <div className="relative w-full max-w-md rounded-xl shadow-xl p-6"
            style={{ background: 'var(--color-bg-secondary)' }}>
            {issuedKey ? (
              <>
                <h2 className="text-lg font-semibold mb-2">API User Created</h2>
                <p className="text-sm mb-3" style={{ color: 'var(--color-text-secondary)' }}>
                  Management key for {issuedKey.user_id}. Copy it now — it won't be shown again.
                </p>
                {issuedKey.api_key && (
                  <div className="flex items-center gap-2 mb-4">
                    <code className="flex-1 px-3 py-2 rounded text-sm font-mono break-all"
                      style={{ background: 'var(--color-bg-tertiary)' }}>
                      {issuedKey.api_key}
                    </code>
                    <button
                      onClick={() => copyText(issuedKey.api_key || '')}
                      className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
                      style={{ background: copied ? '#22c55e' : 'var(--color-primary)' }}>
                      <Copy size={14} /> {copied ? 'Copied' : 'Copy'}
                    </button>
                  </div>
                )}
                <button
                  onClick={() => setCreateOpen(false)}
                  className="w-full px-4 py-2 rounded-lg text-sm font-medium cursor-pointer"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                  Done
                </button>
              </>
            ) : (
              <>
                <h2 className="text-lg font-semibold mb-2">New API User</h2>
                <p className="text-sm mb-4" style={{ color: 'var(--color-text-secondary)' }}>
                  Create a business system (manager API user). A management key (cmk_) will be generated
                  and shown once — hand it to the business system offline.
                </p>
                <input
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') handleCreateApiUser(); }}
                  placeholder="Business system name (e.g. Acme Merchant Platform)"
                  autoFocus
                  className="w-full px-3 py-2 rounded-lg text-sm border-0 outline-none mb-4"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}
                />
                <div className="flex gap-2">
                  <button
                    onClick={() => setCreateOpen(false)}
                    className="flex-1 px-4 py-2 rounded-lg text-sm font-medium cursor-pointer"
                    style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                    Cancel
                  </button>
                  <button
                    onClick={handleCreateApiUser}
                    disabled={creating || !newName.trim()}
                    className="flex-1 flex items-center justify-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
                    style={{ background: 'var(--color-primary)' }}>
                    <Plus size={14} />
                    {creating ? 'Creating...' : 'Create'}
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}

      {/* User Detail Panel */}
      {selectedUser && (
        <div className="fixed inset-0 z-50 flex justify-end">
          <div className="absolute inset-0 bg-black/30" onClick={closePanel} />
          <div className="relative w-full max-w-md h-full overflow-y-auto shadow-xl"
            style={{ background: 'var(--color-bg-secondary)' }}>
            {/* Panel Header */}
            <div className="sticky top-0 z-10 flex items-center justify-between px-6 py-4 border-b"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
              <div className="flex items-center gap-3">
                <button onClick={closePanel}
                  className="p-1 rounded hover:opacity-70 transition-opacity cursor-pointer"
                  style={{ color: 'var(--color-text-secondary)' }}>
                  <ArrowLeft size={18} />
                </button>
                <h2 className="text-lg font-semibold">User Details</h2>
              </div>
              <button onClick={closePanel}
                className="p-1 rounded hover:opacity-70 transition-opacity cursor-pointer"
                style={{ color: 'var(--color-text-secondary)' }}>
                <X size={18} />
              </button>
            </div>

            {selectedUser.type === 'api' ? (
              /* API User Panel */
              <div className="px-6 py-4 space-y-5">
                <section>
                  <h3 className="text-xs font-medium uppercase tracking-wider mb-3"
                    style={{ color: 'var(--color-text-secondary)' }}>General</h3>
                  <div className="space-y-2">
                    <InfoRow label="User ID" value={selectedUser.user_id || '-'} mono />
                    {selectedUser.external_id && (
                      <InfoRow label="External ID" value={selectedUser.external_id} mono />
                    )}
                    <InfoRow label="Name" value={selectedUser.name} />
                    <InfoRow label="Status" value={selectedUser.status} />
                    {selectedUser.owner_id ? (
                      <InfoRow label="Owner" value={selectedUser.owner_name || selectedUser.owner_id} />
                    ) : (
                      <InfoRow label="Created By" value={selectedUser.created_by || '-'} />
                    )}
                    <InfoRow label="Created"
                      value={selectedUser.created_at ? new Date(selectedUser.created_at).toLocaleString() : '-'} />
                  </div>
                </section>

                {!selectedUser.owner_id && (
                  <CallerHeadersSection
                    draft={callerHeadersDraft}
                    saved={callerHeadersSaved}
                    saving={callerHeadersSaving}
                    onChange={setCallerHeadersDraft}
                    onSave={handleSaveCallerHeaders}
                  />
                )}

                {configDraft && (
                  <PermissionsQuotaSection
                    user={selectedUser}
                    draft={configDraft}
                    saved={configSaved}
                    saving={configSaving}
                    agents={agents}
                    onChange={setConfigDraft}
                    onSave={handleSaveConfig}
                  />
                )}

                {issuedKey && (
                  <section>
                    <h3 className="text-xs font-medium uppercase tracking-wider mb-3"
                      style={{ color: 'var(--color-text-secondary)' }}>Management Key</h3>
                    <div className="p-3 rounded-lg" style={{ background: '#22c55e10', border: '1px solid #22c55e30' }}>
                      <div className="text-xs font-medium mb-1" style={{ color: '#22c55e' }}>
                        New management key generated — copy it now, it won't be shown again
                      </div>
                      <code className="text-xs block break-all mb-2" style={{ color: 'var(--color-text)' }}>
                        {issuedKey.api_key}
                      </code>
                      <button onClick={() => copyText(issuedKey.api_key || '')}
                        className="text-xs underline cursor-pointer mr-3"
                        style={{ color: copied ? '#22c55e' : 'var(--color-text-secondary)' }}>
                        {copied ? 'copied' : 'copy'}
                      </button>
                      <button onClick={() => setIssuedKey(null)}
                        className="text-xs underline cursor-pointer"
                        style={{ color: 'var(--color-text-secondary)' }}>
                        dismiss
                      </button>
                    </div>
                  </section>
                )}

                <section>
                  <h3 className="text-xs font-medium uppercase tracking-wider mb-3"
                    style={{ color: 'var(--color-text-secondary)' }}>Actions</h3>
                  <div className="space-y-3">
                    {!selectedUser.owner_id && (
                      <button
                        onClick={() => handleRotateManagerKey(selectedUser)}
                        disabled={actionLoading}
                        className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium cursor-pointer transition-colors disabled:opacity-50"
                        style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}>
                        <KeyRound size={14} />
                        Rotate Management Key
                      </button>
                    )}
                    <button
                      onClick={() => handleToggleApiStatus(selectedUser)}
                      disabled={actionLoading}
                      className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium text-white cursor-pointer transition-colors disabled:opacity-50"
                      style={{ background: selectedUser.status === 'active' ? '#f59e0b' : '#22c55e' }}>
                      {selectedUser.status === 'active' ? <Pause size={14} /> : <Play size={14} />}
                      {selectedUser.status === 'active' ? 'Disable' : 'Enable'}
                    </button>
                  </div>
                </section>
              </div>
            ) : (
              /* Internal User Panel */
              <div className="px-6 py-4 space-y-5">
                <section>
                  <h3 className="text-xs font-medium uppercase tracking-wider mb-3"
                    style={{ color: 'var(--color-text-secondary)' }}>General</h3>
                  <div className="space-y-2">
                    <InfoRow label="Email" value={selectedUser.email || '-'} mono />
                    <InfoRow label="Name" value={selectedUser.name || '-'} />
                    <div className="flex items-start justify-between py-1.5">
                      <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Role</span>
                      <select
                        value={selectedUser.role}
                        onChange={(e) => handleUpdateRole(selectedUser.email || '', e.target.value)}
                        disabled={roleLoading}
                        className="text-sm text-right ml-4 rounded px-2 py-0.5 cursor-pointer border-0 outline-none"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                        <option value="user">user</option>
                        <option value="admin">admin</option>
                      </select>
                    </div>
                    <InfoRow label="Status" value={selectedUser.status} />
                    <InfoRow label="Created"
                      value={selectedUser.created_at ? new Date(selectedUser.created_at).toLocaleString() : '-'} />
                  </div>
                </section>

                <section>
                  <h3 className="text-xs font-medium uppercase tracking-wider mb-3"
                    style={{ color: 'var(--color-text-secondary)' }}>API Key</h3>
                  {newApiKey ? (
                    <div className="p-3 rounded-lg mb-2" style={{ background: '#22c55e10', border: '1px solid #22c55e30' }}>
                      <div className="text-xs font-medium mb-1" style={{ color: '#22c55e' }}>New API Key Generated</div>
                      <code className="text-xs block break-all mb-2" style={{ color: 'var(--color-text)' }}>
                        {newApiKey}
                      </code>
                      <button onClick={() => copyText(newApiKey)}
                        className="text-xs underline cursor-pointer mr-3"
                        style={{ color: copied ? '#22c55e' : 'var(--color-text-secondary)' }}>
                        {copied ? 'copied' : 'copy'}
                      </button>
                      <button onClick={() => setNewApiKey(null)}
                        className="text-xs underline cursor-pointer"
                        style={{ color: 'var(--color-text-secondary)' }}>
                        dismiss
                      </button>
                    </div>
                  ) : selectedUser.has_api_key ? (
                    <div className="p-3 rounded-lg mb-2" style={{ background: 'var(--color-bg-secondary)' }}>
                      <div className="flex items-center gap-2 mb-2">
                        <CheckCircle size={14} style={{ color: '#22c55e' }} />
                        <span className="text-sm font-medium" style={{ color: '#22c55e' }}>Active</span>
                      </div>
                      <code className="text-xs block break-all mb-1 px-2 py-1 rounded"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                        {selectedUser.api_key}
                      </code>
                      {selectedUser.api_key_created_at && (
                        <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                          Created: {new Date(selectedUser.api_key_created_at).toLocaleString()}
                        </div>
                      )}
                    </div>
                  ) : (
                    <div className="p-3 rounded-lg mb-2" style={{ background: 'var(--color-bg-secondary)' }}>
                      <div className="flex items-center gap-2">
                        <XCircle size={14} style={{ color: '#f59e0b' }} />
                        <span className="text-sm" style={{ color: '#f59e0b' }}>No API key</span>
                      </div>
                    </div>
                  )}
                </section>

                {configDraft && (
                  <PermissionsQuotaSection
                    user={selectedUser}
                    draft={configDraft}
                    saved={configSaved}
                    saving={configSaving}
                    agents={agents}
                    onChange={setConfigDraft}
                    onSave={handleSaveConfig}
                  />
                )}

                <section>
                  <h3 className="text-xs font-medium uppercase tracking-wider mb-3"
                    style={{ color: 'var(--color-text-secondary)' }}>Actions</h3>
                  <div className="space-y-3">
                    {selectedUser.status === 'pending' ? (
                      <button
                        onClick={() => handleUpdateStatus(selectedUser.email || '', 'active')}
                        disabled={actionLoading}
                        className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium text-white cursor-pointer transition-colors disabled:opacity-50"
                        style={{ background: '#22c55e' }}>
                        <CheckCircle size={14} />
                        Approve User
                      </button>
                    ) : (
                      <button
                        onClick={() => handleUpdateStatus(selectedUser.email || '', 'pending')}
                        disabled={actionLoading}
                        className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium text-white cursor-pointer transition-colors disabled:opacity-50"
                        style={{ background: '#f59e0b' }}>
                        <XCircle size={14} />
                        Deactivate User
                      </button>
                    )}

                    <div className="h-px" style={{ background: 'var(--color-border)' }} />

                    <button
                      onClick={() => handleGenerateApiKey(selectedUser.email || '')}
                      disabled={actionLoading}
                      className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium cursor-pointer transition-colors disabled:opacity-50"
                      style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}>
                      <KeyRound size={14} />
                      {selectedUser.has_api_key ? 'Regenerate API Key' : 'Generate API Key'}
                    </button>

                    {selectedUser.has_api_key && (
                      <button
                        onClick={() => handleRevokeApiKey(selectedUser.email || '')}
                        disabled={actionLoading}
                        className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium cursor-pointer transition-colors disabled:opacity-50"
                        style={{ background: '#ef444410', color: '#ef4444' }}>
                        <XCircle size={14} />
                        Revoke API Key
                      </button>
                    )}

                    <div className="h-px" style={{ background: 'var(--color-border)' }} />

                    <button
                      onClick={() => handleDeleteUser(selectedUser.email || '')}
                      disabled={actionLoading}
                      className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium cursor-pointer transition-colors disabled:opacity-50"
                      style={{ background: '#ef444420', color: '#ef4444' }}>
                      <Trash2 size={14} />
                      Delete User
                    </button>

                    <div className="h-px" style={{ background: 'var(--color-border)' }} />

                    {generatedPassword ? (
                      <div className="p-3 rounded-lg" style={{ background: '#22c55e10', border: '1px solid #22c55e30' }}>
                        <div className="text-xs font-medium mb-1" style={{ color: '#22c55e' }}>New Password Generated</div>
                        <code className="text-xs block break-all mb-2 font-mono" style={{ color: 'var(--color-text)' }}>
                          {generatedPassword}
                        </code>
                        <button onClick={() => copyText(generatedPassword)}
                          className="text-xs underline cursor-pointer mr-3"
                          style={{ color: copied ? '#22c55e' : 'var(--color-text-secondary)' }}>
                          {copied ? 'copied' : 'copy'}
                        </button>
                        <button onClick={() => setGeneratedPassword('')}
                          className="text-xs underline cursor-pointer"
                          style={{ color: 'var(--color-text-secondary)' }}>
                          dismiss
                        </button>
                      </div>
                    ) : (
                      <button
                        onClick={() => handleResetPassword(selectedUser.email || '')}
                        disabled={resetLoading || actionLoading}
                        className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium cursor-pointer transition-colors disabled:opacity-50"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                        <Key size={14} />
                        {resetLoading ? 'Generating...' : 'Reset Password'}
                      </button>
                    )}
                  </div>
                </section>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function InfoRow({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-start justify-between py-1.5">
      <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{label}</span>
      <span className={`text-sm text-right ml-4 ${mono ? 'font-mono' : ''}`}
        style={{ color: 'var(--color-text)' }}>{value}</span>
    </div>
  );
}

interface ConfigDraft {
  inputQuota: string;
  outputQuota: string;
  permissions: { resourceType: string; resourceId: string }[];
}

interface CallerHeaderDraft {
  headerName: string;
  valueSource: string;  // external_id | user_id | manager_id | metadata
  metadataKey: string;
}

const toM = (tokens?: number) => tokens != null ? String(Math.round((tokens / 1_000_000) * 1_000_000) / 1_000_000) : '';

const toTokens = (m: string) => {
  const v = m.trim();
  if (v === '') return 0;
  const n = Number(v);
  return Number.isFinite(n) && n >= 0 ? Math.round(n * 1_000_000) : 0;
};

function CallerHeadersSection({
  draft, saved, saving, onChange, onSave,
}: {
  draft: CallerHeaderDraft[];
  saved: boolean;
  saving: boolean;
  onChange: (draft: CallerHeaderDraft[]) => void;
  onSave: () => void;
}) {
  const update = (i: number, patch: Partial<CallerHeaderDraft>) => {
    onChange(draft.map((h, j) => (j === i ? { ...h, ...patch } : h)));
  };
  return (
    <section>
      <h3 className="text-xs font-medium uppercase tracking-wider mb-3"
        style={{ color: 'var(--color-text-secondary)' }}>Outbound Caller Headers</h3>
      <p className="text-xs mb-3 leading-relaxed" style={{ color: 'var(--color-text-secondary)' }}>
        HTTP headers injected into API/MCP tool calls made by this business account's sub users.
        The business backend reads them to scope data (e.g. X-MERCHANT-ID ← external_id).
        Empty = no headers injected.
      </p>
      <div className="space-y-2">
        {draft.map((h, i) => (
          <div key={i} className="flex items-center gap-2">
            <input
              value={h.headerName}
              onChange={(e) => update(i, { headerName: e.target.value })}
              placeholder="Header name (e.g. X-MERCHANT-ID)"
              className="flex-1 px-2 py-1.5 rounded text-sm border-0 outline-none"
              style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}
            />
            <select
              value={h.valueSource}
              onChange={(e) => update(i, { valueSource: e.target.value })}
              className="px-2 py-1.5 rounded text-xs border-0 outline-none cursor-pointer"
              style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
              <option value="external_id">external_id</option>
              <option value="user_id">user_id</option>
              <option value="manager_id">manager_id</option>
              <option value="metadata">metadata.&lt;key&gt;</option>
            </select>
            {h.valueSource === 'metadata' && (
              <input
                value={h.metadataKey}
                onChange={(e) => update(i, { metadataKey: e.target.value })}
                placeholder="key (e.g. store_id)"
                className="w-28 px-2 py-1.5 rounded text-xs border-0 outline-none"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}
              />
            )}
            <button
              onClick={() => onChange(draft.filter((_, j) => j !== i))}
              className="p-1 rounded hover:opacity-70 cursor-pointer"
              style={{ color: 'var(--color-text-secondary)' }}
              title="Remove">
              <X size={14} />
            </button>
          </div>
        ))}
        <button
          onClick={() => onChange([...draft, { headerName: '', valueSource: 'external_id', metadataKey: '' }])}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium cursor-pointer"
          style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}>
          <Plus size={12} /> Add Header
        </button>
      </div>
      <button
        onClick={onSave}
        disabled={saving}
        className="w-full mt-3 flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium text-white cursor-pointer transition-colors disabled:opacity-50"
        style={{ background: saved ? '#22c55e' : 'var(--color-primary)' }}>
        {saved ? 'Saved' : saving ? 'Saving...' : 'Save Caller Headers'}
      </button>
    </section>
  );
}

function PermissionsQuotaSection({
  user, draft, saved, saving, agents, onChange, onSave,
}: {
  user: ManagedUser;
  draft: ConfigDraft;
  saved: boolean;
  saving: boolean;
  agents: { id: string; name: string }[];
  onChange: (draft: ConfigDraft) => void;
  onSave: () => void;
}) {
  return (
    <section>
      <h3 className="text-xs font-medium uppercase tracking-wider mb-3"
        style={{ color: 'var(--color-text-secondary)' }}>Permissions & Quota</h3>
      <div className="space-y-3">
        <div>
          <label className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            Daily input token quota (millions, 0 = unlimited)
          </label>
          <input
            type="number"
            min="0"
            step="0.1"
            value={draft.inputQuota}
            onChange={(e) => onChange({ ...draft, inputQuota: e.target.value })}
            placeholder="e.g. 1 = 1M input tokens"
            className="w-full mt-1 px-3 py-2 rounded-lg text-sm border-0 outline-none"
            style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}
          />
          {user.input_token_quota != null && user.input_token_quota > 0 && (
            <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
              Used today: {toM(user.quota_consumed_input_tokens)}M / {toM(user.input_token_quota)}M input
            </div>
          )}
        </div>
        <div>
          <label className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            Daily output token quota (millions, 0 = unlimited)
          </label>
          <input
            type="number"
            min="0"
            step="0.1"
            value={draft.outputQuota}
            onChange={(e) => onChange({ ...draft, outputQuota: e.target.value })}
            placeholder="e.g. 1 = 1M output tokens"
            className="w-full mt-1 px-3 py-2 rounded-lg text-sm border-0 outline-none"
            style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}
          />
          {user.output_token_quota != null && user.output_token_quota > 0 && (
            <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
              Used today: {toM(user.quota_consumed_output_tokens)}M / {toM(user.output_token_quota)}M output
            </div>
          )}
        </div>
        <div>
          <label className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            Agent permissions (empty = unrestricted)
          </label>
          <div className="mt-1 space-y-2">
            {draft.permissions.map((p, i) => (
              <div key={i} className="flex items-center gap-2">
                <select
                  value={p.resourceType}
                  onChange={(e) => {
                    const next = [...draft.permissions];
                    next[i] = { ...next[i], resourceType: e.target.value };
                    onChange({ ...draft, permissions: next });
                  }}
                  className="px-2 py-1.5 rounded text-xs border-0 outline-none cursor-pointer"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                  <option value="agent">agent</option>
                </select>
                <select
                  value={p.resourceId}
                  onChange={(e) => {
                    const next = [...draft.permissions];
                    next[i] = { ...next[i], resourceId: e.target.value };
                    onChange({ ...draft, permissions: next });
                  }}
                  className="flex-1 px-2 py-1.5 rounded text-sm border-0 outline-none cursor-pointer"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                  <option value="">Select agent...</option>
                  {agents.map(a => (
                    <option key={a.id} value={a.id}>{a.name}</option>
                  ))}
                  {p.resourceId && !agents.some(a => a.id === p.resourceId) && (
                    <option value={p.resourceId}>{p.resourceId} (deleted)</option>
                  )}
                </select>
                <button
                  onClick={() => onChange({ ...draft, permissions: draft.permissions.filter((_, j) => j !== i) })}
                  className="p-1 rounded hover:opacity-70 cursor-pointer"
                  style={{ color: 'var(--color-text-secondary)' }}
                  title="Remove">
                  <X size={14} />
                </button>
              </div>
            ))}
            <button
              onClick={() => onChange({ ...draft, permissions: [...draft.permissions, { resourceType: 'agent', resourceId: '' }] })}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium cursor-pointer"
              style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}>
              <Plus size={12} /> Add Agent
            </button>
          </div>
        </div>
        <button
          onClick={onSave}
          disabled={saving}
          className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium text-white cursor-pointer transition-colors disabled:opacity-50"
          style={{ background: saved ? '#22c55e' : 'var(--color-primary)' }}>
          {saved ? 'Saved' : saving ? 'Saving...' : 'Save Config'}
        </button>
      </div>
    </section>
  );
}
