import { act, createEvent, fireEvent, render, screen } from '@testing-library/react';
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
    notify={{
      enabled: false,
      settings: null,
      onLoad: async () => null,
      onToggle: () => {},
      onSave: async () => null,
    }}
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

describe('ChatComposer', () => {
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

  it.each([
    { phase: 'during composition', isComposing: true, keyCode: 13 },
    { phase: 'after compositionend with an IME key code', isComposing: false, keyCode: 229 },
  ])('leaves IME Enter $phase to the input method and sends only on the next Enter', ({ isComposing, keyCode }) => {
    const { onSend } = renderComposer();
    const element = textarea();
    fireEvent.compositionStart(element);
    typeInto('就是dui');
    if (!isComposing) fireEvent.compositionEnd(element, { data: 'dui' });

    const confirm = createEvent.keyDown(element, { key: 'Enter', isComposing, keyCode });
    fireEvent(element, confirm);

    expect(onSend).not.toHaveBeenCalled();
    expect(element.value).toBe('就是dui');
    expect(confirm.defaultPrevented).toBe(false);

    if (isComposing) fireEvent.compositionEnd(element, { data: 'dui' });
    fireEvent.keyUp(element, { key: 'Enter' });
    fireEvent.keyDown(element, { key: 'Enter', isComposing: false, keyCode: 13 });

    expect(onSend).toHaveBeenCalledTimes(1);
    expect(onSend).toHaveBeenCalledWith('就是dui', []);
    expect(element.value).toBe('');
  });

  it.each([
    { phase: 'during composition', isComposing: true, keyCode: 13 },
    { phase: 'after compositionend with an IME key code', isComposing: false, keyCode: 229 },
  ])('does not select an attachment on IME Enter $phase', async ({ isComposing, keyCode }) => {
    const { onSend } = renderComposer();
    await upload('menu.png', 'image/png');
    typeInto('make @me');
    await screen.findByRole('option', { name: /menu\.png/ });

    const confirm = createEvent.keyDown(textarea(), { key: 'Enter', isComposing, keyCode });
    fireEvent(textarea(), confirm);

    expect(textarea().value).toBe('make @me');
    expect(screen.getByRole('option', { name: /menu\.png/ })).not.toBeNull();
    expect(onSend).not.toHaveBeenCalled();
    expect(confirm.defaultPrevented).toBe(false);
  });

  it('keeps Shift+Enter available for a newline without sending', async () => {
    const { onSend } = renderComposer();
    const user = userEvent.setup();
    typeInto('hello');
    await user.click(textarea());
    await user.keyboard('{Shift>}{Enter}{/Shift}');

    expect(textarea().value).toBe('hello\n');
    expect(onSend).not.toHaveBeenCalled();
  });

  it('still sends on Enter when no mention menu is open', () => {
    const { onSend } = renderComposer();

    typeInto('hello');
    fireEvent.keyDown(textarea(), { key: 'Enter' });

    expect(onSend).toHaveBeenCalledWith('hello', []);
  });
});
