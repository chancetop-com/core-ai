import { useEffect, useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Bot, Download, FileUp, Check, ChevronLeft, ChevronRight, Search, Star } from 'lucide-react';
import { api } from '../../api/client';
import type { AgentDefinition } from '../../api/client';
import StatusBadge from '../../components/StatusBadge';

const EXPORT_FIELDS = ['name', 'description', 'type', 'system_prompt', 'model', 'temperature',
  'max_turns', 'timeout_seconds', 'tool_ids', 'input_template', 'variables', 'response_schema'] as const;

function toExportData(a: AgentDefinition): Record<string, unknown> {
  const raw = a as unknown as Record<string, unknown>;
  const data: Record<string, unknown> = {};
  for (const key of EXPORT_FIELDS) {
    if (raw[key] != null) data[key] = raw[key];
  }
  return data;
}

export default function AgentList() {
  const [myAgents, setMyAgents] = useState<AgentDefinition[]>([]);
  const [otherAgents, setOtherAgents] = useState<AgentDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [importing, setImporting] = useState(false);
  const [selectMode, setSelectMode] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [activeTab, setActiveTab] = useState<'my' | 'other'>('my');
  const [myOffset, setMyOffset] = useState(0);
  const [otherOffset, setOtherOffset] = useState(0);
  const [limit, setLimit] = useState(10);
  const [query, setQuery] = useState('');
  const [sortBy, setSortBy] = useState<'updated_at' | 'created_at'>('updated_at');
  const navigate = useNavigate();
  const fileRef = useRef<HTMLInputElement>(null);

  const loadMyAgents = () => {
    setLoading(true);
    api.agents.list(true).then(res => setMyAgents(res.agents || [])).finally(() => setLoading(false));
  };

  const loadOtherAgents = () => {
    api.agents.list(false).then(res => setOtherAgents(res.agents || [])).catch(() => setOtherAgents([]));
  };

  useEffect(() => {
    setLoading(true);
    Promise.all([
      api.agents.list(true),
      api.agents.list(false),
    ]).then(([myRes, otherRes]) => {
      setMyAgents(myRes.agents || []);
      setOtherAgents(otherRes.agents || []);
    }).finally(() => setLoading(false));
  }, []);

  const currentAgents = activeTab === 'my' ? myAgents : otherAgents;
  const currentOffset = activeTab === 'my' ? myOffset : otherOffset;
  const setCurrentOffset = activeTab === 'my' ? setMyOffset : setOtherOffset;

  const trimmedQuery = query.trim().toLowerCase();
  const filteredAgents = currentAgents
    .filter(a => !a.system_default)
    .filter(a => !trimmedQuery || a.name.toLowerCase().includes(trimmedQuery))
    .slice()
    .sort((a, b) => (b[sortBy] || '').localeCompare(a[sortBy] || ''));
  const pagedAgents = filteredAgents.slice(currentOffset, currentOffset + limit);

  const handleCreate = async () => {
    const name = `New Agent ${new Date().toLocaleString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}`;
    const created = await api.agents.create({ name, type: 'AGENT' });
    if (created?.id) navigate(`/agents/${created.id}`);
  };

  const toggleSelect = (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleSelectAll = () => {
    if (selected.size === filteredAgents.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(filteredAgents.map(a => a.id)));
    }
  };

  const handleExport = () => {
    const toExport = filteredAgents.filter(a => selected.has(a.id));
    if (toExport.length === 0) return;
    const exportData = toExport.map(toExportData);
    const json = JSON.stringify(exportData.length === 1 ? exportData[0] : exportData, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    const fileName = toExport.length === 1
      ? `${toExport[0].name.replace(/\s+/g, '-').toLowerCase()}.agent.json`
      : `agents-export-${new Date().toISOString().slice(0, 10)}.json`;
    link.download = fileName;
    link.click();
    URL.revokeObjectURL(url);
    setSelectMode(false);
    setSelected(new Set());
  };

  const handleImportFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setImporting(true);
    try {
      const text = await file.text();
      const data = JSON.parse(text);
      const items = Array.isArray(data) ? data : [data];
      const existingNames = new Set([...myAgents, ...otherAgents].map(a => a.name));
      let created = 0;
      for (const item of items) {
        if (!item.name) continue;
        let name = item.name;
        let suffix = 1;
        while (existingNames.has(name)) {
          name = `${item.name} (${suffix++})`;
        }
        existingNames.add(name);
        try {
          await api.agents.create({ ...item, name });
          created++;
        } catch (err) {
          console.error(`Failed to import agent "${name}":`, err);
        }
      }
      alert(`Imported ${created} of ${items.length} agent(s)`);
      loadMyAgents();
    } catch {
      alert('Invalid JSON file');
    } finally {
      setImporting(false);
      e.target.value = '';
    }
  };

  const formatTime = (iso: string) => {
    if (!iso) return '-';
    const d = new Date(iso);
    const now = Date.now();
    const diff = now - d.getTime();
    if (diff < 60_000) return 'just now';
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
    if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
    return d.toLocaleDateString();
  };

  const handleTabChange = (tab: 'my' | 'other') => {
    setActiveTab(tab);
    setSelectMode(false);
    setSelected(new Set());
    setQuery('');
    setMyOffset(0);
    setOtherOffset(0);
  };

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold">Agents</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Create and manage your AI agents, or browse shared agents from others
          </p>
        </div>
        <div className="flex items-center gap-2">
          {activeTab === 'my' && selectMode ? (
            <>
              <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
                {selected.size} selected
              </span>
              <button onClick={toggleSelectAll}
                className="px-3 py-2 rounded-lg text-sm border cursor-pointer"
                style={{ borderColor: 'var(--color-border)' }}>
                {selected.size === filteredAgents.length ? 'Deselect All' : 'Select All'}
              </button>
              <button onClick={handleExport} disabled={selected.size === 0}
                className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
                style={{ background: 'var(--color-primary)' }}>
                <Download size={14} /> Export ({selected.size})
              </button>
              <button onClick={() => { setSelectMode(false); setSelected(new Set()); }}
                className="px-3 py-2 rounded-lg text-sm border cursor-pointer"
                style={{ borderColor: 'var(--color-border)' }}>
                Cancel
              </button>
            </>
          ) : (
            <>
              {activeTab === 'my' && myAgents.length > 0 && (
                <button onClick={() => setSelectMode(true)}
                  className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)' }}>
                  <Download size={14} /> Export
                </button>
              )}
              {activeTab === 'my' && (
                <label className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm border cursor-pointer"
                  style={{ borderColor: 'var(--color-border)', opacity: importing ? 0.5 : 1 }}>
                  <FileUp size={14} /> {importing ? 'Importing...' : 'Import'}
                  <input ref={fileRef} type="file" accept=".json" className="hidden" onChange={handleImportFile} disabled={importing} />
                </label>
              )}
              {activeTab === 'my' && (
                <button onClick={handleCreate}
                  className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
                  style={{ background: 'var(--color-primary)' }}>
                  <Plus size={16} /> New Agent
                </button>
              )}
            </>
          )}
        </div>
      </div>

      <div className="mb-4 flex items-center justify-between">
        <div className="flex gap-1 p-1 rounded-lg" style={{ background: 'var(--color-bg-secondary)' }}>
          <button
            onClick={() => handleTabChange('my')}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer"
            style={{
              background: activeTab === 'my' ? 'var(--color-bg-tertiary)' : 'transparent',
              color: activeTab === 'my' ? 'var(--color-text)' : 'var(--color-text-secondary)',
            }}>
            <Bot size={14} />
            My Agents
            <span className="px-1.5 py-0.5 rounded text-xs" style={{ background: 'var(--color-bg-primary)', color: 'var(--color-text-secondary)' }}>
              {myAgents.filter(a => !a.system_default).length}
            </span>
          </button>
          <button
            onClick={() => handleTabChange('other')}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer"
            style={{
              background: activeTab === 'other' ? 'var(--color-bg-tertiary)' : 'transparent',
              color: activeTab === 'other' ? 'var(--color-text)' : 'var(--color-text-secondary)',
            }}>
            <Star size={14} />
            Shared Agents
            <span className="px-1.5 py-0.5 rounded text-xs" style={{ background: 'var(--color-bg-primary)', color: 'var(--color-text-secondary)' }}>
              {otherAgents.filter(a => !a.system_default).length}
            </span>
          </button>
        </div>
        <div className="flex items-center gap-2">
          <div className="relative w-64">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2"
              style={{ color: 'var(--color-text-secondary)' }} />
            <input type="text" value={query}
              onChange={e => { setQuery(e.target.value); setMyOffset(0); setOtherOffset(0); }}
              placeholder="Search by name..."
              className="w-full pl-9 pr-3 py-2 rounded-lg border text-sm outline-none"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }} />
          </div>
          <select value={sortBy}
            onChange={e => { setSortBy(e.target.value as 'updated_at' | 'created_at'); setMyOffset(0); setOtherOffset(0); }}
            className="px-3 py-2 rounded-lg border text-sm outline-none cursor-pointer"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
            <option value="updated_at">Sort: Updated ↓</option>
            <option value="created_at">Sort: Created ↓</option>
          </select>
        </div>
      </div>

      <div className="grid gap-4">
        {loading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : pagedAgents.length === 0 ? (
          <div className="text-center py-12 rounded-xl border"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
            {activeTab === 'my'
              ? 'No agents yet. Click "New Agent" to create one, or import from a JSON file.'
              : 'No shared agents available yet.'}
          </div>
        ) : pagedAgents.map(a => (
          <div key={a.id}
            onClick={() => activeTab === 'my' && selectMode ? toggleSelect(a.id, { stopPropagation: () => {} } as React.MouseEvent) : navigate(`/agents/${a.id}`)}
            className="rounded-xl border p-4 cursor-pointer transition-colors"
            style={{
              background: selected.has(a.id) ? 'var(--color-bg-tertiary)' : 'var(--color-bg-secondary)',
              borderColor: selected.has(a.id) ? 'var(--color-primary)' : 'var(--color-border)',
            }}
            onMouseEnter={e => { if (!selected.has(a.id)) e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
            onMouseLeave={e => { if (!selected.has(a.id)) e.currentTarget.style.background = selected.has(a.id) ? 'var(--color-bg-tertiary)' : 'var(--color-bg-secondary)'; }}>
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                {activeTab === 'my' && selectMode ? (
                  <div className="w-5 h-5 rounded border flex items-center justify-center flex-shrink-0"
                    style={{
                      borderColor: selected.has(a.id) ? 'var(--color-primary)' : 'var(--color-border)',
                      background: selected.has(a.id) ? 'var(--color-primary)' : 'transparent',
                    }}>
                    {selected.has(a.id) && <Check size={12} color="white" />}
                  </div>
                ) : (
                  <Bot size={18} style={{ color: 'var(--color-primary)' }} />
                )}
                <span className="font-medium">{a.name}</span>
                <StatusBadge status={a.status} />
                <span className="px-2 py-0.5 rounded text-xs"
                  style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                  {a.type}
                </span>
              </div>
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {formatTime(a.updated_at)}
              </span>
            </div>
            {a.description && (
              <p className="text-sm mt-1 ml-8" style={{ color: 'var(--color-text-secondary)' }}>{a.description}</p>
            )}
            <div className="flex items-center gap-4 mt-2 ml-8 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {a.model && <span>Model: {a.model}</span>}
              {a.max_turns && <span>Max turns: {a.max_turns}</span>}
              {a.created_by && <span>By: {a.created_by}</span>}
            </div>
          </div>
        ))}
      </div>

      {filteredAgents.length > 0 && (
        <div className="flex items-center justify-between mt-4">
          <div className="flex items-center gap-3">
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Showing {currentOffset + 1}-{Math.min(currentOffset + limit, filteredAgents.length)} of {filteredAgents.length}
            </span>
            <select value={limit}
              onChange={e => { setLimit(Number(e.target.value)); setMyOffset(0); setOtherOffset(0); }}
              className="px-2 py-1 rounded-lg border text-xs"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
              {[10, 20, 50].map(n => <option key={n} value={n}>{n} / page</option>)}
            </select>
          </div>
          <div className="flex gap-2">
            <button onClick={() => setCurrentOffset(Math.max(0, currentOffset - limit))} disabled={currentOffset === 0}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <ChevronLeft size={14} /> Prev
            </button>
            <button onClick={() => setCurrentOffset(currentOffset + limit)} disabled={currentOffset + limit >= filteredAgents.length}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              Next <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
