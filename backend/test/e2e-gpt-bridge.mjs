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

const second = await request('/v1/gpt/commands', command, auth);
assert.equal(second.status, 201, JSON.stringify(second.json));
assert.equal(second.json.command.id, command.id);
assert.equal(second.json.command.status, 'PENDING');
assert.equal(second.json.digest, first.json.digest);

for (const transition of ['claim', 'execute', 'succeed']) {
  const result = await request(`/v1/commands/${command.id}/${transition}`, {}, auth);
  assert.equal(result.status, 200, JSON.stringify(result.json));
}

const ota = {
  id: `ota-e2e-${crypto.randomUUID()}`,
  type: 'OTA_INSTALL',
  payload: {
    manifest: {
      schemaVersion: 1,
      releaseId: 'e2e-release',
      appVersion: '0.1.1',
      createdAt: new Date().toISOString(),
      minimumSupportedVersion: '0.1.0',
      artifactRef: 'https://example.invalid/iac33-e2e.apk',
      artifactSha256: '0'.repeat(64),
      artifactSize: 1024,
      algorithm: 'SHA256withECDSA',
      signatureBase64: 'e2e-signature',
      keyId: 'e2e-key',
      rollbackRef: 'e2e-rollback'
    }
  },
  idempotencyKey: `ota-idem-${crypto.randomUUID()}`,
  expiresAt: new Date(Date.now() + 120_000).toISOString()
};
const otaCreate = await request('/v1/gpt/commands', ota, auth);
assert.equal(otaCreate.status, 201, JSON.stringify(otaCreate.json));
assert.equal(otaCreate.json.command.status, 'PENDING');

const otaRepeat = await request('/v1/gpt/commands', ota, auth);
assert.equal(otaRepeat.status, 201, JSON.stringify(otaRepeat.json));
assert.equal(otaRepeat.json.command.id, ota.id);
assert.equal(otaRepeat.json.digest, otaCreate.json.digest);

const otaClaim = await request('/v1/commands/claim-next', { actor: 'ota-e2e' }, auth);
assert.equal(otaClaim.status, 200, JSON.stringify(otaClaim.json));
assert.equal(otaClaim.json.command.id, ota.id);
assert.equal(otaClaim.json.command.status, 'CLAIMED');

for (const transition of ['execute', 'succeed']) {
  const result = await request(`/v1/commands/${ota.id}/${transition}`, {}, auth);
  assert.equal(result.status, 200, JSON.stringify(result.json));
}
assert.equal((await request(`/v1/commands/${ota.id}/succeed`, {}, auth)).status, 409);

const db = new pg.Pool({ connectionString: databaseUrl });
try {
  const row = await db.query('SELECT id,status FROM commands WHERE idempotency_key=$1', [command.idempotencyKey]);
  assert.equal(row.rowCount, 1);
  assert.equal(row.rows[0].id, command.id);
  assert.equal(row.rows[0].status, 'SUCCEEDED');
  const otaRow = await db.query('SELECT id,type,status FROM commands WHERE id=$1', [ota.id]);
  assert.equal(otaRow.rowCount, 1);
  assert.equal(otaRow.rows[0].type, 'OTA_INSTALL');
  assert.equal(otaRow.rows[0].status, 'SUCCEEDED');
  const audit = await db.query('SELECT from_status,to_status FROM command_audit WHERE command_id=$1 ORDER BY id', [ota.id]);
  assert.deepEqual(audit.rows.map((r) => [r.from_status, r.to_status]), [[null, 'PENDING'], ['PENDING', 'CLAIMED'], ['CLAIMED', 'EXECUTING'], ['EXECUTING', 'SUCCEEDED']]);
} finally {
  await db.end();
}

console.log(JSON.stringify({ ok: true, bridge: 'PASS', otaAdmission: 'PASS', otaLifecycle: 'PASS', auth: 'PASS', allowlist: 'PASS', idempotency: 'PASS', audit: 'PASS' }, null, 2));