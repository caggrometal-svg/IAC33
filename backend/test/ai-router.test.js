import test from 'node:test';
import assert from 'node:assert/strict';

const router = await import(`../src/ai-router.js?test=${Date.now()}-${Math.random()}`);
const originalFetch = globalThis.fetch;
const originalEnv = { ...process.env };

const messages = [{ role: 'user', content: 'ping' }];

function restore() {
  process.env = { ...originalEnv };
  globalThis.fetch = originalFetch;
}

test.afterEach(restore);

test('returns first responding provider with diagnostics', async () => {
  process.env.AI_PROVIDER_ORDER = 'groq,gemini';
  process.env.GROQ_API_KEY = 'test-groq';
  delete process.env.GEMINI_API_KEY;
  globalThis.fetch = async (url) => new Response(JSON.stringify({ choices: [{ message: { content: 'pong' } }] }), { status: 200, headers: { 'content-type': 'application/json' } });

  const result = await router.generateWithFreePool({ messages, timeoutMs: 1000 });
  assert.equal(result.provider, 'groq');
  assert.equal(result.text, 'pong');
  assert.deepEqual(result.diagnostics[0].state, 'RESPONDING');
});

test('uses keyless Animica first in the default free pool', async () => {
  delete process.env.AI_PROVIDER_ORDER;
  delete process.env.ANIMICA_MODEL;
  delete process.env.OPENROUTER_API_KEY;
  let requestedUrl = '';
  let requestedBody = null;
  let authorization;
  globalThis.fetch = async (url, options) => {
    requestedUrl = String(url);
    requestedBody = JSON.parse(options.body);
    authorization = options.headers.authorization;
    return new Response(JSON.stringify({ choices: [{ message: { content: 'free-ok' } }] }), { status: 200, headers: { 'content-type': 'application/json' } });
  };

  const result = await router.generateWithFreePool({ messages, timeoutMs: 1000 });
  assert.equal(result.provider, 'animica');
  assert.equal(result.model, 'animica-chat');
  assert.equal(result.text, 'free-ok');
  assert.equal(requestedUrl, 'https://animica.dev/v1/chat/completions');
  assert.equal(requestedBody.model, 'animica-chat');
  assert.equal(authorization, undefined);
});

test('fails over after rate limit', async () => {
  process.env.AI_PROVIDER_ORDER = 'openrouter,groq';
  process.env.OPENROUTER_API_KEY = 'test-openrouter';
  process.env.GROQ_API_KEY = 'test-groq';
  let calls = 0;
  globalThis.fetch = async (url) => {
    calls += 1;
    if (String(url).includes('openrouter.ai')) return new Response(JSON.stringify({ error: { message: 'rate limited' } }), { status: 429, headers: { 'content-type': 'application/json' } });
    return new Response(JSON.stringify({ choices: [{ message: { content: 'fallback-ok' } }] }), { status: 200, headers: { 'content-type': 'application/json' } });
  };

  const result = await router.generateWithFreePool({ messages, timeoutMs: 1000 });
  assert.equal(calls, 2);
  assert.equal(result.provider, 'groq');
  assert.equal(result.text, 'fallback-ok');
  assert.equal(result.diagnostics[0].state, 'RATE_LIMITED');
  assert.equal(result.diagnostics[1].state, 'RESPONDING');
});

test('reports all providers unavailable with diagnostics', async () => {
  process.env.AI_PROVIDER_ORDER = 'openrouter,groq';
  process.env.OPENROUTER_API_KEY = 'test-openrouter';
  process.env.GROQ_API_KEY = 'test-groq';
  globalThis.fetch = async (url) => new Response(JSON.stringify({ error: { message: 'down' } }), { status: 503, headers: { 'content-type': 'application/json' } });

  await assert.rejects(
    router.generateWithFreePool({ messages, timeoutMs: 1000 }),
    (error) => {
      assert.equal(error.message, 'AI_PROVIDERS_UNAVAILABLE');
      assert.equal(error.diagnostics.length, 2);
      assert.equal(error.diagnostics[0].state, 'FAILED');
      assert.equal(error.diagnostics[1].state, 'FAILED');
      return true;
    },
  );
});

test('passes a bounded timeout to each provider from the global connectivity budget', async () => {
  process.env.AI_PROVIDER_ORDER = 'groq';
  process.env.GROQ_API_KEY = 'test-groq';
  process.env.AI_TOTAL_TIMEOUT_MS = '1500';
  let observedSignal;
  globalThis.fetch = async (_url, options) => {
    observedSignal = options.signal;
    return new Response(JSON.stringify({ choices: [{ message: { content: 'budget-ok' } }] }), { status: 200, headers: { 'content-type': 'application/json' } });
  };

  const result = await router.generateWithFreePool({ messages, timeoutMs: 10000 });
  assert.equal(result.text, 'budget-ok');
  assert.equal(observedSignal.aborted, false);
});

test('default connectivity budget leaves time for provider failover', async () => {
  delete process.env.AI_PROVIDER_ORDER;
  delete process.env.AI_TOTAL_TIMEOUT_MS;
  process.env.OPENROUTER_API_KEY = 'test-openrouter';
  let calls = 0;
  globalThis.fetch = async (url, options) => {
    calls += 1;
    if (String(url).includes('animica.dev')) {
      assert.ok(options.signal);
      return new Response(JSON.stringify({ error: { message: 'temporary outage' } }), { status: 503, headers: { 'content-type': 'application/json' } });
    }
    return new Response(JSON.stringify({ choices: [{ message: { content: 'fallback-ok' } }] }), { status: 200, headers: { 'content-type': 'application/json' } });
  };

  const result = await router.generateWithFreePool({ messages });
  assert.equal(calls, 2);
  assert.equal(result.provider, 'openrouter');
  assert.equal(result.text, 'fallback-ok');
});
