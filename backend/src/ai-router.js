const providers = {
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

const RETRYABLE_STATUSES = new Set([408, 425, 429, 500, 502, 503, 504]);
const FREE_PROVIDER_DEFAULTS = ['kilo', 'horde', 'pollinations'];
const PROVIDER_CIRCUIT_FAILURES = Math.min(Math.max(Number(process.env.AI_CIRCUIT_FAILURES || 2), 1), 5);
const PROVIDER_CIRCUIT_OPEN_MS = Math.min(Math.max(Number(process.env.AI_CIRCUIT_OPEN_MS || 30000), 5000), 300000);
const MAX_PROVIDER_RESPONSE_BYTES = 2 * 1024 * 1024;
const providerHealth = new Map();

const parseList = (value, fallback) =>
  String(value || fallback).split(',').map((item) => item.trim()).filter(Boolean);

function isRetryable(error) {
  if (!error) return false;
  if (error?.name === 'AbortError') return false;
  if (RETRYABLE_STATUSES.has(Number(error?.status))) return true;
  if (error?.name === 'TypeError') return true;
  return ['ECONNRESET', 'ECONNREFUSED', 'ETIMEDOUT', 'EAI_AGAIN'].includes(error?.code);
}

function retryAfterMs(error) {
  const value = Number(error?.retryAfterMs || 0);
  return Number.isFinite(value) && value > 0 ? Math.min(value, 1500) : 0;
}

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
  if (id === 'kilo') return Math.min(Math.max(Number(process.env.AI_KILO_TIMEOUT_MS || 2500), 1000), 6000);
  if (id === 'horde') return Math.min(Math.max(Number(process.env.AI_HORDE_TIMEOUT_MS || 4500), 2000), 8000);
  if (id === 'pollinations') return Math.min(Math.max(Number(process.env.AI_POLLINATIONS_TIMEOUT_MS || 2500), 1000), 6000);
  return Math.min(Math.max(Number(process.env.AI_PROVIDER_TIMEOUT_MS || 2200), 900), 7000);
}

function boundedRetryDelay(error, attempt, remainingMs) {
  const exponential = Math.min(1000, 150 * (2 ** Math.max(0, attempt - 1)));
  const requested = retryAfterMs(error) || exponential;
  const jitter = Math.floor(Math.random() * 75);
  return Math.min(1000, requested + jitter, Math.max(0, remainingMs - 1));
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
    throw providerError(502, 'Provider returned invalid JSON');
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
    const payload = { contents };
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
        '/ai/run/' +
        encodeURIComponent(model),
      {
        method: 'POST',
        headers: {
          authorization: 'Bearer ' + process.env.CLOUDFLARE_API_TOKEN,
          'content-type': 'application/json'
        },
        body: JSON.stringify({ messages }),
        signal
      }
    );
    const json = await readJsonBounded(response);
    if (!response.ok) throw responseError(response, json, 'Cloudflare request failed');
    const text = json?.result?.response || json?.result?.text || '';
    if (!text) throw providerError(502, 'Cloudflare returned empty response');
    return { text, model };
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
        max_tokens: Math.min(Number(process.env.KILO_MAX_TOKENS || 512), 1024)
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
      body: JSON.stringify({ model, messages }),
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
          max_length: Math.min(Number(process.env.AI_HORDE_MAX_LENGTH || 256), 512),
          max_context_length: Math.min(Number(process.env.AI_HORDE_MAX_CONTEXT || 4096), 8192),
          temperature: Number(process.env.AI_HORDE_TEMPERATURE || 0.4)
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
      body: JSON.stringify({ model, messages }),
      signal
    });
    const json = await readJsonBounded(response);
    if (!response.ok) throw responseError(response, json, 'Provider request failed');
    const text = json?.choices?.[0]?.message?.content || '';
    if (!text) throw providerError(502, 'Provider returned empty response');
    return { text, model };
  } finally {
    cleanup();
  }
}

function circuitOpen(id) {
  const state = providerHealth.get(id);
  if (!state) return false;
  if (state.openUntil > Date.now()) return true;
  providerHealth.delete(id);
  return false;
}

function recordSuccess(id) {
  providerHealth.delete(id);
}

function recordFailure(id, error) {
  if (!isRetryable(error)) return;
  const current = providerHealth.get(id) || { failures: 0, openUntil: 0 };
  current.failures += 1;
  if (current.failures >= PROVIDER_CIRCUIT_FAILURES) {
    current.openUntil = Date.now() + PROVIDER_CIRCUIT_OPEN_MS;
  }
  providerHealth.set(id, current);
}

export async function generateWithFreePool({ messages, timeoutMs = 18000, signal }) {
  const order = [...new Set(
    parseList(process.env.AI_PROVIDER_ORDER, FREE_PROVIDER_DEFAULTS.join(','))
  )];
  const diagnostics = [];
  const totalBudgetMs = Math.min(
    Math.max(Number(process.env.AI_TOTAL_TIMEOUT_MS || 9000), 2500),
    15000
  );
  const budgetMs = Math.min(timeoutMs, totalBudgetMs);
  const deadline = Date.now() + budgetMs;
  const masterController = new AbortController();
  const onAbort = () => masterController.abort(signal.reason);
  if (signal) {
    if (signal.aborted) masterController.abort(signal.reason);
    else signal.addEventListener('abort', onAbort, { once: true });
  }
  const budgetTimer = setTimeout(() => masterController.abort(), budgetMs);

  try {
    const available = order.filter((id) => {
      const provider = providers[id];
      if (!provider) {
        diagnostics.push({ provider: id, state: 'UNKNOWN_PROVIDER' });
        return false;
      }
      if (circuitOpen(id)) {
        diagnostics.push({ provider: id, state: 'CIRCUIT_OPEN' });
        return false;
      }
      if (provider.key && !process.env[provider.key]) {
        diagnostics.push({ provider: id, state: 'NOT_CONFIGURED' });
        return false;
      }
      return true;
    });

    if (!available.length) {
      const error = new Error('AI_PROVIDERS_UNAVAILABLE');
      error.diagnostics = diagnostics;
      throw error;
    }

    async function attemptProvider(id) {
      const provider = providers[id];
      const maxAttempts = Math.min(
        Math.max(Number(process.env.AI_PROVIDER_RETRIES || 1), 1),
        3
      );
      let lastError = null;

      for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
        if (masterController.signal.aborted) {
          const error = new Error('ABORTED');
          error.name = 'AbortError';
          throw error;
        }
        const remainingMs = deadline - Date.now();
        if (remainingMs <= 0) break;
        const attemptTimeoutMs = Math.min(
          providerTimeoutMs(id),
          timeoutMs,
          remainingMs
        );

        try {
          const result = await provider.call(
            messages,
            attemptTimeoutMs,
            attempt,
            masterController.signal
          );
          recordSuccess(id);
          return result;
        } catch (error) {
          if (masterController.signal.aborted) throw error;
          lastError = error;
          if (attempt >= maxAttempts || !isRetryable(error)) break;
          const remainingAfterFailure = deadline - Date.now();
          if (remainingAfterFailure <= 50) break;
          await sleep(
            boundedRetryDelay(error, attempt, remainingAfterFailure),
            masterController.signal
          );
        }
      }

      recordFailure(id, lastError);
      throw lastError || providerError(504, 'Provider timeout');
    }

    const batchSize = Math.min(2, available.length);
    for (let offset = 0; offset < available.length; offset += batchSize) {
      const batch = available.slice(offset, offset + batchSize);
      const pending = batch.map((id) => {
        const started = Date.now();
        const promise = attemptProvider(id)
          .then((result) => ({ ok: true, id, result, latencyMs: Date.now() - started }))
          .catch((error) => ({ ok: false, id, error, latencyMs: Date.now() - started }));
        return { id, promise };
      });

      while (pending.length) {
        if (masterController.signal.aborted) {
          const error = new Error('ABORTED');
          error.name = 'AbortError';
          throw error;
        }
        const settled = await Promise.race(pending.map((entry) => entry.promise));
        const index = pending.findIndex((entry) => entry.id === settled.id);
        if (index >= 0) pending.splice(index, 1);

        diagnostics.push({
          provider: settled.id,
          state: settled.ok ? 'RESPONDING' :
            settled.error?.status === 429 ? 'RATE_LIMITED' :
            settled.error?.name === 'AbortError' ? 'TIMEOUT' : 'FAILED',
          status: settled.ok ? 200 : (settled.error?.status || 500),
          latencyMs: settled.latencyMs
        });

        if (settled.ok) {
          masterController.abort();
          return { ...settled.result, provider: settled.id };
        }
      }
    }

    const error = new Error('AI_PROVIDERS_UNAVAILABLE');
    error.diagnostics = diagnostics;
    throw error;
  } finally {
    clearTimeout(budgetTimer);
    if (signal) signal.removeEventListener('abort', onAbort);
    masterController.abort();
  }
}
