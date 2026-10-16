import { test } from 'node:test';
import assert from 'node:assert/strict';
import composerKeyboard from '../dist/test/composerKeyboard.js';

const { handleComposerKeyDown } = composerKeyboard;

function keyEvent(properties = {}) {
  return Object.assign(new Event('keydown', { cancelable: true }), {
    key: 'Enter',
    keyCode: 13,
    isComposing: false,
    shiftKey: false,
    ...properties,
  });
}

for (const [name, properties] of [
  ['during composition', { isComposing: true }],
  ['after compositionend', { keyCode: 229 }],
]) {
  test(`IME confirmation Enter ${name} is left to the input method; the next Enter sends`, () => {
    let sends = 0;
    const send = () => { sends++; };
    const confirmation = keyEvent(properties);

    handleComposerKeyDown(confirmation, send);

    assert.equal(sends, 0);
    assert.equal(confirmation.defaultPrevented, false);

    const nextEnter = keyEvent();
    handleComposerKeyDown(nextEnter, send);

    assert.equal(sends, 1);
    assert.equal(nextEnter.defaultPrevented, true);
  });
}

test('normal Enter prevents a newline before sending once', () => {
  let sends = 0;
  const event = keyEvent();

  handleComposerKeyDown(event, () => {
    assert.equal(event.defaultPrevented, true);
    sends++;
  });

  assert.equal(sends, 1);
});

for (const [name, properties] of [
  ['Shift+Enter', { shiftKey: true }],
  ['ordinary text key', { key: 'a', keyCode: 65 }],
  ['IME Process key', { key: 'Process', keyCode: 229, isComposing: true }],
]) {
  test(`${name} keeps its default behavior without sending`, () => {
    let sends = 0;
    const event = keyEvent(properties);

    handleComposerKeyDown(event, () => { sends++; });

    assert.equal(sends, 0);
    assert.equal(event.defaultPrevented, false);
  });
}
