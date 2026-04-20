import { useState, useEffect, useCallback } from 'react';
import { Users, CheckCircle, XCircle, RefreshCw } from 'lucide-react';
import { adminApi } from '../../api/client';

interface UserStatus {
  email: string;
  name: string;
  role: string;
  status: string;
  created_at: string;
}

export default function UserManagement() {
  const [users, setUsers] = useState<UserStatus[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionLoading, setActionLoading] = useState<string | null>(null);

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

  useEffect(() => {
    loadUsers();
  }, [loadUsers]);

  const handleUpdateStatus = async (email: string, status: string) => {
    setActionLoading(email);
    try {
      await adminApi.updateUserStatus(email, status);
      await loadUsers();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update user');
    } finally {
      setActionLoading(null);
    }
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
        <button onClick={loadUsers}
          className="flex items-center gap-2 px-3 py-2 rounded-lg text-sm cursor-pointer transition-colors"
          style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
          <RefreshCw size={14} />
          Refresh
        </button>
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
                <th className="px-4 py-3 text-right text-xs font-medium uppercase tracking-wider"
                  style={{ color: 'var(--color-text-secondary)' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {users.map((user, idx) => (
                <tr key={user.email}
                  className="border-t"
                  style={{ borderColor: 'var(--color-border)', background: idx % 2 === 0 ? 'transparent' : 'var(--color-bg-secondary)' }}>
                  <td className="px-4 py-3 text-sm font-mono">{user.email}</td>
                  <td className="px-4 py-3 text-sm">{user.name || '-'}</td>
                  <td className="px-4 py-3 text-sm">{getRoleBadge(user.role)}</td>
                  <td className="px-4 py-3 text-sm">{getStatusBadge(user.status)}</td>
                  <td className="px-4 py-3 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                    {user.created_at ? new Date(user.created_at).toLocaleDateString() : '-'}
                  </td>
                  <td className="px-4 py-3 text-right">
                    {user.role !== 'admin' && (
                      actionLoading === user.email ? (
                        <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                          Updating...
                        </span>
                      ) : user.status === 'pending' ? (
                        <button
                          onClick={() => handleUpdateStatus(user.email, 'active')}
                          className="flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-medium text-white cursor-pointer"
                          style={{ background: '#22c55e' }}>
                          <CheckCircle size={12} />
                          Approve
                        </button>
                      ) : (
                        <button
                          onClick={() => handleUpdateStatus(user.email, 'pending')}
                          className="flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-medium cursor-pointer"
                          style={{ background: '#f59e0b', color: 'white' }}>
                          <XCircle size={12} />
                          Deactivate
                        </button>
                      )
                    )}
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
    </div>
  );
}