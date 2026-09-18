import test from 'node:test';
import assert from 'node:assert/strict';
import { STOP_SEQUENCES, sanitizeAssistantText, classifyIntent } from '../src/ai-output.js';

test('uses the exact stop sequences required by the chat template', () => {
  assert.deepEqual(STOP_SEQUENCES, ['\nUSER:', '\nASSISTANT:']);
});

test('sanitizes prompt/template leakage before rendering', () => {
  const clean = sanitizeAssistantText(
    'Respuesta útil\nASSISTANT: leaked\nUSER: leaked user'
  );
  assert.equal(clean, 'Respuesta útil');
});

test('removes internal template and diagnostic lines', () => {
  const clean = sanitizeAssistantText(
    'Respuesta\nSYSTEM: secret\nprovider: kilo\nHTTP 503\nContinuación'
  );
  assert.equal(clean, 'Respuesta\nContinuación');
});

test('classifies local capability intents before the remote route', () => {
  assert.equal(classifyIntent([{ role: 'user', content: 'muéstrame mi GPS' }]), 'LOCATION');
  assert.equal(classifyIntent([{ role: 'user', content: 'analiza un terremoto' }]), 'SEISMIC');
  assert.equal(classifyIntent([{ role: 'user', content: 'abre multimedia' }]), 'MULTIMEDIA');
});
