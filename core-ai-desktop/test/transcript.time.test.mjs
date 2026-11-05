// Message bubble time labels: same-day, yesterday, older and cross-year formatting.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import transcriptTime from '../dist/test/transcriptTime.js';

const { messageTimeLabel } = transcriptTime;

const local = (year, month, day, hour, minute) => new Date(year, month - 1, day, hour, minute, 0).getTime();

test('same day shows only the 24-hour clock', () => {
  const now = new Date(2026, 10, 5, 18, 0, 0);
  assert.equal(messageTimeLabel(local(2026, 11, 5, 9, 5), now).label, '09:05');
  assert.equal(messageTimeLabel(local(2026, 11, 5, 23, 59), now).label, '23:59');
});

test('yesterday is named, not dated', () => {
  const now = new Date(2026, 10, 5, 0, 10, 0);
  assert.equal(messageTimeLabel(local(2026, 11, 4, 14, 32), now).label, 'Yesterday 14:32');
});

test('older messages carry month, day, and the year only when it differs', () => {
  const now = new Date(2026, 10, 5, 12, 0, 0);
  assert.equal(messageTimeLabel(local(2026, 10, 3, 8, 7), now).label, 'Oct 3, 08:07');
  assert.equal(messageTimeLabel(local(2025, 12, 31, 23, 0), now).label, 'Dec 31, 2025, 23:00');
});

test('the tooltip keeps the full local timestamp and bad input is rejected', () => {
  const stamp = local(2026, 11, 5, 14, 32);
  assert.equal(messageTimeLabel(stamp, new Date(2026, 10, 5, 18, 0, 0)).title, new Date(stamp).toLocaleString());
  assert.equal(messageTimeLabel(Number.NaN), null);
});
