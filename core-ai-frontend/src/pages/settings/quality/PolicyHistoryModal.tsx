import { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import { qualityApi, type QualityPolicy } from '../../../api/quality';

export default function PolicyHistoryModal({ onClose }: { onClose: () => void }) {
  const [history, setHistory] = useState<QualityPolicy[]>([]);
  const [active, setActive] = useState<QualityPolicy | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    qualityApi.policies()
      .then(response => {
        setActive(response.active);
        setHistory(response.history || []);
      })
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load policy history'))
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.5)' }} onClick={onClose}>
      <div className="rounded-xl w-full max-w-2xl mx-4 max-h-[80vh] flex flex-col"
        style={{ background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)' }}
        onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between px-5 py-4 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <div>
            <h3 className="font-semibold">Quality policy history</h3>
            <div className="text-xs mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>
              Active revision {active?.revision ?? '-'} · generation {active?.generation ?? '-'}
            </div>
          </div>
          <button onClick={onClose} className="p-1 rounded cursor-pointer" style={{ color: 'var(--color-text-secondary)' }} title="Close">
            <X size={18} />
          </button>
        </div>

        <div className="p-5 overflow-auto">
          {loading ? (
            <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
          ) : error ? (
            <div className="rounded-lg px-3 py-2 text-sm" style={{ background: 'rgba(220,38,38,0.12)', color: 'var(--color-error)' }}>{error}</div>
          ) : history.length === 0 ? (
            <div className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>No policy revisions yet.</div>
          ) : (
            <div className="space-y-2">
              {history.map(item => (
                <div key={item.revision} className="rounded-lg border px-3 py-2 text-sm" style={{ borderColor: 'var(--color-border)' }}>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-mono text-xs">#{item.revision}</span>
                    {item.active && (
                      <span className="text-xs px-1.5 py-0.5 rounded" style={{ background: 'var(--color-primary-bg)', color: 'var(--color-primary)' }}>active</span>
                    )}
                    <span className="flex-1 min-w-0 truncate" title={item.change_reason}>{item.change_reason}</span>
                  </div>
                  <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                    {item.activated_by} {item.activated_at ? new Date(item.activated_at).toLocaleString() : ''}
                    {' · '}
                    {item.bindings?.length ?? 0} bound rules
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
