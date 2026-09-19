  if (retryAfterMs > 0) error.retryAfterMs = retryAfterMs;
  return error;
}

const FREE_PROVIDER_DEFAULTS = ['kilo', 'horde', 'pollinations', 'animica', 'ollama'];
const parseList = (value, fallback) => String(value || fallback).split(',').map((item) => item.trim()).filter(Boolean);
const NONZERO_COST_PROVIDERS = new Set(['openai','anthropic','deepseek','xai','gemini','groq','openrouter','cloudflare','freeinference']);
const ZERO_COST_MODE = String(process.env.AI_ZERO_COST_MODE || 'true').toLowerCase() !== 'false';
const effectiveProviderOrder = (value) => parseList(value, FREE_PROVIDER_DEFAULTS.join(','))
  .filter((id) => !ZERO_COST_MODE || !NONZERO_COST_PROVIDERS.has(id));
const MAX_PROVIDER_RESPONSE_BYTES = 2 * 1024 * 1024;

function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      const error = new Error('ABORTED');
      error.name = 'AbortError';
      reject(error);