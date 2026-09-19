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

test('sends exact prompt stop sequences to OpenAI-compatible providers', async () => {
  process.env.AI_PROVIDER_ORDER = 'kilo';
  let requestBody;
  globalThis.fetch = async (_url, options) => {
    requestBody = JSON.parse(options.body);
    return new Response(
      JSON.stringify({ choices: [{ message: { content: 'pong' } }] }),
      { status: 200, headers: { 'content-type': 'application/json' } }
    );
  };

  await router.generateWithFreePool({ messages, timeoutMs: 1000 });
  assert.deepEqual(requestBody.stop, ['\nUSER:', '\nASSISTANT:']);
});

test('returns the first responding free provider', async () => {
  process.env.AI_PROVIDER_ORDER = 'kilo';
  let calls = 0;
  globalThis.fetch = async () => {
    calls += 1;
    return new Response(
      JSON.stringify({ choices: [{ message: { content: 'pong' } }] }),
      { status: 200, headers: { 'content-type': 'application/json' } }
    );
  };

  const result = await router.generateWithFreePool({ messages, timeoutMs: 1000 });
  assert.equal(result.provider, 'kilo');
  assert.equal(result.text, 'pong');
  assert.ok(calls >= 1);
});

test('retries the same provider after HTTP 503', async () => {
  process.env.AI_PROVIDER_ORDER = 'kilo';
  process.env.AI_PROVIDER_RETRIES = '2';
  let calls = 0;
  globalThis.fetch = async () => {
    calls += 1;
    if (calls === 1) {
      return new Response(
        JSON.stringify({ error: { message: 'temporarily unavailable' } }),
        { status: 503, headers: { 'content-type': 'application/json' } }
      );
    }
    return new Response(
      JSON.stringify({ choices: [{ message: { content: 'recovered' } }] }),
      { status: 200, headers: { 'content-type': 'application/json' } }
    );
  };

  const result = await router.generateWithFreePool({ messages, timeoutMs: 3000 });
  assert.equal(result.provider, 'kilo');
  assert.equal(result.text, 'recovered');
  assert.equal(calls, 2);
});

test('fails over silently from a 503 provider to the next provider', async () => {
  process.env.AI_PROVIDER_ORDER = 'openrouter,groq';
  process.env.OPENROUTER_API_KEY = 'test-openrouter';
  process.env.GROQ_API_KEY = 'test-groq';
  process.env.AI_PROVIDER_RETRIES = '1';
  globalThis.fetch = async (url) => {
    const host = new URL(String(url)).hostname;
    if (host === 'openrouter.ai') {
      return new Response(
        JSON.stringify({ error: { message: 'down' } }),
        { status: 503, headers: { 'content-type': 'application/json' } }
      );
    }
    return new Response(
      JSON.stringify({ choices: [{ message: { content: 'fallback-ok' } }] }),
      { status: 200, headers: { 'content-type': 'application/json' } }
    );
  };

  const result = await router.generateWithFreePool({ messages, timeoutMs: 3000 });
  assert.equal(result.provider, 'groq');
  assert.equal(result.text, 'fallback-ok');
});

test('reports internal diagnostics when every explicitly selected provider fails', async () => {
  process.env.AI_PROVIDER_ORDER = 'openrouter,groq';
  process.env.OPENROUTER_API_KEY = 'test-openrouter';
  process.env.GROQ_API_KEY = 'test-groq';
  process.env.AI_PROVIDER_RETRIES = '1';
  globalThis.fetch = async () =>
    new Response(
      JSON.stringify({ error: { message: 'down' } }),
      { status: 503, headers: { 'content-type': 'application/json' } }
    );

  await assert.rejects(
    router.generateWithFreePool({ messages, timeoutMs: 2000 }),
    (error) => {
      assert.equal(error.message, 'AI_PROVIDERS_UNAVAILABLE');
      assert.equal(error.diagnostics.length, 2);
      assert.equal(error.diagnostics[0].state, 'FAILED');
      assert.equal(error.diagnostics[1].state, 'FAILED');
      return true;
    }
  );
});

test('default order starts with the free Kilo gateway', async () => {
  delete process.env.AI_PROVIDER_ORDER;
  const requestedUrls = [];
  globalThis.fetch = async (url, options) => {
    const value = String(url);
    requestedUrls.push(value);
    if (value === 'https://api.kilo.ai/api/gateway/chat/completions') {
      const requestBody = JSON.parse(options.body);
      assert.equal(requestBody.model, 'kilo-auto/free');
      return new Response(
        JSON.stringify({ choices: [{ message: { content: 'free-ok' } }] }),
        { status: 200, headers: { 'content-type': 'application/json' } }
      );
    }
    return new Response(
      JSON.stringify({ error: { message: 'not selected' } }),
      { status: 503, headers: { 'content-type': 'application/json' } }
    );
  };

  const result = await router.generateWithFreePool({ messages, timeoutMs: 1000 });
  assert.equal(result.provider, 'kilo');
  assert.equal(result.text, 'free-ok');
  assert.ok(requestedUrls.includes('https://api.kilo.ai/api/gateway/chat/completions'));
});


test('prioritizes a provider that has recently succeeded', async () => {
  router.resetRouterState();
  process.env.AI_PROVIDER_ORDER = 'kilo';
  process.env.AI_PROVIDER_RETRIES = '1';

  globalThis.fetch = async () =>
    new Response(
      JSON.stringify({ choices: [{ message: { content: 'known-good' } }] }),
      { status: 200, headers: { 'content-type': 'application/json' } }
    );

  const warmup = await router.generateWithFreePool({ messages, timeoutMs: 1000 });
  assert.equal(warmup.provider, 'kilo');

  process.env.AI_PROVIDER_ORDER = 'horde,kilo';
  const requested = [];
  globalThis.fetch = async (url) => {
    requested.push(String(url));
    return new Response(
      JSON.stringify({ choices: [{ message: { content: 'adaptive-ok' } }] }),
      { status: 200, headers: { 'content-type': 'application/json' } }
    );
  };

  const result = await router.generateWithFreePool({ messages, timeoutMs: 1000 });
  assert.equal(result.provider, 'kilo');
  assert.equal(result.text, 'adaptive-ok');
  assert.match(requested[0], /api\.kilo\.ai/);

  router.resetRouterState();
});

test('uses a global connectivity budget', async () => {
  process.env.AI_PROVIDER_ORDER = 'kilo';
  process.env.AI_TOTAL_TIMEOUT_MS = '1500';
  let observedSignal;
  globalThis.fetch = async (_url, options) => {
    observedSignal = options.signal;
    return new Response(
      JSON.stringify({ choices: [{ message: { content: 'budget-ok' } }]}),
      { status: 200, headers: { 'content-type': 'application/json' } }
    );
  };

  const result = await router.generateWithFreePool({ messages, timeoutMs: 10000 });
  assert.equal(result.text, 'budget-ok');
  assert.equal(observedSignal.aborted, false);
});

test('aborts losing providers after the winner responds', async () => {
  process.env.AI_PROVIDER_ORDER = 'kilo,horde';
  process.env.AI_PROVIDER_RETRIES = '1';
  let loserAborted = false;

  globalThis.fetch = async (url, options) => {
    const host = new URL(String(url)).hostname;
    if (host === 'api.kilo.ai') {
      await new Promise((resolve) => setTimeout(resolve, 30));
      return new Response(
        JSON.stringify({ choices: [{ message: { content: 'winner' } }]}),
        { status: 200, headers: { 'content-type': 'application/json' } }
      );
    }
    if (host === 'aihorde.net') {
      return new Promise((resolve, reject) => {
        if (options.signal.aborted) {
          loserAborted = true;
          const error = new Error('aborted');
          error.name = 'AbortError';
          reject(error);
          return;
        }
        options.signal.addEventListener('abort', () => {
          loserAborted = true;
          const error = new Error('aborted');
          error.name = 'AbortError';
          reject(error);
        }, { once: true });
      });
    }
    throw new Error('unexpected host');
  };

  const result = await router.generateWithFreePool({ messages, timeoutMs: 2000 });
  assert.equal(result.provider, 'kilo');
  assert.equal(result.text, 'winner');
  assert.equal(loserAborted, true);
});

test('rejects unsafe configurable endpoints and uses the trusted Kilo endpoint', async () => {
  process.env.AI_PROVIDER_ORDER = 'kilo';
  process.env.KILO_ENDPOINTS = 'https://evil.example/v1/chat/completions,https://api.kilo.ai/api/gateway/chat/completions';
  let requestedUrl = '';

  globalThis.fetch = async (url) => {
    requestedUrl = String(url);
    return new Response(
      JSON.stringify({ choices: [{ message: { content: 'trusted' } }]}),
      { status: 200, headers: { 'content-type': 'application/json' } }
    );
  };

  const result = await router.generateWithFreePool({ messages, timeoutMs: 1000 });
  assert.equal(result.provider, 'kilo');
  assert.equal(result.text, 'trusted');
  assert.equal(requestedUrl, 'https://api.kilo.ai/api/gateway/chat/completions');
});


test('uses private Ollama before public fallback providers', async () => {
  process.env.AI_PROVIDER_ORDER = 'ollama,kilo';
  process.env.IAC33_OLLAMA_ENDPOINT = 'https://llm.example.test';
  process.env.IAC33_OLLAMA_API_KEY = 'test-ollama-key';
  process.env.IAC33_OLLAMA_MODEL = 'gpt-oss:20b';
  let requestedUrl = '';
  let requestHeaders = {};
  globalThis.fetch = async (url, options) => {
    requestedUrl = String(url);
    requestHeaders = options.headers;
    const body = JSON.parse(options.body);
    assert.equal(body.model, 'gpt-oss:20b');
    assert.equal(body.stream, false);
    return new Response(
      JSON.stringify({ choices: [{ message: { content: 'private-ok' } }], model: 'gpt-oss:20b' }),
      { status: 200, headers: { 'content-type': 'application/json' } }
    );
  };
  const result = await router.generateWithFreePool({ messages, timeoutMs: 3000 });
  assert.equal(result.provider, 'ollama');
  assert.equal(result.text, 'private-ok');
  assert.equal(requestedUrl, 'https://llm.example.test/v1/chat/completions');
  assert.equal(requestHeaders['x-iac33-llm-key'], 'test-ollama-key');
});


test('routes through the recovered Andrew2 Render backend', async () => {
  process.env.AI_PROVIDER_ORDER = 'andrew2';
  let requestedUrl = '';
  globalThis.fetch = async (url, options) => {
    requestedUrl = String(url);
    assert.equal(options.headers['x-iac33-bridge'], 'IAC33/Andrew2');
    return new Response(
      JSON.stringify({ ok: true, provider: 'openai', model: 'gpt-5.6-luna', text: 'andrew2-ok' }),
      { status: 200, headers: { 'content-type': 'application/json' } }
    );
  };
  const result = await router.generateWithFreePool({ messages, timeoutMs: 2000 });
  assert.equal(result.provider, 'andrew2');
  assert.equal(result.text, 'andrew2-ok');
  assert.equal(requestedUrl, 'https://andrew2-api.onrender.com/v1/ai/generate');
});

test('keeps the legacy provider pool available without making it mandatory', async () => {
  process.env.AI_PROVIDER_ORDER = 'anthropic,deepseek,xai';
  process.env.ANTHROPIC_API_KEY = 'test-anthropic';
  process.env.DEEPSEEK_API_KEY = 'test-deepseek';
  process.env.XAI_API_KEY = 'test-xai';
  let calls = 0;
  globalThis.fetch = async (url) => {
    calls += 1;
    const host = new URL(String(url)).hostname;
    if (host === 'api.anthropic.com') {
      return new Response(JSON.stringify({ content: [{ text: 'anthropic-ok' }], model: 'test' }), { status: 200 });
    }
    return new Response(JSON.stringify({ choices: [{ message: { content: 'unexpected' } }] }), { status: 200 });
  };
  const result = await router.generateWithFreePool({ messages, timeoutMs: 3000 });
  assert.equal(result.provider, 'anthropic');
  assert.equal(result.text, 'anthropic-ok');
  assert.ok(calls >= 1);
});
