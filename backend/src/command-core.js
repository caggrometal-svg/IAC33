const allowedTransitions = new Map([
  ['PENDING', new Set(['CLAIMED', 'EXPIRED', 'REJECTED'])],
  ['CLAIMED', new Set(['EXECUTING', 'FAILED', 'EXPIRED'])],
  ['EXECUTING', new Set(['SUCCEEDED', 'FAILED'])]
]);

const MAX_COMMAND_PAYLOAD_BYTES = 128 * 1024;
const MAX_COMMAND_TTL_MS = 24 * 60 * 60 * 1000;
const DEVICE_ID_PATTERN = /^[A-Za-z0-9._:-]{16,128}$/;

function validCommand(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return false;
  if (typeof input.id !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(input.id)) return false;
  if (typeof input.type !== 'string' || !/^[A-Za-z0-9._:-]{1,64}$/.test(input.type)) return false;
  if (!input.payload || typeof input.payload !== 'object' || Array.isArray(input.payload)) return false;
  if (typeof input.idempotencyKey !== 'string' || !/^[A-Za-z0-9._:-]{8,128}$/.test(input.idempotencyKey)) return false;
  if (input.targetDeviceId !== undefined && (typeof input.targetDeviceId !== 'string' || !DEVICE_ID_PATTERN.test(input.targetDeviceId))) return false;
  const expiresAt = Date.parse(input.expiresAt);
  if (!Number.isFinite(expiresAt)) return false;
  const now = Date.now();
  if (expiresAt <= now || expiresAt > now + MAX_COMMAND_TTL_MS) return false;
  try {
    if (Buffer.byteLength(JSON.stringify(input.payload), 'utf8') > MAX_COMMAND_PAYLOAD_BYTES) return false;
  } catch {
    return false;
  }
  return true;
}

export { allowedTransitions, validCommand, DEVICE_ID_PATTERN };
