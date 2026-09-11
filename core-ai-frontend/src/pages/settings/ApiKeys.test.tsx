import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { userApi } from '../../api/client';
import ApiKeys from './ApiKeys';

describe('Security gateway endpoint', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(userApi, 'getApiKey').mockResolvedValue({ api_key: 'coreai_test', created_at: new Date().toISOString() });
  });

  it('shows the gateway base URL of the host the user is on', async () => {
    render(<ApiKeys />);

    const baseUrl = `${window.location.origin}/api/gateway/v1`;
    expect(await screen.findByText(baseUrl)).toBeTruthy();
    expect(screen.getByText(`${baseUrl}/models`)).toBeTruthy();
  });

  it('copies the base URL so it can be pasted into an external tool', async () => {
    const writeText = vi.fn<(text: string) => Promise<void>>().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });

    render(<ApiKeys />);

    await userEvent.click(await screen.findByTitle('Copy base URL'));
    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/api/gateway/v1`);
  });
});
