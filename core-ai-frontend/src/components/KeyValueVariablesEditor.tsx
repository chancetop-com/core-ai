import { Plus, Trash2 } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';

interface VariableRow {
  id: string;
  key: string;
  value: string;
}

interface KeyValueVariablesEditorProps {
  value?: Record<string, string>;
  onChange: (value: Record<string, string> | undefined) => void;
  disabled?: boolean;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
}

/**
 * @author stephen
 */
export default function KeyValueVariablesEditor({
  value,
  onChange,
  disabled,
  keyPlaceholder = 'Key',
  valuePlaceholder = 'Value',
}: KeyValueVariablesEditorProps) {
  const [rows, setRows] = useState<VariableRow[]>([]);
  const idRef = useRef(0);

  const nextId = () => {
    idRef.current += 1;
    return `var-row-${idRef.current}`;
  };

  const rowsToMap = (source: VariableRow[]) => {
    const normalized: Record<string, string> = {};
    for (const row of source) {
      const key = row.key.trim();
      if (!key) continue;
      normalized[key] = row.value;
    }
    return Object.keys(normalized).length > 0 ? normalized : undefined;
  };

  const mapToRows = (source?: Record<string, string>) => {
    const entries = Object.entries(source || {});
    if (entries.length === 0) {
      return [{ id: nextId(), key: '', value: '' }];
    }
    return entries.map(([key, rowValue]) => ({ id: nextId(), key, value: rowValue ?? '' }));
  };

  const mapsEqual = (left?: Record<string, string>, right?: Record<string, string>) => {
    const leftKeys = Object.keys(left || {});
    const rightKeys = Object.keys(right || {});
    if (leftKeys.length !== rightKeys.length) return false;
    for (const key of leftKeys) {
      if ((left || {})[key] !== (right || {})[key]) return false;
    }
    return true;
  };

  useEffect(() => {
    if (rows.length === 0) {
      setRows(mapToRows(value));
      return;
    }
    const current = rowsToMap(rows);
    if (!mapsEqual(value, current)) {
      setRows(mapToRows(value));
    }
  }, [value]);

  const duplicateKeys = useMemo(() => {
    const counts = new Map<string, number>();
    for (const row of rows) {
      const key = row.key.trim();
      if (!key) continue;
      counts.set(key, (counts.get(key) || 0) + 1);
    }
    return new Set(Array.from(counts.entries()).filter(([, count]) => count > 1).map(([key]) => key));
  }, [rows]);

  const emitChange = (nextRows: VariableRow[]) => {
    onChange(rowsToMap(nextRows));
  };

  const updateRow = (id: string, patch: Partial<VariableRow>) => {
    setRows(prev => {
      const next = prev.map(row => row.id === id ? { ...row, ...patch } : row);
      emitChange(next);
      return next;
    });
  };

  const addRow = () => {
    setRows(prev => [...prev, { id: nextId(), key: '', value: '' }]);
  };

  const removeRow = (id: string) => {
    setRows(prev => {
      const next = prev.filter(row => row.id !== id);
      const fallback = next.length > 0 ? next : [{ id: nextId(), key: '', value: '' }];
      emitChange(fallback);
      return fallback;
    });
  };

  return (
    <div className="space-y-2">
      {rows.map(row => {
        const key = row.key.trim();
        const duplicated = key && duplicateKeys.has(key);
        return (
          <div key={row.id} className="grid grid-cols-[1fr_1fr_auto] gap-2 items-center">
            <input
              value={row.key}
              onChange={e => updateRow(row.id, { key: e.target.value })}
              placeholder={keyPlaceholder}
              disabled={disabled}
              className="px-3 py-2 rounded-lg border text-sm outline-none"
              style={{
                borderColor: duplicated ? 'var(--color-error)' : 'var(--color-border)',
                background: 'var(--color-bg-secondary)',
                color: 'var(--color-text)',
              }}
            />
            <input
              value={row.value}
              onChange={e => updateRow(row.id, { value: e.target.value })}
              placeholder={valuePlaceholder}
              disabled={disabled}
              className="px-3 py-2 rounded-lg border text-sm outline-none"
              style={{
                borderColor: 'var(--color-border)',
                background: 'var(--color-bg-secondary)',
                color: 'var(--color-text)',
              }}
            />
            <button
              type="button"
              onClick={() => removeRow(row.id)}
              disabled={disabled}
              className="p-2 rounded-lg border cursor-pointer disabled:opacity-40"
              style={{ borderColor: 'var(--color-border)', color: 'var(--color-error)' }}
              title="Remove variable"
            >
              <Trash2 size={14} />
            </button>
          </div>
        );
      })}
      {duplicateKeys.size > 0 && (
        <p className="text-xs" style={{ color: 'var(--color-error)' }}>
          Duplicate variable keys found. Later rows override earlier rows.
        </p>
      )}
      <button
        type="button"
        onClick={addRow}
        disabled={disabled}
        className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-xs cursor-pointer disabled:opacity-40"
        style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}
      >
        <Plus size={12} /> Add variable
      </button>
    </div>
  );
}

