import { useState, useEffect, useCallback } from 'react';
import { Users, CheckCircle, XCircle, RefreshCw, Trash2, Key, KeyRound, X, ArrowLeft } from 'lucide-react';
import { adminApi, type UserStatus } from '../../api/client';

export default function UserManagement() {
  const [users, setUsers] = useState<UserStatus[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionLoading, setActionLoading] = useState(false);
  const [selectedUser, setSelectedUser] = useState<UserStatus | null>(null);
  const [newApiKey, setNewApiKey] = useState<string | null>(null);
  const [roleLoading, setRoleLoading] = useState(false);
  const [resetLoading, setResetLoading] = useState(false);
  const [generatedPassword, setGeneratedPassword] = useState('');

  const loadUsers = useCallback(async () => {
    try {
      setError('');
      const res = await adminApi.listUsers();
      setUsers(res.users);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load users');
    } finally {
      setLoading(false);
    }
  }, []);

  const refreshUser = useCallback(async () => {
    const res = await adminApi.listUsers();
    setUsers(res.users);
    if (selectedUser) {
      const updated = res.users.find(u => u.email === selectedUser.email);
      if (updated) setSelectedUser(updated);
    }
  }, [selectedUser]);

  useEffect(() => {
    loadUsers();
  }, [loadUsers]);

  const handleUpdateStatus = async (email: string, status: string) => {
    setActionLoading(true);
    try {
      await adminApi.updateUserStatus(email, status);
      await refreshUser();
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
      await refreshUser();
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
      await refreshUser();
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
      await refreshUser();
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
      await refreshUser();
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

  const closePanel = () => {
    setSelectedUser(null);
    setNewApiKey(null);
    setGeneratedPassword('');
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

  const getRoleBadge = (role: string) => {
    const isAdmin = role === 'admin';
    return (
      <span className="px-2 py-0.5 rounded text-xs font-medium"
        style={{
          background: isAdmin ? '#8b5cf620' : '#3b82f620',
          color: isAdmin ? '#8b5cf6' : '#3b82f6',
        }}>
        {role}
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
                  style={{ color: 'var(--color-text-secondary)' }}>Email</th>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider"
                  style={{ color: 'var(--color-text-secondary)' }}>Name</th>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider"
                  style={{ color: 'var(--color-text-secondary)' }}>Role</th>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider"
                  style={{ color: 'var(--color-text-secondary)' }}>Status</th>
                <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider"
                  style={{ color: 'var(--color-text-secondary)' }}>Created</th>
              </tr>
            </thead>
            <tbody>
              {users.map((user, idx) => (
                <tr key={user.email}
                  className="border-t"
                  style={{ borderColor: 'var(--color-border)', background: idx % 2 === 0 ? 'transparent' : 'var(--color-bg-secondary)' }}>
                  <td className="px-4 py-3 text-sm font-mono">
                    <button
                      onClick={() => { setSelectedUser(user); setNewApiKey(null); setGeneratedPassword(''); }}
                      className="text-left cursor-pointer hover:underline"
                      style={{ color: 'var(--color-primary)' }}>
                      {user.email}
                    </button>
                  </td>
                  <td className="px-4 py-3 text-sm">{user.name || '-'}</td>
                  <td className="px-4 py-3 text-sm">{getRoleBadge(user.role)}</td>
                  <td className="px-4 py-3 text-sm">{getStatusBadge(user.status)}</td>
                  <td className="px-4 py-3 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                    {user.created_at ? new Date(user.created_at).toLocaleDateString() : '-'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {users.length === 0 && (
            <div className="py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>
              No users found
            </div>
          )}
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

            {/* Panel Content */}
            <div className="px-6 py-4 space-y-5">
              {/* General Info */}
              <section>
                <h3 className="text-xs font-medium uppercase tracking-wider mb-3"
                  style={{ color: 'var(--color-text-secondary)' }}>General</h3>
                <div className="space-y-2">
                  <InfoRow label="Email" value={selectedUser.email} mono />
                  <InfoRow label="Name" value={selectedUser.name || '-'} />
                  <div className="flex items-start justify-between py-1.5">
                    <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Role</span>
                    <select
                      value={selectedUser.role}
                      onChange={(e) => handleUpdateRole(selectedUser.email, e.target.value)}
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

              {/* API Key Section */}
              <section>
                <h3 className="text-xs font-medium uppercase tracking-wider mb-3"
                  style={{ color: 'var(--color-text-secondary)' }}>API Key</h3>
                {newApiKey ? (
                  <div className="p-3 rounded-lg mb-2" style={{ background: '#22c55e10', border: '1px solid #22c55e30' }}>
                    <div className="text-xs font-medium mb-1" style={{ color: '#22c55e' }}>New API Key Generated</div>
                    <code className="text-xs block break-all mb-2" style={{ color: 'var(--color-text)' }}>
                      {newApiKey}
                    </code>
                    <button onClick={() => { navigator.clipboard.writeText(newApiKey); }}
                      className="text-xs underline cursor-pointer mr-3"
                      style={{ color: 'var(--color-text-secondary)' }}>
                      copy
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

              {/* Actions */}
              <section>
                <h3 className="text-xs font-medium uppercase tracking-wider mb-3"
                  style={{ color: 'var(--color-text-secondary)' }}>Actions</h3>
                <div className="space-y-3">
                  {selectedUser.status === 'pending' ? (
                        <button
                          onClick={() => handleUpdateStatus(selectedUser.email, 'active')}
                          disabled={actionLoading}
                          className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium text-white cursor-pointer transition-colors disabled:opacity-50"
                          style={{ background: '#22c55e' }}>
                          <CheckCircle size={14} />
                          Approve User
                        </button>
                      ) : (
                        <button
                          onClick={() => handleUpdateStatus(selectedUser.email, 'pending')}
                          disabled={actionLoading}
                          className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium text-white cursor-pointer transition-colors disabled:opacity-50"
                          style={{ background: '#f59e0b' }}>
                          <XCircle size={14} />
                          Deactivate User
                        </button>
                      )}

                      <div className="h-px" style={{ background: 'var(--color-border)' }} />

                      <button
                        onClick={() => handleGenerateApiKey(selectedUser.email)}
                        disabled={actionLoading}
                        className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium cursor-pointer transition-colors disabled:opacity-50"
                        style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}>
                        <KeyRound size={14} />
                        {selectedUser.has_api_key ? 'Regenerate API Key' : 'Generate API Key'}
                      </button>

                      {selectedUser.has_api_key && (
                        <button
                          onClick={() => handleRevokeApiKey(selectedUser.email)}
                          disabled={actionLoading}
                          className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium cursor-pointer transition-colors disabled:opacity-50"
                          style={{ background: '#ef444410', color: '#ef4444' }}>
                          <XCircle size={14} />
                          Revoke API Key
                        </button>
                      )}

                      <div className="h-px" style={{ background: 'var(--color-border)' }} />

                      <button
                        onClick={() => handleDeleteUser(selectedUser.email)}
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
                          <button onClick={() => { navigator.clipboard.writeText(generatedPassword); }}
                            className="text-xs underline cursor-pointer mr-3"
                            style={{ color: 'var(--color-text-secondary)' }}>
                            copy
                          </button>
                          <button onClick={() => setGeneratedPassword('')}
                            className="text-xs underline cursor-pointer"
                            style={{ color: 'var(--color-text-secondary)' }}>
                            dismiss
                          </button>
                        </div>
                      ) : (
                        <button
                          onClick={() => handleResetPassword(selectedUser.email)}
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
