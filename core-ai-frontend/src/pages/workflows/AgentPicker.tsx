import { useEffect, useRef, useState, type CSSProperties, type UIEvent } from 'react';
import { api, type WorkflowAgentOption } from '../../api/client';

interface AgentPickerProps {
  value: string;
  selectedName?: string;
  type: 'AGENT' | 'LLM_CALL';
  onChange: (option: WorkflowAgentOption) => void;
}

type Scope = 'mine' | 'shared';

const PAGE_SIZE = 20;

export default function AgentPicker({ value, selectedName, type, onChange }: AgentPickerProps) {
  const rootRef = useRef<HTMLDivElement>(null);
  const nextPageRequestedRef = useRef(false);
  const [open, setOpen] = useState(false);
  const [selectionProps, setSelectionProps] = useState({ type, value });
  const [scope, setScope] = useState<Scope>('mine');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const [items, setItems] = useState<WorkflowAgentOption[]>([]);
  const [total, setTotal] = useState(0);
  const [selected, setSelected] = useState<WorkflowAgentOption>();
  const [selectedChecked, setSelectedChecked] = useState(false);
  const [error, setError] = useState('');
  const [retryToken, setRetryToken] = useState(0);

  if (selectionProps.type !== type || selectionProps.value !== value) {
    setSelectionProps({ type, value });
    setPage(1);
    setItems([]);
    setTotal(0);
    setSelected(undefined);
    setSelectedChecked(false);
    setError('');
  }

  useEffect(() => {
    if (!open) return;

    const closeOnOutsidePointer = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('pointerdown', closeOnOutsidePointer);
    return () => document.removeEventListener('pointerdown', closeOnOutsidePointer);
  }, [open]);

  useEffect(() => {
    if (!open) return;

    let cancelled = false;
    const timer = window.setTimeout(() => {
      api.workflows.agentOptions(scope, type, query.trim(), page, PAGE_SIZE, value || undefined)
        .then((response) => {
          if (cancelled) return;
          setError('');
          setTotal(response.total);
          setSelected(response.selected);
          setSelectedChecked(true);
          setItems((previous) => {
            const incoming = page === 1 ? response.items : [...previous, ...response.items];
            return [...new Map(incoming.map((item) => [item.id, item])).values()];
          });
          nextPageRequestedRef.current = false;
        })
        .catch((cause: Error) => {
          if (cancelled) return;
          setError(cause.message);
          nextPageRequestedRef.current = false;
        });
    }, query.trim() ? 250 : 0);

    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [open, page, query, retryToken, scope, type, value]);

  const resetResults = () => {
    setPage(1);
    setItems([]);
    setTotal(0);
    setError('');
    setSelected(undefined);
    setSelectedChecked(false);
    nextPageRequestedRef.current = false;
  };

  const switchScope = (nextScope: Scope) => {
    if (nextScope === scope) return;
    setScope(nextScope);
    setQuery('');
    resetResults();
  };

  const changeQuery = (nextQuery: string) => {
    setQuery(nextQuery);
    resetResults();
  };

  const selectOption = (option: WorkflowAgentOption) => {
    onChange(option);
    setOpen(false);
  };

  const onResultsScroll = (event: UIEvent<HTMLDivElement>) => {
    const element = event.currentTarget;
    const reachedBoundary = element.scrollHeight - element.scrollTop - element.clientHeight <= 24;
    if (reachedBoundary && items.length < total && !nextPageRequestedRef.current) {
      nextPageRequestedRef.current = true;
      setPage((current) => current + 1);
    }
  };

  const triggerName = value ? (selectedName || selected?.name || 'Selected agent') : 'Select agent';
  const displayedItems = items.filter((item) => item.id !== selected?.id);

  return (
    <div ref={rootRef} style={rootStyle}>
      <button
        type="button"
        aria-expanded={open}
        aria-haspopup="listbox"
        onClick={() => setOpen((current) => !current)}
        style={triggerStyle}
      >
        {triggerName}
      </button>
      {open && (
        <div style={popupStyle}>
          <div role="group" aria-label="Agent scope" style={tabsStyle}>
            <button
              type="button"
              aria-pressed={scope === 'mine'}
              onClick={() => switchScope('mine')}
              style={tabStyle}
            >
              My agents
            </button>
            <button
              type="button"
              aria-pressed={scope === 'shared'}
              onClick={() => switchScope('shared')}
              style={tabStyle}
            >
              Shared agents
            </button>
          </div>
          <input
            type="text"
            inputMode="search"
            aria-label="Search agents"
            value={query}
            onChange={(event) => changeQuery(event.currentTarget.value)}
            style={searchStyle}
          />
          {error && (
            <div role="alert" style={messageStyle}>
              <span>{error}</span>
              <button type="button" onClick={() => setRetryToken((current) => current + 1)}>Retry</button>
            </div>
          )}
          {!error && value && selectedChecked && !selected && (
            <p style={messageStyle}>Unavailable — replace this agent</p>
          )}
          <div
            role="listbox"
            aria-label="Agent results"
            onScroll={onResultsScroll}
            style={resultsStyle}
          >
            {selected && (
              <div style={pinnedStyle}>
                <OptionButton option={selected} onSelect={selectOption} />
              </div>
            )}
            {displayedItems.map((item) => (
              <div key={item.id}>
                <OptionButton option={item} onSelect={selectOption} />
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function OptionButton({ option, onSelect }: {
  option: WorkflowAgentOption;
  onSelect: (option: WorkflowAgentOption) => void;
}) {
  return (
    <button
      type="button"
      aria-label={`${option.name} — ${option.status.toLowerCase()}`}
      onClick={() => onSelect(option)}
      style={optionStyle}
    >
      <span>{option.name}</span>
      <span style={statusStyle}>{option.status}</span>
    </button>
  );
}

const rootStyle: CSSProperties = { position: 'relative' };
const triggerStyle: CSSProperties = {
  width: '100%',
  padding: '7px 9px',
  textAlign: 'left',
  border: '1px solid var(--color-border)',
  borderRadius: 6,
  background: 'var(--color-bg)',
  color: 'var(--color-text)',
  cursor: 'pointer',
};
const popupStyle: CSSProperties = {
  position: 'absolute',
  zIndex: 20,
  top: 'calc(100% + 4px)',
  left: 0,
  right: 0,
  padding: 8,
  border: '1px solid var(--color-border)',
  borderRadius: 8,
  background: 'var(--color-bg)',
  boxShadow: '0 8px 24px rgba(0, 0, 0, 0.16)',
};
const tabsStyle: CSSProperties = { display: 'flex', gap: 4, marginBottom: 8 };
const tabStyle: CSSProperties = {
  flex: 1,
  padding: '5px 8px',
  border: '1px solid var(--color-border)',
  borderRadius: 5,
  background: 'transparent',
  color: 'var(--color-text)',
  cursor: 'pointer',
};
const searchStyle: CSSProperties = {
  width: '100%',
  padding: '6px 8px',
  border: '1px solid var(--color-border)',
  borderRadius: 5,
  background: 'var(--color-bg)',
  color: 'var(--color-text)',
};
const messageStyle: CSSProperties = { margin: '8px 0', fontSize: 12, color: 'var(--color-text-secondary)' };
const resultsStyle: CSSProperties = { maxHeight: 240, marginTop: 8, overflowY: 'auto' };
const pinnedStyle: CSSProperties = { borderBottom: '1px solid var(--color-border)', marginBottom: 4, paddingBottom: 4 };
const optionStyle: CSSProperties = {
  width: '100%',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
  padding: '7px 8px',
  border: 0,
  borderRadius: 5,
  background: 'transparent',
  color: 'var(--color-text)',
  textAlign: 'left',
  cursor: 'pointer',
};
const statusStyle: CSSProperties = { fontSize: 10, color: 'var(--color-text-secondary)' };
