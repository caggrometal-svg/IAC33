import crypto from 'node:crypto';

const MAX_TTL_MS = 24 * 60 * 60 * 1000;
const MAX_PAYLOAD_BYTES = 128 * 1024;
const ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;
const IDEMPOTENCY_PATTERN = /^[A-Za-z0-9._:-]{8,128}$/;
const ALLOWED_TYPES = new Set(['sync', 'update', 'device.action']);

function validateGptCommand(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return false;
  if (typeof input.id !== 'string' || !ID_PATTERN.test(input.id)) return false;
  if (typeof input.type !== 'string' || !ALLOWED_TYPES.has(input.type)) return false;
  if (!input.payload || typeof input.payload !== 'object' || Array.isArray(input.payload)) return false;
  if (typeof input.idempotencyKey !== 'string' || !IDEMPOTENCY_PATTERN.test(input.idempotencyKey)) return false;
  const expiresAt = Date.parse(input.expiresAt);
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now() || expiresAt > Date.now() + MAX_TTL_MS) return false;
  try {
    if (Buffer.byteLength(JSON.stringify(input.payload), 'utf8') > MAX_PAYLOAD_BYTES) return false;
  } catch { return false; }
  return true;
}

function commandDigest(input) {
  return crypto.createHash('sha256').update(JSON.stringify({ id: input.id, type: input.type, payload: input.payload, expiresAt: input.expiresAt, targetDeviceId: input.targetDeviceId || null })).digest('hex');
}

export { ALLOWED_TYPES, validateGptCommand, commandDigest };
