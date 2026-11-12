import { test } from 'node:test';
import assert from 'node:assert/strict';
import voice from '../dist/test/voice.js';

const { encodeWav, bytesToBase64 } = voice;

function text(view, offset, length) {
  let value = '';
  for (let i = 0; i < length; i += 1) value += String.fromCharCode(view.getUint8(offset + i));
  return value;
}

test('encodeWav writes a mono 16-bit PCM WAV header', () => {
  const samples = new Float32Array([0, 0.5, -0.5, 1]);
  const bytes = encodeWav(samples, 16000);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

  assert.equal(text(view, 0, 4), 'RIFF');
  assert.equal(view.getUint32(4, true), 36 + samples.length * 2);
  assert.equal(text(view, 8, 4), 'WAVE');
  assert.equal(text(view, 12, 4), 'fmt ');
  assert.equal(view.getUint16(20, true), 1);
  assert.equal(view.getUint16(22, true), 1);
  assert.equal(view.getUint32(24, true), 16000);
  assert.equal(view.getUint16(34, true), 16);
  assert.equal(text(view, 36, 4), 'data');
  assert.equal(view.getUint32(40, true), samples.length * 2);
  assert.equal(view.getInt16(44, true), 0);
  assert.equal(view.getInt16(46, true), Math.round(0.5 * 0x7fff) - 1); // DataView truncates the .5
  assert.equal(view.getInt16(48, true), Math.round(-0.5 * 0x8000));
  assert.equal(view.getInt16(50, true), 0x7fff);
});

test('encodeWav clamps samples outside [-1, 1]', () => {
  const bytes = encodeWav(new Float32Array([2, -2]), 8000);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

  assert.equal(view.getInt16(44, true), 0x7fff);
  assert.equal(view.getInt16(46, true), -0x8000);
});

test('bytesToBase64 survives chunks larger than the spread limit', () => {
  const bytes = new Uint8Array(70_000);
  bytes[0] = 1;
  bytes[69_999] = 2;

  const decoded = Buffer.from(bytesToBase64(bytes), 'base64');

  assert.equal(decoded.length, bytes.length);
  assert.equal(decoded[0], 1);
  assert.equal(decoded[69_999], 2);
});
