import { useEffect, useMemo, useState } from 'react';
import { rbacApi } from '../../api/client';
import { ArrowLeft, CheckCircle, Plus, Save, Trash2, X } from 'lucide-react';

interface RolesTabProps {
  onRolesChanged?: (roles: string[]) => void;
}

const BUILTIN_ROLES = ['admin', 'user', 'member'];

function groupByDomain(catalog: string[]): { domain: string; permissions: string[] }[] {
  const groups = new Map<string, string[]>();
  for (const code of catalog) {
    const domain = code.substring(0, code.indexOf('.'));
    const list = groups.get(domain) || [];
    list.push(code);
    groups.set(domain, list);
  }
  return [...groups.entries()].map(([domain, permissions]) => ({ domain, permissions }));
}

/** RBAC role editor: role list table + slide-in detail drawer, matching the users page layout. */
export default function RolesTab({ onRolesChanged }: RolesTabProps) {
  const [roles, setRoles] = useState<Record<string, string[]>>({});
  const [catalog, setCatalog] = useState<string[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [editingName, setEditingName] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [newRoleName, setNewRoleName] = useState('');

  useEffect(() => {
    rbacApi.listRoles()
      .then(res => {
        setRoles(res.roles || {});
        setCatalog(res.catalog || []);
        setLoading(false);
      })
      .catch(e => {
        setError(e instanceof Error ? e.message : 'Failed to load roles');
        setLoading(false);
      });
  }, []);

  const groups = useMemo(() => groupByDomain(catalog), [catalog]);
  const roleNames = Object.keys(roles);

  const openRole = (name: string) => {
    setSelected(name);
    setEditingName(name);
    setSaved(false);
    setError('');
  };

  const closePanel = () => {
    setSelected(null);
    setSaved(false);
  };

  const save = async () => {
    if (!selected) return;
    setSaving(true);
    setError('');
    try {
      await rbacApi.updateRoles(roles);
      setSaved(true);
      window.setTimeout(() => setSaved(false), 2500);
      onRolesChanged?.(Object.keys(roles));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save roles');
    } finally {
      setSaving(false);
    }
  };

  const addRole = () => {
    const name = newRoleName.trim();
    if (!name || roles[name]) return;
    setRoles({ ...roles, [name]: [] });
    setNewRoleName('');
    setCreateOpen(false);
    openRole(name);
  };

  const renameRole = (oldName: string, newName: string) => {
    const trimmed = newName.trim();
    if (!trimmed || trimmed === oldName || roles[trimmed] || BUILTIN_ROLES.includes(oldName)) return;
    const next = { ...roles };
    next[trimmed] = next[oldName];
    delete next[oldName];
    setRoles(next);
    setSelected(trimmed);
    setEditingName(trimmed);
  };

  const removeRole = (name: string) => {
    if (BUILTIN_ROLES.includes(name)) return;
    const next = { ...roles };
    delete next[name];
    setRoles(next);
    closePanel();
  };

  const togglePermission = (permission: string) => {
    if (!selected) return;
    const current = roles[selected] || [];
    const next = current.includes(permission)
      ? current.filter(p => p !== permission)
      : [...current, permission];
    setRoles({ ...roles, [selected]: next });
    setSaved(false);
  };

  const toggleDomain = (_domain: string, permissions: string[]) => {
    if (!selected) return;
    const current = roles[selected] || [];
    const allGranted = permissions.every(p => current.includes(p));
    const next = allGranted
      ? current.filter(p => !permissions.includes(p))
      : [...new Set([...current, ...permissions])];
    setRoles({ ...roles, [selected]: next });
    setSaved(false);
  };

  if (loading) {
    return <div className="flex items-center justify-center py-12 text-sm" style={{ color: 'var(--color-text-secondary)' }}>Loading roles...</div>;
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          Roles map to page/API permissions. Changes take effect within 5 minutes.
          <span className="ml-2 font-medium" style={{ color: 'var(--color-text)' }}>manage implies view; admin = all.</span>
        </p>
        <button onClick={() => setCreateOpen(true)}
          className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
          style={{ background: 'var(--color-primary)' }}>
          <Plus size={14} /> New Role
        </button>
      </div>

      {error && (
        <div className="px-4 py-3 rounded-lg text-sm" style={{ background: '#ef444420', color: 'var(--color-error)' }}>
          {error}
        </div>
      )}

      {/* Role list table */}
      <div className="rounded-xl border overflow-hidden" style={{ borderColor: 'var(--color-border)' }}>
        <table className="w-full">
          <thead>
            <tr style={{ background: 'var(--color-bg-secondary)' }}>
              <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider"
                style={{ color: 'var(--color-text-secondary)' }}>Name</th>
              <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider"
                style={{ color: 'var(--color-text-secondary)' }}>Type</th>
              <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider"
                style={{ color: 'var(--color-text-secondary)' }}>Permissions</th>
              <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider"
                style={{ color: 'var(--color-text-secondary)' }}>Granted</th>
            </tr>
          </thead>
          <tbody>
            {roleNames.map((name, idx) => (
              <tr key={name}
                className="border-t cursor-pointer hover:opacity-80"
                style={{ borderColor: 'var(--color-border)', background: idx % 2 === 0 ? 'transparent' : 'var(--color-bg-secondary)' }}
                onClick={() => openRole(name)}>
                <td className="px-4 py-3 text-sm font-medium">{name}</td>
                <td className="px-4 py-3 text-sm">
                  <span className="px-2 py-0.5 rounded text-xs font-medium"
                    style={{
                      background: name === 'admin' ? '#f59e0b20' : '#3b82f620',
                      color: name === 'admin' ? '#f59e0b' : '#3b82f6',
                    }}>
                    {name === 'admin' ? 'Superuser' : BUILTIN_ROLES.includes(name) ? 'Builtin' : 'Custom'}
                  </span>
                </td>
                <td className="px-4 py-3 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                  {name === 'admin' ? 'All (implicit)' : `${(roles[name] || []).length} permissions`}
                </td>
                <td className="px-4 py-3 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                  {name === 'admin' ? '*' : (roles[name] || []).slice(0, 4).join(', ') + ((roles[name] || []).length > 4 ? ', …' : '') || '-'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {roleNames.length === 0 && (
          <div className="py-12 text-center" style={{ color: 'var(--color-text-secondary)' }}>No roles found</div>
        )}
      </div>

      {/* Role Detail Drawer */}
      {selected && (
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
                <h2 className="text-lg font-semibold">Role Details</h2>
              </div>
              <button onClick={closePanel}
                className="p-1 rounded hover:opacity-70 transition-opacity cursor-pointer"
                style={{ color: 'var(--color-text-secondary)' }}>
                <X size={18} />
              </button>
            </div>

            <div className="px-6 py-4 space-y-5">
              {/* General */}
              <section>
                <h3 className="text-xs font-medium uppercase tracking-wider mb-3"
                  style={{ color: 'var(--color-text-secondary)' }}>General</h3>
                <div className="space-y-2">
                  <div className="flex items-start justify-between py-1.5">
                    <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Name</span>
                    {BUILTIN_ROLES.includes(selected) ? (
                      <span className="text-sm text-right ml-4">{selected}</span>
                    ) : (
                      <input
                        value={editingName}
                        onChange={(e) => setEditingName(e.target.value)}
                        onBlur={() => renameRole(selected, editingName)}
                        className="text-sm text-right ml-4 rounded px-2 py-0.5 border-0 outline-none"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}
                      />
                    )}
                  </div>
                  <div className="flex items-start justify-between py-1.5">
                    <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Type</span>
                    <span className="text-sm text-right ml-4 capitalize">{selected}</span>
                  </div>
                  {selected === 'admin' && (
                    <div className="p-3 rounded-lg text-sm" style={{ background: '#f59e0b20', color: '#f59e0b' }}>
                      Admin is an implicit wildcard — all permissions granted, not editable.
                    </div>
                  )}
                </div>
              </section>

              {/* Permissions */}
              {selected !== 'admin' && (
                <section>
                  <h3 className="text-xs font-medium uppercase tracking-wider mb-3"
                    style={{ color: 'var(--color-text-secondary)' }}>
                    Permissions ({roles[selected]?.length || 0} granted)
                  </h3>
                  <div className="grid grid-cols-1 gap-x-4 gap-y-3">
                    {groups.map(({ domain, permissions }) => {
                      const current = roles[selected] || [];
                      const allGranted = permissions.every(p => current.includes(p));
                      return (
                        <div key={domain} className="space-y-1">
                          <label className="flex items-center gap-2 text-xs font-medium uppercase tracking-wider cursor-pointer"
                            style={{ color: 'var(--color-text-secondary)' }}>
                            <input type="checkbox" checked={allGranted} onChange={() => toggleDomain(domain, permissions)} />
                            {domain}
                          </label>
                          <div className="pl-5 space-y-0.5">
                            {permissions.map(p => (
                              <label key={p} className="flex items-center gap-2 text-xs cursor-pointer" style={{ color: 'var(--color-text)' }}>
                                <input type="checkbox" checked={current.includes(p)} onChange={() => togglePermission(p)} />
                                {p}
                              </label>
                            ))}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </section>
              )}

              {/* Actions */}
              <section>
                <h3 className="text-xs font-medium uppercase tracking-wider mb-3"
                  style={{ color: 'var(--color-text-secondary)' }}>Actions</h3>
                <div className="space-y-3">
                  {!BUILTIN_ROLES.includes(selected) && (
                    <button
                      onClick={() => removeRole(selected)}
                      className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium cursor-pointer transition-colors"
                      style={{ background: '#ef444420', color: '#ef4444' }}>
                      <Trash2 size={14} /> Delete Role
                    </button>
                  )}
                  {selected !== 'admin' && (
                    <button
                      onClick={save}
                      disabled={saving}
                      className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium text-white cursor-pointer transition-colors disabled:opacity-50"
                      style={{ background: saved ? '#22c55e' : 'var(--color-primary)' }}>
                      {saved ? <CheckCircle size={14} /> : <Save size={14} />}
                      {saved ? 'Saved' : 'Save Role'}
                    </button>
                  )}
                </div>
              </section>
            </div>
          </div>
        </div>
      )}

      {/* Create Role Modal */}
      {createOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div className="absolute inset-0 bg-black/30" onClick={() => setCreateOpen(false)} />
          <div className="relative w-full max-w-md rounded-xl shadow-xl p-6"
            style={{ background: 'var(--color-bg-secondary)' }}>
            <h2 className="text-lg font-semibold mb-2">New Role</h2>
            <p className="text-sm mb-3" style={{ color: 'var(--color-text-secondary)' }}>
              Give the role a name, then grant permissions in the detail panel.
            </p>
            <input
              value={newRoleName}
              onChange={(e) => setNewRoleName(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') addRole(); }}
              placeholder="Role name"
              autoFocus
              className="w-full px-3 py-2 rounded-lg text-sm mb-4 border-0 outline-none"
              style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}
            />
            <div className="flex items-center gap-2">
              <button onClick={addRole} disabled={!newRoleName.trim() || !!roles[newRoleName.trim()]}
                className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
                style={{ background: 'var(--color-primary)' }}>
                <Plus size={14} /> Create
              </button>
              <button onClick={() => setCreateOpen(false)}
                className="px-3 py-2 rounded-lg text-sm cursor-pointer"
                style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
