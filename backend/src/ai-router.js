const providers = {
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
  const configuredOrder = (process.env.AI_PROVIDER_ORDER || 'openrouter,gemini,cloudflare,groq,freeinference,animica').split(',').map(x => x.trim().toLowerCase()).filter(Boolean);
  const order = [...new Set(configuredOrder)]; const diagnostics = [];
  const totalBudgetMs = Math.min(Math.max(Number(process.env.AI_TOTAL_TIMEOUT_MS || 9000), 1000), 120000);
  const deadline = Date.now() + totalBudgetMs;
  for (const id of order) {
    const provider = providers[id]; if (!provider || (provider.key && !process.env[provider.key])) { diagnostics.push({ provider: id, state: 'NOT_CONFIGURED' }); continue; }
    const remainingMs = deadline - Date.now();
    if (remainingMs <= 0) { diagnostics.push({ provider: id, state: 'BUDGET_EXHAUSTED' }); break; }
    const providerTimeoutMs = Math.min(Math.max(Number(process.env.AI_PROVIDER_TIMEOUT_MS || 2500), 1000), 10000);
    const attemptTimeoutMs = Math.min(timeoutMs, providerTimeoutMs, remainingMs);
    const started = Date.now();
    try { const result = await provider.call(messages, attemptTimeoutMs); diagnostics.push({ provider: id, state: 'RESPONDING', latencyMs: Date.now() - started }); return { ...result, provider: id, diagnostics }; }
    catch (error) { diagnostics.push({ provider: id, state: error?.name === 'AbortError' ? 'TIMEOUT' : error?.status === 429 ? 'RATE_LIMITED' : 'FAILED', status: error?.status || 500, latencyMs: Date.now() - started }); }
  }
  const error = new Error('AI_PROVIDERS_UNAVAILABLE'); error.diagnostics = diagnostics; throw error;
}