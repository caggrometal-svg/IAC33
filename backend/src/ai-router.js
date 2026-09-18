    const remainingMs = deadline - Date.now();
    if (remainingMs <= 0) { diagnostics.push({ provider: id, state: 'BUDGET_EXHAUSTED' }); break; }
    const attemptTimeoutMs = Math.min(timeoutMs, remainingMs);
    const started = Date.now();
    try { const result = await provider.call(messages, attemptTimeoutMs); diagnostics.push({ provider: id, state: 'RESPONDING', latencyMs: Date.now() - started }); return { ...result, provider: id, diagnostics }; }
    catch (error) { diagnostics.push({ provider: id, state: error?.name === 'AbortError' ? 'TIMEOUT' : error?.status === 429 ? 'RATE_LIMITED' : 'FAILED', status: error?.status || 500, latencyMs: Date.now() - started }); }
  }
  const error = new Error('AI_PROVIDERS_UNAVAILABLE'); error.diagnostics = diagnostics; throw error;
}