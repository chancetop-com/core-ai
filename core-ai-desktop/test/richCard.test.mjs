import { test } from 'node:test';
import assert from 'node:assert/strict';
import richCard from '../dist/test/richCard.js';

const { parseRichCard, formFieldSpecs, buildFormPayload } = richCard;

const form = (...fields) => ({ type: 'form', fields });
const specsOf = (...fields) => formFieldSpecs(form(...fields));

test('a card parses from JSON text or an already-parsed object', () => {
  const parsed = parseRichCard(JSON.stringify({ schema_version: 1, title: 'Menu', blocks: [{ type: 'divider' }] }));
  assert.equal(parsed.title, 'Menu');
  assert.equal(parsed.blocks.length, 1);

  assert.equal(parseRichCard({ blocks: [{ type: 'text', text: 'hi' }] }).blocks[0].text, 'hi');
});

test('garbage, empty blocks and non-object cards are ignored', () => {
  assert.equal(parseRichCard('not json'), null);
  assert.equal(parseRichCard(''), null);
  assert.equal(parseRichCard('{"blocks":[]}'), null);
  assert.equal(parseRichCard('{"blocks":[{"no":"type"}]}'), null);
  assert.equal(parseRichCard('[1,2]'), null);
  assert.equal(parseRichCard(null), null);
});

test('form fields default to text and normalize both option shapes', () => {
  const specs = specsOf(
    { name: 'name', label: 'Name' },
    { name: 'plan', label: 'Plan', type: 'select', options: ['Basic', { label: 'Pro', value: 'pro' }] },
  );
  assert.equal(specs[0].type, 'text');
  assert.equal(specs[0].required, false);
  assert.deepEqual(specs[1].options, [{ label: 'Basic', value: 'Basic' }, { label: 'Pro', value: 'pro' }]);
});

test('required fields block submission and optional empty fields stay out of the payload', () => {
  const specs = specsOf(
    { name: 'name', label: 'Name', required: true },
    { name: 'notes', label: 'Notes', type: 'textarea' },
    { name: 'agree', label: 'Agree', type: 'checkbox' },
  );

  const missing = buildFormPayload(specs, { notes: '  ', agree: false });
  assert.deepEqual(missing.errors, { name: 'Required' });
  assert.deepEqual(missing.payload, { agree: false });

  const filled = buildFormPayload(specs, { name: 'Ada', notes: '', agree: true });
  assert.deepEqual(filled.errors, {});
  assert.deepEqual(filled.payload, { name: 'Ada', agree: true });
});

test('number fields enforce type and bounds and submit a number', () => {
  const specs = specsOf({ name: 'seats', label: 'Seats', type: 'number', min: 1, max: 10 });

  assert.equal(buildFormPayload(specs, { seats: 'abc' }).errors.seats, 'Enter a number');
  assert.equal(buildFormPayload(specs, { seats: '0' }).errors.seats, 'Must be at least 1');
  assert.equal(buildFormPayload(specs, { seats: '11' }).errors.seats, 'Must be at most 10');
  assert.deepEqual(buildFormPayload(specs, { seats: '5' }).payload, { seats: 5 });
});

test('select and radio values must come from the option list', () => {
  const specs = specsOf({ name: 'plan', label: 'Plan', type: 'select', options: [{ label: 'Pro', value: 'pro' }] });

  assert.equal(buildFormPayload(specs, { plan: 'free' }).errors.plan, 'Choose one of the options');
  assert.deepEqual(buildFormPayload(specs, { plan: 'pro' }).payload, { plan: 'pro' });
});

test('email and url fields validate before they travel to the agent', () => {
  const specs = specsOf(
    { name: 'email', label: 'Email', type: 'email' },
    { name: 'site', label: 'Site', type: 'url' },
  );

  assert.equal(buildFormPayload(specs, { email: 'nope', site: 'https://x.test' }).errors.email, 'Enter a valid email');
  assert.equal(buildFormPayload(specs, { email: 'a@b.co', site: 'javascript:alert(1)' }).errors.site, 'Enter a valid http(s) URL');
  assert.deepEqual(buildFormPayload(specs, { email: 'a@b.co', site: 'https://x.test' }).payload,
    { email: 'a@b.co', site: 'https://x.test' });
});

test('multi-choice fields filter unknown values and honour required', () => {
  const specs = specsOf({
    name: 'channels', label: 'Channels', type: 'checkboxes', required: true,
    options: ['Email', { label: '短信', value: 'sms' }],
  });

  const empty = buildFormPayload(specs, { channels: [] });
  assert.deepEqual(empty.errors, { channels: 'Choose at least one' });

  const stray = buildFormPayload(specs, { channels: ['Email', 'pigeon'] });
  assert.deepEqual(stray.errors, {});
  assert.deepEqual(stray.payload, { channels: ['Email'] });
});

test('date, time and datetime fields reject malformed values', () => {
  const specs = specsOf(
    { name: 'day', label: 'Day', type: 'date' },
    { name: 'at', label: 'At', type: 'time' },
    { name: 'when', label: 'When', type: 'datetime' },
  );

  const bad = buildFormPayload(specs, { day: '2026-13-40', at: '25:00', when: 'not a date' });
  assert.equal(bad.errors.day, 'Enter a valid date');
  assert.equal(bad.errors.at, 'Enter a valid time');
  assert.equal(bad.errors.when, 'Enter a valid date and time');

  const good = buildFormPayload(specs, { day: '2026-10-30', at: '09:05', when: '2026-10-30T09:05' });
  assert.deepEqual(good.errors, {});
});
