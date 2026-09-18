import crypto from 'node:crypto';

const MAX_TTL_MS = 24 * 60 * 60 * 1000;
const MAX_PAYLOAD_BYTES = 128 * 1024;
const ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;
const IDEMPOTENCY_PATTERN = /^[A-Za-z0-9._:-]{8,128}$/;
const DEVICE_ID_PATTERN = /^[A-Za-z0-9._:-]{16,128}$/;
const ALLOWED_TYPES = new Set(['sync', 'update', 'device.action', 'OTA_INSTALL']);

function validateGptCommand(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return false;
  if (typeof input.id !== 'string' || !ID_PATTERN.test(input.id)) return false;
  if (typeof input.type !== 'string' || !ALLOWED_TYPES.has(input.type)) return false;
  if (!input.payload || typeof input.payload !== 'object' || Array.isArray(input.payload)) return false;
  if (typeof input.idempotencyKey !== 'string' || !IDEMPOTENCY_PATTERN.test(input.idempotencyKey)) return false;
  if (input.type === 'OTA_INSTALL' && !validateOtaPayload(input.payload)) return false;
  if (input.targetDeviceId !== undefined && (typeof input.targetDeviceId !== 'string' || !DEVICE_ID_PATTERN.test(input.targetDeviceId))) return false;
  const expiresAt = Date.parse(input.expiresAt);
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now() || expiresAt > Date.now() + MAX_TTL_MS) return false;
  try {
    if (Buffer.byteLength(JSON.stringify(input.payload), 'utf8') > MAX_PAYLOAD_BYTES) return false;
  } catch { return false; }
  return true;
}

function validateOtaPayload(payload) {
  const manifest = payload?.manifest;
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) return false;
  if (manifest.schemaVersion !== 1) return false;
  if (typeof manifest.releaseId !== 'string' || !ID_PATTERN.test(manifest.releaseId)) return false;
  if (typeof manifest.appVersion !== 'string' || !/^\\d+(\\.\\d+){2,3}$/.test(manifest.appVersion)) return false;
  if (typeof manifest.minimumSupportedVersion !== 'string' || !/^\\d+(\\.\\d+){2,3}$/.test(manifest.minimumSupportedVersion)) return false;
  if (typeof manifest.createdAt !== 'string' || !Number.isFinite(Date.parse(manifest.createdAt))) return false;
  if (typeof manifest.artifactRef !== 'string') return false;
  let artifactUrl;
  try { artifactUrl = new URL(manifest.artifactRef); } catch { return false; }
  if (artifactUrl.protocol !== 'https:') return false;
  if (!['github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com'].includes(artifactUrl.hostname.toLowerCase())) return false;
  if (!Number.isSafeInteger(manifest.artifactSize) || manifest.artifactSize <= 0 || manifest.artifactSize > 100 * 1024 * 1024) return false;
  if (typeof manifest.artifactSha256 !== 'string' || !/^[0-9a-fA-F]{64}$/.test(manifest.artifactSha256)) return false;
  if (manifest.algorithm !== 'SHA256withECDSA') return false;
  if (manifest.keyId !== 'iac33-bridge-ecdsa-v1') return false;
  if (typeof manifest.signatureBase64 !== 'string' || !/^[A-Za-z0-9+/=_-]+$/.test(manifest.signatureBase64)) return false;
  if (typeof manifest.rollbackRef !== 'string' || !manifest.rollbackRef.trim()) return false;
  return true;
}

function stableJson(value) {
  if (Array.isArray(value)) return value.map(stableJson);
  if (value && typeof value === 'object') {
    return Object.keys(value).sort().reduce((out, key) => {
      out[key] = stableJson(value[key]);
      return out;
    }, {});
  }
  return value;
}

function commandDigest(input) {
  const expiresAt = new Date(input.expiresAt);
  const normalized = {
    id: input.id,
    type: input.type,
    payload: stableJson(input.payload),
    expiresAt: Number.isFinite(expiresAt.getTime()) ? expiresAt.toISOString() : input.expiresAt,
    targetDeviceId: input.targetDeviceId || null
  };
  return crypto.createHash('sha256').update(JSON.stringify(normalized)).digest('hex');
}

export { ALLOWED_TYPES, validateGptCommand, commandDigest, validateOtaPayload };
