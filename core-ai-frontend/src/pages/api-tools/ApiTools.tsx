import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Key, Power, PowerOff, Trash2, Edit2, X, Save, ChevronLeft, ChevronRight, RefreshCw, ExternalLink } from 'lucide-react';
import { api } from '../../api/client';
import type { ServiceApiView } from '../../api/client';

export default function ApiTools() {
  const navigate = useNavigate();
  const [serviceApis, setServiceApis] = useState<ServiceApiView[]>([]);
  const [loading, setLoading] = useState(true);
  const [offset, setOffset] = useState(0);
  const [limit, setLimit] = useState(10);
  const [editing, setEditing] = useState<ServiceApiView | null>(null);
  const [creating, setCreating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [updatingFromSys, setUpdatingFromSys] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    api.serviceApis.list()
      .then(res => {
        setServiceApis(res.service_apis || []);
      })
      .catch(() => {
        setServiceApis([]);
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(load, [load]);

  const pagedApis = serviceApis.slice(offset, offset + limit);

  const handleToggle = async (apiItem: ServiceApiView) => {
    try {
      if (apiItem.enabled) {
        await api.serviceApis.disable(apiItem.id, 'admin');
      } else {
        await api.serviceApis.enable(apiItem.id, 'admin');
      }
      load();
    } catch (err) {
      console.error('Failed to toggle service API:', err);
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Delete this service API?')) return;
    await api.serviceApis.delete(id);
    load();
  };

  const handleUpdateFromSysApi = async (apiItem: ServiceApiView) => {
    if (!apiItem.url) {
      alert('No URL configured for this service API');
      return;
    }
    if (!confirm(`Update from system API at ${apiItem.url}?`)) return;
    setUpdatingFromSys(apiItem.id);
    try {
      await api.serviceApis.updateFromSysApi(apiItem.id, apiItem.url, 'admin');
      load();
    } catch (err) {
      console.error('Failed to update from system API:', err);
      alert('Failed to update from system API. Check console for details.');
    } finally {
      setUpdatingFromSys(null);
    }
  };

  const handleSave = async () => {
    if (!editing) return;
    setSaving(true);
    try {
      if (creating) {
        await api.serviceApis.create({
          name: editing.name,
          description: editing.description,
          operator: 'admin',
        });
      } else {
        await api.serviceApis.update(editing.id, {
          description: editing.description,
          enabled: editing.enabled,
          url: editing.url,
          base_url: editing.base_url,
          operator: 'admin',
        });
      }
      setEditing(null);
      setCreating(false);
      load();
    } catch (err) {
      console.error('Failed to save service API:', err);
      alert('Failed to save. Check console for details.');
    } finally {
      setSaving(false);
    }
  };

  const getOperationCount = (apiItem: ServiceApiView) => {
    if (!apiItem.service_additional) return 0;
    return apiItem.service_additional.reduce(
      (sum, s) => sum + (s.operation_additional?.length || 0),
      0
    );
  };

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">API Tools</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Manage Service API definitions and tool integrations
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={() => {
              setEditing({
                id: '',
                name: '',
                description: '',
                enabled: true,
                base_url: '',
                url: '',
                version: '',
                payload: '',
                service_additional: [],
                type_additional: [],
                created_by: '',
                created_at: '',
                updated_by: '',
                updated_at: '',
              });
              setCreating(true);
            }}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            <Plus size={16} /> New API
          </button>
        </div>
      </div>

      {/* API list */}
      <div className="grid gap-4">
        {loading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : serviceApis.length === 0 ? (
          <div className="text-center py-12 rounded-xl border"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            No service APIs configured. Click "New API" to add one.
          </div>
        ) : pagedApis.map(s => (
          <div key={s.id}
            className="rounded-xl border p-4 transition-colors"
            style={{
              background: 'var(--color-bg-secondary)',
              borderColor: s.enabled ? 'var(--color-border)' : '#7f1d1d',
              opacity: s.enabled ? 1 : 0.7,
            }}>
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <Key size={18} style={{ color: s.enabled ? 'var(--color-primary)' : 'var(--color-text-secondary)' }} />
                <button onClick={() => navigate(`/api-tools/${s.id}`)}
                  className="font-medium hover:underline flex items-center gap-1 cursor-pointer text-left"
                  style={{ background: 'none', border: 'none', padding: 0 }}>
                  {s.name}
                  <ExternalLink size={12} style={{ color: 'var(--color-text-secondary)' }} />
                </button>
                <span className={`px-2 py-0.5 rounded text-xs ${s.enabled ? 'text-white' : ''}`}
                  style={s.enabled
                    ? { background: '#065f46' }
                    : { background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                  {s.enabled ? 'Enabled' : 'Disabled'}
                </span>
                {s.url && (
                  <span className="px-2 py-0.5 rounded text-xs font-mono"
                    style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                    {s.url}
                  </span>
                )}
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={() => handleUpdateFromSysApi(s)}
                  disabled={updatingFromSys === s.id || !s.url}
                  className="p-1.5 rounded border cursor-pointer disabled:opacity-40"
                  style={{ borderColor: 'var(--color-border)' }}
                  title="Update from System API">
                  <RefreshCw size={14} className={updatingFromSys === s.id ? 'animate-spin' : ''} />
                </button>
                <button
                  onClick={() => { setEditing({ ...s }); setCreating(false); }}
                  className="p-1.5 rounded border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)' }}
                  title="Edit">
                  <Edit2 size={14} />
                </button>
                <button
                  onClick={() => handleToggle(s)}
                  className="p-1.5 rounded border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)' }}
                  title={s.enabled ? 'Disable' : 'Enable'}>
                  {s.enabled ? <PowerOff size={14} /> : <Power size={14} />}
                </button>
                <button
                  onClick={() => handleDelete(s.id)}
                  className="p-1.5 rounded border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)', color: '#f87171' }}
                  title="Delete">
                  <Trash2 size={14} />
                </button>
              </div>
            </div>
            {s.description && (
              <p className="text-sm mt-1 ml-7" style={{ color: 'var(--color-text-secondary)' }}>{s.description}</p>
            )}
            <div className="mt-1 ml-7 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              Operations: {getOperationCount(s)} | Version: {s.version || 'N/A'} | Updated: {s.updated_at ? new Date(s.updated_at).toLocaleString() : 'N/A'}
            </div>
          </div>
        ))}
      </div>

      {/* Pagination */}
      {serviceApis.length > 0 && (
        <div className="flex items-center justify-between mt-4">
          <div className="flex items-center gap-3">
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Showing {offset + 1}-{Math.min(offset + limit, serviceApis.length)} of {serviceApis.length}
            </span>
            <select value={limit}
              onChange={e => { setLimit(Number(e.target.value)); setOffset(0); }}
              className="px-2 py-1 rounded-lg border text-xs"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
              {[10, 20, 50].map(n => <option key={n} value={n}>{n} / page</option>)}
            </select>
          </div>
          <div className="flex gap-2">
            <button
              onClick={() => setOffset(Math.max(0, offset - limit))}
              disabled={offset === 0}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <ChevronLeft size={14} /> Prev
            </button>
            <button
              onClick={() => setOffset(offset + limit)}
              disabled={offset + limit >= serviceApis.length}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              Next <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}

      {/* Edit/Create Modal */}
      {editing && (
        <ApiToolModal
          api={editing}
          onChange={setEditing}
          onSave={handleSave}
          onClose={() => { setEditing(null); setCreating(false); }}
          saving={saving}
          creating={creating}
        />
      )}
    </div>
  );
}

function ApiToolModal({
  api: apiItem,
  onChange,
  onSave,
  onClose,
  saving,
  creating,
}: {
  api: ServiceApiView;
  onChange: (s: ServiceApiView) => void;
  onSave: () => void;
  onClose: () => void;
  saving: boolean;
  creating: boolean;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center"
      style={{ background: 'rgba(0,0,0,0.5)' }}
      onClick={onClose}>
      <div className="w-full max-w-lg mx-4 rounded-xl border p-6"
        style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}
        onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold">{creating ? 'New Service API' : 'Edit Service API'}</h2>
          <button onClick={onClose} className="p-1 rounded cursor-pointer" style={{ color: 'var(--color-text-secondary)' }}>
            <X size={18} />
          </button>
        </div>

        <div className="space-y-4">
          <div>
            <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>Name *</label>
            <input type="text" value={apiItem.name}
              onChange={e => onChange({ ...apiItem, name: e.target.value })}
              className="w-full px-3 py-2 rounded-lg border text-sm"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
          </div>
          <div>
            <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>Description</label>
            <input type="text" value={apiItem.description}
              onChange={e => onChange({ ...apiItem, description: e.target.value })}
              className="w-full px-3 py-2 rounded-lg border text-sm"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
          </div>
          {!creating && (
            <>
              <div>
                <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>Base URL</label>
                <input type="text" value={apiItem.base_url}
                  onChange={e => onChange({ ...apiItem, base_url: e.target.value })}
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
              </div>
              <div>
                <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>API Definition URL</label>
                <input type="text" value={apiItem.url}
                  onChange={e => onChange({ ...apiItem, url: e.target.value })}
                  placeholder="https://example.com/api/definition.json"
                  className="w-full px-3 py-2 rounded-lg border text-sm"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
              </div>
              <div className="flex items-center gap-2">
                <label className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>Enabled</label>
                <input type="checkbox" checked={apiItem.enabled}
                  onChange={e => onChange({ ...apiItem, enabled: e.target.checked })}
                  className="w-4 h-4" />
              </div>
            </>
          )}
        </div>

        <div className="flex justify-end gap-2 mt-6">
          <button onClick={onClose}
            className="px-4 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            Cancel
          </button>
          <button onClick={onSave} disabled={saving || !apiItem.name}
            className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
            style={{ background: 'var(--color-primary)' }}>
            <Save size={14} /> {saving ? 'Saving...' : creating ? 'Create' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  );
}
