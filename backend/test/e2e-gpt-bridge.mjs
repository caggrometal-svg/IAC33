import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import pg from 'pg';

const baseUrl = (process.env.E2E_BASE_URL || '').replace(/\/$/, '');
const controlToken = process.env.CONTROL_TOKEN || '';
const databaseUrl = process.env.DATABASE_URL || '';
assert.ok(baseUrl, 'E2E_BASE_URL is required');
assert.ok(controlToken, 'CONTROL_TOKEN is required');
assert.ok(databaseUrl, 'DATABASE_URL is required');

async function request(path, body, headers = {}) {
  const response = await fetch(`${baseUrl}${path}`, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body) });
  const text = await response.text();
  return { status: response.status, json: text ? JSON.parse(text) : {} };
}

const auth = { authorization: `Bearer ${controlToken}` };
const unauthorized = await request('/v1/gpt/commands', {});
assert.equal(unauthorized.status, 401);

const invalid = await request('/v1/gpt/commands', {
  id: `gpt-invalid-${crypto.randomUUID()}`,
  type: 'shell.exec',
  payload: { command: 'echo forbidden' },
  idempotencyKey: `gpt-invalid-${crypto.randomUUID()}`,
  expiresAt: new Date(Date.now() + 60_000).toISOString()
}, auth);
assert.equal(invalid.status, 400);
assert.equal(invalid.json.error, 'INVALID_GPT_COMMAND');

const command = {
  id: `gpt-e2e-${crypto.randomUUID()}`,
  type: 'sync',
  payload: { action: 'refresh', source: 'gpt-e2e' },
  idempotencyKey: `gpt-idem-${crypto.randomUUID()}`,
  expiresAt: new Date(Date.now() + 120_000).toISOString()
};
const first = await request('/v1/gpt/commands', command, auth);
assert.equal(first.status, 201, JSON.stringify(first.json));
assert.equal(first.json.ok, true);
assert.equal(first.json.command.status, 'PENDING');
assert.equal(first.json.command.id, command.id);
assert.equal(first.json.digest.length, 64);

const second = await request('/v1/gpt/commands', { ...command, id: `different-${crypto.randomUUID()}` }, auth);
assert.equal(second.status, 201, JSON.stringify(second.json));
assert.equal(second.json.command.id, command.id);
assert.equal(second.json.command.status, 'PENDING');
assert.equal(second.json.digest, first.json.digest);

const db = new pg.Pool({ connectionString: databaseUrl });
try {
  const row = await db.query('SELECT id,status FROM commands WHERE idempotency_key=$1', [command.idempotencyKey]);
  assert.equal(row.rowCount, 1);
  assert.equal(row.rows[0].id, command.id);
  assert.equal(row.rows[0].status, 'PENDING');
  const audit = await db.query('SELECT actor,to_status FROM command_audit WHERE command_id=$1 ORDER BY id', [command.id]);
  assert.deepEqual(audit.rows.map((r) => [r.actor, r.to_status]), [['gpt', 'PENDING']]);
} finally {
  await db.end();
}

console.log(JSON.stringify({ ok: true, bridge: 'PASS', auth: 'PASS', allowlist: 'PASS', idempotency: 'PASS', audit: 'PASS' }, null, 2));
