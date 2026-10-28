// IM routing bindings: peer > account > channel, with config order as the tie-break.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import bindingsModule from '../dist/test/bindings.js';

const { resolveBindingWorkspace } = bindingsModule;

function message(overrides = {}) {
  return {
    channel: 'openclaw-weixin',
    accountId: 'a1',
    chatType: 'direct',
    target: 'peer1',
    ...overrides,
  };
}

const chain = [
  { match: { channel: 'openclaw-weixin' }, workspace: 'channel-ws' },
  { match: { channel: 'openclaw-weixin', accountId: 'a1' }, workspace: 'account-ws' },
  { match: { channel: 'openclaw-weixin', peer: { kind: 'direct' } }, workspace: 'peer-wildcard-ws' },
  { match: { channel: 'openclaw-weixin', peer: { kind: 'direct', id: 'peer1' } }, workspace: 'peer-exact-ws' },
];

test('the most specific binding wins', () => {
  assert.equal(resolveBindingWorkspace(chain, message()), 'peer-exact-ws');
  assert.equal(resolveBindingWorkspace(chain.slice(0, 3), message()), 'peer-wildcard-ws');
  assert.equal(resolveBindingWorkspace(chain.slice(0, 2), message()), 'account-ws');
  assert.equal(resolveBindingWorkspace(chain.slice(0, 1), message()), 'channel-ws');
});

test('peer kind and id must match', () => {
  assert.equal(resolveBindingWorkspace(chain, message({ chatType: 'group' })), 'account-ws');
  assert.equal(resolveBindingWorkspace(chain, message({ target: 'peer2' })), 'peer-wildcard-ws');
});

test('a different channel never matches', () => {
  assert.equal(resolveBindingWorkspace(chain, message({ channel: 'telegram' })), null);
});

test('a wildcard account and peer id work', () => {
  const wildcard = [
    { match: { channel: 'openclaw-weixin', accountId: '*', peer: { id: '*' } }, workspace: 'wide-ws' },
  ];
  assert.equal(resolveBindingWorkspace(wildcard, message({ accountId: 'other', target: 'peer9' })), 'wide-ws');
});

test('the first binding wins a score tie', () => {
  const tied = [
    { match: { channel: 'openclaw-weixin' }, workspace: 'first' },
    { match: { channel: 'openclaw-weixin' }, workspace: 'second' },
  ];
  assert.equal(resolveBindingWorkspace(tied, message()), 'first');
});
