import { useEffect, useMemo, useState } from 'react';
import { Plus, RefreshCw, Trash2, X } from 'lucide-react';
import Pagination from '../../../components/Pagination';
import {
  qualityExemptionsApi,
  type QualityExemptionEntry,
  type QualityExemptionList,
  type QualityExemptionSubject,
  type QualityExemptionType,
} from '../../../api/quality';
import QualityExemptionPicker from './QualityExemptionPicker';

type Filter = 'ALL' | QualityExemptionType;

interface Row {
  type: QualityExemptionType;
  entry: QualityExemptionEntry;
}

export default function QualityExemptionsPage() {
  const [data, setData] = useState<QualityExemptionList | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [filter, setFilter] = useState<Filter>('ALL');
  const [search, setSearch] = useState('');
  const [offset, setOffset] = useState(0);
  const [limit, setLimit] = useState(20);

  const [adding, setAdding] = useState(false);
  const [type, setType] = useState<QualityExemptionType>('AGENT');
  const [selected, setSelected] = useState<QualityExemptionSubject | null>(null);
  const [busy, setBusy] = useState(false);

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      setData(await qualityExemptionsApi.list());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load quality exemptions');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const rows = useMemo<Row[]>(() => {
    if (!data) return [];
    const agentRows: Row[] = (data.agent.entries || []).map(entry => ({ type: 'AGENT' as const, entry }));
    const skillRows: Row[] = (data.skill.entries || []).map(entry => ({ type: 'SKILL' as const, entry }));
    const needle = search.trim().toLowerCase();
    return [...agentRows, ...skillRows].filter(row => {
      if (filter !== 'ALL' && row.type !== filter) return false;
      if (!needle) return true;
      return [row.entry.name, row.entry.value, row.entry.detail]
        .some(value => value?.toLowerCase().includes(needle));
    });
  }, [data, filter, search]);

  useEffect(() => { setOffset(0); }, [filter, search]);

  const pageRows = rows.slice(offset, offset + limit);

  const openAdd = () => {
    setAdding(true);
    setType('AGENT');
    setSelected(null);
    setNotice('');
    setError('');
  };

  const closeAdd = () => {
    setAdding(false);
    setSelected(null);
  };

  const generation = type === 'AGENT' ? data?.agent.generation : data?.skill.generation;

  const add = async () => {
    if (!selected) return;
    setBusy(true);
    setError('');
    try {
      const next = await qualityExemptionsApi.add(type, selected.id, generation);
      setData(next);
      setAdding(false);
      setSelected(null);
      setNotice(`${selected.name} is now exempt from the ${type === 'AGENT' ? 'agent' : 'skill'} publish quality gate.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to add exemption');
    } finally {
      setBusy(false);
    }
  };

  const remove = async (row: Row) => {
    const label = row.entry.name || row.entry.value;
    if (!window.confirm(`Remove the exemption for ${label}? The publish quality gate will apply to it again.`)) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const next = await qualityExemptionsApi.remove(
        row.type,
        row.entry.value,
        row.type === 'AGENT' ? data?.agent.generation : data?.skill.generation,
      );
      setData(next);
      setNotice(`${label} is no longer exempt.`);
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to remove exemption';
      await load();
      setError(message);
    } finally {
      setBusy(false);
    }
  };

  const agentCount = data?.agent.entries?.length ?? 0;
  const skillCount = data?.skill.entries?.length ?? 0;

  return (
    <div>
      {error && (
        <div className="rounded-lg px-3 py-2 mb-4 text-sm" style={{ background: 'rgba(220,38,38,0.12)', color: 'var(--color-error)' }}>
          {error}
        </div>
      )}
      {notice && (
        <div className="rounded-lg px-3 py-2 mb-4 text-sm" style={{ background: 'rgba(22,163,74,0.12)', color: 'var(--color-success)' }}>
          {notice}
        </div>
      )}

      <div className="flex flex-wrap items-center gap-3 mb-4 text-sm">
        <span className="px-2 py-1 rounded" style={{ background: 'var(--color-bg-tertiary)' }}>
          {agentCount} agent · {skillCount} skill exempt
        </span>
        <span style={{ color: 'var(--color-text-secondary)' }}>
          Agent policy rev {data?.agent.policy_revision ?? '-'} · Skill policy rev {data?.skill.policy_revision ?? '-'}
        </span>
        <div className="ml-auto flex items-center gap-2">
          <button onClick={() => load()} disabled={loading}
            className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer disabled:opacity-50"
            style={{ borderColor: 'var(--color-border)' }}>
            <RefreshCw size={14} /> Refresh
          </button>
          <button onClick={openAdd}
            className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm cursor-pointer"
            style={{ background: 'var(--color-primary)', color: 'white' }}>
            <Plus size={14} /> Add exemption
          </button>
        </div>
      </div>

      <div className="rounded-lg border px-4 py-3 mb-4 text-xs" style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
        Exempted agents and skills skip every publish quality rule. Built-in system agents and personal
        assistant copies are always exempt, so they cannot be added here. Each change activates a new policy revision.
      </div>

      {adding && (
        <div className="rounded-lg border mb-4" style={{ borderColor: 'var(--color-border)' }}>
          <div className="flex items-center justify-between px-4 py-3 border-b" style={{ borderColor: 'var(--color-border)' }}>
            <div className="text-sm font-medium">Add exemption</div>
            <button onClick={closeAdd} className="cursor-pointer" aria-label="Close add exemption">
              <X size={16} style={{ color: 'var(--color-text-secondary)' }} />
            </button>
          </div>
          <div className="p-4 grid gap-4">
            <div className="flex items-center gap-2">
              <TypeButton active={type === 'AGENT'} onClick={() => { setType('AGENT'); setSelected(null); }}>Agent</TypeButton>
              <TypeButton active={type === 'SKILL'} onClick={() => { setType('SKILL'); setSelected(null); }}>Skill</TypeButton>
            </div>
            <QualityExemptionPicker key={type} type={type} selected={selected} onSelect={setSelected} />
            <div className="flex items-center gap-3">
              <div className="text-xs flex-1" style={{ color: 'var(--color-text-secondary)' }}>
                {selected
                  ? `${selected.name} — ${selected.detail}`
                  : `Select an ${type === 'AGENT' ? 'agent' : 'skill'} to exempt.`}
              </div>
              <button onClick={closeAdd} disabled={busy}
                className="px-3 py-2 rounded-lg text-sm border cursor-pointer disabled:opacity-50"
                style={{ borderColor: 'var(--color-border)' }}>
                Cancel
              </button>
              <button onClick={add} disabled={busy || !selected || generation == null}
                className="px-3 py-2 rounded-lg text-sm cursor-pointer disabled:opacity-50"
                style={{ background: 'var(--color-primary)', color: 'white' }}>
                {busy ? 'Adding...' : 'Add exemption'}
              </button>
            </div>
          </div>
        </div>
      )}

      <div className="flex flex-wrap items-center gap-2 mb-3">
        <FilterChip active={filter === 'ALL'} onClick={() => setFilter('ALL')}>All {agentCount + skillCount}</FilterChip>
        <FilterChip active={filter === 'AGENT'} onClick={() => setFilter('AGENT')}>Agents {agentCount}</FilterChip>
        <FilterChip active={filter === 'SKILL'} onClick={() => setFilter('SKILL')}>Skills {skillCount}</FilterChip>
        <input
          value={search}
          onChange={e => setSearch(e.target.value)}
          placeholder="Filter exemptions"
          aria-label="Filter exemptions"
          className="ml-auto rounded-lg border px-3 py-2 text-sm w-64"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text)' }}
        />
      </div>

      <div className="rounded-lg border overflow-x-auto" style={{ borderColor: 'var(--color-border)' }}>
        <table className="w-full text-sm">
          <thead style={{ background: 'var(--color-bg-secondary)' }}>
            <tr className="text-left" style={{ color: 'var(--color-text-secondary)' }}>
              <th className="px-3 py-3 font-medium">Type</th>
              <th className="px-3 py-3 font-medium">Subject</th>
              <th className="px-3 py-3 font-medium">Status</th>
              <th className="px-3 py-3 font-medium text-right">Action</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={4} className="px-3 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>Loading...</td></tr>
            ) : pageRows.length === 0 ? (
              <tr><td colSpan={4} className="px-3 py-8 text-center" style={{ color: 'var(--color-text-secondary)' }}>
                {agentCount + skillCount === 0 ? 'No exemptions. Every agent and skill is checked by the publish quality gate.' : 'No entries match this filter.'}
              </td></tr>
            ) : pageRows.map(row => (
              <tr key={`${row.type}:${row.entry.value}`} className="border-t" style={{ borderColor: 'var(--color-border)' }}>
                <td className="px-3 py-3">
                  <span className="px-2 py-1 rounded text-xs font-medium"
                    style={{ background: 'var(--color-bg-tertiary)' }}>
                    {row.type === 'AGENT' ? 'Agent' : 'Skill'}
                  </span>
                </td>
                <td className="px-3 py-3">
                  <div className="font-medium">{row.entry.name || row.entry.value}</div>
                  <div className="text-xs font-mono" style={{ color: 'var(--color-text-secondary)' }}>
                    {row.entry.detail || row.entry.value}
                  </div>
                </td>
                <td className="px-3 py-3 text-xs">
                  <Status row={row} />
                </td>
                <td className="px-3 py-3 text-right">
                  <button onClick={() => remove(row)} disabled={busy}
                    aria-label={`Remove exemption ${row.entry.name || row.entry.value}`}
                    className="inline-flex items-center gap-1 px-2 py-1 rounded text-xs border cursor-pointer disabled:opacity-50"
                    style={{ borderColor: 'var(--color-border)', color: 'var(--color-error)' }}>
                    <Trash2 size={12} /> Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {rows.length > 0 && (
        <Pagination
          offset={offset}
          limit={limit}
          total={rows.length}
          shown={pageRows.length}
          onOffsetChange={setOffset}
          onLimitChange={nextLimit => { setLimit(nextLimit); setOffset(0); }}
        />
      )}
    </div>
  );
}

function Status({ row }: { row: Row }) {
  if (row.entry.automatic) {
    return <span style={{ color: 'var(--color-text-secondary)' }}>Automatic</span>;
  }
  if (row.entry.resolved === false) {
    return <span style={{ color: 'var(--color-error)' }}>Subject not found</span>;
  }
  return <span style={{ color: 'var(--color-success)' }}>Active</span>;
}

function TypeButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button onClick={onClick}
      className="px-3 py-2 rounded-lg text-sm font-medium cursor-pointer"
      style={{
        background: active ? 'var(--color-primary-bg)' : 'transparent',
        color: active ? 'var(--color-primary)' : 'var(--color-text-secondary)',
        border: '1px solid var(--color-border)',
      }}>
      {children}
    </button>
  );
}

function FilterChip({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button onClick={onClick}
      className="px-3 py-1.5 rounded-lg text-sm cursor-pointer"
      style={{
        background: active ? 'var(--color-bg-tertiary)' : 'transparent',
        color: active ? 'var(--color-text)' : 'var(--color-text-secondary)',
      }}>
      {children}
    </button>
  );
}
