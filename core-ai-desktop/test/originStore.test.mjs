import { test } from 'node:test';
import assert from 'node:assert/strict';
import originModule from '../dist/test/originStore.js';

const { OriginStore } = originModule;

function message(overrides = {}) {
  return {
    id: 'm_1',
    channel: 'openclaw-weixin',
    accountId: 'acct',
    target: 'peer@im.wechat',
    sessionKey: 'session-key',
    chatType: 'direct',
    sender: { id: 'peer@im.wechat', name: '张三' },
    groupSubject: null,
    text: 'hi',
    media: [],
    platformMessageId: 'p_1',
    replyToId: null,
    wasMentioned: null,
    receivedAt: Date.now(),
    replyable: true,
    ...overrides,
  };
}

test('direct inbound origin is addressable by session until cleared', () => {
  const store = new OriginStore();
  const grant = store.begin({ message: message(), workspace: 'ws-1', sessionId: 'session-1' });
  assert.equal(grant.target, 'peer@im.wechat');
  assert.equal(store.getForSession('session-1').originId, grant.originId);
  store.clear('m_1');
  assert.equal(store.getForSession('session-1'), null);
});

test('group and addressless messages never get an origin', () => {
  const store = new OriginStore();
  assert.equal(store.begin({ message: message({ chatType: 'group' }), workspace: 'ws', sessionId: 's' }), null);
  assert.equal(store.begin({ message: message({ target: null }), workspace: 'ws', sessionId: 's' }), null);
});
