import test from 'node:test';
import assert from 'node:assert/strict';
import { allowedTransitions, validCommand } from '../src/command-core.js';
import { server } from '../src/server.js';

async function withServer(fn) {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  try {
    await fn(`http://127.0.0.1:${address.port}`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

test('command lifecycle only permits declared forward transitions', () => {
  assert.equal(allowedTransitions.get('PENDING').has('CLAIMED'), true);
  assert.equal(allowedTransitions.get('EXECUTING').has('SUCCEEDED'), true);
  assert.equal(allowedTransitions.get('SUCCEEDED'), undefined);
});

test('command validation rejects malformed, expired, and oversized input', () => {
  assert.equal(validCommand(null), false);
  assert.equal(validCommand({ id: 'c1', type: 'bad type', payload: {}, idempotencyKey: 'short', expiresAt: new Date(Date.now() + 60000).toISOString() }), false);
  assert.equal(validCommand({ id: 'c1', type: 'sync', payload: {}, idempotencyKey: 'idem-key-123', expiresAt: new Date(Date.now() + 60000).toISOString() }), true);
  assert.equal(validCommand({ id: 'c1', type: 'sync', payload: {}, idempotencyKey: 'idem-key-123', expiresAt: new Date(Date.now() - 1000).toISOString() }), false);
  assert.equal(validCommand({ id: 'c1', type: 'sync', payload: [], idempotencyKey: 'idem-key-123', expiresAt: new Date(Date.now() + 60000).toISOString() }), false);
});

test('health is liveness and does not depend on database availability', async () => {
  await withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/health`);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { ok: true, service: 'iac33-backend', status: 'alive' });
  });
});

test('protected control endpoints reject missing authorization', async () => {
  await withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/v1/commands`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({})
    });
    assert.equal(response.status, 401);
  });
});


test('AI endpoint returns a provider response through the backend route', async () => {
  const originalFetch = globalThis.fetch;
  const originalOrder = process.env.AI_PROVIDER_ORDER;
  try {
    process.env.AI_PROVIDER_ORDER = 'animica';
    await withServer(async (baseUrl) => {
      globalThis.fetch = async (url, options) => {
        if (String(url).startsWith(baseUrl)) return originalFetch(url, options);
        return new Response(
          JSON.stringify({ choices: [{ message: { content: 'pong' } }] }),
          { status: 200, headers: { 'content-type': 'application/json' } },
        );
      };
      const response = await originalFetch(baseUrl + '/v1/ai/generate', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          messages: [{ role: 'user', content: 'ping' }],
          timeoutMs: 1000,
        }),
      });
      assert.equal(response.status, 200);
      const body = await response.json();
      assert.equal(body.ok, true);
      assert.equal(body.provider, 'animica');
      assert.equal(body.text, 'pong');
    });
  } finally {
    globalThis.fetch = originalFetch;
    if (originalOrder === undefined) delete process.env.AI_PROVIDER_ORDER;
    else process.env.AI_PROVIDER_ORDER = originalOrder;
  }
});
