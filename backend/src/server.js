import http from 'node:http';
import crypto from 'node:crypto';
import { Pool } from 'pg';
import { allowedTransitions, validCommand } from './command-core.js';
import { generateWithFreePool } from './ai-router.js';
import { validateGptCommand, commandDigest } from './gpt-command-bridge.js';
import { verifyGitHubActionsToken } from './github-oidc.js';
import { fetchLatestSeismic } from './seismic.js';

const MAX_BODY_BYTES = 256 * 1024;
const MAX_AI_MESSAGES = 64;
const MAX_AI_MESSAGE_CHARS = 32_000;
const AI_MIN_TIMEOUT_MS = 1_000;
const AI_MAX_TIMEOUT_MS = 45_000;
const READY_TIMEOUT_MS = 2_000;
const DEVICE_ID_PATTERN = /^[A-Za-z0-9._:-]{16,128}$/;
const port = Number(process.env.PORT || 3000);
const controlToken = process.env.CONTROL_TOKEN || '';
const devicePairingToken = process.env.DEVICE_PAIRING_TOKEN || '';
const databaseUrl = process.env.DATABASE_URL || '';
const databaseNeedsSsl = databaseUrl && !/(localhost|127\.0\.0\.1)(:|\/|$)/i.test(databaseUrl);
const pool = databaseUrl ? new Pool({ connectionString: databaseUrl, ...(databaseNeedsSsl ? { ssl: { rejectUnauthorized: false } } : {}), connectionTimeoutMillis: READY_TIMEOUT_MS, idleTimeoutMillis: 10000, max: 5 }) : null;
const aiWindow = new Map();

if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('INVALID_PORT');

function authorized(req) {
  if (!controlToken) return false;
  const value = req.headers.authorization || '';
  const expected = Buffer.from(`Bearer ${controlToken}`);
  const actual = Buffer.from(value);
  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
}

async function authorizedGptBridge(req) {
  if (authorized(req)) return true;
  const value = String(req.headers.authorization || '');
  if (!value.startsWith('Bearer ')) return false;
  return verifyGitHubActionsToken(value.slice(7));
}

function send(res, status, body) {
  if (res.headersSent) return;
  const data = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' });
  res.end(data);
}

function clientKey(req) {
  return String(req.headers['x-forwarded-for'] || req.socket.remoteAddress || 'unknown').split(',')[0].trim().slice(0, 128);
}

function aiAllowed(req) {
  const key = clientKey(req);
  const now = Date.now();
  const previous = aiWindow.get(key) || [];
  const recent = previous.filter((time) => now - time < 60_000);
  if (recent.length >= Number(process.env.AI_REQUESTS_PER_MINUTE || 20)) return false;
  recent.push(now);
  aiWindow.set(key, recent);
  if (aiWindow.size > 5000) for (const [candidate, times] of aiWindow) if (!times.some((time) => now - time < 60_000)) aiWindow.delete(candidate);
  return true;
}

async function body(req, maxBytes = MAX_BODY_BYTES) {
  const chunks = [];
  let total = 0;
  let oversized = false;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > maxBytes) { oversized = true; continue; }
    chunks.push(chunk);
  }
  if (oversized) { const error = new Error('BODY_TOO_LARGE'); error.status = 413; throw error; }
  if (!chunks.length) return {};
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { const error = new Error('INVALID_JSON'); error.status = 400; throw error; }
}

function tokenMatches(value, expected) {
  if (!expected || typeof value !== 'string') return false;
  const a = Buffer.from(value); const b = Buffer.from(expected);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function canonicalRequest(method, path, timestamp, nonce, rawBody) { return [method.toUpperCase(), path, String(timestamp), nonce, rawBody].join('\n'); }

async function verifyDeviceRequest(req, rawBody) {
  if (!pool) return { ok: false, status: 503, error: 'DATABASE_UNCONFIGURED' };
  const deviceId = String(req.headers['x-device-id'] || '');
  const timestamp = Number(req.headers['x-device-timestamp']);
  const nonce = String(req.headers['x-device-nonce'] || '');
  const signature = String(req.headers['x-device-signature'] || '');
  if (!deviceId || !Number.isSafeInteger(timestamp) || !nonce || !signature) return { ok: false, status: 401, error: 'DEVICE_AUTH_REQUIRED' };
  const skewMs = Number(process.env.DEVICE_AUTH_SKEW_MS || 120000);
  if (!Number.isFinite(skewMs) || skewMs < 1000 || skewMs > 900000) return { ok: false, status: 500, error: 'DEVICE_AUTH_CONFIG_INVALID' };
  if (Math.abs(Date.now() - timestamp) > skewMs) return { ok: false, status: 401, error: 'DEVICE_AUTH_EXPIRED' };
  if (!DEVICE_ID_PATTERN.test(deviceId)) return { ok: false, status: 401, error: 'DEVICE_AUTH_INVALID_DEVICE_ID' };
  if (!/^[A-Za-z0-9._:-]{16,128}$/.test(nonce)) return { ok: false, status: 401, error: 'DEVICE_AUTH_INVALID_NONCE' };
  if (!/^[A-Za-z0-9+/=_-]{32,2048}$/.test(signature)) return { ok: false, status: 401, error: 'DEVICE_AUTH_INVALID_SIGNATURE' };
  const result = await pool.query('SELECT public_key_pem FROM devices WHERE id=$1', [deviceId]);
  if (!result.rowCount) return { ok: false, status: 401, error: 'DEVICE_UNKNOWN' };
  const message = canonicalRequest(req.method, new URL(req.url, 'http://localhost').pathname, timestamp, nonce, rawBody);
  let valid = false;
  try { valid = crypto.verify('sha256', Buffer.from(message), result.rows[0].public_key_pem, Buffer.from(signature, 'base64')); } catch { valid = false; }
  if (!valid) return { ok: false, status: 401, error: 'DEVICE_AUTH_INVALID_SIGNATURE' };
  const nonceInsert = await pool.query(`INSERT INTO device_nonces(device_id,nonce,expires_at) VALUES($1,$2,to_timestamp($3/1000.0)+interval '2 minutes') ON CONFLICT DO NOTHING RETURNING nonce`, [deviceId, nonce, timestamp]);
  if (!nonceInsert.rowCount) return { ok: false, status: 409, error: 'DEVICE_AUTH_REPLAY' };
  await pool.query('UPDATE devices SET last_seen_at=now() WHERE id=$1', [deviceId]);
  await pool.query('DELETE FROM device_nonces WHERE expires_at <= now()');
  return { ok: true, deviceId };
}

async function createCommand(input, actor = 'api') {
  if (!pool) throw new Error('DATABASE_UNCONFIGURED');
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const existing = await client.query('SELECT id,status FROM commands WHERE idempotency_key=$1 FOR UPDATE', [input.idempotencyKey]);
    if (existing.rowCount) { await client.query('COMMIT'); return existing.rows[0]; }
    const result = await client.query(`INSERT INTO commands(id,type,payload,idempotency_key,status,expires_at,target_device_id) VALUES($1,$2,$3,$4,'PENDING',$5,$6) RETURNING id,status`, [input.id, input.type, input.payload, input.idempotencyKey, new Date(input.expiresAt), input.targetDeviceId || null]);
    await client.query('INSERT INTO command_audit(command_id,to_status,actor,detail) VALUES($1,$2,$3,$4)', [input.id, 'PENDING', actor, { type: input.type, targetDeviceId: input.targetDeviceId || null }]);
    await client.query('COMMIT');
    return result.rows[0];
  } catch (error) { await client.query('ROLLBACK').catch(() => {}); throw error; }
  finally { client.release(); }
}

async function claimNextCommand(actor = 'worker', deviceId = null) {
  if (!pool) throw new Error('DATABASE_UNCONFIGURED');
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    await client.query("UPDATE commands SET status='EXPIRED', updated_at=now() WHERE status='PENDING' AND expires_at <= now()");
    const params = deviceId ? [deviceId] : [];
    const filter = deviceId ? 'AND (target_device_id IS NULL OR target_device_id=$1)' : '';
    const next = await client.query(`SELECT id,type,payload,idempotency_key,expires_at,target_device_id FROM commands WHERE status='PENDING' AND expires_at > now() ${filter} ORDER BY created_at ASC FOR UPDATE SKIP LOCKED LIMIT 1`, params);
    if (!next.rowCount) { await client.query('COMMIT'); return null; }
    const command = next.rows[0];
    const updated = await client.query(`UPDATE commands SET status='CLAIMED', claimed_by=$2, claimed_at=now(), updated_at=now() WHERE id=$1 AND status='PENDING' RETURNING id,type,payload,idempotency_key,expires_at,status,claimed_at,target_device_id,claimed_by`, [command.id, deviceId ? `device:${deviceId}` : actor]);
    if (!updated.rowCount) { await client.query('ROLLBACK').catch(() => {}); return null; }
    await client.query('INSERT INTO command_audit(command_id,from_status,to_status,actor,detail) VALUES($1,$2,$3,$4,$5)', [command.id, 'PENDING', 'CLAIMED', actor, { atomic: true }]);
    await client.query('COMMIT');
    return updated.rows[0];
  } catch (error) { await client.query('ROLLBACK').catch(() => {}); throw error; }
  finally { client.release(); }
}

async function transition(id, target, actor, detail = {}, deviceId = null) {
  if (!pool) throw new Error('DATABASE_UNCONFIGURED');
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const current = await client.query('SELECT status,claimed_by,target_device_id FROM commands WHERE id=$1 FOR UPDATE', [id]);
    if (!current.rowCount) { await client.query('ROLLBACK').catch(() => {}); return null; }
    const row = current.rows[0];
    if (deviceId && row.target_device_id && row.target_device_id !== deviceId) { await client.query('ROLLBACK').catch(() => {}); return { rejected: true, error: 'DEVICE_COMMAND_OWNERSHIP' }; }
    if (deviceId && row.claimed_by && row.claimed_by !== `device:${deviceId}`) { await client.query('ROLLBACK').catch(() => {}); return { rejected: true, error: 'DEVICE_COMMAND_CLAIMED_BY_OTHER' }; }
    const from = row.status;
    if (!allowedTransitions.get(from)?.has(target)) { await client.query('ROLLBACK').catch(() => {}); return { rejected: true, from, target }; }
    const result = await client.query('UPDATE commands SET status=$2, updated_at=now(), executed_at=CASE WHEN $2 IN (\'SUCCEEDED\',\'FAILED\') THEN now() ELSE executed_at END, result=CASE WHEN $2 IN (\'SUCCEEDED\',\'FAILED\') THEN $3::jsonb ELSE result END WHERE id=$1 RETURNING id,status,claimed_by,target_device_id', [id, target, JSON.stringify(detail)]);
    await client.query('INSERT INTO command_audit(command_id,from_status,to_status,actor,detail) VALUES($1,$2,$3,$4,$5)', [id, from, target, actor, detail]);
    await client.query('COMMIT');
    return result.rows[0];
  } catch (error) { await client.query('ROLLBACK').catch(() => {}); throw error; }
  finally { client.release(); }
}

async function readinessProbe() {
  if (!pool) return false;
  const timeout = new Promise((resolve) => setTimeout(() => resolve(false), READY_TIMEOUT_MS));
  const query = pool.query({ text: 'SELECT 1', statement_timeout: READY_TIMEOUT_MS }).then(() => true).catch(() => false);
  return Promise.race([query, timeout]);
}

const server = http.createServer(async (req, res) => {
  try {
    const path = new URL(req.url, 'http://localhost').pathname;
    if (req.method === 'GET' && path === '/health') return send(res, 200, { ok: true, service: 'iac33-backend', status: 'alive' });
    if (req.method === 'GET' && path === '/v1/seismic/latest') {
      try {
        const result = await fetchLatestSeismic();
        return send(res, 200, { ok: true, ...result });
      } catch (error) {
        return send(res, 503, { ok: false, error: error.message || 'SEISMIC_SOURCE_UNAVAILABLE' });
      }
    }
    if (req.method === 'GET' && path === '/ready') {
      if (!pool) return send(res, 503, { ok: false, service: 'iac33-backend', status: 'not_ready', database: false });
      if (await readinessProbe()) return send(res, 200, { ok: true, service: 'iac33-backend', status: 'ready', database: true, deviceAuth: Boolean(devicePairingToken) });
      return send(res, 503, { ok: false, service: 'iac33-backend', status: 'not_ready', database: false });
    }
    if (req.method === 'POST' && path === '/v1/ai/generate') {
      if (!aiAllowed(req)) return send(res, 429, { ok: false, error: 'AI_RATE_LIMITED' });
      const input = await body(req);
      if (!Array.isArray(input.messages) || !input.messages.length || input.messages.length > MAX_AI_MESSAGES || input.messages.some((m) => !m || !['system', 'user', 'assistant'].includes(m.role) || typeof m.content !== 'string' || !m.content.trim() || m.content.length > MAX_AI_MESSAGE_CHARS)) return send(res, 400, { ok: false, error: 'INVALID_AI_REQUEST' });
      const requestedTimeout = Number(input.timeoutMs || 30000);
      const timeoutMs = Number.isFinite(requestedTimeout) ? Math.min(Math.max(requestedTimeout, AI_MIN_TIMEOUT_MS), AI_MAX_TIMEOUT_MS) : 30000;
      try { const result = await generateWithFreePool({ messages: input.messages, timeoutMs }); return send(res, 200, { ok: true, provider: result.provider, model: result.model, text: result.text, diagnostics: result.diagnostics }); }
      catch (error) { return send(res, 503, { ok: false, error: error.message || 'AI_PROVIDERS_UNAVAILABLE', diagnostics: error.diagnostics || [] }); }
    }
    if (req.method === 'POST' && path === '/v1/devices/enroll') {
      const input = await body(req);
      if (!devicePairingToken || !tokenMatches(input.pairingToken, devicePairingToken)) return send(res, 401, { ok: false, error: 'PAIRING_REQUIRED' });
      if (!pool) return send(res, 503, { ok: false, error: 'DATABASE_UNCONFIGURED' });
      if (typeof input.deviceId !== 'string' || !DEVICE_ID_PATTERN.test(input.deviceId) || typeof input.publicKeyPem !== 'string' || !/^-----BEGIN PUBLIC KEY-----[\s\S]+-----END PUBLIC KEY-----\s*$/.test(input.publicKeyPem)) return send(res, 400, { ok: false, error: 'INVALID_DEVICE_IDENTITY' });
      await pool.query('INSERT INTO devices(id,public_key_pem) VALUES($1,$2) ON CONFLICT(id) DO UPDATE SET public_key_pem=EXCLUDED.public_key_pem', [input.deviceId, input.publicKeyPem]);
      return send(res, 201, { ok: true, deviceId: input.deviceId });
    }
    const deviceProtected = path.startsWith('/v1/device/');
    let deviceAuth = null;
    if (deviceProtected) {
      const rawBodyObject = await body(req);
      const canonicalBody = JSON.stringify(rawBodyObject);
      deviceAuth = await verifyDeviceRequest(req, canonicalBody);
      if (!deviceAuth.ok) return send(res, deviceAuth.status, { ok: false, error: deviceAuth.error });
      req.bodyRaw = canonicalBody;
    }
    if (!deviceProtected) {
      const gptBridgePath = req.method === 'POST' && path === '/v1/gpt/commands';
      if (gptBridgePath) {
        if (!(await authorizedGptBridge(req))) return send(res, 401, { ok: false, error: 'UNAUTHORIZED' });
      } else if (!authorized(req)) {
        return send(res, 401, { ok: false, error: 'UNAUTHORIZED' });
      }
    }
    if (req.method === 'POST' && path === '/v1/gpt/commands') {
      const input = await body(req);
      if (!validateGptCommand(input)) return send(res, 400, { ok: false, error: 'INVALID_GPT_COMMAND' });
      try {
        const command = await createCommand(input, 'gpt');
        return send(res, 201, { ok: true, command, digest: commandDigest(input) });
      } catch (error) {
        if (error.message === 'DATABASE_UNCONFIGURED') return send(res, 503, { ok: false, error: 'DATABASE_UNCONFIGURED' });
        throw error;
      }
    }
    if (req.method === 'POST' && path === '/v1/commands') {
      const input = await body(req);
      if (!validCommand(input)) return send(res, 400, { ok: false, error: 'INVALID_COMMAND' });
      return send(res, 201, { ok: true, command: await createCommand(input) });
    }
    if (req.method === 'POST' && path === '/v1/commands/claim-next') {
      const input = await body(req);
      const command = await claimNextCommand(typeof input.actor === 'string' && input.actor.trim() ? input.actor.trim() : 'worker');
      return send(res, 200, { ok: true, command });
    }
    if (req.method === 'POST' && path === '/v1/device/commands/claim-next') {
      const command = await claimNextCommand(`device:${deviceAuth.deviceId}`, deviceAuth.deviceId);
      return send(res, 200, { ok: true, command });
    }
    const deviceMatch = path.match(/^\/v1\/device\/commands\/([^/]+)\/(execute|succeed|fail)$/);
    if (req.method === 'POST' && deviceMatch) {
      const target = { execute: 'EXECUTING', succeed: 'SUCCEEDED', fail: 'FAILED' }[deviceMatch[2]];
      const input = req.bodyRaw ? JSON.parse(req.bodyRaw || '{}') : {};
      const result = await transition(decodeURIComponent(deviceMatch[1]), target, `device:${deviceAuth.deviceId}`, input.detail || {}, deviceAuth.deviceId);
      if (!result) return send(res, 404, { ok: false, error: 'NOT_FOUND' });
      if (result.rejected) return send(res, 409, { ok: false, error: result.error || 'INVALID_TRANSITION', ...result });
      return send(res, 200, { ok: true, command: result });
    }
    const match = path.match(/^\/v1\/commands\/([^/]+)\/(claim|execute|succeed|fail)$/);
    if (req.method === 'POST' && match) {
      const target = { claim: 'CLAIMED', execute: 'EXECUTING', succeed: 'SUCCEEDED', fail: 'FAILED' }[match[2]];
      const result = await transition(decodeURIComponent(match[1]), target, 'api');
      if (!result) return send(res, 404, { ok: false, error: 'NOT_FOUND' });
      if (result.rejected) return send(res, 409, { ok: false, error: result.error || 'INVALID_TRANSITION', ...result });
      return send(res, 200, { ok: true, command: result });
    }
    return send(res, 404, { ok: false, error: 'NOT_FOUND' });
  } catch (error) {
    if (res.headersSent) return;
    const status = Number.isInteger(error?.status) ? error.status : 500;
    const code = error?.message === 'DATABASE_UNCONFIGURED' ? 'DATABASE_UNCONFIGURED' : error?.message === 'BODY_TOO_LARGE' ? 'BODY_TOO_LARGE' : error?.message === 'INVALID_JSON' ? 'INVALID_JSON' : 'INTERNAL_ERROR';
    return send(res, status, { ok: false, error: code });
  }
});

server.on('clientError', (_error, socket) => socket.destroy());

export { server, allowedTransitions, validCommand };