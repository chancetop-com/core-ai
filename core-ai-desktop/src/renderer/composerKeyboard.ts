export function handleComposerKeyDown(event: KeyboardEvent, send: () => void): void {
  if (event.isComposing || event.keyCode === 229) return;
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    send();
  }
}
