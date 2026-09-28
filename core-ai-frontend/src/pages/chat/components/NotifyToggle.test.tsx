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
  targets: [{ channel_id: 'stephen-qq-wechat', channel_type: 'openclaw', recipient: 'qqbot:c2c:OPENID' }],
};

const notConfigured: NotificationSettings = {
  channels: [{ channel_id: 'stephen-qq-wechat', channel_type: 'openclaw' }],
  targets: [{ channel_id: 'stephen-qq-wechat', channel_type: 'openclaw', recipient: 'qqbot:c2c:OPENID' }],
};

const noChannels: NotificationSettings = { channels: [], targets: [] };

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
    expect(screen.getByText(/Send to: stephen-qq-wechat · qqbot:c2c:OPENID/)).toBeTruthy();
  });

  it('flips the per-chat switch once a target exists', async () => {
    const props = renderToggle();

    await userEvent.click(bell());
    await userEvent.click(screen.getByText('Off for this chat'));

    expect(props.onToggle).toHaveBeenCalledWith(true);
  });

  it('offers a known address as a pick instead of asking for an openid', async () => {
    const props = renderToggle({ settings: notConfigured, onLoad: vi.fn(async () => notConfigured) });

    await userEvent.click(bell());

    expect(screen.getByText('QQ/WeChat — stephen-qq-wechat')).toBeTruthy();
    expect(screen.queryByPlaceholderText('e.g. qqbot:c2c:<openid>')).toBeNull();
    expect(screen.queryByText('Other address…')).toBeNull();

    await userEvent.click(screen.getByText('Save'));

    expect(props.onSave).toHaveBeenCalledWith({
      channelId: 'stephen-qq-wechat',
      recipient: 'qqbot:c2c:OPENID',
      minMinutes: 5,
    });
  });

  it('saves and turns on when the switch is clicked with nothing saved yet', async () => {
    const props = renderToggle({ settings: notConfigured, onLoad: vi.fn(async () => notConfigured) });

    await userEvent.click(bell());
    await userEvent.click(screen.getByText('Off for this chat'));

    expect(props.onSave).toHaveBeenCalledWith({
      channelId: 'stephen-qq-wechat',
      recipient: 'qqbot:c2c:OPENID',
      minMinutes: 5,
    });
  });

  it('warns when the only channel is shared with other people', async () => {
    const shared: NotificationSettings = {
      channels: [{ channel_id: 'team-slack', channel_type: 'slack', shared: true }],
      targets: [{ channel_id: 'team-slack', channel_type: 'slack', recipient: 'C123', shared: true }],
    };
    renderToggle({ settings: shared, onLoad: vi.fn(async () => shared) });

    await userEvent.click(bell());

    expect(screen.getByText('Slack — team-slack (shared)')).toBeTruthy();
    expect(screen.getByText(/this channel is shared — the message is visible to others there/)).toBeTruthy();
  });

  it('tells the user why nothing can be picked yet', async () => {
    const noTargets: NotificationSettings = {
      channels: [{ channel_id: 'stephen-qq-wechat', channel_type: 'openclaw' }],
      targets: [],
    };
    renderToggle({ settings: noTargets, onLoad: vi.fn(async () => noTargets) });

    await userEvent.click(bell());

    expect(screen.getByText(/No address to pick yet: send the bot a message on QQ\/WeChat once/)).toBeTruthy();
    expect(screen.queryByText('Save')).toBeNull();
  });

  it('says so when the platform has no channel to offer', async () => {
    renderToggle({ settings: noChannels, onLoad: vi.fn(async () => noChannels) });

    await userEvent.click(bell());

    expect(screen.getByText(/No channel is available for notifications yet/)).toBeTruthy();
  });
});
