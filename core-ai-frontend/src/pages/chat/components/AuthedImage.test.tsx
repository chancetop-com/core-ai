import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import AuthedImage from './AuthedImage';

describe('shared images', () => {
  it('fetches a shared image anonymously and never offers editing', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('image', { headers: { 'Content-Type': 'image/png' } })));
    const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:shared-image');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    render(<AuthedImage src="/api/public/session-shares/token/files/image/content" alt="Shared image" readOnly />);
    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(await screen.findByAltText('Shared image')).toBeTruthy();
    expect(fetch).toHaveBeenCalledWith('/api/public/session-shares/token/files/image/content', {
      credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer',
    });
    expect(screen.queryByTitle('圈选编辑')).toBeNull();
  });

  it('does not fetch a private or external image from shared content', async () => {
    const fetcher = vi.fn();
    vi.stubGlobal('fetch', fetcher);
    render(<AuthedImage src="/api/files/private/content" alt="Private" readOnly />);
    expect(await screen.findByText(/Image is not included in this share/)).toBeTruthy();
    expect(fetcher).not.toHaveBeenCalled();
  });
});
