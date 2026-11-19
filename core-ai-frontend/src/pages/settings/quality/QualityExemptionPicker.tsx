import { useEffect, useState } from 'react';
import { Search } from 'lucide-react';
import {
  qualityExemptionsApi,
  type QualityExemptionSubject,
  type QualityExemptionType,
} from '../../../api/quality';

interface Props {
  type: QualityExemptionType;
  selected: QualityExemptionSubject | null;
  onSelect: (subject: QualityExemptionSubject | null) => void;
}

export default function QualityExemptionPicker({ type, selected, onSelect }: Props) {
  const [query, setQuery] = useState('');
  const [subjects, setSubjects] = useState<QualityExemptionSubject[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    const timer = window.setTimeout(() => {
      setLoading(true);
      setError('');
      qualityExemptionsApi.subjects(type, query)
        .then(response => {
          if (cancelled) return;
          setSubjects(response.subjects || []);
        })
        .catch((e: Error) => {
          if (cancelled) return;
          setSubjects([]);
          setError(e.message);
        })
        .finally(() => {
          if (!cancelled) setLoading(false);
        });
    }, query.trim() ? 250 : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [type, query]);

  const select = (subject: QualityExemptionSubject) => {
    onSelect(selected?.id === subject.id ? null : subject);
  };

  return (
    <div className="rounded-lg border" style={{ borderColor: 'var(--color-border)' }}>
      <div className="p-3 border-b" style={{ borderColor: 'var(--color-border)' }}>
        <div className="relative">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2"
            style={{ color: 'var(--color-text-secondary)' }} />
          <input
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder={type === 'AGENT' ? 'Search agents by name' : 'Search skills by name or namespace'}
            aria-label="Search subjects"
            className="w-full rounded-lg border pl-9 pr-3 py-2 text-sm"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text)' }}
          />
        </div>
      </div>

      <div className="max-h-60 overflow-auto" role="listbox" aria-label="Exemption subjects">
        {error && (
          <div className="px-3 py-6 text-sm text-center" style={{ color: 'var(--color-error)' }}>{error}</div>
        )}
        {!error && loading && (
          <div className="px-3 py-6 text-sm text-center" style={{ color: 'var(--color-text-secondary)' }}>Searching...</div>
        )}
        {!error && !loading && subjects.length === 0 && (
          <div className="px-3 py-6 text-sm text-center" style={{ color: 'var(--color-text-secondary)' }}>
            No {type === 'AGENT' ? 'agents' : 'skills'} found
          </div>
        )}
        {!error && subjects.map(subject => {
          const disabled = Boolean(subject.already_exempt || subject.automatic);
          const active = selected?.id === subject.id;
          return (
            <button
              key={subject.id}
              type="button"
              role="option"
              aria-selected={active}
              disabled={disabled}
              onClick={() => select(subject)}
              className="w-full text-left px-3 py-2 border-t cursor-pointer disabled:cursor-default"
              style={{
                borderColor: 'var(--color-border)',
                background: active ? 'var(--color-primary-bg)' : 'transparent',
                opacity: disabled ? 0.55 : 1,
              }}>
              <div className="flex items-center gap-2">
                <span className="text-sm font-medium truncate">{subject.name}</span>
                {subject.automatic && <Badge tone="muted">Automatic</Badge>}
                {!subject.automatic && subject.already_exempt && <Badge tone="muted">Exempt</Badge>}
                {active && <Badge tone="primary">Selected</Badge>}
              </div>
              {subject.detail && (
                <div className="text-xs font-mono truncate mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>
                  {subject.detail}
                </div>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

function Badge({ tone, children }: { tone: 'muted' | 'primary'; children: React.ReactNode }) {
  return (
    <span
      className="px-1.5 py-0.5 rounded text-[10px] font-medium"
      style={{
        background: tone === 'primary' ? 'var(--color-primary-bg)' : 'var(--color-bg-tertiary)',
        color: tone === 'primary' ? 'var(--color-primary)' : 'var(--color-text-secondary)',
      }}>
      {children}
    </span>
  );
}
