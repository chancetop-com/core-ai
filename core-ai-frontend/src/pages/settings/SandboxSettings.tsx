import { useEffect, useState, type FormEvent } from 'react';
import { Box, CheckCircle2, CircleAlert, RefreshCw, Save } from 'lucide-react';
import { api, type SandboxSnapshotSettings } from '../../api/client';

export default function SandboxSettings() {
  const [settings, setSettings] = useState<SandboxSnapshotSettings | null>(null);
  const [retentionDays, setRetentionDays] = useState('');
  const [loadVersion, setLoadVersion] = useState(0);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    let active = true;
    api.sandboxSnapshotSettings.get()
      .then(response => {
        if (!active) return;
        setSettings(response);
        setRetentionDays(String(response.retention_days));
      })
      .catch(err => {
        if (active) setError(err instanceof Error ? err.message : 'Failed to load sandbox snapshot settings.');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, [loadVersion]);

  const refresh = () => {
    setLoading(true);
    setSettings(null);
    setError('');
    setSaved(false);
    setLoadVersion(version => version + 1);
  };

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!settings || saving) return;
    const days = Number(retentionDays);
    if (!retentionDays.trim() || !Number.isInteger(days) || days < 1 || days > 3650) {
      setError('Retention period must be a whole number between 1 and 3650 days.');
      setSaved(false);
      return;
    }
    setSaving(true);
    setError('');
    setSaved(false);
    try {
      const response = await api.sandboxSnapshotSettings.update({ retention_days: days });
      setSettings(response);
      setRetentionDays(String(response.retention_days));
      setSaved(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save sandbox snapshot settings.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="p-8 max-w-3xl">
      <div className="flex items-center justify-between gap-4 mb-6">
        <div>
          <h1 className="text-2xl font-semibold flex items-center gap-2">
            <Box size={22} />
            Sandbox
          </h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
            Configure how long filesystem snapshots are available for sandbox resume across the server.
          </p>
        </div>
        <button
          onClick={refresh}
          disabled={loading || saving}
          className="inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50"
          style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
          <RefreshCw size={16} />
          Refresh
        </button>
      </div>

      {error && (
        <div role="alert" className="mb-4 rounded-lg border px-4 py-3 text-sm flex items-center gap-2"
          style={{ borderColor: '#ef4444', background: 'rgba(239,68,68,0.08)', color: '#ef4444' }}>
          <CircleAlert size={16} className="shrink-0" />
          {error}
        </div>
      )}
      {saved && (
        <div role="status" className="mb-4 rounded-lg border px-4 py-3 text-sm flex items-center gap-2"
          style={{ borderColor: '#22c55e', background: 'rgba(34,197,94,0.08)', color: '#22c55e' }}>
          <CheckCircle2 size={16} className="shrink-0" />
          Sandbox snapshot settings saved.
        </div>
      )}
      {loading && (
        <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>Loading sandbox snapshot settings...</p>
      )}

      {!loading && settings && (
        <form onSubmit={save} noValidate>
          <section className="rounded-xl border" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
            <div className="p-5 border-b" style={{ borderColor: 'var(--color-border)' }}>
              <h2 className="font-semibold">Sandbox Resume</h2>
              <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                Changes apply only to new snapshots. Existing snapshots keep their original expiration dates.
              </p>
            </div>
            <div className="p-5">
              <label htmlFor="snapshot-retention-days" className="block text-sm font-medium mb-2">Retention period (days)</label>
              <input
                id="snapshot-retention-days"
                type="number"
                min={1}
                max={3650}
                step={1}
                required
                value={retentionDays}
                disabled={saving}
                aria-describedby="snapshot-retention-help"
                onChange={event => {
                  setRetentionDays(event.target.value);
                  setError('');
                  setSaved(false);
                }}
                className="w-40 rounded-lg border px-3 py-2 text-sm outline-none disabled:opacity-50"
                style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }}
              />
              <p id="snapshot-retention-help" className="text-xs mt-2" style={{ color: 'var(--color-text-secondary)' }}>
                Enter a whole number from 1 to 3650. The default is 14 days from snapshot creation.
              </p>
              {settings.updated_at && (
                <p className="text-xs mt-4" style={{ color: 'var(--color-text-secondary)' }}>
                  Last updated: {new Date(settings.updated_at).toLocaleString()}
                  {settings.updated_by && ` by ${settings.updated_by}`}
                </p>
              )}
            </div>
          </section>
          <button
            type="submit"
            disabled={saving}
            className="mt-6 inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50"
            style={{ background: 'var(--color-primary)', color: '#fff' }}>
            <Save size={16} />
            {saving ? 'Saving...' : 'Save Settings'}
          </button>
        </form>
      )}
    </div>
  );
}
