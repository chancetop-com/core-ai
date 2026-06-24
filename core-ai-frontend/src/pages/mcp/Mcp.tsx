import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Server, Power, PowerOff, Trash2, Edit2, X, Save, ChevronLeft, ChevronRight, ChevronRight as ArrowRight } from 'lucide-react';
import { api } from '../../api/client';
import type { ToolRegistryView, McpConnectionState } from '../../api/client';
import { ConnectionStateBadge, EnabledBadge } from './badges';

export default function Mcp() {
  const navigate = useNavigate();
  const [tools, setTools] = useState<ToolRegistryView[]>([]);
  const [loading, setLoading] = useState(true);
  const [offset, setOffset] = useState(0);
  const [limit, setLimit] = useState(10);
  const [categoryFilter, setCategoryFilter] = useState('');
  const [categories, setCategories] = useState<string[]>([]);
  const [editing, setEditing] = useState<ToolRegistryView | null>(null);
  const [creating, setCreating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [statusMap, setStatusMap] = useState<Record<string, McpConnectionState>>({});

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([
      api.tools.list(categoryFilter || undefined),
      api.tools.categories(),
    ]).then(([res, catRes]) => {
      setTools(res.tools || []);
      setCategories(catRes.categories || []);
    }).catch(() => {
      setTools([]);
      setCategories([]);
    }).finally(() => setLoading(false));
  }, [categoryFilter]);

  useEffect(load, [load]);

  const mcpServers = tools.filter(t => t.type === 'MCP');
  const pagedServers = mcpServers.slice(offset, offset + limit);

  useEffect(() => {
    if (pagedServers.length === 0) return;
    let cancelled = false;
    pagedServers.forEach(s => {
      if (!s.enabled) return;
      api.tools.getMcpServerStatus(s.id).then(r => {
        if (!cancelled) setStatusMap(prev => ({ ...prev, [s.id]: r.state }));
      }).catch(() => {});
    });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [offset, limit, mcpServers.length]);

  const handleToggle = async (server: ToolRegistryView) => {
    try {
      if (server.enabled) {
        await api.tools.disableMcpServer(server.id);
      } else {
        await api.tools.enableMcpServer(server.id);
      }
      load();
    } catch (err) {
      console.error('Failed to toggle MCP server:', err);
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Delete this MCP server?')) return;
    await api.tools.deleteMcpServer(id);
    load();
  };

  const handleSave = async () => {
    if (!editing) return;
    setSaving(true);
    try {
      if (creating) {
        await api.tools.createMcpServer({
          name: editing.name,
          description: editing.description,
          category: editing.category,
          config: editing.config,
          enabled: editing.enabled,
        });
      } else {
        await api.tools.updateMcpServer(editing.id, {
          name: editing.name,
          description: editing.description,
          category: editing.category,
          config: editing.config,
          enabled: editing.enabled,
        });
      }
      setEditing(null);
      setCreating(false);
      load();
    } catch (err) {
      console.error('Failed to save MCP server:', err);
      alert('Failed to save. Check console for details.');
    } finally {
      setSaving(false);
    }
  };

  const handleImported = () => {
    setEditing(null);
    setCreating(false);
    load();
  };

  // Parse config keys for display
  const getConfigSummary = (config: Record<string, string>) => {
    const keys = Object.keys(config);
    if (keys.length === 0) return 'No config';
    return keys.slice(0, 3).join(', ') + (keys.length > 3 ? ' +' + (keys.length - 3) : '');
  };

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">MCP Servers</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Manage Model Context Protocol server connections
          </p>
        </div>
        <button onClick={() => {
          setEditing({
            id: '',
            name: '',
            description: '',
            type: 'MCP',
            category: '',
            config: {},
            enabled: true,
          });
          setCreating(true);
        }}
          className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
          style={{ background: 'var(--color-primary)' }}>
          <Plus size={16} /> New MCP Server
        </button>
      </div>

      {/* Filters */}
      {categories.length > 0 && (
        <div className="flex items-center gap-3 mb-4">
          <select
            value={categoryFilter}
            onChange={e => { setCategoryFilter(e.target.value); setOffset(0); }}
            className="px-3 py-2 rounded-lg border text-sm"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
            <option value="">All Categories</option>
            {categories.map(c => <option key={c} value={c}>{c}</option>)}
          </select>
          <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            {mcpServers.length} server{mcpServers.length !== 1 ? 's' : ''}
          </span>
        </div>
      )}

      {/* Server list */}
      <div className="grid gap-4">
        {loading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : mcpServers.length === 0 ? (
          <div className="text-center py-12 rounded-xl border"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            No MCP servers configured. Click &ldquo;New MCP Server&rdquo; to add one.
          </div>
        ) : pagedServers.map(s => (
          <div key={s.id}
            className="rounded-xl border p-4 transition-colors cursor-pointer hover:opacity-90"
            style={{
              background: s.enabled ? 'var(--color-bg-secondary)' : 'var(--color-bg-secondary)',
              borderColor: s.enabled ? 'var(--color-border)' : '#7f1d1d',
              opacity: s.enabled ? 1 : 0.7,
            }}
            onClick={() => navigate(`/mcp/${encodeURIComponent(s.id)}`)}>
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-start gap-3 min-w-0 flex-1">
                <Server size={18} className="shrink-0 mt-0.5" style={{ color: s.enabled ? 'var(--color-primary)' : 'var(--color-text-secondary)' }} />
                <div className="min-w-0 flex-1">
                  <div className="font-medium break-words" title={s.name}>{s.name}</div>
                  <div className="flex flex-wrap items-center gap-2 mt-1.5">
                    <EnabledBadge enabled={s.enabled} />
                    {s.enabled && <ConnectionStateBadge state={statusMap[s.id]} />}
                    {s.config?.transport === 'sandbox_hosted' && (
                      <span className="px-2 py-0.5 rounded text-xs font-medium"
                        style={{ background: '#1e3a5f', color: '#93c5fd' }}>
                        Dynamic MCP
                      </span>
                    )}
                    {s.category && (
                      <span className="px-2 py-0.5 rounded text-xs"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                        {s.category}
                      </span>
                    )}
                  </div>
                </div>
              </div>
              <div className="flex items-center gap-2 shrink-0" onClick={e => e.stopPropagation()}>
                <button onClick={() => {
                  setEditing({ ...s });
                  setCreating(false);
                }}
                  className="p-1.5 rounded border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)' }}
                  title="Edit">
                  <Edit2 size={14} />
                </button>
                <button onClick={() => handleToggle(s)}
                  className="p-1.5 rounded border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)' }}
                  title={s.enabled ? 'Disable' : 'Enable'}>
                  {s.enabled ? <PowerOff size={14} /> : <Power size={14} />}
                </button>
                <button onClick={() => handleDelete(s.id)}
                  className="p-1.5 rounded border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)', color: '#f87171' }}
                  title="Delete">
                  <Trash2 size={14} />
                </button>
                <button onClick={() => navigate(`/mcp/${encodeURIComponent(s.id)}`)}
                  className="p-1.5 rounded border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)' }}
                  title="Open details">
                  <ArrowRight size={14} />
                </button>
              </div>
            </div>
            <div className="ml-[30px] mt-2 space-y-1">
              {s.description && (
                <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>{s.description}</p>
              )}
              <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                Config keys: {getConfigSummary(s.config)}
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Pagination */}
      {mcpServers.length > 0 && (
        <div className="flex items-center justify-between mt-4">
          <div className="flex items-center gap-3">
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Showing {offset + 1}-{Math.min(offset + limit, mcpServers.length)} of {mcpServers.length}
            </span>
            <select value={limit}
              onChange={e => { setLimit(Number(e.target.value)); setOffset(0); }}
              className="px-2 py-1 rounded-lg border text-xs"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
              {[10, 20, 50].map(n => <option key={n} value={n}>{n} / page</option>)}
            </select>
          </div>
          <div className="flex gap-2">
            <button onClick={() => setOffset(Math.max(0, offset - limit))} disabled={offset === 0}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <ChevronLeft size={14} /> Prev
            </button>
            <button onClick={() => setOffset(offset + limit)} disabled={offset + limit >= mcpServers.length}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              Next <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}

      {/* Edit/Create Modal */}
      {editing && (
        <McpServerModal
          server={editing}
          onChange={setEditing}
          onSave={handleSave}
          onImported={handleImported}
          onClose={() => { setEditing(null); setCreating(false); }}
          saving={saving}
          creating={creating}
        />
      )}
    </div>
  );
}

function McpServerModal({
  server,
  onChange,
  onSave,
  onImported,
  onClose,
  saving,
  creating,
}: {
  server: ToolRegistryView;
  onChange: (s: ToolRegistryView) => void;
  onSave: () => void;
  onImported: () => void;
  onClose: () => void;
  saving: boolean;
  creating: boolean;
}) {
  const [configKey, setConfigKey] = useState('');
  const [configValue, setConfigValue] = useState('');
  const [mode, setMode] = useState<'manual' | 'import'>('manual');
  const [importJson, setImportJson] = useState('');
  const [importError, setImportError] = useState<string | null>(null);

  const handleImport = async () => {
    if (!importJson.trim()) return;
    setImportError(null);
    try {
      const result = await api.tools.importMcpServers({ config: importJson, category: server.category || undefined, enabled: server.enabled });
      setImportJson('');
      onImported();
      alert('Imported ' + result.total + ' MCP server' + (result.total !== 1 ? 's' : '') + ' successfully.');
    } catch (err) {
      setImportError(err instanceof Error ? err.message : 'Import failed');
    }
  };

  const addConfigEntry = () => {
    if (!configKey.trim()) return;
    onChange({
      ...server,
      config: { ...server.config, [configKey.trim()]: configValue },
    });
    setConfigKey('');
    setConfigValue('');
  };

  const removeConfigEntry = (key: string) => {
    const newConfig = { ...server.config };
    delete newConfig[key];
    onChange({ ...server, config: newConfig });
  };

  const showImport = creating && mode === 'import';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center"
      style={{ background: 'rgba(0,0,0,0.5)' }}
      onClick={onClose}>
      <div className="w-full max-w-lg mx-4 rounded-xl border p-6"
        style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}
        onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold">{creating ? 'New MCP Server' : 'Edit MCP Server'}</h2>
          <button onClick={onClose} className="p-1 rounded cursor-pointer" style={{ color: 'var(--color-text-secondary)' }}>
            <X size={18} />
          </button>
        </div>

        {creating && (
          <div className="flex gap-1 mb-4 p-1 rounded-lg" style={{ background: 'var(--color-bg-tertiary)' }}>
            <button onClick={() => setMode('manual')}
              className="flex-1 py-1.5 rounded-md text-xs font-medium cursor-pointer"
              style={{
                background: mode === 'manual' ? 'var(--color-bg)' : 'transparent',
                color: mode === 'manual' ? 'var(--color-text)' : 'var(--color-text-secondary)',
              }}>Manual</button>
            <button onClick={() => setMode('import')}
              className="flex-1 py-1.5 rounded-md text-xs font-medium cursor-pointer"
              style={{
                background: mode === 'import' ? 'var(--color-bg)' : 'transparent',
                color: mode === 'import' ? 'var(--color-text)' : 'var(--color-text-secondary)',
              }}>Import JSON</button>
          </div>
        )}

        {showImport ? (
          <div className="space-y-4">
            <div>
              <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>mcpServers JSON</label>
              <textarea value={importJson} onChange={e => { setImportJson(e.target.value); setImportError(null); }}
                placeholder={`{"mcpServers":{"MyServer":{"command":"npx","args":["-y","@scope/server"],"env":{"KEY":"value"}}}}`}
                spellCheck={false}
                className="w-full font-mono text-xs px-3 py-3 rounded-lg border resize-y"
                style={{ minHeight: 200, borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
              {importError && (
                <div className="text-xs rounded p-2 mt-2" style={{ background: '#7f1d1d', color: '#fff' }}>{importError}</div>
              )}
            </div>
            <div>
              <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>Category</label>
              <input type="text" value={server.category}
                onChange={e => onChange({ ...server, category: e.target.value })}
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
            </div>
            <div className="flex items-center gap-2">
              <label className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>Enabled</label>
              <input type="checkbox" checked={server.enabled}
                onChange={e => onChange({ ...server, enabled: e.target.checked })}
                className="w-4 h-4" />
            </div>
          </div>
        ) : (
          <div className="space-y-4">
            <div>
              <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>Name *</label>
              <input type="text" value={server.name}
                onChange={e => onChange({ ...server, name: e.target.value })}
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
            </div>
            <div>
              <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>Description</label>
              <input type="text" value={server.description}
                onChange={e => onChange({ ...server, description: e.target.value })}
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
            </div>
            <div>
              <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>Category</label>
              <input type="text" value={server.category}
                onChange={e => onChange({ ...server, category: e.target.value })}
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
            </div>

            {/* Config entries */}
            <div>
              <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>Configuration</label>
              <div className="space-y-2 mb-2">
                {Object.entries(server.config).map(([k, v]) => (
                  <div key={k} className="flex items-center gap-2">
                    <span className="text-xs font-mono flex-1 px-2 py-1 rounded"
                      style={{ background: 'var(--color-bg-tertiary)' }}>
                      {k}: {v}
                    </span>
                    <button onClick={() => removeConfigEntry(k)}
                      className="p-1 rounded cursor-pointer"
                      style={{ color: '#f87171' }}>
                      <X size={14} />
                    </button>
                  </div>
                ))}
              </div>
              <div className="flex items-center gap-2">
                <input type="text" placeholder="Key" value={configKey}
                  onChange={e => setConfigKey(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') addConfigEntry(); }}
                  className="flex-1 px-2 py-1.5 rounded border text-xs"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                <input type="text" placeholder="Value" value={configValue}
                  onChange={e => setConfigValue(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') addConfigEntry(); }}
                  className="flex-1 px-2 py-1.5 rounded border text-xs"
                  style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }} />
                <button onClick={addConfigEntry}
                  className="px-2 py-1.5 rounded text-xs cursor-pointer"
                  style={{ background: 'var(--color-primary)', color: 'white' }}>
                  Add
                </button>
              </div>
            </div>

            <div className="flex items-center gap-2">
              <label className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>Enabled</label>
              <input type="checkbox" checked={server.enabled}
                onChange={e => onChange({ ...server, enabled: e.target.checked })}
                className="w-4 h-4" />
            </div>
          </div>
        )}

        <div className="flex justify-end gap-2 mt-6">
          <button onClick={onClose}
            className="px-4 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            Cancel
          </button>
          {showImport ? (
            <button onClick={handleImport} disabled={saving || !importJson.trim()}
              className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
              style={{ background: 'var(--color-primary)' }}>
              <Save size={14} /> {saving ? 'Importing...' : 'Import'}
            </button>
          ) : (
            <button onClick={onSave} disabled={saving || !server.name}
              className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
              style={{ background: 'var(--color-primary)' }}>
              <Save size={14} /> {saving ? 'Saving...' : creating ? 'Create' : 'Save'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
