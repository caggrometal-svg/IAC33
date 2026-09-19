export class ProviderPoolUnavailableError extends Error {
  trace;
  diagnostics;

  constructor(trace, diagnostics) {
    super('AI_PROVIDERS_UNAVAILABLE');
    this.name = 'ProviderPoolUnavailableError';
    this.trace = trace;
    this.diagnostics = diagnostics;
  }
}

function boundedMs(value, fallback, min, max) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.min(Math.max(parsed, min), max) : fallback;
}

function abortError() {
  const error = new Error('ABORTED');
  error.name = 'AbortError';
  return error;
}

export async function generateSequential({
  messages,
  timeoutMs = 30000,
  signal,
  order,
  providers,
  health,
  providerTimeoutMs,
  configured = (provider) => !provider.key || Boolean(process.env[provider.key])
}) {
  const totalBudgetMs = boundedMs(
    process.env.AI_TOTAL_TIMEOUT_MS,
    30000,
    5000,
    30000
  );
  const budgetMs = Math.min(boundedMs(timeoutMs, 30000, 1000, 45000), totalBudgetMs);
  const deadline = Date.now() + budgetMs;
  const trace = {};

  for (const id of order) {
    if (signal?.aborted) throw abortError();

    const provider = providers[id];
    if (!provider) {
      trace[id] = 'OFFLINE';
      continue;
    }

    if (health.isCoolingDown(id)) {
      trace[id] = health.get(id).state;
      continue;
    }

    if (!configured(provider)) {
      trace[id] = 'OFFLINE';
      continue;
    }

    const remainingMs = deadline - Date.now();
    if (remainingMs <= 0) {
      trace[id] = 'TIMEOUT';
      break;
    }

    const attemptTimeoutMs = Math.min(
      providerTimeoutMs(id),
      budgetMs,
      remainingMs
    );

    try {
      const result = await provider.call(messages, attemptTimeoutMs, 1, signal);
      if (!result || typeof result.text !== 'string' || !result.text.trim()) {
        const empty = new Error('Provider returned empty response');
        empty.status = 502;
        throw empty;
      }

      health.recordSuccess(id, { status: 200 });
      trace[id] = 'ONLINE';
      return {
        ...result,
        provider: id,
        trace
      };
    } catch (error) {
      if (signal?.aborted) throw error;

      const healthRecord = health.recordFailure(id, error);
      trace[id] = healthRecord.state;
    }
  }

  throw new ProviderPoolUnavailableError(
    trace,
    health.snapshot(order)
  );
}


export async function generateHedged({
  messages,
  timeoutMs = 30000,
  signal,
  order,
  providers,
  health,
  providerTimeoutMs,
  configured = (provider) => !provider.key || Boolean(process.env[provider.key]),
  maxParallel = 2
}) {
  const totalBudgetMs = boundedMs(
    process.env.AI_TOTAL_TIMEOUT_MS,
    30000,
    5000,
    30000
  );
  const budgetMs = Math.min(boundedMs(timeoutMs, 30000, 1000, 45000), totalBudgetMs);
  const deadline = Date.now() + budgetMs;
  const trace = {};
  const controller = new AbortController();
  const abortFromParent = () => controller.abort(signal?.reason);
  if (signal) {
    if (signal.aborted) controller.abort(signal.reason);
    else signal.addEventListener('abort', abortFromParent, { once: true });
  }
  const timer = setTimeout(() => controller.abort(), budgetMs);
  const parallel = Math.min(Math.max(Number(maxParallel) || 2, 1), 3);
  let winnerSelected = false;

  try {
    const candidates = [];
    for (const id of order) {
      const provider = providers[id];
      if (!provider) {
        trace[id] = 'OFFLINE';
        continue;
      }
      if (health.isCoolingDown(id)) {
        trace[id] = health.get(id).state;
        continue;
      }
      if (!configured(provider)) {
        trace[id] = 'OFFLINE';
        continue;
      }
      candidates.push(id);
    }

    if (!candidates.length) {
      throw new ProviderPoolUnavailableError(trace, health.snapshot(order));
    }

    const attempt = async (id) => {
      const remainingMs = deadline - Date.now();
      if (remainingMs <= 0) {
        const error = new Error('TIMEOUT');
        error.name = 'AbortError';
        throw error;
      }
      const attemptTimeoutMs = Math.min(providerTimeoutMs(id), remainingMs);
      try {
        const result = await providers[id].call(
          messages,
          attemptTimeoutMs,
          1,
          controller.signal
        );
        if (!result || typeof result.text !== 'string' || !result.text.trim()) {
          const empty = new Error('Provider returned empty response');
          empty.status = 502;
          throw empty;
        }
        health.recordSuccess(id, { status: 200 });
        trace[id] = 'ONLINE';
        return { ...result, provider: id, trace };
      } catch (error) {
        if (controller.signal.aborted && (signal?.aborted || winnerSelected || Date.now() >= deadline)) throw error;
        const healthRecord = health.recordFailure(id, error);
        trace[id] = healthRecord.state;
        throw error;
      }
    };

    let offset = 0;
    while (offset < candidates.length) {
      const size = offset === 0 ? 1 : Math.min(parallel, candidates.length - offset);
      const batch = candidates.slice(offset, offset + size);
      const pending = batch.map((id) =>
        attempt(id)
          .then((result) => ({ ok: true, id, result }))
          .catch((error) => ({ ok: false, id, error }))
      );
      let nextPending = pending.map((promise, index) => ({
        index,
        promise: promise.then((result) => ({ ...result, index }))
      }));

      const failures = [];
      while (nextPending.length) {
        if (controller.signal.aborted && (signal?.aborted || Date.now() >= deadline)) {
          throw abortError();
        }
        const settled = await Promise.race(nextPending.map((item) => item.promise));
        if (settled.ok) {
          winnerSelected = true;
          controller.abort();
          return settled.result;
        }
        failures.push(settled);
        nextPending = nextPending.filter((item) => item.index !== settled.index);
      }

      if (failures.length) {
        offset += size;
        continue;
      }
      offset += size;
    }

    throw new ProviderPoolUnavailableError(
      trace,
      health.snapshot(order)
    );
  } finally {
    clearTimeout(timer);
    if (signal) signal.removeEventListener('abort', abortFromParent);
    controller.abort();
  }
}
