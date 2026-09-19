import test from 'node:test';
import assert from 'node:assert/strict';

test('diagnostico_conectividad ejecuta comprobaciones reales sin consumir un proveedor IA', async () => {
  const oldEnv = { ...process.env };
  const originalFetch = globalThis.fetch;
  try {
    process.env.AI_PROVIDER_ORDER = 'animica,groq';
    delete process.env.GROQ_API_KEY;
    globalThis.fetch = async (url) => {
      const value = String(url);
      if (value.includes('generate_204')) return new Response('', { status: 204 });
      if (value.includes('animica.dev/v1/models')) return new Response('{}', { status: 200 });
      throw new Error('unexpected health URL: ' + value);
    };
    const router = await import('../src/ai-router.js?connectivity-test=' + Date.now());
    router.resetRouterState();
    const result = await router.generateWithFreePool({
      messages: [{ role: 'user', content: 'diagnostico_conectividad' }],
      timeoutMs: 2000
    });
    assert.equal(result.provider, 'diagnostics');
    assert.equal(result.model, 'connectivity-diagnostics');
    assert.match(result.text, /INTERNET: OK/);
    assert.match(result.text, /ANIMICA: OK/);
    assert.match(result.text, /ANDROID→BACKEND: NO VERIFICADO/);
    assert.equal(result.diagnostics.some((item) => item.provider === 'groq'), true);
  } finally {
    process.env = { ...oldEnv };
    globalThis.fetch = originalFetch;
  }
});
