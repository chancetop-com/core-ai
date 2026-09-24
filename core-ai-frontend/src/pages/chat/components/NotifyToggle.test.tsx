import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import NotifyToggle, { type NotifyToggleProps } from './NotifyToggle';
import type { NotificationSettings } from '../../../api/client';

const configured: NotificationSettings = {
  channel_id: 'stephen-qq-wechat',
  recipient: 'qqbot:c2c:OPENID',
  min_minutes: 5,
  channels: [{ channel_id: 'stephen-qq-wechat', channel_type: 'openclaw' }],
};

const noChannels: NotificationSettings = { channels: [] };

const notConfigured: NotificationSettings = {
  channels: [{ channel_id: 'stephen-qq-wechat', channel_type: 'openclaw' }],
};

function renderToggle(overrides: Partial<NotifyToggleProps> = {}) {
  const props: NotifyToggleProps = {
    enabled: false,
    settings: configured,
    onLoad: vi.fn(async () => configured),
    onToggle: vi.fn(),
    onSave: vi.fn(async () => null),
    ...overrides,
  };
  render(<NotifyToggle {...props} />);
  return props;
}

function bell(): HTMLButtonElement {
  return screen.getByTitle(/^Get a /) as HTMLButtonElement;
}

describe('NotifyToggle', () => {
  it('explains itself with the platform name instead of a bare bell', async () => {
    renderToggle();

    const button = bell();
    expect(button.title).toContain('QQ/WeChat');
    expect(button.title).toContain('currently off for this chat');

    await userEvent.click(button);

    expect(screen.getByText('Session notifications')).toBeTruthy();
    expect(screen.getByText(/Get a QQ\/WeChat message when a long turn of this chat finishes/)).toBeTruthy();
    expect(screen.getByText('Send to: stephen-qq-wechat · qqbot:c2c:OPENID')).toBeTruthy();
  });

  it('flips the per-chat switch once a target exists', async () => {
    const props = renderToggle();

    await userEvent.click(bell());
    await userEvent.click(screen.getByText('Off for this chat'));

    expect(props.onToggle).toHaveBeenCalledWith(true);
  });

  it('offers the setup form on first use and saves the target', async () => {
    const props = renderToggle({
      settings: notConfigured,
      onLoad: vi.fn(async () => notConfigured),
    });

    await userEvent.click(bell());

    const recipient = screen.getByPlaceholderText('e.g. qqbot:c2c:<openid>');
    await userEvent.type(recipient, 'qqbot:c2c:OPENID');
    await userEvent.click(screen.getByText('Save'));

    expect(props.onSave).toHaveBeenCalledWith({
      channelId: 'stephen-qq-wechat',
      recipient: 'qqbot:c2c:OPENID',
      minMinutes: 5,
    });
  });

  it('says so when the platform has no channel to offer', async () => {
    renderToggle({ settings: noChannels });

    await userEvent.click(bell());

    expect(screen.getByText(/No channel is available for notifications yet/)).toBeTruthy();
    expect((screen.getByText('Save') as HTMLButtonElement).disabled).toBe(true);
  });
});
