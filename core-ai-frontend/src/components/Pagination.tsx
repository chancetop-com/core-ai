import { ChevronLeft, ChevronRight } from 'lucide-react';

const DEFAULT_PAGE_SIZES = [10, 20, 50];

// Numbered pagination: first/last always visible, a sliding window around the current page, ellipsis between
function pageItems(page: number, totalPages: number): (number | '...')[] {
  if (totalPages <= 7) return Array.from({ length: totalPages }, (_, i) => i + 1);
  const pages = new Set<number>([1, totalPages, page - 1, page, page + 1]);
  const sorted = [...pages].filter(p => p >= 1 && p <= totalPages).sort((a, b) => a - b);
  const items: (number | '...')[] = [];
  let prev = 0;
  for (const p of sorted) {
    if (prev && p - prev > 1) items.push('...');
    items.push(p);
    prev = p;
  }
  return items;
}

export default function Pagination({ offset, limit, total, shown, loading = false, pageSizes = DEFAULT_PAGE_SIZES, onOffsetChange, onLimitChange }: {
  offset: number;
  limit: number;
  total: number;
  shown: number;
  loading?: boolean;
  pageSizes?: number[];
  onOffsetChange: (offset: number) => void;
  onLimitChange: (limit: number) => void;
}) {
  const page = Math.floor(offset / limit) + 1;
  // total < 0 means the server could not count this filter combination — fall back to prev/next paging
  const hasTotal = total >= 0;
  const totalPages = Math.max(1, Math.ceil(Math.max(total, 0) / limit));
  const buttonStyle = { borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' };

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 mt-4">
      <div className="flex items-center gap-3 text-sm" style={{ color: 'var(--color-text-secondary)' }}>
        <span>
          {loading ? 'Loading...'
            : !hasTotal ? `Showing ${shown > 0 ? offset + 1 : 0}-${offset + shown}`
            : total > 0 ? `Showing ${offset + 1}-${offset + shown} of ${total}`
            : 'No results'}
        </span>
        <label className="flex items-center gap-1.5">
          <select value={limit} onChange={event => onLimitChange(Number(event.target.value))}
            className="px-2 py-1 rounded-md border text-sm cursor-pointer"
            style={{ ...buttonStyle, background: 'var(--color-bg-tertiary)' }}>
            {pageSizes.map(size => <option key={size} value={size}>{size}</option>)}
          </select>
          per page
        </label>
      </div>
      <div className="flex items-center gap-1.5">
        <button onClick={() => onOffsetChange(Math.max(0, offset - limit))} disabled={offset === 0}
          className="px-2.5 py-1.5 rounded-md border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
          style={buttonStyle}>
          <ChevronLeft size={14} /> Prev
        </button>
        {hasTotal && pageItems(page, totalPages).map((item, index) => item === '...' ? (
          <span key={`gap-${index}`} className="px-1 text-sm" style={{ color: 'var(--color-text-secondary)' }}>...</span>
        ) : (
          <button key={item} onClick={() => onOffsetChange((item - 1) * limit)} disabled={item === page}
            className="min-w-[32px] px-2 py-1.5 rounded-md border text-sm cursor-pointer disabled:cursor-default"
            style={item === page
              ? { borderColor: 'var(--color-primary)', background: 'var(--color-primary)', color: 'white', fontWeight: 600 }
              : buttonStyle}>
            {item}
          </button>
        ))}
        <button onClick={() => onOffsetChange(offset + limit)}
          disabled={hasTotal ? page >= totalPages : shown < limit}
          className="px-2.5 py-1.5 rounded-md border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
          style={buttonStyle}>
          Next <ChevronRight size={14} />
        </button>
      </div>
    </div>
  );
}
