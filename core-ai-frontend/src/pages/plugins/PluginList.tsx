import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ChevronLeft,
  ChevronRight,
  Download,
  ExternalLink,
  Package,
  Plus,
  Puzzle,
  RefreshCw,
  Search,
  Trash2,
  X,
} from 'lucide-react';
import { api } from '../../api/client';
import type { PluginRegistryEntry, RegisterPluginRequest } from '../../api/client';
import { useAuth } from '../../api/auth';
import { hasPermission } from '../../api/permissions';
import { useListState } from '../../hooks/useListState';

const PAGE_SIZES = [10, 20, 50];

const MANIFEST_TEMPLATE = `{
  "name": "Example Plugin",
  "version": "1.0.0",
  "description": "What this plugin does",
  "coreai": {
    "schemaVersion": 1,
    "id": "com.example.plugin",
    "desktopApi": ">=1.0.0",
    "capabilities": {
      "required": ["ui.dock"],
      "optional": []
    },
    "components": [],
    "contributes": {
      "dock": [
        {
          "id": "main",
          "title": "Example",
          "blocks": [{ "type": "text", "text": "Hello from the plugin" }]
        }
      ]
    }
  }
}`;

interface PublishForm extends RegisterPluginRequest {
  manifest: string;
}

const EMPTY_FORM: PublishForm = {
  manifest: MANIFEST_TEMPLATE,
  download_url: '',
  sha256: '',
  size: null,
  vendor: '',
  homepage: '',
};

function formatBytes(bytes: number | null | undefined): string {
  if (!bytes || bytes <= 0) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatTime(value: string | null | undefined): string {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return date.toLocaleString();
}

function ManifestPreview({ manifest }: { manifest: string }) {
  const parsed = useMemo(() => {
    try {
      const root = JSON.parse(manifest) as Record<string, unknown>;
      const coreai = (root.coreai ?? {}) as Record<string, unknown>;
      return {
        id: typeof coreai.id === 'string' ? coreai.id : null,
        name: typeof root.name === 'string' ? root.name : null,
        version: typeof root.version === 'string' ? root.version : null,
      };
    } catch {
      return null;
    }
  }, [manifest]);

  return (
    <div className="flex flex-wrap items-center gap-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
      {parsed ? (
        <>
          <span className="px-2 py-0.5 rounded font-mono" style={{ background: 'var(--color-bg-tertiary)' }}>
            {parsed.id ?? 'no coreai.id'}
          </span>
          <span>{parsed.name ?? 'unnamed'}</span>
          <span>v{parsed.version ?? '?'}</span>
        </>
      ) : (
        <span style={{ color: '#f87171' }}>manifest is not valid JSON</span>
      )}
    </div>
  );
}

export default function PluginList() {
  const { user } = useAuth();
  const canManage = hasPermission(user?.permissions, 'plugin.manage');

  const [plugins, setPlugins] = useState<PluginRegistryEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [offset, setOffset] = useListState('plugins.offset', 0);
  const [limit, setLimit] = useListState('plugins.limit', 10);
  const [search, setSearch] = useListState('plugins.search', '');
  const [detail, setDetail] = useState<PluginRegistryEntry | null>(null);
  const [publishing, setPublishing] = useState(false);
  const [form, setForm] = useState<PublishForm>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    api.plugins.list(search.trim() || undefined, offset, limit)
      .then(res => {
        setPlugins(res.plugins || []);
        setTotal(res.total || 0);
      })
      .catch(err => {
        setPlugins([]);
        setTotal(0);
        setError(err instanceof Error ? err.message : 'Failed to load plugins');
      })
      .finally(() => setLoading(false));
  }, [search, offset, limit]);

  useEffect(() => {
    const timer = setTimeout(load, 250);
    return () => clearTimeout(timer);
  }, [load]);

  useEffect(() => {
    if (loading) return;
    if (total > 0 && offset >= total) {
      setOffset(Math.floor((total - 1) / limit) * limit);
    } else if (total === 0 && offset > 0) {
      setOffset(0);
    }
  }, [loading, total, limit, offset, setOffset]);

  const remove = async (plugin: PluginRegistryEntry) => {
    if (!confirm(`Delete "${plugin.name}" from the registry? The package file itself is not deleted.`)) return;
    try {
      await api.plugins.delete(plugin.id);
      if (detail?.id === plugin.id) setDetail(null);
      if (plugins.length === 1 && offset > 0) setOffset(Math.max(0, offset - limit));
      else void load();
    } catch (err) {
      alert('Delete failed: ' + (err instanceof Error ? err.message : 'unknown error'));
    }
  };

  const openPublish = () => {
    setForm(EMPTY_FORM);
    setFormError(null);
    setPublishing(true);
  };

  const submit = async () => {
    if (!form.manifest.trim()) {
      setFormError('manifest is required');
      return;
    }
    if (!form.download_url.trim()) {
      setFormError('download URL is required');
      return;
    }
    if (!/^[a-fA-F0-9]{64}$/.test(form.sha256.trim())) {
      setFormError('sha256 must be 64 hexadecimal characters');
      return;
    }
    setSaving(true);
    setFormError(null);
    try {
      await api.plugins.register({
        manifest: form.manifest,
        download_url: form.download_url.trim(),
        sha256: form.sha256.trim().toLowerCase(),
        size: form.size ?? null,
        vendor: form.vendor?.trim() || null,
        homepage: form.homepage?.trim() || null,
      });
      setPublishing(false);
      setOffset(0);
      void load();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Publish failed');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="p-6">
      <div className="flex items-start justify-between gap-4 mb-6">
        <div>
          <h1 className="text-2xl font-semibold flex items-center gap-2">
            <Puzzle size={22} style={{ color: 'var(--color-primary)' }} />
            Plugins
          </h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Desktop plugin packages pinned by the registry. The server verifies identity, capabilities and digest;
            package bytes stay at the download URL you publish.
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <button
            onClick={load}
            className="flex items-center gap-2 px-3 py-2 rounded-lg border text-sm cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}
          >
            <RefreshCw size={14} /> Refresh
          </button>
          {canManage && (
            <button
              onClick={openPublish}
              className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
              style={{ background: 'var(--color-primary)' }}
            >
              <Plus size={16} /> Publish plugin
            </button>
          )}
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-3 mb-4">
        <div className="relative flex-1 min-w-[240px] max-w-lg">
          <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2" style={{ color: 'var(--color-text-secondary)' }} />
          <input
            value={search}
            onChange={e => {
              setSearch(e.target.value);
              setOffset(0);
            }}
            placeholder="Search by name, id, vendor or description..."
            className="w-full pl-9 pr-3 py-2 rounded-lg border text-sm"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}
          />
        </div>
        <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
          {total} plugin{total === 1 ? '' : 's'}
        </span>
      </div>

      {loading ? (
        <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
      ) : error ? (
        <div className="text-center py-12 rounded-xl border"
          style={{ background: 'var(--color-bg-secondary)', borderColor: '#7f1d1d', color: '#f87171' }}>
          {error}
        </div>
      ) : plugins.length === 0 ? (
        <div className="text-center py-12 rounded-xl border"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
          {search.trim() ? 'No plugins match this search.' : 'No plugins registered yet.'}
        </div>
      ) : (
        <div className="grid gap-4">
          {plugins.map(plugin => (
            <PluginCard
              key={plugin.id}
              plugin={plugin}
              canManage={canManage}
              onDetail={() => setDetail(plugin)}
              onDelete={() => void remove(plugin)}
            />
          ))}
        </div>
      )}

      {total > 0 && (
        <div className="flex items-center justify-between mt-4">
          <div className="flex items-center gap-3">
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Showing {offset + 1}-{Math.min(offset + limit, total)} of {total}
            </span>
            <select
              value={limit}
              onChange={e => {
                setLimit(Number(e.target.value));
                setOffset(0);
              }}
              className="px-2 py-1 rounded-lg border text-xs"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}
            >
              {PAGE_SIZES.map(size => <option key={size} value={size}>{size} / page</option>)}
            </select>
          </div>
          <div className="flex gap-2">
            <button
              onClick={() => setOffset(Math.max(0, offset - limit))}
              disabled={offset === 0}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}
            >
              <ChevronLeft size={14} /> Prev
            </button>
            <button
              onClick={() => setOffset(offset + limit)}
              disabled={offset + limit >= total}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}
            >
              Next <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}

      {detail && (
        <PluginDetailModal
          plugin={detail}
          canManage={canManage}
          onClose={() => setDetail(null)}
          onDelete={() => void remove(detail)}
        />
      )}

      {publishing && (
        <PublishModal
          form={form}
          saving={saving}
          error={formError}
          onChange={setForm}
          onClose={() => setPublishing(false)}
          onSubmit={() => void submit()}
        />
      )}
    </div>
  );
}

function PluginCard({
  plugin,
  canManage,
  onDetail,
  onDelete,
}: {
  plugin: PluginRegistryEntry;
  canManage: boolean;
  onDetail: () => void;
  onDelete: () => void;
}) {
  const capabilities = [...(plugin.required_capabilities ?? []), ...(plugin.optional_capabilities ?? [])];

  return (
    <div className="rounded-xl border p-4"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <Package size={17} style={{ color: 'var(--color-primary)' }} />
            <button
              onClick={onDetail}
              className="font-medium hover:underline cursor-pointer text-left"
              style={{ background: 'none', border: 'none', padding: 0 }}
            >
              {plugin.name}
            </button>
            <span className="px-2 py-0.5 rounded text-xs font-mono"
              style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
              v{plugin.version}
            </span>
            {plugin.vendor && (
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{plugin.vendor}</span>
            )}
          </div>
          <div className="mt-1 text-xs font-mono truncate" style={{ color: 'var(--color-text-secondary)' }}>
            {plugin.id}
          </div>
          {plugin.description && (
            <p className="text-sm mt-2" style={{ color: 'var(--color-text-secondary)' }}>{plugin.description}</p>
          )}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <a
            href={plugin.download_url}
            target="_blank"
            rel="noreferrer"
            className="p-1.5 rounded border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}
            title="Download package"
          >
            <Download size={14} />
          </a>
          <button
            onClick={onDetail}
            className="px-3 py-1.5 rounded-lg border text-sm cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}
          >
            Details
          </button>
          {canManage && (
            <button
              onClick={onDelete}
              className="p-1.5 rounded border cursor-pointer"
              style={{ borderColor: 'var(--color-border)', color: '#f87171' }}
              title="Delete"
            >
              <Trash2 size={14} />
            </button>
          )}
        </div>
      </div>

      {capabilities.length > 0 && (
        <div className="flex flex-wrap gap-1.5 mt-3">
          {capabilities.map(capability => (
            <span key={capability} className="px-2 py-0.5 rounded text-xs font-mono"
              style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
              {capability}
            </span>
          ))}
        </div>
      )}

      <div className="mt-3 text-xs flex flex-wrap gap-x-4 gap-y-1" style={{ color: 'var(--color-text-secondary)' }}>
        <span>sha256: {plugin.sha256.slice(0, 16)}…</span>
        <span>size: {formatBytes(plugin.size)}</span>
        <span>components: {(plugin.components ?? []).join(', ') || 'none'}</span>
        <span>updated: {formatTime(plugin.updated_at)}</span>
      </div>
    </div>
  );
}

function PluginDetailModal({
  plugin,
  canManage,
  onClose,
  onDelete,
}: {
  plugin: PluginRegistryEntry;
  canManage: boolean;
  onClose: () => void;
  onDelete: () => void;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ background: 'rgba(0,0,0,0.5)' }}
      onClick={onClose}>
      <div className="w-full max-w-3xl max-h-[90vh] overflow-y-auto rounded-xl border p-6"
        style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}
        onClick={e => e.stopPropagation()}>
        <div className="flex items-start justify-between gap-3 mb-4">
          <div>
            <h2 className="text-lg font-semibold flex items-center gap-2">
              <Package size={18} /> {plugin.name}
              <span className="text-sm font-mono" style={{ color: 'var(--color-text-secondary)' }}>v{plugin.version}</span>
            </h2>
            <div className="text-xs font-mono mt-1" style={{ color: 'var(--color-text-secondary)' }}>{plugin.id}</div>
          </div>
          <button onClick={onClose} className="p-1 rounded cursor-pointer" style={{ color: 'var(--color-text-secondary)' }}>
            <X size={18} />
          </button>
        </div>

        <dl className="grid grid-cols-[140px_1fr] gap-x-3 gap-y-2 text-sm">
          <dt style={{ color: 'var(--color-text-secondary)' }}>Vendor</dt>
          <dd>{plugin.vendor || '-'}</dd>
          <dt style={{ color: 'var(--color-text-secondary)' }}>Owner</dt>
          <dd className="font-mono">{plugin.owner_id || '-'}</dd>
          <dt style={{ color: 'var(--color-text-secondary)' }}>Updated</dt>
          <dd>{formatTime(plugin.updated_at)}</dd>
          <dt style={{ color: 'var(--color-text-secondary)' }}>Size</dt>
          <dd>{formatBytes(plugin.size)}</dd>
          <dt style={{ color: 'var(--color-text-secondary)' }}>sha256</dt>
          <dd className="font-mono break-all">{plugin.sha256}</dd>
          <dt style={{ color: 'var(--color-text-secondary)' }}>Download</dt>
          <dd className="break-all">
            <a href={plugin.download_url} target="_blank" rel="noreferrer"
              className="hover:underline inline-flex items-center gap-1" style={{ color: 'var(--color-primary)' }}>
              {plugin.download_url} <ExternalLink size={12} />
            </a>
          </dd>
          {plugin.homepage && (
            <>
              <dt style={{ color: 'var(--color-text-secondary)' }}>Homepage</dt>
              <dd className="break-all">
                <a href={plugin.homepage} target="_blank" rel="noreferrer"
                  className="hover:underline" style={{ color: 'var(--color-primary)' }}>
                  {plugin.homepage}
                </a>
              </dd>
            </>
          )}
          <dt style={{ color: 'var(--color-text-secondary)' }}>Required</dt>
          <dd>{(plugin.required_capabilities ?? []).join(', ') || 'none'}</dd>
          <dt style={{ color: 'var(--color-text-secondary)' }}>Optional</dt>
          <dd>{(plugin.optional_capabilities ?? []).join(', ') || 'none'}</dd>
          <dt style={{ color: 'var(--color-text-secondary)' }}>Components</dt>
          <dd>{(plugin.components ?? []).join(', ') || 'none'}</dd>
        </dl>

        <div className="mt-5">
          <div className="text-sm mb-2" style={{ color: 'var(--color-text-secondary)' }}>Manifest</div>
          <pre className="rounded-lg border p-3 text-xs overflow-auto max-h-80 font-mono"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            {plugin.manifest}
          </pre>
        </div>

        <div className="flex justify-end gap-2 mt-5">
          {canManage && (
            <button
              onClick={onDelete}
              className="px-4 py-2 rounded-lg text-sm border cursor-pointer"
              style={{ borderColor: '#7f1d1d', color: '#f87171' }}
            >
              Delete
            </button>
          )}
          <button onClick={onClose}
            className="px-4 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

function PublishModal({
  form,
  saving,
  error,
  onChange,
  onClose,
  onSubmit,
}: {
  form: PublishForm;
  saving: boolean;
  error: string | null;
  onChange: (form: PublishForm) => void;
  onClose: () => void;
  onSubmit: () => void;
}) {
  const update = (patch: Partial<PublishForm>) => onChange({ ...form, ...patch });

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
      style={{ background: 'rgba(0,0,0,0.5)' }}
      onClick={onClose}>
      <div className="w-full max-w-2xl max-h-[90vh] overflow-y-auto rounded-xl border p-6"
        style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}
        onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold">Publish plugin</h2>
          <button onClick={onClose} className="p-1 rounded cursor-pointer" style={{ color: 'var(--color-text-secondary)' }}>
            <X size={18} />
          </button>
        </div>

        <div className="text-sm mb-4" style={{ color: 'var(--color-text-secondary)' }}>
          The registry pins the package; it does not host it. Upload the zip to an HTTPS URL you control, then
          publish its manifest and sha256 here.
        </div>

        <div className="space-y-4">
          <div>
            <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>Manifest (plugin.json) *</label>
            <textarea
              value={form.manifest}
              onChange={e => update({ manifest: e.target.value })}
              rows={12}
              spellCheck={false}
              className="w-full px-3 py-2 rounded-lg border text-xs font-mono"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}
            />
            <div className="mt-2">
              <ManifestPreview manifest={form.manifest} />
            </div>
          </div>

          <div>
            <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>Download URL *</label>
            <input
              value={form.download_url}
              onChange={e => update({ download_url: e.target.value })}
              placeholder="https://github.com/org/repo/releases/download/v1.0.0/plugin.zip"
              className="w-full px-3 py-2 rounded-lg border text-sm"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>sha256 *</label>
              <input
                value={form.sha256}
                onChange={e => update({ sha256: e.target.value })}
                placeholder="64 hexadecimal characters"
                className="w-full px-3 py-2 rounded-lg border text-sm font-mono"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}
              />
            </div>
            <div>
              <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>Size (bytes)</label>
              <input
                type="number"
                value={form.size ?? ''}
                onChange={e => update({ size: e.target.value ? Number(e.target.value) : null })}
                placeholder="optional, max 268435456"
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>Vendor</label>
              <input
                value={form.vendor ?? ''}
                onChange={e => update({ vendor: e.target.value })}
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}
              />
            </div>
            <div>
              <label className="block text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>Homepage</label>
              <input
                value={form.homepage ?? ''}
                onChange={e => update({ homepage: e.target.value })}
                placeholder="https://..."
                className="w-full px-3 py-2 rounded-lg border text-sm"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}
              />
            </div>
          </div>

          {error && <div className="text-sm" style={{ color: '#f87171' }}>{error}</div>}
        </div>

        <div className="flex justify-end gap-2 mt-6">
          <button onClick={onClose}
            className="px-4 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            Cancel
          </button>
          <button onClick={onSubmit} disabled={saving}
            className="px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
            style={{ background: 'var(--color-primary)' }}>
            {saving ? 'Publishing...' : 'Publish'}
          </button>
        </div>
      </div>
    </div>
  );
}
