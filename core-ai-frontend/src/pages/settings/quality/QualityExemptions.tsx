import { useEffect, useState } from 'react';
import { ShieldOff } from 'lucide-react';
import type { QualityAdminApi, QualityPolicy } from '../../../api/quality';

interface Props {
  api: QualityAdminApi;
  policy: QualityPolicy | null;
  kind: 'agent' | 'skill';
  onSaved: () => Promise<void> | void;
}

export default function QualityExemptions({ api, policy, kind, onSaved }: Props) {
  const [entries, setEntries] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const exemptions = policy?.exemptions || [];

  useEffect(() => {
    setEntries((policy?.exemptions || []).join('\n'));
  }, [policy?.exemptions]);

  const save = async () => {
    if (!policy) return;
    const values = entries.split('\n').map(value => value.trim()).filter(Boolean);
    setBusy(true);
    setMessage('');
    try {
      await api.activate({
        expected_generation: policy.generation,
        change_reason: reason.trim() || 'Update quality exemptions',
        exemptions: values,
      });
      setReason('');
      setMessage('Exemptions saved as a new policy revision.');
      await onSaved();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Failed to save exemptions');
    } finally {
      setBusy(false);
    }
  };

  const hint = kind === 'agent'
    ? 'One agent id (or exact agent name) per line. Built-in system agents and personal assistant copies are exempt automatically.'
    : 'One skill name or namespace/name per line. Built-in agent exemptions do not apply to skills.';

  return (
    <div className="rounded-lg border mb-4" style={{ borderColor: 'var(--color-border)' }}>
      <div className="px-4 py-3 border-b" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
        <div className="flex items-center gap-2 text-sm font-medium">
          <ShieldOff size={14} /> Exemptions
          <span className="text-xs font-normal" style={{ color: 'var(--color-text-secondary)' }}>
            {exemptions.length} listed · policy revision {policy?.revision ?? '-'}
          </span>
        </div>
        <div className="text-xs mt-1" style={{ color: 'var(--color-text-secondary)' }}>{hint}</div>
      </div>
      <div className="p-4 grid gap-3 md:grid-cols-[1fr_240px]">
        <textarea
          value={entries}
          onChange={e => setEntries(e.target.value)}
          rows={4}
          spellCheck={false}
          placeholder={kind === 'agent' ? 'agent-id-1' : 'namespace/skill-name'}
          className="w-full rounded-lg border px-3 py-2 text-sm font-mono"
          style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text)' }}
        />
        <div className="flex flex-col gap-2">
          <input
            value={reason}
            onChange={e => setReason(e.target.value)}
            placeholder="Change reason (optional)"
            className="w-full rounded-lg border px-3 py-2 text-sm"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-primary)', color: 'var(--color-text)' }}
          />
          <button
            onClick={save}
            disabled={busy || !policy}
            className="px-3 py-2 rounded-lg text-sm cursor-pointer disabled:opacity-50"
            style={{ background: 'var(--color-primary)', color: 'white' }}>
            {busy ? 'Saving...' : 'Save exemptions'}
          </button>
        </div>
      </div>
      {message && (
        <div className="px-4 pb-3 text-xs" style={{ color: message.startsWith('Exemptions saved') ? 'var(--color-success)' : 'var(--color-error)' }}>
          {message}
        </div>
      )}
    </div>
  );
}
