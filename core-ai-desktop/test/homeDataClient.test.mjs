// Home data client tests: the plugin page never holds the credential, so every server call must be
// signed with the stored login, hit a fixed path, and fail with a stable code the page can render.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import home from '../dist/test/homeData.js';

const { HomeDataClient, HomeDataError, validHomeId } = home;

function withServer(handler) {
  return new Promise((resolve) => {
    const calls = [];
    const server = http.createServer((request, response) => {
      const chunks = [];
      request.on('data', (chunk) => chunks.push(chunk));
      request.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        const call = { method: request.method, url: request.url, headers: request.headers, body };
        calls.push(call);
        handler(call, response);
      });
    });
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      resolve({ server, calls, base: `http://127.0.0.1:${port}` });
    });
  });
}

function json(response, status, payload) {
  const body = JSON.stringify(payload);
  response.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  response.end(body);
}

function client(base, entry = {}) {
  return new HomeDataClient({
    loadEntries: () => [{ server_url: `${base}/`, api_key: 'secret-key', user_id: 'u-1', name: 'Stephen', active: true, ...entry }],
  });
}

test('status reports the host but never the credential', () => {
  const instance = client('https://dev.example.com');
  const status = instance.status();
  assert.equal(status.signedIn, true);
  assert.equal(status.serverHost, 'dev.example.com');
  assert.equal(status.userName, 'Stephen');
  assert.equal(JSON.stringify(status).includes('secret-key'), false);
});

test('no stored login fails closed', async () => {
  const instance = new HomeDataClient({ loadEntries: () => [{ server_url: 'https://dev.example.com', api_key: '', active: true }] });
  assert.deepEqual(instance.status(), { signedIn: false, serverHost: null, userName: null });
  await assert.rejects(() => instance.reports(), (error) => error instanceof HomeDataError && error.code === 'not_signed_in');
});

test('reports use the fixed endpoint, the stored token and clamped paging', async () => {
  const { server, calls, base } = await withServer((call, response) => {
    json(response, 200, {
      total: 42,
      reports: [{
        id: 'r-1', title: 'Weekly revenue', description: 'desc', display_type: 'METRIC', dataset_id: 'd-1',
        dataset_name: 'Orders', revision: 3, updated_at: '2026-11-18T00:00:00Z', created_at: '2026-11-01T00:00:00Z',
        spec_json: '{"secret":"should-not-matter"}',
      }],
    });
  });
  try {
    const result = await client(base).reports(0, 500);
    assert.equal(calls[0].headers.authorization, 'Bearer secret-key');
    assert.equal(calls[0].url, '/api/reports?offset=0&limit=50');
    assert.equal(result.total, 42);
    assert.deepEqual(result.reports[0], {
      id: 'r-1', title: 'Weekly revenue', description: 'desc', displayType: 'METRIC', datasetId: 'd-1',
      datasetName: 'Orders', revision: 3, updatedAt: '2026-11-18T00:00:00Z', createdAt: '2026-11-01T00:00:00Z',
    });
  } finally {
    server.close();
  }
});

test('report data parses columns and rows and keeps the request range', async () => {
  const { server, calls, base } = await withServer((call, response) => {
    json(response, 200, {
      report_id: 'r-1',
      status: 'READY',
      timezone: 'Asia/Shanghai',
      from_inclusive: '2026-11-11T00:00:00Z',
      to_exclusive: '2026-11-18T00:00:00Z',
      columns_json: JSON.stringify([{ name: 'Day', type: 'DATE' }, { name: 'Revenue', type: 'NUMBER' }]),
      rows_json: JSON.stringify([['2026-11-11', '1200'], ['2026-11-12', null]]),
      matched_record_count: 2,
      has_more: false,
      freshness_status: 'WITHIN_EXPECTATION',
    });
  });
  try {
    const data = await client(base).reportData('r-1', { rangeKind: 'ROLLING_DAYS', days: 7 });
    assert.equal(calls[0].url, '/api/reports/r-1/data?range_kind=ROLLING_DAYS&days=7&page_size=100&offset=0');
    assert.deepEqual(data.columns, [{ name: 'Day', type: 'DATE' }, { name: 'Revenue', type: 'NUMBER' }]);
    assert.deepEqual(data.rows, [['2026-11-11', '1200'], ['2026-11-12', null]]);
    assert.equal(data.matchedRecordCount, 2);
    assert.equal(data.freshnessStatus, 'WITHIN_EXPECTATION');
  } finally {
    server.close();
  }
});

test('unsupported report ranges are dropped instead of forwarded', async () => {
  const { server, calls, base } = await withServer((call, response) => json(response, 200, { columns_json: '[]', rows_json: '[]' }));
  try {
    await client(base).reportData('r-1', { rangeKind: 'FIXED', days: 5000 });
    assert.equal(calls[0].url, '/api/reports/r-1/data?page_size=100&offset=0');
  } finally {
    server.close();
  }
});

test('todos map source and origin fields, defaulting historical rows to MANUAL', async () => {
  const { server, calls, base } = await withServer((call, response) => {
    json(response, 200, {
      todos: [
        { id: 't-1', title: 'Manual item', completed: false, priority: 'high' },
        {
          id: 't-2', title: 'Auto item', completed: true, source: 'AUTO', origin_date: '2026-11-18',
          origin_session_ids: ['s-1', 's-2'], dedupe_key: 'auto:2026-11-18:s-1:abc', generated_by: 'deepseek/deepseek-v4-flash',
        },
      ],
    });
  });
  try {
    const result = await client(base).todos();
    assert.equal(calls[0].url, '/api/for-you/todos');
    assert.equal(result.todos[0].source, 'MANUAL');
    assert.deepEqual(result.todos[0].originSessionIds, []);
    assert.equal(result.todos[1].source, 'AUTO');
    assert.equal(result.todos[1].originDate, '2026-11-18');
    assert.equal(result.todos[1].dedupeKey, 'auto:2026-11-18:s-1:abc');
  } finally {
    server.close();
  }
});

test('create, update and delete send only write fields and reject bad input locally', async () => {
  const { server, calls, base } = await withServer((call, response) => {
    if (call.method === 'POST') {
      json(response, 200, { id: 't-9', title: 'Ship release', completed: false, source: 'MANUAL' });
      return;
    }
    if (call.method === 'PUT') {
      json(response, 200, { id: 't-9', title: 'Ship release', completed: true, source: 'MANUAL' });
      return;
    }
    json(response, 200, { deleted: true });
  });
  const instance = client(base);
  try {
    const created = await instance.createTodo({ title: '  Ship release  ', priority: 'urgent', description: 'today' });
    assert.equal(created.id, 't-9');
    assert.deepEqual(JSON.parse(calls[0].body), { title: 'Ship release', description: 'today', priority: 'normal' });

    await instance.updateTodo('t-9', { completed: true });
    assert.equal(calls[1].method, 'PUT');
    assert.equal(calls[1].url, '/api/for-you/todos/t-9');
    assert.deepEqual(JSON.parse(calls[1].body), { completed: true });

    const removed = await instance.deleteTodo('t-9');
    assert.equal(removed.deleted, true);
    assert.equal(calls[2].method, 'DELETE');

    await assert.rejects(() => instance.createTodo({ title: '   ' }), /title/);
    await assert.rejects(() => instance.updateTodo('t-9', {}), /nothing to update/);
    assert.equal(calls.length, 3);
  } finally {
    server.close();
  }
});

test('server status becomes a stable page error code', async () => {
  const { server, base } = await withServer((call, response) => {
    if (call.url.startsWith('/api/reports')) {
      json(response, 403, { error_code: 'FORBIDDEN', message: 'report.view required' });
      return;
    }
    json(response, 401, { message: 'unauthorized' });
  });
  try {
    await assert.rejects(() => client(base).reports(), (error) => error.code === 'forbidden' && error.status === 403);
    await assert.rejects(() => client(base).todos(), (error) => error.code === 'not_signed_in');
  } finally {
    server.close();
  }
});

test('an unreachable server reads as offline', async () => {
  const instance = client('http://127.0.0.1:9');
  await assert.rejects(() => instance.reports(), (error) => error.code === 'offline');
});

test('ids are validated before a path is built', async () => {
  assert.equal(validHomeId('4d2b30b0-91b2-4f1d-9f55-0d72f4a1b9aa'), true);
  assert.equal(validHomeId('../etc/passwd'), false);
  assert.equal(validHomeId('a/b'), false);
  const instance = client('https://dev.example.com');
  await assert.rejects(() => instance.report('../x'), (error) => error.code === 'invalid_request');
  await assert.rejects(() => instance.deleteTodo(''), (error) => error.code === 'invalid_request');
});

test('digest query is only sent for an ISO date', async () => {
  const { server, calls, base } = await withServer((call, response) => json(response, 200, { date: '2026-11-18', status: 'COMPLETED', todo_count: 3 }));
  try {
    const instance = client(base);
    const digest = await instance.todoDigest('2026-11-18');
    await instance.todoDigest('not-a-date');
    assert.equal(calls[0].url, '/api/for-you/todos/digest?date=2026-11-18');
    assert.equal(calls[1].url, '/api/for-you/todos/digest');
    assert.equal(digest.status, 'COMPLETED');
    assert.equal(digest.todoCount, 3);
  } finally {
    server.close();
  }
});
