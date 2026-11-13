import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { sessionShareApi } from '../../../api/sessionShares';
import SessionShareModal from './SessionShareModal';

vi.mock('../../../api/sessionShares', () => ({
  sessionShareApi: { get: vi.fn(), create: vi.fn(), revoke: vi.fn() },
}));
vi.mock('../../../api/permissions', () => ({ usePermission: () => true }));

const share = {
  id: 'share-1', session_id: 'session-1', url: '/shared/sessions/token',
  max_message_seq: 2, message_count: 2, created_at: '2026-11-13T00:00:00Z', expires_at: '2035-01-01T00:00:00Z',
};

beforeEach(() => {
  vi.mocked(sessionShareApi.get).mockResolvedValue({ artifacts: [] });
  vi.mocked(sessionShareApi.create).mockResolvedValue(share);
  vi.mocked(sessionShareApi.revoke).mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', { value: { writeText: vi.fn().mockResolvedValue(undefined) }, configurable: true });
});

describe('SessionShareModal', () => {
  it('discloses anonymous trace content and defaults to seven days', async () => {
    render(<SessionShareModal sessionId="session-1" onClose={vi.fn()} />);
    expect(await screen.findByRole('button', { name: 'Create and copy link' })).toBeTruthy();
    expect(screen.getByText(/Anyone with the link/)).toBeTruthy();
    expect(screen.getByText(/system prompts, injected memories/)).toBeTruthy();
    expect(screen.getByRole('combobox', { name: 'Share expiry' })).toHaveProperty('value', '7');
    fireEvent.click(screen.getByRole('button', { name: 'Create and copy link' }));
    await waitFor(() => expect(sessionShareApi.create).toHaveBeenCalledWith('session-1', 7));
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeTruthy();
    expect(screen.getByRole('textbox', { name: 'Share link' })).toHaveProperty('value', `${window.location.origin}/shared/sessions/token`);
  });

  it('allows revoking an existing link while a new turn is running', async () => {
    vi.mocked(sessionShareApi.get).mockResolvedValueOnce({ share, artifacts: [] });
    render(<SessionShareModal sessionId="session-1" running onClose={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Revoke link' }));
    await waitFor(() => expect(sessionShareApi.revoke).toHaveBeenCalledWith('session-1', 'share-1'));
    expect(await screen.findByRole('button', { name: 'Create and copy link' })).toHaveProperty('disabled', true);
    expect(screen.getByText(/Wait for the current turn/)).toBeTruthy();
  });

  it('allows retrying creation after a scope race instead of disabling the dialog', async () => {
    vi.mocked(sessionShareApi.create).mockRejectedValueOnce(new Error('Session scope changed.'));
    render(<SessionShareModal sessionId="session-1" onClose={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Create and copy link' }));
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Session scope changed.');
    expect(screen.getByRole('button', { name: 'Create and copy link' })).toHaveProperty('disabled', false);
  });
});
