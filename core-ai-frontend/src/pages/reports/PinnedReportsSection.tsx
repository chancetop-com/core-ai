import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PinOff, RefreshCw } from 'lucide-react';
import { api } from '../../api/client';
import type { ReportDataView, ReportPinView } from '../../api/client';
import ReportRenderer, { type ReportDisplay } from './ReportRenderer';

function parseDisplay(value: string | null | undefined): ReportDisplay | null {
  if (!value) return null;
  try {
    return JSON.parse(value) as ReportDisplay;
  } catch {
    return null;
  }
}

export default function PinnedReportsSection() {
  const navigate = useNavigate();
  const [pins, setPins] = useState<ReportPinView[]>([]);
  const [layoutRevision, setLayoutRevision] = useState(0);
  const [dataByPin, setDataByPin] = useState<Record<string, ReportDataView | null>>({});
  const [errorsByPin, setErrorsByPin] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const layout = await api.reports.pins.layout();
      const nextPins = layout.pins ?? [];
      setPins(nextPins);
      setLayoutRevision(layout.layout_revision ?? 0);
      if (nextPins.length > 0) {
        const response = await api.reports.pins.data({
          pin_ids: nextPins.slice(0, 8).map(pin => pin.id),
          page_size: 100,
        });
        const nextData: Record<string, ReportDataView | null> = {};
        const nextErrors: Record<string, string> = {};
        for (const item of response.items ?? []) {
          if (!item.pin_id) continue;
          nextData[item.pin_id] = item.data ?? null;
          if (item.error_code) nextErrors[item.pin_id] = item.error_message || item.error_code;
        }
        setDataByPin(nextData);
        setErrorsByPin(nextErrors);
      } else {
        setDataByPin({});
        setErrorsByPin({});
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    const timer = window.setInterval(load, 5 * 60 * 1000);
    return () => window.clearInterval(timer);
  }, [load]);

  const unpin = async (pin: ReportPinView) => {
    try {
      await api.reports.pins.unpin(pin.id, layoutRevision);
      await load();
    } catch {
      await load();
    }
  };

  if (loading && pins.length === 0) {
    return (
      <section className="mb-6">
        <h2 className="text-base font-semibold mb-3">My reports</h2>
        <div className="rounded-xl border p-6 animate-pulse" style={{ borderColor: 'var(--color-border)' }}>
          <div className="h-4 w-32 rounded mb-3" style={{ background: 'var(--color-bg-tertiary)' }} />
          <div className="h-3 w-48 rounded" style={{ background: 'var(--color-bg-tertiary)' }} />
        </div>
      </section>
    );
  }

  if (pins.length === 0) {
    return (
      <section className="mb-6">
        <h2 className="text-base font-semibold mb-3">My reports</h2>
        <button onClick={() => navigate('/datasets/reports')}
          className="w-full rounded-xl border p-4 text-left cursor-pointer hover:bg-[var(--color-bg-secondary)]"
          style={{ borderColor: 'var(--color-border)' }}>
          <div className="text-sm font-medium">No pinned reports</div>
          <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Create a report in chat, then pin it from the report page.
          </div>
        </button>
      </section>
    );
  }

  return (
    <section className="mb-6">
      <div className="flex items-center justify-between mb-3">
        <h2 className="text-base font-semibold">My reports</h2>
        <button onClick={load}
          className="inline-flex items-center gap-1 text-xs cursor-pointer"
          style={{ color: 'var(--color-text-secondary)' }}>
          <RefreshCw size={13} /> Refresh
        </button>
      </div>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        {pins.map(pin => {
          const data = dataByPin[pin.id] ?? null;
          const error = errorsByPin[pin.id];
          return (
            <div key={pin.id}
              className={`rounded-xl border p-4 ${pin.width === 'FULL' ? 'md:col-span-2' : ''}`}
              style={{ borderColor: 'var(--color-border)' }}>
              <div className="flex items-start justify-between gap-3 mb-2">
                <button onClick={() => navigate(`/datasets/reports/${pin.report_id}`)}
                  className="min-w-0 text-left cursor-pointer">
                  <div className="text-sm font-medium truncate">{pin.title || 'Untitled report'}</div>
                  <div className="text-[11px] mt-0.5" style={{ color: 'var(--color-text-secondary)' }}>
                    {pin.display_type || 'TABLE'} · rev {pin.report_revision ?? 1}
                  </div>
                </button>
                <button onClick={() => unpin(pin)}
                  className="w-7 h-7 rounded flex items-center justify-center cursor-pointer shrink-0"
                  title="Unpin"
                  style={{ color: 'var(--color-text-secondary)' }}>
                  <PinOff size={14} />
                </button>
              </div>
              {error ? (
                <div className="text-xs py-4" style={{ color: '#dc2626' }}>{error}</div>
              ) : (
                <ReportRenderer data={data} displayType={pin.display_type} display={parseDisplay(pin.display_json)} height={180} />
              )}
            </div>
          );
        })}
      </div>
    </section>
  );
}
