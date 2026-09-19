import test from 'node:test';
import assert from 'node:assert/strict';
import { generateHedged, generateSequential, ProviderPoolUnavailableError } from '../src/ai/router.ts';
import { ProviderHealthMonitor } from '../src/ai/provider-health.ts';

function timeoutError() {
  const error = new Error('timeout');
  error.name = 'AbortError';
  return error;
}

test('cascade is strictly sequential and stops on the first useful response', async () => {
  const calls = [];
  const health = new ProviderHealthMonitor();
  const providers = {
    first: {
      key: null,
      call: async () => {
        calls.push('first');
        const error = new Error('rate limit');
        error.status = 429;
        throw error;
      }
    },
    second: {
      key: null,
      call: async () => {
        calls.push('second');
        throw timeoutError();
      }
    },
    third: {
      key: null,
      call: async () => {
        calls.push('third');
        return { text: 'respuesta utilizable', model: 'test-model' };
      }
    }
  };

  const result = await generateSequential({
    messages: [{ role: 'user', content: 'hola' }],
    timeoutMs: 5000,
    order: ['first', 'second', 'third'],
    providers,
    health,
    providerTimeoutMs: () => 100
  });

  assert.deepEqual(calls, ['first', 'second', 'third']);
  assert.equal(result.provider, 'third');
  assert.equal(result.text, 'respuesta utilizable');
  assert.equal(health.get('first').state, 'RATE_LIMITED');
  assert.equal(health.get('second').state, 'TIMEOUT');
  assert.equal(health.get('third').state, 'ONLINE');
});

test('cascade returns a structured trace when every provider fails', async () => {
  const health = new ProviderHealthMonitor();
  const providers = {
    first: {
      key: null,
      call: async () => {
        const error = new Error('rate limit');
        error.status = 429;
        throw error;
      }
    },
    second: {
      key: null,
      call: async () => { throw timeoutError(); }
    },
    third: {
      key: null,
      call: async () => { throw new Error('connection refused'); }
    }
  };

  await assert.rejects(
    () => generateSequential({
      messages: [{ role: 'user', content: 'hola' }],
      timeoutMs: 5000,
      order: ['first', 'second', 'third'],
      providers,
      health,
      providerTimeoutMs: () => 100
    }),
    (error) => {
      assert.ok(error instanceof ProviderPoolUnavailableError);
      assert.deepEqual(error.trace, {
        first: 'RATE_LIMITED',
        second: 'TIMEOUT',
        third: 'OFFLINE'
      });
      return true;
    }
  );
});


test('hedged router removes a failed provider correctly and continues to the next batch', async () => {
  const calls = [];
  const health = new ProviderHealthMonitor();
  const providers = {
    first: {
      key: null,
      call: async () => {
        calls.push('first');
        throw new Error('first failed');
      }
    },
    second: {
      key: null,
      call: async () => {
        calls.push('second');
        throw new Error('second failed');
      }
    },
    third: {
      key: null,
      call: async () => {
        calls.push('third');
        return { text: 'hedged success', model: 'test-model' };
      }
    }
  };

  const started = Date.now();
  const result = await generateHedged({
    messages: [{ role: 'user', content: 'hola' }],
    timeoutMs: 1000,
    order: ['first', 'second', 'third'],
    providers,
    health,
    providerTimeoutMs: () => 100,
    maxParallel: 2
  });

  assert.ok(Date.now() - started < 700);
  assert.equal(result.provider, 'third');
  assert.equal(result.text, 'hedged success');
  assert.deepEqual(calls, ['first', 'second', 'third']);
});
