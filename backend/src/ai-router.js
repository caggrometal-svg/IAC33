const providers = {
  horde: { key: null, async call(messages, timeoutMs) { return callAiHorde(messages, timeoutMs); } },
  pollinations: { key: null, async call(messages, timeoutMs) { return callPollinations(messages, timeoutMs); } },
  kilo: { key: null, async call(messages, timeoutMs) { return callKilo(messages, timeoutMs); } },
  openrouter: { key: 'OPENROUTER_API_KEY', async call(messages, timeoutMs) { return callOpenAiCompatible('https://openrouter.ai/api/v1/chat/completions', process.env.OPENROUTER_API_KEY, process.env.OPENROUTER_MODEL || 'openrouter/free', messages, timeoutMs); } },
  groq: { key: 'GROQ_API_KEY', async call(messages, timeoutMs) { return callOpenAiCompatible('https://api.groq.com/openai/v1/chat/completions', process.env.GROQ_API_KEY, process.env.GROQ_MODEL || 'openai/gpt-oss-120b', messages, timeoutMs); } },
  gemini: { key: 'GEMINI_API_KEY', async call(messages, timeoutMs) { const model = process.env.GEMINI_MODEL || 'gemini-2.5-flash-lite'; return callGemini(messages, timeoutMs, model); } },
  cloudflare: { key: 'CLOUDFLARE_API_TOKEN', async call(messages, timeoutMs) { const account = process.env.CLOUDFLARE_ACCOUNT_ID; const model = process.env.CLOUDFLARE_MODEL || '@cf/zai-org/glm-4.7-flash'; if (!account) throw providerError(503, 'Cloudflare account not configured'); return callCloudflare(messages, timeoutMs, account, model); } },
  freeinference: { key: 'FREEINFERENCE_API_KEY', async call(messages, timeoutMs) { return callOpenAiCompatible('https://freeinference.org/v1/chat/completions', process.env.FREEINFERENCE_API_KEY, process.env.FREEINFERENCE_MODEL || 'glm-5.1', messages, timeoutMs); } },
  animica: { key: null, async call(messages, timeoutMs) { return callOpenAiCompatible('https://animica.dev/v1/chat/completions', null, process.env.ANIMICA_MODEL || 'animica-chat', messages, timeoutMs); } }
};
function providerError(status, message) { const error = new Error(message); error.status = status; return error; }

async function readJsonBounded(response) {
  const text = await response.text();
  if (Buffer.byteLength(text, 'utf8') > 2 * 1024 * 1024) throw providerError(502, 'Provider response too large');
  try { return text ? JSON.parse(text) : {}; } catch { throw providerError(502, 'Provider returned invalid JSON'); }
}
async function callGemini(messages, timeoutMs, model) {
  const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const contents = messages.filter(m => m.role !== 'system').map(m => ({ role: m.role === 'assistant' ? 'model' : 'user', parts: [{ text: m.content }] }));
    const system = messages.filter(m => m.role === 'system').map(m => m.content).join('\n');
    const payload = { contents }; if (system) payload.systemInstruction = { parts: [{ text: system }] };
    const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(process.env.GEMINI_API_KEY)}`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload), signal: controller.signal });
    const json = await readJsonBounded(response).catch(() => ({})); if (!response.ok) throw providerError(response.status, json?.error?.message || 'Gemini request failed');
    const text = json?.candidates?.[0]?.content?.parts?.map(p => p.text || '').join('') || ''; if (!text) throw providerError(502, 'Gemini returned empty response'); return { text, model };
  } finally { clearTimeout(timer); }
}
async function callCloudflare(messages, timeoutMs, account, model) {
  const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(account)}/ai/run/${encodeURIComponent(model)}`, { method: 'POST', headers: { authorization: `Bearer ${process.env.CLOUDFLARE_API_TOKEN}`, 'content-type': 'application/json' }, body: JSON.stringify({ messages }), signal: controller.signal });
    const json = await readJsonBounded(response).catch(() => ({})); if (!response.ok) throw providerError(response.status, json?.errors?.[0]?.message || 'Cloudflare request failed');
    const text = json?.result?.response || json?.result?.text || ''; if (!text) throw providerError(502, 'Cloudflare returned empty response'); return { text, model };
  } finally { clearTimeout(timer); }
}
async function callKilo(messages, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const model = process.env.KILO_MODEL || 'kilo-auto/free';
  try {
    const response = await fetch('https://api.kilo.ai/api/gateway/chat/completions', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ model, messages, stream: false, max_tokens: Math.min(Number(process.env.KILO_MAX_TOKENS || 512), 1024) }),
      signal: controller.signal
    });
    const json = await readJsonBounded(response).catch(() => ({}));
    if (!response.ok) throw providerError(response.status || 502, json?.error?.message || 'Kilo request failed');
    const text = json?.choices?.[0]?.message?.content || '';
    if (!text) throw providerError(502, 'Kilo returned empty response');
    return { text, model: json?.model || model };
  } finally { clearTimeout(timer); }
}
async function callPollinations(messages, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const model = process.env.POLLINATIONS_MODEL || 'openai';
  try {
    const response = await fetch('https://text.pollinations.ai/openai', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ model, messages }),
      signal: controller.signal
    });
    const json = await readJsonBounded(response).catch(() => ({}));
    if (!response.ok) throw providerError(response.status || 502, json?.error?.message || 'Pollinations request failed');
    const text = json?.choices?.[0]?.message?.content || '';
    if (!text) throw providerError(502, 'Pollinations returned empty response');
    return { text, model };
  } finally { clearTimeout(timer); }
}
async function callAiHorde(messages, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const apiKey = process.env.AI_HORDE_API_KEY || '0000000000';
  const model = process.env.AI_HORDE_MODEL || '';
  const prompt = messages.map((message) => String(message.role || 'user').toUpperCase() + ': ' + String(message.content || '')).join('\n') + '\nASSISTANT:';
  try {
    const payload = {
      prompt,
      params: {
        max_length: Math.min(Number(process.env.AI_HORDE_MAX_LENGTH || 256), 512),
        max_context_length: Math.min(Number(process.env.AI_HORDE_MAX_CONTEXT || 4096), 8192),
        temperature: Number(process.env.AI_HORDE_TEMPERATURE || 0.4)
      }
    };
    if (model) payload.models = [model];
    const response = await fetch('https://aihorde.net/api/v2/generate/text/async', {
      method: 'POST',
      headers: { apikey: apiKey, 'Client-Agent': 'IAC33:2.0', 'content-type': 'application/json' },
      body: JSON.stringify(payload),
      signal: controller.signal
    });
    const json = await readJsonBounded(response).catch(() => ({}));
    if (!response.ok || !json.id) throw providerError(response.status || 502, json?.message || 'AI Horde submit failed');
    const deadline = Date.now() + timeoutMs - 250;
    while (Date.now() < deadline) {
      const statusResponse = await fetch(`https://aihorde.net/api/v2/generate/text/status/${encodeURIComponent(json.id)}`, { headers: { 'Client-Agent': 'IAC33:2.0' }, signal: controller.signal });
      const status = await readJsonBounded(statusResponse).catch(() => ({}));
      if (!statusResponse.ok) throw providerError(statusResponse.status || 502, status?.message || 'AI Horde status failed');
      if (status.done) {
        const text = status.generations?.[0]?.text || '';
        if (!text) throw providerError(502, 'AI Horde returned empty response');
        return { text, model: status.generations?.[0]?.model || model || 'ai-horde' };
      }
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
    try { await fetch(`https://aihorde.net/api/v2/generate/text/status/${encodeURIComponent(json.id)}`, { method: 'DELETE', headers: { 'Client-Agent': 'IAC33:2.0' } }); } catch {}
    throw providerError(504, 'AI Horde generation timeout');
  } finally { clearTimeout(timer); }
}
async function callOpenAiCompatible(url, key, model, messages, timeoutMs) {
  const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const headers = { 'content-type': 'application/json' }; if (key) headers.authorization = `Bearer ${key}`;
    const response = await fetch(url, { method: 'POST', headers, body: JSON.stringify({ model, messages }), signal: controller.signal });
    const json = await readJsonBounded(response).catch(() => ({})); if (!response.ok) throw providerError(response.status, json?.error?.message || 'Provider request failed');
    const text = json?.choices?.[0]?.message?.content || ''; if (!text) throw providerError(502, 'Provider returned empty response'); return { text, model };
  } finally { clearTimeout(timer); }
}
export async function generateWithFreePool({ messages, timeoutMs = 18000 }) {
  const configuredOrder = (process.env.AI_PROVIDER_ORDER || 'kilo,pollinations,horde,openrouter,gemini,cloudflare,groq,freeinference,animica').split(',').map(x => x.trim().toLowerCase()).filter(Boolean);
  const order = [...new Set(configuredOrder)]; const diagnostics = [];
  const totalBudgetMs = Math.min(Math.max(Number(process.env.AI_TOTAL_TIMEOUT_MS || 7000), 1000), 120000);
  const deadline = Date.now() + totalBudgetMs;
  const available = order.filter((id) => {
    const provider = providers[id];
    if (!provider || (provider.key && !process.env[provider.key])) {
      diagnostics.push({ provider: id, state: 'NOT_CONFIGURED' });
      return false;
    }
    return true;
  });
  if (!available.length) {
    const error = new Error('AI_PROVIDERS_UNAVAILABLE'); error.diagnostics = diagnostics; throw error;
  }

  // Fast path: race the first free/community providers instead of waiting
  // sequentially. The first valid answer wins, which makes conversation
  // latency much closer to a normal chat service when one provider is slow.
  const wave = available.slice(0, Math.min(3, available.length));
  const attempts = wave.map(async (id) => {
    const provider = providers[id];
    const remainingMs = deadline - Date.now();
    const providerTimeoutMs =
      id === 'kilo' ? Math.min(Math.max(Number(process.env.AI_KILO_TIMEOUT_MS || 2500), 1200), 6000) :
      id === 'horde' ? Math.min(Math.max(Number(process.env.AI_HORDE_TIMEOUT_MS || 4500), 2500), 8000) :
      id === 'pollinations' ? Math.min(Math.max(Number(process.env.AI_POLLINATIONS_TIMEOUT_MS || 2500), 1200), 6000) :
      Math.min(Math.max(Number(process.env.AI_PROVIDER_TIMEOUT_MS || 2200), 900), 7000);
    const attemptTimeoutMs = Math.min(timeoutMs, providerTimeoutMs, Math.max(1000, remainingMs));
    const started = Date.now();
    try {
      const result = await provider.call(messages, attemptTimeoutMs);
      return { ok: true, id, result, latencyMs: Date.now() - started };
    } catch (error) {
      return { ok: false, id, error, latencyMs: Date.now() - started };
    }
  });

  const pending = attempts.map((promise) => ({ promise }));
  while (pending.length) {
    const winner = await Promise.race(pending.map((entry) => entry.promise));
    const index = pending.findIndex((entry) => entry.promise === attempts.find((promise) => promise === entry.promise && false));
    const entryIndex = pending.findIndex((entry) => entry.promise === pending.find((candidate) => candidate.promise === entry.promise)?.promise);
    const settled = winner;
    const removeIndex = pending.findIndex((entry) => entry.promise === attempts.find((promise) => promise === entry.promise));
    pending.splice(removeIndex >= 0 ? removeIndex : 0, 1);
    if (settled.ok) {
      diagnostics.push({ provider: settled.id, state: 'RESPONDING', latencyMs: settled.latencyMs });
      return { ...settled.result, provider: settled.id, diagnostics };
    }
    diagnostics.push({
      provider: settled.id,
      state: settled.error?.name === 'AbortError' ? 'TIMEOUT' : settled.error?.status === 429 ? 'RATE_LIMITED' : 'FAILED',
      status: settled.error?.status || 500,
      latencyMs: settled.latencyMs
    });
  }

  // Second wave: remaining providers are true sequential fallbacks, preserving
  // quota and avoiding a thundering herd against every configured service.
  for (const id of available.slice(3)) {
    const remainingMs = deadline - Date.now();
    if (remainingMs <= 0) break;
    const provider = providers[id];
    const providerTimeoutMs = Math.min(Math.max(Number(process.env.AI_PROVIDER_TIMEOUT_MS || 2200), 900), 7000);
    const attemptTimeoutMs = Math.min(timeoutMs, providerTimeoutMs, remainingMs);
    const started = Date.now();
    try {
      const result = await provider.call(messages, attemptTimeoutMs);
      diagnostics.push({ provider: id, state: 'RESPONDING', latencyMs: Date.now() - started });
      return { ...result, provider: id, diagnostics };
    } catch (error) {
      diagnostics.push({ provider: id, state: error?.name === 'AbortError' ? 'TIMEOUT' : error?.status === 429 ? 'RATE_LIMITED' : 'FAILED', status: error?.status || 500, latencyMs: Date.now() - started });
    }
  }
  const error = new Error('AI_PROVIDERS_UNAVAILABLE'); error.diagnostics = diagnostics; throw error;
}