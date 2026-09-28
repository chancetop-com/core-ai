import { useEffect, useState } from 'react';
import { Bell, BellRing, Loader2, X } from 'lucide-react';
import type { NotificationSettings, NotificationTarget } from '../../../api/client';

const CHANNEL_LABELS: Record<string, string> = {
  openclaw: 'QQ/WeChat',
  weclaw: 'WeChat',
  telegram: 'Telegram',
  slack: 'Slack',
};

function channelLabel(channelType?: string): string {
  if (!channelType) return 'notification';
  return CHANNEL_LABELS[channelType] ?? channelType;
}

function targetKey(target: NotificationTarget): string {
  return `${target.channel_id}|${target.recipient}`;
}

function targetLabel(target: NotificationTarget): string {
  return `${channelLabel(target.channel_type)} — ${target.channel_id}${target.shared ? ' (shared)' : ''}`;
}

export interface NotifyToggleProps {
  /** the per-chat switch stored on the session */
  enabled: boolean;
  /** the user's own notification target; null until loaded */
  settings: NotificationSettings | null;
  onLoad: () => Promise<NotificationSettings | null>;
  onToggle: (next: boolean) => void;
  onSave: (data: { channelId: string; recipient: string; minMinutes: number }) => Promise<string | null>;
}

/**
 * Per-chat completion-notification control: the switch plus the user's own delivery target, in one
 * dialog. The target is always an address the platform has seen this user write from — others'
 * addresses are neither listed nor typeable, and a shared channel is labelled as such because a
 * notice sent there is visible to more than the user.
 */
export default function NotifyToggle({ enabled, settings, onLoad, onToggle, onSave }: NotifyToggleProps) {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState(false);
  const [selected, setSelected] = useState('');
  const [minMinutes, setMinMinutes] = useState('5');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const targets = settings?.targets ?? [];
  const configured = Boolean(settings?.channel_id && settings?.recipient);
  const savedTarget = targets.find(t => t.channel_id === settings?.channel_id && t.recipient === settings?.recipient);
  const selectedTarget = targets.find(t => targetKey(t) === selected);
  const label = channelLabel((savedTarget ?? targets[0])?.channel_type);

  const applySettings = (next: NotificationSettings | null) => {
    if (!next) return;
    const known = next.targets ?? [];
    const saved = known.find(t => t.channel_id === next.channel_id && t.recipient === next.recipient);
    // default to the address the platform already knows: the user only has to confirm it
    setSelected(targetKey(saved ?? known[0] ?? { channel_id: '', recipient: '' }));
    setMinMinutes(next.min_minutes != null ? String(next.min_minutes) : '5');
  };

  useEffect(() => {
    if (!open) return;
    setLoading(true);
    onLoad()
      .then(next => {
        applySettings(next);
        setEditing(!(next?.channel_id && next?.recipient));
      })
      .finally(() => setLoading(false));
  }, [open, onLoad]);

  const close = () => {
    setOpen(false);
    setEditing(false);
    setError('');
  };

  const save = async () => {
    if (!selectedTarget) return;
    const minutes = Number(minMinutes);
    if (!Number.isInteger(minutes) || minutes < 1) {
      setError('Notify after must be a whole number of minutes, at least 1.');
      return;
    }
    setSaving(true);
    setError('');
    const failure = await onSave({
      channelId: selectedTarget.channel_id,
      recipient: selectedTarget.recipient,
      minMinutes: minutes,
    });
    setSaving(false);
    if (failure) {
      setError(failure);
      return;
    }
    setEditing(false);
  };

  const title = configured
    ? `Get a ${label} message when a long turn of this chat finishes — currently ${enabled ? 'on' : 'off'} for this chat`
    : `Get a ${label} message when a long turn of this chat finishes — click to set it up`;

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="p-3 rounded-xl cursor-pointer transition-colors disabled:opacity-30 shrink-0"
        style={{
          background: enabled ? 'var(--color-primary)' + '20' : 'var(--color-bg-tertiary)',
          border: '1px solid var(--color-border)',
          color: enabled ? 'var(--color-primary)' : 'var(--color-text-secondary)',
        }}
        title={title}>
        {enabled ? <BellRing size={18} /> : <Bell size={18} />}
      </button>

      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center"
          style={{ background: 'rgba(0,0,0,0.5)' }}
          onClick={close}>
          <div className="rounded-2xl shadow-2xl flex flex-col overflow-hidden"
            style={{ width: 'min(460px, 92vw)', maxHeight: '85vh', background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}
            onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between px-5 py-4 border-b"
              style={{ borderColor: 'var(--color-border)' }}>
              <h2 className="text-base font-semibold">Session notifications</h2>
              <button onClick={close} className="p-1 rounded cursor-pointer hover:opacity-70"
                style={{ color: 'var(--color-text-secondary)' }} title="Close">
                <X size={18} />
              </button>
            </div>

            <div className="px-5 py-4 overflow-y-auto">
              <p className="text-xs mb-4 leading-relaxed" style={{ color: 'var(--color-text-secondary)' }}>
                Get a {label} message when a long turn of this chat finishes, so you can walk away from
                a slow session. Short turns stay quiet.
              </p>

              {loading ? (
                <div className="flex items-center gap-2 text-xs mb-4" style={{ color: 'var(--color-text-secondary)' }}>
                  <Loader2 size={14} className="animate-spin" /> Loading…
                </div>
              ) : (
                <>
                  <button
                    onClick={() => {
                      if (!configured) {
                        // nothing saved yet: persist the address the platform already knows and let
                        // the caller turn the chat on, rather than looking like a dead switch
                        void save();
                        return;
                      }
                      onToggle(!enabled);
                    }}
                    className="flex items-center gap-2 w-full cursor-pointer mb-4">
                    <span className="w-9 h-5 rounded-full relative shrink-0 transition-colors"
                      style={{ background: enabled ? 'var(--color-primary)' : 'var(--color-border)' }}>
                      <span className="absolute top-0.5 w-4 h-4 rounded-full bg-white transition-all"
                        style={{ left: enabled ? '18px' : '2px' }} />
                    </span>
                    <span className="text-sm">{enabled ? 'On for this chat' : 'Off for this chat'}</span>
                  </button>

                  {configured && !editing ? (
                    <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                      <div className="truncate">
                        Send to: {settings?.channel_id}{savedTarget?.shared ? ' (shared)' : ''} · {settings?.recipient}
                      </div>
                      <div className="mt-0.5">Notify after {settings?.min_minutes ?? 5} min</div>
                      <button onClick={() => { applySettings(settings); setEditing(true); }}
                        className="mt-2 underline cursor-pointer"
                        style={{ color: 'var(--color-primary)' }}>
                        Change target
                      </button>
                    </div>
                  ) : !targets.length ? (
                    <div className="text-xs leading-relaxed" style={{ color: 'var(--color-text-secondary)' }}>
                      {settings?.channels?.length
                        ? `No address to pick yet: send the bot a message on ${channelLabel(settings.channels[0].channel_type)} once — after that it shows up here as a choice.`
                        : 'No channel is available for notifications yet — ask an admin to add one (Triggers → Channels).'}
                    </div>
                  ) : (
                    <div className="space-y-3">
                      <label className="block">
                        <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Send to</span>
                        <select value={selected} onChange={e => setSelected(e.target.value)}
                          className="w-full mt-1 px-3 py-2 rounded-lg text-sm border-0 outline-none cursor-pointer"
                          style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }}>
                          {targets.map(t => (
                            <option key={targetKey(t)} value={targetKey(t)}>{targetLabel(t)}</option>
                          ))}
                        </select>
                        {selectedTarget && (
                          <div className="text-xs mt-1 break-all" style={{ color: 'var(--color-text-secondary)' }}>
                            {selectedTarget.recipient}
                            {selectedTarget.shared && ' · this channel is shared — the message is visible to others there'}
                          </div>
                        )}
                      </label>
                      <label className="block">
                        <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Notify after (minutes)</span>
                        <input type="number" min="1" value={minMinutes} onChange={e => setMinMinutes(e.target.value)}
                          className="w-full mt-1 px-3 py-2 rounded-lg text-sm border-0 outline-none"
                          style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text)' }} />
                      </label>
                      {error && <div className="text-xs" style={{ color: 'var(--color-error)' }}>{error}</div>}
                      <div className="flex items-center gap-2 pt-1">
                        <button onClick={() => void save()} disabled={saving || !selectedTarget}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-white cursor-pointer disabled:opacity-50"
                          style={{ background: 'var(--color-primary)' }}>
                          {saving && <Loader2 size={12} className="animate-spin" />}
                          Save
                        </button>
                        {configured && (
                          <button onClick={() => { setEditing(false); setError(''); }}
                            className="px-3 py-1.5 rounded-lg text-xs cursor-pointer"
                            style={{ color: 'var(--color-text-secondary)' }}>
                            Cancel
                          </button>
                        )}
                      </div>
                    </div>
                  )}
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
}
