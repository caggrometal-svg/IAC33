import test from 'node:test';
import assert from 'node:assert/strict';
import { allowedTransitions, validCommand } from '../src/command-core.js';

test('command lifecycle only permits declared forward transitions', () => {
  assert.equal(allowedTransitions.get('PENDING').has('CLAIMED'), true);
  assert.equal(allowedTransitions.get('EXECUTING').has('SUCCEEDED'), true);
  assert.equal(allowedTransitions.get('SUCCEEDED'), undefined);
});

test('command validation rejects malformed input', () => {
  assert.equal(validCommand(null), false);
  assert.equal(validCommand({ id: 'c1', type: 'bad type', payload: {}, idempotencyKey: 'short', expiresAt: new Date().toISOString() }), false);
  assert.equal(validCommand({ id: 'c1', type: 'sync', payload: {}, idempotencyKey: 'idem-key-123', expiresAt: new Date(Date.now() + 60000).toISOString() }), true);
});
