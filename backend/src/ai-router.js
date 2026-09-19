import { STOP_SEQUENCES, sanitizeAssistantText } from './ai-output.js';
import { providerHealth } from './ai/provider-health.ts';
import { generateSequential } from './ai/router.ts';

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

const FREE_PROVIDER_DEFAULTS = ['gemini', 'groq', 'openrouter', 'deepseek', 'cloudflare', 'kilo', 'horde', 'pollinations', 'animica', 'ollama'];
const parseList = (value, fallback) => String(value || fallback).split(',').map((item) => item.trim()).filter(Boolean);
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

async async function callAnthropic(messages, timeoutMs, parentSignal) {
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

export async function generateWithFreePool({ messages, timeoutMs = 30000, signal }) {
  const order = [...new Set(
    parseList(process.env.AI_PROVIDER_ORDER, FREE_PROVIDER_DEFAULTS.join(','))
  )];

  return generateSequential({
    messages,
    timeoutMs,
    signal,
    order,
    providers,
    health: providerHealth,
    providerTimeoutMs
  });
}
