import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createRef } from 'react';
import ChatComposer from './ChatComposer';
import type { ChatComposerHandle } from './ChatComposer';

function renderComposer(onSend = vi.fn()) {
  const ref = createRef<ChatComposerHandle>();
  render(<ChatComposer
    ref={ref}
    status="idle"
    selectedAgentId="agent-1"
    messagesContainerRef={{ current: null }}
    loadedToolIds={new Set()}
    loadedSkillIds={new Set()}
    loadedSubAgentIds={new Set()}
    preToolIds={new Set()}
    preSkillIds={new Set()}
    preSubAgentIds={new Set()}
    datasetConfigs={[]}
    showVoiceSidebar={false}
    getToolChipName={id => id}
    getSkillChipName={id => id}
    getAgentChipName={id => id}
    onOpenConfig={vi.fn()}
    onToggleVoiceSidebar={vi.fn()}
    onSend={onSend}
    onCancel={vi.fn()}
    onToast={vi.fn()}
  />);
  return { onSend, ref };
}

function textarea(): HTMLTextAreaElement {
  return screen.getByPlaceholderText('Send a message...') as HTMLTextAreaElement;
}

async function upload(name: string, type: string) {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  await userEvent.upload(input, new File(['x'], name, { type }));
  await screen.findByText(new RegExp(name.replace('.', '\\.')));
}

function typeInto(value: string) {
  const element = textarea();
  fireEvent.change(element, { target: { value } });
  element.setSelectionRange(value.length, value.length);
  fireEvent.keyUp(element, { key: value.at(-1) ?? '' });
}

describe('ChatComposer attachment mentions', () => {
  beforeEach(() => {
    // jsdom under this vitest version exposes a localStorage without getItem; the composer reads the api key from it
    vi.stubGlobal('localStorage', { getItem: () => 'test-key' });
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (String(url).includes('upload-credential')) {
        return { ok: true, json: async () => ({ upload_url: 'https://blob/put', blob_url: 'https://blob/file', container: 'c', blob_name: 'b' }) };
      }
      return { ok: true };
    }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('numbers ready image badges in upload order and leaves files unnumbered', async () => {
    renderComposer();

    await upload('menu.png', 'image/png');
    await upload('report.pdf', 'application/pdf');
    await upload('receipt.jpg', 'image/jpeg');

    expect(screen.getByText('Image 1 · menu.png')).not.toBeNull();
    expect(screen.getByText('report.pdf')).not.toBeNull();
    expect(screen.getByText('Image 2 · receipt.jpg')).not.toBeNull();
  });

  it('gives pasted images short sequential names', async () => {
    renderComposer();
    const file = new File(['x'], 'blob', { type: 'image/png' });

    await act(async () => {
      fireEvent.paste(textarea(), { clipboardData: { items: [{ type: 'image/png', getAsFile: () => file }] } });
    });

    expect(await screen.findByText('Image 1 · pasted-1.png')).not.toBeNull();
  });

  it('opens the attachment menu on @ and inserts the chosen file name as plain text', async () => {
    const { onSend } = renderComposer();
    await upload('menu.png', 'image/png');

    typeInto('make @me');
    const option = await screen.findByRole('option', { name: /menu\.png/ });
    expect(option).not.toBeNull();

    fireEvent.keyDown(textarea(), { key: 'Enter' });

    expect(textarea().value).toBe('make menu.png ');
    expect(screen.queryByRole('option')).toBeNull();
    expect(onSend).not.toHaveBeenCalled();
  });

  it('moves the highlight with ArrowDown and keeps it through the key release', async () => {
    renderComposer();
    await upload('menu.png', 'image/png');
    await upload('receipt.jpg', 'image/jpeg');

    typeInto('@image');
    await screen.findAllByRole('option');
    fireEvent.keyDown(textarea(), { key: 'ArrowDown' });
    fireEvent.keyUp(textarea(), { key: 'ArrowDown' });

    const options = screen.getAllByRole('option');
    expect(options.map(option => option.getAttribute('aria-selected'))).toEqual(['false', 'true']);
  });

  it('still sends on Enter when no mention menu is open', () => {
    const { onSend } = renderComposer();

    typeInto('hello');
    fireEvent.keyDown(textarea(), { key: 'Enter' });

    expect(onSend).toHaveBeenCalledWith('hello', []);
  });
});
