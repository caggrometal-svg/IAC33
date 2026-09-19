export type ProviderState = 'ONLINE' | 'RATE_LIMITED' | 'TIMEOUT' | 'OFFLINE';

export interface ProviderHealthRecord {
  provider: string;
  state: ProviderState;
  failures: number;
  cooldownUntil: number;
  cooldownRemainingMs: number;
  lastStatus: number | null;
  lastError: string | null;
  lastUpdatedAt: string | null;
  known: boolean;
}

function boundedMs(value, fallback, min, max) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.min(Math.max(parsed, min), max) : fallback;
}

function classifyError(error) {
  const status = Number(error?.status || 0);
  if (status === 429) return 'RATE_LIMITED';
  if (error?.name === 'AbortError' || status === 408 || status === 504) return 'TIMEOUT';
  return 'OFFLINE';
}

function cooldownFor(state, error) {
  if (state === 'RATE_LIMITED') {
    const retryAfter = boundedMs(error?.retryAfterMs, 0, 0, 300000);
    const configured = boundedMs(process.env.AI_RATE_LIMIT_COOLDOWN_MS, 60000, 5000, 300000);
    return Math.max(retryAfter, configured);
  }
  if (state === 'TIMEOUT') {
    return boundedMs(process.env.AI_TIMEOUT_COOLDOWN_MS, 15000, 5000, 120000);
  }
  return 0;
}

function publicRecord(provider, record, now = Date.now()) {
  if (!record) {
    return {
      provider,
      state: 'ONLINE',
      failures: 0,
      cooldownUntil: 0,
      cooldownRemainingMs: 0,
      lastStatus: null,
      lastError: null,
      lastUpdatedAt: null,
      known: false
    };
  }

  const remaining = Math.max(0, record.cooldownUntil - now);
  return {
    provider,
    state: record.state,
    failures: record.failures,
    cooldownUntil: remaining > 0 ? record.cooldownUntil : 0,
    cooldownRemainingMs: remaining,
    lastStatus: record.lastStatus ?? null,
    lastError: record.lastError ?? null,
    lastUpdatedAt: record.lastUpdatedAt ?? null,
    known: true
  };
}

export class ProviderHealthMonitor {
  records = new Map();

  isCoolingDown(provider) {
    const record = this.records.get(provider);
    return Boolean(record && record.cooldownUntil > Date.now());
  }

  get(provider) {
    return publicRecord(provider, this.records.get(provider));
  }

  recordSuccess(provider, meta = {}) {
    this.records.set(provider, {
      state: 'ONLINE',
      failures: 0,
      cooldownUntil: 0,
      lastStatus: Number.isFinite(Number(meta.status)) ? Number(meta.status) : 200,
      lastError: null,
      lastUpdatedAt: new Date().toISOString()
    });
    return this.get(provider);
  }

  recordFailure(provider, error, meta = {}) {
    const state = classifyError(error);
    const previous = this.records.get(provider);
    const failures = (previous?.failures || 0) + 1;
    const now = Date.now();

    this.records.set(provider, {
      state,
      failures,
      cooldownUntil: now + cooldownFor(state, error),
      lastStatus: Number(error?.status || meta.status || 0) || null,
      lastError: String(error?.message || 'PROVIDER_ERROR').slice(0, 240),
      lastUpdatedAt: new Date(now).toISOString()
    });

    return this.get(provider);
  }

  snapshot(providers = []) {
    const ids = [...new Set(providers)];
    for (const provider of this.records.keys()) {
      if (!ids.includes(provider)) ids.push(provider);
    }
    return Object.fromEntries(ids.map((provider) => [provider, this.get(provider)]));
  }
}

export const providerHealth = new ProviderHealthMonitor();

export function classifyProviderError(error) {
  return classifyError(error);
}
