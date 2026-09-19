import { STOP_SEQUENCES, sanitizeAssistantText } from './ai-output.js';
import { providerHealth } from './ai/provider-health.ts';
import { generateHedged } from './ai/router.ts';

const providers = {
  openai: { key: 'OPENAI_API_KEY', async call(messages, timeoutMs, _attempt, signal) { return callOpenAiCompatible('https://api.openai.com/v1/chat/completions', process.env.OPENAI_API_KEY, process.env.OPENAI_MODEL || 'gpt-4o-mini', messages, timeoutMs, signal); } },
  ollama: { key: 'IAC33_OLLAMA_API_KEY', async call(messages, timeoutMs, _attempt, signal) { return callOllama(messages, timeoutMs, signal); } },
  anthropic: { key: 'ANTHROPIC_API_KEY', async call(messages, timeoutMs, _attempt, signal) { return callAnthropic(messages, timeoutMs, signal); } },
  deepseek: { key: 'DEEPSEEK_API_KEY', async call(messages, timeoutMs, _attempt, signal) { return callOpenAiCompatible('https://api.deepseek.com/chat/completions', process.env.DEEPSEEK_API_KEY, process.env.DEEPSEEK_MODEL || 'deepseek-chat', messages, timeoutMs, signal); } },
  xai: { key: 'XAI_API_KEY', async call(messages, timeoutMs, _attempt, signal) { return callOpenAiCompatible('https://api.x.ai/v1/chat/completions', process.env.XAI_API_KEY, process.env.XAI_MODEL || 'grok-3-mini', messages, timeoutMs, signal); } },
  horde: { key: null, async call(messages, timeoutMs, attempt, signal) { return callAiHorde(messages, timeoutMs, attempt, signal); } },
  pollinations: { key: null, async call(messages, timeoutMs, attempt, signal) { return callPollinations(messages, timeoutMs, attempt, signal); } },
  kilo: { key: null, async call(messages, timeoutMs, attempt, signal) { return callKilo(messages, timeoutMs, attempt, signal); } },
  openrouter: { key: 'OPENROUTER_API_KEY', async call(messages, timeoutMs, _attempt, signal) { return callOpenAiCompatible('https://openrouter.ai/api/v1/chat/completions', process.env.OPENROUTER_API_KEY, process.env.OPENROUTER_MODEL || 'openrouter/free', messages, timeoutMs, signal); } },
  groq: { key: 'GROQ_API_KEY', async call(messages, timeoutMs, _attempt, signal) { return callOpenAiCompatible('https://api.groq.com/openai/v1/chat/completions', process.env.GROQ_API_KEY, process.env.GROQ_MODEL || 'openai/gpt-oss-120b', messages, timeoutMs, signal); } },
  gemini: { key: 'GEMINI_API_KEY', async call(messages, timeoutMs, _attempt, signal) { const model = process.env.GEMINI_MODEL || 'gemini-2.5-flash-lite'; return callGemini(messages, timeoutMs, model, signal); } },
  cloudflare: { key: 'CLOUDFLARE_API_TOKEN', async call(messages, timeoutMs, _attempt, signal) { const account = process.env.CLOUDFLARE_ACCOUNT_ID; const model = process.env.CLOUDFLARE_MODEL || '@cf/zai-org/glm-4.7-flash'; if (!account) throw providerError(503, 'Cloudflare account not configured'); return callCloudflare(messages, timeoutMs, account, model, signal); } },
  freeinference: { key: 'FREEINFERENCE_API_KEY', async call(messages, timeoutMs, _attempt, signal) { return callOpenAiCompatible('https://freeinference.org/v1/chat/completions', process.env.FREEINFERENCE_API_KEY, process.env.FREEINFERENCE_MODEL || 'glm-5.1', messages, timeoutMs, signal); } },
  animica: { key: null, async call(messages, timeoutMs, _attempt, signal) { return callOpenAiCompatible('https://animica.dev/v1/chat/completions', null, process.env.ANIMICA_MODEL || 'animica-chat', messages, timeoutMs, signal); } }
};

function providerError(status, message, retryAfterMs = 0) {
  const error = new Error(message);
  error.status = status;
  if (retryAfterMs > 0) error.retryAfterMs = retryAfterMs;
  return error;
}

const FREE_PROVIDER_DEFAULTS = ['kilo', 'horde', 'pollinations', 'animica', 'ollama'];
const parseList = (value, fallback) => String(value || fallback).split(',').map((item) => item.trim()).filter(Boolean);
const NONZERO_COST_PROVIDERS = new Set(['openai','anthropic','deepseek','xai','gemini','groq','openrouter','cloudflare','freeinference']);
const ZERO_COST_MODE = String(process.env.AI_ZERO_COST_MODE || 'false').toLowerCase() !== 'false';
const effectiveProviderOrder = (value) => parseList(value, FREE_PROVIDER_DEFAULTS.join(','))
  .filter((id) => !ZERO_COST_MODE || !NONZERO_COST_PROVIDERS.has(id));
const MAX_PROVIDER_RESPONSE_BYTES = 2 * 1024 * 1024;

function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      const error = new Error('ABORTED');
      error.name = 'AbortError';
      reject(error);
      return;
    }
    const timer = setTimeout(resolve, ms);
    const onAbort = () => {
      clearTimeout(timer);
      const error = new Error('ABORTED');
      error.name = 'AbortError';
      reject(error);
    };
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

function providerTimeoutMs(id) {
  if (id === 'ollama') return Math.min(Math.max(Number(process.env.IAC33_OLLAMA_TIMEOUT_MS || 12000), 2000), 20000);
  if (id === 'kilo') return Math.min(Math.max(Number(process.env.AI_KILO_TIMEOUT_MS || 4500), 1500), 8000);
  if (id === 'horde') return Math.min(Math.max(Number(process.env.AI_HORDE_TIMEOUT_MS || 7000), 2500), 10000);
  if (id === 'pollinations') return Math.min(Math.max(Number(process.env.AI_POLLINATIONS_TIMEOUT_MS || 4500), 1500), 8000);
  return Math.min(Math.max(Number(process.env.AI_PROVIDER_TIMEOUT_MS || 4500), 1200), 10000);
}

function readRetryAfter(response) {
  const value = response?.headers?.get('retry-after');
  if (!value) return 0;
  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.min(seconds * 1000, 1500);
  const date = Date.parse(value);
  if (!Number.isFinite(date)) return 0;
  return Math.min(Math.max(0, date - Date.now()), 1500);
}

function safeEndpointList(value, fallback, allowedHosts) {
  const candidates = parseList(value, fallback);
  const valid = candidates.map((raw) => {
    try {
      const parsed = new URL(raw);
      if (parsed.protocol !== 'https:') return null;
      if (!allowedHosts.has(parsed.hostname.toLowerCase())) return null;
      return parsed.toString().replace(/\/$/, '');
    } catch {
      return null;
    }
  }).filter(Boolean);
  return valid.length ? valid : [fallback];
}

function timeoutSignal(parentSignal, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let onAbort = null;
  if (parentSignal) {
    onAbort = () => controller.abort(parentSignal.reason);
    if (parentSignal.aborted) controller.abort(parentSignal.reason);
    else parentSignal.addEventListener('abort', onAbort, { once: true });
  }
  return {
    signal: controller.signal,
    cleanup() {
      clearTimeout(timer);
      if (parentSignal && onAbort) parentSignal.removeEventListener('abort', onAbort);
    }
  };
}

async function readJsonBounded(response) {
  const declaredLength = Number(response?.headers?.get('content-length'));
  if (Number.isFinite(declaredLength) && declaredLength > MAX_PROVIDER_RESPONSE_BYTES) {
    throw providerError(502, 'Provider response too large');
  }
  const text = await response.text();
  if (Buffer.byteLength(text, 'utf8') > MAX_PROVIDER_RESPONSE_BYTES) {
    throw providerError(502, 'Provider response too large');
  }
  try {
    return text ? JSON.parse(text) : {};
  } catch {
    // Preserve the HTTP status so 429/5xx responses remain eligible for failover.
    return {};
  }
}

function responseError(response, json, fallback) {
  return providerError(
    response.status || 502,
    json?.error?.message || json?.message || json?.errors?.[0]?.message || fallback,
    readRetryAfter(response)
  );
}

async function callGemini(messages, timeoutMs, model, parentSignal) {
  const { signal, cleanup } = timeoutSignal(parentSignal, timeoutMs);
  try {
    const contents = messages.filter((m) => m.role !== 'system').map((m) => ({
      role: m.role === 'assistant' ? 'model' : 'user',
      parts: [{ text: m.content }]
    }));
    const system = messages.filter((m) => m.role === 'system').map((m) => m.content).join('\\n');
    const payload = {
      contents,
      generationConfig: { stopSequences: STOP_SEQUENCES }
    };
    if (system) payload.systemInstruction = { parts: [{ text: system }] };
    const response = await fetch(
      'https://generativelanguage.googleapis.com/v1beta/models/' +
        encodeURIComponent(model) +
        ':generateContent?key=' +
        encodeURIComponent(process.env.GEMINI_API_KEY),
      {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(payload),
        signal
      }
    );
    const json = await readJsonBounded(response);
    if (!response.ok) throw responseError(response, json, 'Gemini request failed');
    const text = json?.candidates?.[0]?.content?.parts?.map((part) => part.text || '').join('') || '';
    if (!text) throw providerError(502, 'Gemini returned empty response');
    return { text, model };
  } finally {
    cleanup();
  }
}

async function callCloudflare(messages, timeoutMs, account, model, parentSignal) {
  const { signal, cleanup } = timeoutSignal(parentSignal, timeoutMs);
  try {
    const response = await fetch(
      'https://api.cloudflare.com/client/v4/accounts/' +
        encodeURIComponent(account) +
        '/ai/v1/chat/completions',
      {
        method: 'POST',
        headers: {
          authorization: 'Bearer ' + process.env.CLOUDFLARE_API_TOKEN,
          'content-type': 'application/json'
        },
        body: JSON.stringify({
          model,
          messages,
          stream: false,
          stop: STOP_SEQUENCES
        }),
        signal
      }
    );
    const json = await readJsonBounded(response);
    if (!response.ok) throw responseError(response, json, 'Cloudflare request failed');
    const text = json?.choices?.[0]?.message?.content || json?.result?.response || json?.result?.text || '';
    if (!text) throw providerError(502, 'Cloudflare returned empty response');
    return { text, model };
  } finally {
    cleanup();
  }
}

async function callAnthropic(messages, timeoutMs, parentSignal) {
  const { signal, cleanup } = timeoutSignal(parentSignal, timeoutMs);
  try {
    const system = messages.filter((m) => m.role === 'system').map((m) => m.content).join('\\n');
    const bodyMessages = messages.filter((m) => m.role !== 'system');
    const response = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-api-key': process.env.ANTHROPIC_API_KEY,
        'anthropic-version': '2023-06-01'
      },
      body: JSON.stringify({
        model: process.env.ANTHROPIC_MODEL || 'claude-3-5-haiku-latest',
        max_tokens: Math.min(Number(process.env.ANTHROPIC_MAX_TOKENS || 512), 1024),
        ...(system ? { system } : {}),
        messages: bodyMessages
      }),
      signal
    });
    const json = await readJsonBounded(response);
    if (!response.ok) throw responseError(response, json, 'Anthropic request failed');
    const text = sanitizeAssistantText(json?.content?.map((part) => part.text || '').join('') || '');
    if (!text) throw providerError(502, 'Anthropic returned empty response');
    return { text, model: json?.model || process.env.ANTHROPIC_MODEL || 'anthropic' };
  } finally {
    cleanup();
  }
}

async function callOllama(messages, timeoutMs, parentSignal) {
  const { signal, cleanup } = timeoutSignal(parentSignal, timeoutMs);
  const endpoint = String(process.env.IAC33_OLLAMA_ENDPOINT || '').trim().replace(/\/$/, '');
  const model = process.env.IAC33_OLLAMA_MODEL || 'gpt-oss:20b';
  const apiKey = process.env.IAC33_OLLAMA_API_KEY || '';
  if (!endpoint || !apiKey) throw providerError(503, 'Ollama server not configured');
  let url;
  try {
    url = new URL(endpoint.endsWith('/v1/chat/completions') ? endpoint : endpoint + '/v1/chat/completions');
    if (url.protocol !== 'https:') throw new Error('OLLAMA_ENDPOINT_MUST_USE_HTTPS');
  } catch {
    throw providerError(503, 'Ollama endpoint invalid');
  }
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-iac33-llm-key': apiKey },
      body: JSON.stringify({ model, messages, stream: false }),
      signal
    });
    const json = await readJsonBounded(response);
    if (!response.ok) throw responseError(response, json, 'Ollama request failed');
    const text = sanitizeAssistantText(json?.choices?.[0]?.message?.content || '');
    if (!text) throw providerError(502, 'Ollama returned empty response');
    return { text, model: json?.model || model };
  } finally {
    cleanup();
  }
}

async function callKilo(messages, timeoutMs, attempt = 1, parentSignal) {
  const { signal, cleanup } = timeoutSignal(parentSignal, timeoutMs);
  const model = process.env.KILO_MODEL || 'kilo-auto/free';
  try {
    const endpoints = safeEndpointList(
      process.env.KILO_ENDPOINTS,
      'https://api.kilo.ai/api/gateway/chat/completions',
      new Set(['api.kilo.ai'])
    );
    const endpoint = endpoints[(Math.max(attempt, 1) - 1) % endpoints.length];
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        model,
        messages,
        stream: false,
        stop: STOP_SEQUENCES,
        max_tokens: Math.min(Number(process.env.KILO_MAX_TOKENS || 1200), 2048)
      }),
      signal
    });
    const json = await readJsonBounded(response);
    if (!response.ok) throw responseError(response, json, 'Kilo request failed');
    const text = json?.choices?.[0]?.message?.content || '';
    if (!text) throw providerError(502, 'Kilo returned empty response');
    return { text, model: json?.model || model };
  } finally {
    cleanup();
  }
}

async function callPollinations(messages, timeoutMs, attempt = 1, parentSignal) {
  const { signal, cleanup } = timeoutSignal(parentSignal, timeoutMs);
  const model = process.env.POLLINATIONS_MODEL || 'openai';
  try {
    const endpoints = safeEndpointList(
      process.env.POLLINATIONS_ENDPOINTS,
      'https://text.pollinations.ai/openai',
      new Set(['text.pollinations.ai', 'gen.pollinations.ai'])
    );
    const endpoint = endpoints[(Math.max(attempt, 1) - 1) % endpoints.length];
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ model, messages, stop: STOP_SEQUENCES }),
      signal
    });
    const json = await readJsonBounded(response);
    if (!response.ok) throw responseError(response, json, 'Pollinations request failed');
    const text = json?.choices?.[0]?.message?.content || '';
    if (!text) throw providerError(502, 'Pollinations returned empty response');
    return { text, model };
  } finally {
    cleanup();
  }
}

async function callAiHorde(messages, timeoutMs, attempt = 1, parentSignal) {
  const { signal, cleanup } = timeoutSignal(parentSignal, timeoutMs);
  const apiKey = process.env.AI_HORDE_API_KEY || '0000000000';
  const model = process.env.AI_HORDE_MODEL || '';
  const prompt = messages
    .map((message) => String(message.role || 'user').toUpperCase() + ': ' + String(message.content || ''))
    .join('\\n') + '\\nASSISTANT:';
  let requestId = '';
  let taskFinished = false;
  try {
    const bases = safeEndpointList(
      process.env.AI_HORDE_ENDPOINTS,
      'https://aihorde.net',
      new Set(['aihorde.net'])
    );
    const base = bases[(Math.max(attempt, 1) - 1) % bases.length];
    const response = await fetch(base + '/api/v2/generate/text/async', {
      method: 'POST',
      headers: {
        apikey: apiKey,
        'Client-Agent': 'IAC33:2.0',
        'content-type': 'application/json'
      },
      body: JSON.stringify({
        prompt,
        params: {
          max_length: Math.min(Number(process.env.AI_HORDE_MAX_LENGTH || 768), 1024),
          max_context_length: Math.min(Number(process.env.AI_HORDE_MAX_CONTEXT || 4096), 8192),
          temperature: Number(process.env.AI_HORDE_TEMPERATURE || 0.4),
          stop_sequence: STOP_SEQUENCES
        },
        ...(model ? { models: [model] } : {})
      }),
      signal
    });
    const json = await readJsonBounded(response);
    if (!response.ok || !json.id) throw responseError(response, json, 'AI Horde submit failed');
    requestId = String(json.id);

    const deadline = Date.now() + Math.max(250, timeoutMs - 250);
    while (Date.now() < deadline) {
      const statusResponse = await fetch(
        base + '/api/v2/generate/text/status/' + encodeURIComponent(requestId),
        {
          headers: { 'Client-Agent': 'IAC33:2.0' },
          signal
        }
      );
      const status = await readJsonBounded(statusResponse);
      if (!statusResponse.ok) throw responseError(statusResponse, status, 'AI Horde status failed');
      if (status.done) {
        const text = status.generations?.[0]?.text || '';
        if (!text) throw providerError(502, 'AI Horde returned empty response');
        taskFinished = true;
        return {
          text,
          model: status.generations?.[0]?.model || model || 'ai-horde'
        };
      }
      await sleep(700, signal);
    }
    throw providerError(504, 'AI Horde generation timeout');
  } catch (error) {
    if (requestId && !taskFinished) {
      try {
        const bases = safeEndpointList(
          process.env.AI_HORDE_ENDPOINTS,
          'https://aihorde.net',
          new Set(['aihorde.net'])
        );
        const base = bases[(Math.max(attempt, 1) - 1) % bases.length];
        await fetch(
          base + '/api/v2/generate/text/status/' + encodeURIComponent(requestId),
          {
            method: 'DELETE',
            headers: { 'Client-Agent': 'IAC33:2.0' }
          }
        );
      } catch {}
    }
    throw error;
  } finally {
    cleanup();
  }
}

async function callOpenAiCompatible(url, key, model, messages, timeoutMs, parentSignal) {
  const { signal, cleanup } = timeoutSignal(parentSignal, timeoutMs);
  try {
    const headers = { 'content-type': 'application/json' };
    if (key) headers.authorization = 'Bearer ' + key;
    const response = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify({ model, messages, stop: STOP_SEQUENCES }),
      signal
    });
    const json = await readJsonBounded(response);
    if (!response.ok) throw responseError(response, json, 'Provider request failed');
    const text = sanitizeAssistantText(json?.choices?.[0]?.message?.content || '');
    if (!text) throw providerError(502, 'Provider returned empty response');
    return { text, model };
  } finally {
    cleanup();
  }
}


async function callKiloStream(messages, timeoutMs, parentSignal, onDelta) {
  const { signal, cleanup } = timeoutSignal(parentSignal, timeoutMs);
  const model = process.env.KILO_MODEL || 'kilo-auto/free';
  try {
    const response = await fetch('https://api.kilo.ai/api/gateway/chat/completions', {
      method: 'POST',
      headers: { 'content-type': 'application/json', accept: 'text/event-stream' },
      body: JSON.stringify({
        model,
        messages,
        stream: true,
        stop: STOP_SEQUENCES,
        max_tokens: Math.min(Number(process.env.KILO_MAX_TOKENS || 1200), 2048)
      }),
      signal
    });
    if (!response.ok) {
      const json = await readJsonBounded(response);
      throw responseError(response, json, 'Kilo streaming request failed');
    }
    if (!response.body?.getReader) throw providerError(502, 'Kilo streaming body unavailable');
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let text = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop() || '';
      for (const line of lines) {
        if (!line.startsWith('data:')) continue;
        const data = line.slice(5).trim();
        if (!data) continue;
        if (data === '[DONE]') return { text: sanitizeAssistantText(text), model };
        let json;
        try { json = JSON.parse(data); } catch { continue; }
        const delta = json?.choices?.[0]?.delta?.content || '';
        if (delta) {
          text += delta;
          await onDelta(delta);
        }
      }
    }
    const clean = sanitizeAssistantText(text);
    if (!clean) throw providerError(502, 'Kilo streaming returned empty response');
    return { text: clean, model };
  } finally {
    cleanup();
  }
}

export async function streamWithFreePool({ messages, timeoutMs = 30000, signal, onDelta }) {
  const order = [...new Set(effectiveProviderOrder(process.env.AI_PROVIDER_ORDER))];
  if (!order.length) throw new ProviderPoolUnavailableError({}, providerHealth.snapshot([]));

  if (order.includes('kilo') && !providerHealth.isCoolingDown('kilo')) {
    try {
      const result = await callKiloStream(messages, Math.min(providerTimeoutMs('kilo'), timeoutMs), signal, onDelta);
      providerHealth.recordSuccess('kilo', { status: 200 });
      return { ...result, provider: 'kilo' };
    } catch (error) {
      if (signal?.aborted) throw error;
      providerHealth.recordFailure('kilo', error);
    }
  }

  const result = await generateWithFreePool({ messages, timeoutMs, signal });
  await onDelta(result.text);
  return result;
}

export async function generateWithFreePool({ messages, timeoutMs = 30000, signal }) {
  const order = [...new Set(effectiveProviderOrder(process.env.AI_PROVIDER_ORDER))];

  return generateHedged({
    messages,
    timeoutMs,
    signal,
    order,
    providers,
    health: providerHealth,
    providerTimeoutMs,
    maxParallel: process.env.AI_MAX_PARALLEL_PROVIDERS || 2
  });
}


const PROVIDER_HEALTH_URLS = {
  openai: 'https://api.openai.com/v1/models',
  anthropic: 'https://api.anthropic.com/v1/models',
  deepseek: 'https://api.deepseek.com/models',
  xai: 'https://api.x.ai/v1/models',
  openrouter: 'https://openrouter.ai/api/v1/models',
  groq: 'https://api.groq.com/openai/v1/models',
  gemini: 'https://generativelanguage.googleapis.com/v1beta/models',
  cloudflare: null,
  kilo: 'https://api.kilo.ai/api/gateway/models',
  horde: 'https://aihorde.net/api/v2/status/heartbeat',
  pollinations: 'https://text.pollinations.ai/',
  freeinference: 'https://freeinference.org/v1/models',
  animica: 'https://animica.dev/v1/models'
};

const connectivityHealthCache = new Map();
const CONNECTIVITY_CACHE_MS = Math.min(Math.max(Number(process.env.AI_HEALTHCHECK_CACHE_MS || 30000), 1000), 300000);
const CONNECTIVITY_TIMEOUT_MS = Math.min(Math.max(Number(process.env.AI_HEALTHCHECK_TIMEOUT_MS || 1800), 500), 5000);

function liveHealthConfigured(id) {
  const provider = providers[id];
  if (!provider) return false;
  if (id === 'ollama') return Boolean(process.env.IAC33_OLLAMA_ENDPOINT && process.env.IAC33_OLLAMA_API_KEY);
  if (id === 'cloudflare') return Boolean(process.env.CLOUDFLARE_API_TOKEN && process.env.CLOUDFLARE_ACCOUNT_ID);
  return !provider.key || Boolean(process.env[provider.key]);
}

function liveHealthUrl(id) {
  if (id === 'ollama') {
    const endpoint = String(process.env.IAC33_OLLAMA_ENDPOINT || '').trim().replace(/\/$/, '');
    if (!endpoint) return null;
    try {
      const parsed = new URL(endpoint);
      if (parsed.protocol !== 'https:') return null;
      return parsed.pathname.endsWith('/v1/chat/completions')
        ? parsed.origin + parsed.pathname.replace('/v1/chat/completions', '/api/tags')
        : endpoint + '/api/tags';
    } catch {
      return null;
    }
  }
  if (id === 'cloudflare') {
    const account = String(process.env.CLOUDFLARE_ACCOUNT_ID || '').trim();
    return account ? 'https://api.cloudflare.com/client/v4/accounts/' + encodeURIComponent(account) + '/ai/models/search?per_page=1' : null;
  }
  if (id === 'gemini') {
    const key = String(process.env.GEMINI_API_KEY || '').trim();
    return key ? PROVIDER_HEALTH_URLS.gemini + '?key=' + encodeURIComponent(key) : null;
  }
  return PROVIDER_HEALTH_URLS[id] || null;
}

async function liveProbe(url, headers = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), CONNECTIVITY_TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      method: 'GET',
      headers: { accept: 'application/json,text/plain,*/*', ...headers },
      signal: controller.signal
    });
    await response.text().catch(() => '');
    return { status: response.status, ok: response.ok };
  } catch (error) {
    return {
      status: null,
      ok: false,
      state: error?.name === 'AbortError' ? 'TIMEOUT' : 'NETWORK_ERROR',
      error: String(error?.message || error).slice(0, 160)
    };
  } finally {
    clearTimeout(timer);
  }
}

export async function checkProviderHealth(id, { force = false } = {}) {
  if (!providers[id]) return { provider: id, state: 'UNKNOWN_PROVIDER' };
  if (!liveHealthConfigured(id)) return { provider: id, state: 'NOT_CONFIGURED' };
  const now = Date.now();
  const cached = connectivityHealthCache.get(id);
  if (!force && cached && now - cached.checkedAt < CONNECTIVITY_CACHE_MS) {
    return { ...cached.result, cached: true };
  }

  const url = liveHealthUrl(id);
  if (!url) return { provider: id, state: 'NOT_VERIFIABLE' };

  const headers = {};
  if (id === 'anthropic') {
    headers['x-api-key'] = process.env.ANTHROPIC_API_KEY;
    headers['anthropic-version'] = '2023-06-01';
  } else if (id === 'horde') {
    headers['Client-Agent'] = 'IAC33:2.0';
  } else if (id === 'ollama') {
    headers['x-iac33-llm-key'] = process.env.IAC33_OLLAMA_API_KEY;
  } else if (id === 'cloudflare') {
    headers.authorization = 'Bearer ' + process.env.CLOUDFLARE_API_TOKEN;
  } else if (providers[id]?.key && process.env[providers[id].key]) {
    headers.authorization = 'Bearer ' + process.env[providers[id].key];
  }

  const started = Date.now();
  const observed = await liveProbe(url, headers);
  const state = observed.ok ? 'OK'
    : observed.state === 'TIMEOUT' ? 'TIMEOUT'
    : observed.status === 429 ? 'RATE_LIMITED'
    : observed.status === 401 || observed.status === 403 ? 'AUTH_ERROR'
    : observed.status >= 500 ? 'UNAVAILABLE'
    : observed.state === 'NETWORK_ERROR' ? 'NETWORK_ERROR'
    : 'ERROR';

  const result = {
    provider: id,
    state,
    status: observed.status,
    latencyMs: Date.now() - started,
    ...(observed.error ? { error: observed.error } : {})
  };
  connectivityHealthCache.set(id, { checkedAt: Date.now(), result });
  return result;
}

export async function diagnoseConnectivity() {
  const order = [...new Set(parseList(
    process.env.AI_PROVIDER_ORDER,
    FREE_PROVIDER_DEFAULTS.join(',')
  ))];
  const internetStarted = Date.now();
  const internet = await liveProbe(
    process.env.AI_INTERNET_HEALTHCHECK_URL || 'https://www.google.com/generate_204'
  );
  const providerResults = await Promise.all(order.map((id) => checkProviderHealth(id, { force: true })));
  return {
    internet: {
      state: internet.ok || internet.status === 204 ? 'OK' : (internet.state || 'ERROR'),
      status: internet.status,
      latencyMs: Date.now() - internetStarted,
      ...(internet.error ? { error: internet.error } : {})
    },
    router: 'hedged-failover',
    order,
    providers: providerResults,
    generatedAt: new Date().toISOString()
  };
}
