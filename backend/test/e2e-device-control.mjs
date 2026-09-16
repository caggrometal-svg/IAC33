import assert from 'node:assert/strict';
import crypto from 'node:crypto';

const baseUrl = (process.env.E2E_BASE_URL || '').replace(/\/$/, '');
const pairingToken = process.env.DEVICE_PAIRING_TOKEN || '';
const controlToken = process.env.CONTROL_TOKEN || '';

for (const [name, value] of Object.entries({ E2E_BASE_URL: baseUrl, DEVICE_PAIRING_TOKEN: pairingToken, CONTROL_TOKEN: controlToken })) {
  assert.ok(value, `${name} is required`);
}

async function request(path, { body = {}, headers = {} } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body: JSON.stringify(body)
  });
  const text = await response.text();
  let json = {};
  if (text) json = JSON.parse(text);
  return { status: response.status, json };
}

const health = await fetch(`${baseUrl}/health`).then(async (r) => ({ status: r.status, json: await r.json() }));
assert.equal(health.status, 200);
assert.equal(health.json.ok, true);
assert.equal(health.json.database, true);
assert.equal(health.json.deviceAuth, true);

const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const publicKeyPem = publicKey.export({ type: 'spki', format: 'pem' });
const deviceId = `iac33-e2e-${crypto.randomUUID()}`;

const enrolled = await request('/v1/devices/enroll', {
  body: { deviceId, publicKeyPem, pairingToken }
});
assert.equal(enrolled.status, 201, JSON.stringify(enrolled.json));

function signed(path, body, forcedNonce) {
  const timestamp = Date.now();
  const nonce = forcedNonce || crypto.randomUUID().replaceAll('-', '');
  const rawBody = JSON.stringify(body);
  const canonical = ['POST', path, String(timestamp), nonce, rawBody].join('\n');
  const signature = crypto.sign('sha256', Buffer.from(canonical), privateKey).toString('base64');
  return { body, headers: {
    'X-Device-Id': deviceId,
    'X-Device-Timestamp': String(timestamp),
    'X-Device-Nonce': nonce,
    'X-Device-Signature': signature
  }, nonce };
}

const commandId = crypto.randomUUID();
const idempotencyKey = `e2e-${crypto.randomUUID()}`;
const expiresAt = new Date(Date.now() + 120_000).toISOString();
const created = await request('/v1/commands', {
  body: { id: commandId, type: 'PING', payload: { source: 'e2e' }, idempotencyKey, expiresAt },
  headers: { authorization: `Bearer ${controlToken}` }
});
assert.equal(created.status, 201, JSON.stringify(created.json));
assert.equal(created.json.command.status, 'PENDING');

const claimedRequest = signed('/v1/device/commands/claim-next', {});
const claimed = await request('/v1/device/commands/claim-next', claimedRequest);
assert.equal(claimed.status, 200, JSON.stringify(claimed.json));
assert.equal(claimed.json.command.id, commandId);
assert.equal(claimed.json.command.status, 'CLAIMED');

const executePath = `/v1/device/commands/${encodeURIComponent(commandId)}/execute`;
const executing = await request(executePath, signed(executePath, {}));
assert.equal(executing.status, 200, JSON.stringify(executing.json));
assert.equal(executing.json.command.status, 'EXECUTING');

const succeedPath = `/v1/device/commands/${encodeURIComponent(commandId)}/succeed`;
const succeeded = await request(succeedPath, signed(succeedPath, { detail: { message: 'ACK:PING', idempotencyKey } }));
assert.equal(succeeded.status, 200, JSON.stringify(succeeded.json));
assert.equal(succeeded.json.command.status, 'SUCCEEDED');

const replayNonce = crypto.randomUUID().replaceAll('-', '');
const replayPayload = signed('/v1/device/commands/claim-next', {}, replayNonce);
const firstReplayProbe = await request('/v1/device/commands/claim-next', replayPayload);
assert.equal(firstReplayProbe.status, 200, JSON.stringify(firstReplayProbe.json));
const replayRejected = await request('/v1/device/commands/claim-next', replayPayload);
assert.equal(replayRejected.status, 409, JSON.stringify(replayRejected.json));
assert.equal(replayRejected.json.error, 'DEVICE_AUTH_REPLAY');

const badSignature = signed('/v1/device/commands/claim-next', {});
badSignature.headers['X-Device-Signature'] = crypto.randomBytes(64).toString('base64');
const invalidSignature = await request('/v1/device/commands/claim-next', badSignature);
assert.equal(invalidSignature.status, 401);
assert.equal(invalidSignature.json.error, 'DEVICE_AUTH_INVALID_SIGNATURE');

console.log(JSON.stringify({
  ok: true,
  deviceId,
  commandId,
  lifecycle: ['ENROLLED', 'PENDING', 'CLAIMED', 'EXECUTING', 'SUCCEEDED'],
  replayProtection: 'PASS',
  invalidSignature: 'PASS'
}, null, 2));
