import assert from 'node:assert/strict';
import { dirname, resolve } from 'node:path';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');

let vite;
let datasetBindingsDiffer;

before(async () => {
  vite = await createServer({ root: frontendRoot, appType: 'custom', logLevel: 'silent', server: { middlewareMode: true } });
  ({ datasetBindingsDiffer } = await vite.ssrLoadModule('/src/pages/chat/datasetConfigDrift.ts'));
});

after(async () => {
  await vite?.close();
});

test('no drift when both sides are empty', () => {
  assert.equal(datasetBindingsDiffer([], undefined), false);
});

test('drift when the agent gained a binding the session never saw', () => {
  assert.equal(
    datasetBindingsDiffer([], [{ dataset_id: 'd1', permission: 'WRITE', is_output: true }]),
    true,
  );
});

test('drift when a permission changed', () => {
  assert.equal(
    datasetBindingsDiffer(
      [{ dataset_id: 'd1', permission: 'READ', is_output: false }],
      [{ dataset_id: 'd1', permission: 'WRITE', is_output: false }],
    ),
    true,
  );
});

test('no drift for the same bindings in a different order', () => {
  const snapshot = [
    { dataset_id: 'd1', permission: 'WRITE', is_output: false },
    { dataset_id: 'd2', permission: 'READ', is_output: true },
  ];
  assert.equal(datasetBindingsDiffer(snapshot, [...snapshot].reverse()), false);
});
