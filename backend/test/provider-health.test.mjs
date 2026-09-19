import test from 'node:test';
import assert from 'node:assert/strict';
import { ProviderHealthMonitor } from '../src/ai/provider-health.ts';

test('429 and timeout enter cooldown with the correct health state', () => {
  const health = new ProviderHealthMonitor();

  const limited = new Error('rate limited');
  limited.status = 429;
  limited.retryAfterMs = 2500;
  const rate = health.recordFailure('gemini', limited);

  assert.equal(rate.state, 'RATE_LIMITED');
  assert.ok(rate.cooldownRemainingMs >= 5000);

  const timeout = new Error('timed out');
  timeout.name = 'AbortError';
  const timed = health.recordFailure('groq', timeout);

  assert.equal(timed.state, 'TIMEOUT');
  assert.ok(timed.cooldownRemainingMs >= 5000);

  const online = health.recordSuccess('gemini');
  assert.equal(online.state, 'ONLINE');
  assert.equal(online.failures, 0);
  assert.equal(online.cooldownRemainingMs, 0);
});

test('cooldown prevents an immediate retry', () => {
  const health = new ProviderHealthMonitor();
  const error = new Error('offline');
  error.status = 503;

  health.recordFailure('provider-a', error);
  assert.equal(health.isCoolingDown('provider-a'), true);
});
