import { useState, useEffect } from 'react';
import { X, Search, Check, Loader2 } from 'lucide-react';

/** A generic picker modal for tools or skills */
interface PickerItem {
  id: string;
  name: string;
  description: string;
  type?: string;
  category?: string;
}

interface ResourcePickerProps {
  title: string;
  items: PickerItem[];
  loading: boolean;
  loadedIds: Set<string>;
  selectedIds: Set<string>;
  onToggle: (id: string) => void;
  onLoad: () => void;
  onClose: () => void;
}

export default function ResourcePicker({
  title,
  items,
  loading,
  loadedIds,
  selectedIds,
  onToggle,
  onLoad,
  onClose,
}: ResourcePickerProps) {
  const [search, setSearch] = useState('');

  const filtered = items.filter(item =>
    item.name.toLowerCase().includes(search.toLowerCase()) ||
    item.description.toLowerCase().includes(search.toLowerCase()) ||
    (item.category && item.category.toLowerCase().includes(search.toLowerCase())),
  );

  const selectableCount = filtered.filter(item => !loadedIds.has(item.id)).length;

  // Keyboard: Escape to close
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      style={{ background: 'rgba(0,0,0,0.5)' }}
      onClick={onClose}
    >
      <div
        className="rounded-2xl shadow-2xl flex flex-col overflow-hidden"
        style={{
          width: 'min(560px, 90vw)',
          maxHeight: '80vh',
          background: 'var(--color-bg)',
          border: '1px solid var(--color-border)',
        }}
        onClick={e => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b"
          style={{ borderColor: 'var(--color-border)' }}>
          <h2 className="text-base font-semibold" style={{ color: 'var(--color-text)' }}>{title}</h2>
          <button onClick={onClose}
            className="p-1.5 rounded-lg cursor-pointer transition-colors hover:opacity-80"
            style={{ color: 'var(--color-text-secondary)' }}>
            <X size={18} />
          </button>
        </div>

        {/* Search */}
        <div className="px-5 py-3 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <div className="flex items-center gap-2 px-3 py-2 rounded-lg"
            style={{ background: 'var(--color-bg-tertiary)', border: '1px solid var(--color-border)' }}>
            <Search size={14} style={{ color: 'var(--color-text-muted)' }} />
            <input
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="Search tools or skills..."
              className="flex-1 text-sm outline-none"
              style={{ background: 'transparent', color: 'var(--color-text)' }}
            />
          </div>
        </div>

        {/* List */}
        <div className="flex-1 overflow-auto px-5 py-3" style={{ maxHeight: '400px' }}>
          {loading ? (
            <div className="flex items-center justify-center py-12 gap-2"
              style={{ color: 'var(--color-text-secondary)' }}>
              <Loader2 size={18} className="animate-spin" />
              <span className="text-sm">Loading...</span>
            </div>
          ) : filtered.length === 0 ? (
            <div className="text-center py-12 text-sm"
              style={{ color: 'var(--color-text-secondary)' }}>
              No items found
            </div>
          ) : (
            <div className="flex flex-col gap-1">
              {filtered.map(item => {
                const isLoaded = loadedIds.has(item.id);
                const isSelected = selectedIds.has(item.id);
                return (
                  <button
                    key={item.id}
                    onClick={() => !isLoaded && onToggle(item.id)}
                    disabled={isLoaded}
                    className={`flex items-start gap-3 px-3 py-2.5 rounded-lg text-left transition-colors cursor-pointer
                      ${isLoaded ? 'opacity-50 cursor-not-allowed' : 'hover:opacity-90'}`}
                    style={{
                      background: isSelected
                        ? 'var(--color-primary)' + '18'
                        : isLoaded
                          ? 'var(--color-success)' + '10'
                          : 'transparent',
                      border: isSelected
                        ? '1px solid var(--color-primary)'
                        : '1px solid transparent',
                    }}
                  >
                    <div className="mt-0.5 shrink-0">
                      {isLoaded ? (
                        <span className="text-xs font-medium px-1.5 py-0.5 rounded"
                          style={{ color: 'var(--color-success)', background: 'var(--color-success)' + '18' }}>
                          loaded
                        </span>
                      ) : isSelected ? (
                        <Check size={16} style={{ color: 'var(--color-primary)' }} />
                      ) : (
                        <div className="w-4 h-4 rounded"
                          style={{ border: '1.5px solid var(--color-border)' }} />
                      )}
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-medium" style={{ color: 'var(--color-text)' }}>
                        {item.name}
                      </div>
                      {item.description && (
                        <div className="text-xs mt-0.5 truncate"
                          style={{ color: 'var(--color-text-secondary)' }}>
                          {item.description}
                        </div>
                      )}
                      {item.category && (
                        <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-muted)' }}>
                          {item.category}
                        </div>
                      )}
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between px-5 py-3 border-t"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
          <span className="text-xs" style={{ color: 'var(--color-text-muted)' }}>
            {selectedIds.size > 0
              ? `${selectedIds.size} selected`
              : `${selectableCount} available`}
          </span>
          <div className="flex gap-2">
            <button onClick={onClose}
              className="px-4 py-2 rounded-lg text-sm cursor-pointer transition-colors"
              style={{
                background: 'transparent',
                color: 'var(--color-text-secondary)',
                border: '1px solid var(--color-border)',
              }}>
              Cancel
            </button>
            <button
              onClick={onLoad}
              disabled={selectedIds.size === 0}
              className="px-4 py-2 rounded-lg text-sm font-medium cursor-pointer transition-colors disabled:opacity-40"
              style={{ background: 'var(--color-primary)', color: 'white' }}>
              Load
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
