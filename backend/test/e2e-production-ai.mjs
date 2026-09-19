const BASE_URL = (process.env.PRODUCTION_BASE_URL || 'https://iac33-backend.onrender.com').replace(/\/$/, '');

async function fetchJson(path, options = {}, timeoutMs = 50000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(BASE_URL + path, {
      ...options,
      signal: controller.signal,
      headers: {
        accept: 'application/json',
        ...(options.body ? { 'content-type': 'application/json' } : {}),
        ...(options.headers || {})
      }
    });
    const text = await response.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch {}
    return { response, text, json };
  } finally {
    clearTimeout(timer);
  }
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const health = await fetchJson('/health', {}, 15000);
assert(health.response.ok, 'Production /health failed: HTTP ' + health.response.status);

const ready = await fetchJson('/ready', {}, 15000);
assert(ready.response.ok, 'Production /ready failed: HTTP ' + ready.response.status);

const diagnostics = await fetchJson('/v1/ai/diagnostics', {}, 20000);
assert(diagnostics.response.ok, 'Production AI diagnostics failed: HTTP ' + diagnostics.response.status);

const providerSummary = Array.isArray(diagnostics.json?.providers)
  ? diagnostics.json.providers.map((p) => p.provider + '=' + p.state).join(', ')
  : 'unavailable';

const generate = await fetchJson('/v1/ai/generate', {
  method: 'POST',
  body: JSON.stringify({
    messages: [
      { role: 'user', content: 'Responde exactamente: IAC33_PROD_SMOKE_OK' }
    ]
  })
}, 50000);

assert(generate.response.ok, 'Production /v1/ai/generate failed: HTTP ' + generate.response.status + ' body=' + generate.text.slice(0, 500));
assert(generate.json?.ok === true, 'Production AI returned ok != true');
assert(typeof generate.json?.text === 'string' && generate.json.text.trim().length > 0, 'Production AI returned empty text');
assert(generate.json.text.includes('IAC33_PROD_SMOKE_OK'), 'Production AI response did not contain the smoke marker');
const freeProviders = new Set(['kilo', 'horde', 'pollinations', 'animica', 'ollama']);
assert(freeProviders.has(String(generate.json?.provider || '').toLowerCase()),
  'Production AI violated zero-cost routing: provider=' + String(generate.json?.provider || 'unknown'));

console.log(JSON.stringify({
  ok: true,
  health: health.json,
  ready: ready.json,
  diagnosticsOk: diagnostics.json?.ok ?? null,
  router: diagnostics.json?.router ?? null,
  providers: providerSummary,
  providerUsed: generate.json?.provider ?? null,
  model: generate.json?.model ?? null,
  textPreview: generate.json.text.trim().slice(0, 120)
}, null, 2));
