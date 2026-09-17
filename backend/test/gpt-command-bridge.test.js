import test from 'node:test';
import assert from 'node:assert/strict';
import { validateGptCommand, commandDigest, ALLOWED_TYPES } from '../src/gpt-command-bridge.js';

const base = () => ({ id: 'gpt-cmd-001', type: 'sync', payload: { action: 'refresh' }, idempotencyKey: 'idem-gpt-001', expiresAt: new Date(Date.now() + 60_000).toISOString() });

test('GPT bridge accepts bounded allowed command', () => assert.equal(validateGptCommand(base()), true));
test('GPT bridge accepts OTA install command for the device bridge', () => assert.equal(validateGptCommand({ ...base(), type: 'OTA_INSTALL', targetDeviceId: 'device-1234567890123456', payload: { manifest: { releaseId: 'release-2' } } }), true));
test('GPT bridge rejects unauthorized command type', () => assert.equal(validateGptCommand({ ...base(), type: 'shell.exec' }), false));
test('GPT bridge rejects expired command', () => assert.equal(validateGptCommand({ ...base(), expiresAt: new Date(Date.now() - 1).toISOString() }), false));
test('GPT bridge rejects oversized payload', () => assert.equal(validateGptCommand({ ...base(), payload: { data: 'x'.repeat(128 * 1024) } }), false));
test('GPT bridge rejects malformed target device identity', () => assert.equal(validateGptCommand({ ...base(), targetDeviceId: 'device-1' }), false));
test('GPT bridge accepts bounded target device identity', () => assert.equal(validateGptCommand({ ...base(), targetDeviceId: 'device-1234567890123456' }), true));
test('GPT bridge exposes explicit allowlist', () => {
  assert.deepEqual([...ALLOWED_TYPES].sort(), ['OTA_INSTALL', 'device.action', 'sync', 'update'].sort());
  assert.equal(commandDigest(base()).length, 64);
});
