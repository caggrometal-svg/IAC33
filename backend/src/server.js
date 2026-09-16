import http from 'node:http';
import crypto from 'node:crypto';
import { Pool } from 'pg';
import { allowedTransitions, validCommand } from './command-core.js';
import { generateWithFreePool } from './ai-router.js';

const port = Number(process.env.PORT || 3000);
const controlToken = process.env.CONTROL_TOKEN || '';
const devicePairingToken = process.env.DEVICE_PAIRING_TOKEN || '';
const databaseUrl = process.env.DATABASE_URL || '';
const databaseNeedsSsl = databaseUrl && !/(localhost|127\.0\.0\.1)(:|\/|$)/i.test(databaseUrl);
const pool = databaseUrl ? new Pool({ connectionString: databaseUrl, ...(databaseNeedsSsl ? { ssl: { rejectUnauthorized: false } } : {}), connectionTimeoutMillis: 5000, idleTimeoutMillis: 10000, max: 5 }) : null;
const aiWindow = new Map();

function authorized(req) {
  if (!controlToken) return false;
  const value = req.headers.authorization || '';
  const expected = Buffer.from(`Bearer ${controlToken}`);
  const actual = Buffer.from(value);
  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
}

function send(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(data);
}

function clientKey(req) {
  return String(req.headers['x-forwarded-for'] || req.socket.remoteAddress || 'unknown').split(',')[0].trim();
}

function aiAllowed(req) {
  const key = clientKey(req);
  const now = Date.now();
  const previous = aiWindow.get(key) || [];
  const recent = previous.filter((time) => now - time < 60_000);
  if (recent.length >= Number(process.env.AI_REQUESTS_PER_MINUTE || 20)) return false;
  recent.push(now);
  aiWindow.set(key, recent);
  return true;
}

async function body(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  if (!chunks.length) return {};
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

function tokenMatches(value, expected) {
  if (!expected || typeof value !== 'string') return false;
  const a = Buffer.from(value);
  const b = Buffer.from(expected);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function canonicalRequest(method, path, timestamp, nonce, rawBody) {
  return [method.toUpperCase(), path, String(timestamp), nonce, rawBody].join('\n');
}

async function verifyDeviceRequest(req, rawBody) {
  if (!pool) return { ok: false, status: 503, error: 'DATABASE_UNCONFIGURED' };
  const deviceId = String(req.headers['x-device-id'] || '');
  const timestamp = Number(req.headers['x-device-timestamp']);
  const nonce = String(req.headers['x-device-nonce'] || '');
  const signature = String(req.headers['x-device-signature'] || '');
  if (!deviceId || !Number.isSafeInteger(timestamp) || !nonce || !signature) return { ok: false, status: 401, error: 'DEVICE_AUTH_REQUIRED' };
  const skewMs = Number(process.env.DEVICE_AUTH_SKEW_MS || 120000);
  if (Math.abs(Date.now() - timestamp) > skewMs) return { ok: false, status: 401, error: 'DEVICE_AUTH_EXPIRED' };
  if (!/^[A-Za-z0-9._:-]{16,128}$/.test(nonce)) return { ok: false, status: 401, error: 'DEVICE_AUTH_INVALID_NONCE' };
  const result = await pool.query('SELECT public_key_pem FROM devices WHERE id=$1', [deviceId]);
  if (!result.rowCount) return { ok: false, status: 401, error: 'DEVICE_UNKNOWN' };
  const message = canonicalRequest(req.method, new URL(req.url, 'http://localhost').pathname, timestamp, nonce, rawBody);
  let valid = false;
  try {
    valid = crypto.verify('sha256', Buffer.from(message), result.rows[0].public_key_pem, Buffer.from(signature, 'base64'));
  } catch { valid = false; }
  if (!valid) return { ok: false, status: 401, error: 'DEVICE_AUTH_INVALID_SIGNATURE' };
  const nonceInsert = await pool.query(
    `INSERT INTO device_nonces(device_id,nonce,expires_at) VALUES($1,$2,to_timestamp($3/1000.0)+interval '2 minutes') ON CONFLICT DO NOTHING RETURNING nonce`,
    [deviceId, nonce, timestamp]
  );
  if (!nonceInsert.rowCount) return { ok: false, status: 409, error: 'DEVICE_AUTH_REPLAY' };
  await pool.query('UPDATE devices SET last_seen_at=now() WHERE id=$1', [deviceId]);
  await pool.query('DELETE FROM device_nonces WHERE expires_at <= now()');
  return { ok: true, deviceId };
}

async function createCommand(input) {
  if (!pool) throw new Error('DATABASE_UNCONFIGURED');
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const existing = await client.query('SELECT id,status FROM commands WHERE idempotency_key=$1 FOR UPDATE', [input.idempotencyKey]);
    if (existing.rowCount) {
      await client.query('COMMIT');
      return existing.rows[0];
    }
    const result = await client.query(
      `INSERT INTO commands(id,type,payload,idempotency_key,status,expires_at) VALUES($1,$2,$3,$4,'PENDING',$5) RETURNING id,status`,
      [input.id, input.type, input.payload, input.idempotencyKey, new Date(input.expiresAt)]
    );
    await client.query('INSERT INTO command_audit(command_id,to_status,actor,detail) VALUES($1,$2,$3,$4)', [input.id, 'PENDING', 'api', { type: input.type }]);
    await client.query('COMMIT');
    return result.rows[0];
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally { client.release(); }
}

async function claimNextCommand(actor = 'worker') {
  if (!pool) throw new Error('DATABASE_UNCONFIGURED');
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    await client.query("UPDATE commands SET status='EXPIRED', updated_at=now() WHERE status='PENDING' AND expires_at <= now()");
    const next = await client.query(
      `SELECT id,type,payload,idempotency_key,expires_at
       FROM commands
       WHERE status='PENDING' AND expires_at > now()
       ORDER BY created_at ASC
       FOR UPDATE SKIP LOCKED
       LIMIT 1`
    );
    if (!next.rowCount) {
      await client.query('COMMIT');
      return null;
    }
    const command = next.rows[0];
    const updated = await client.query(
      `UPDATE commands SET status='CLAIMED', claimed_at=now(), updated_at=now()
       WHERE id=$1 AND status='PENDING'
       RETURNING id,type,payload,idempotency_key,expires_at,status,claimed_at`, [command.id]
    );
    if (!updated.rowCount) { await client.query('ROLLBACK'); return null; }
    await client.query('INSERT INTO command_audit(command_id,from_status,to_status,actor,detail) VALUES($1,$2,$3,$4,$5)', [command.id, 'PENDING', 'CLAIMED', actor, { atomic: true }]);
    await client.query('COMMIT');
    return updated.rows[0];
  } catch (error) { await client.query('ROLLBACK'); throw error; }
  finally { client.release(); }
}

async function transition(id, target, actor, detail = {}) {
  if (!pool) throw new Error('DATABASE_UNCONFIGURED');
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const current = await client.query('SELECT status FROM commands WHERE id=$1 FOR UPDATE', [id]);
    if (!current.rowCount) { await client.query('ROLLBACK'); return null; }
    const from = current.rows[0].status;
    if (!allowedTransitions.get(from)?.has(target)) { await client.query('ROLLBACK'); return { rejected: true, from, target }; }
    const result = await client.query('UPDATE commands SET status=$2, updated_at=now(), executed_at=CASE WHEN $2 IN (\'SUCCEEDED\',\'FAILED\') THEN now() ELSE executed_at END, result=CASE WHEN $2 IN (\'SUCCEEDED\',\'FAILED\') THEN $3::jsonb ELSE result END WHERE id=$1 RETURNING id,status', [id, target, JSON.stringify(detail)]);
    await client.query('INSERT INTO command_audit(command_id,from_status,to_status,actor,detail) VALUES($1,$2,$3,$4,$5)', [id, from, target, actor, detail]);
    await client.query('COMMIT');
    return result.rows[0];
  } catch (error) { await client.query('ROLLBACK'); throw error; }
  finally { client.release(); }
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/health') {
      let database = false;
      if (pool) {
        try { await pool.query({ text: 'SELECT 1', statement_timeout: 4000 }); database = true; } catch { database = false; }
      }
      return send(res, database ? 200 : 503, { ok: database, service: 'iac33-backend', database, deviceAuth: Boolean(database && devicePairingToken) });
    }

    if (req.method === 'POST' && req.url === '/v1/ai/generate') {
      if (!aiAllowed(req)) return send(res, 429, { ok: false, error: 'AI_RATE_LIMITED' });
      const input = await body(req);
      if (!Array.isArray(input.messages) || !input.messages.length || input.messages.some((m) => !m || typeof m.content !== 'string' || !m.content.trim())) return send(res, 400, { ok: false, error: 'INVALID_AI_REQUEST' });
      try {
        const result = await generateWithFreePool({ messages: input.messages, timeoutMs: Math.min(Number(input.timeoutMs || 30000), 45000) });
        return send(res, 200, { ok: true, provider: result.provider, model: result.model, text: result.text, diagnostics: result.diagnostics });
      } catch (error) { return send(res, 503, { ok: false, error: error.message || 'AI_PROVIDERS_UNAVAILABLE', diagnostics: error.diagnostics || [] }); }
    }

    if (req.method === 'POST' && req.url === '/v1/devices/enroll') {
      const input = await body(req);
      if (!devicePairingToken || !tokenMatches(input.pairingToken, devicePairingToken)) return send(res, 401, { ok: false, error: 'PAIRING_REQUIRED' });
      if (!pool) return send(res, 503, { ok: false, error: 'DATABASE_UNCONFIGURED' });
      if (typeof input.deviceId !== 'string' || !/^[A-Za-z0-9._:-]{16,128}$/.test(input.deviceId) || typeof input.publicKeyPem !== 'string' || !input.publicKeyPem.includes('PUBLIC KEY')) return send(res, 400, { ok: false, error: 'INVALID_DEVICE_IDENTITY' });
      await pool.query('INSERT INTO devices(id,public_key_pem) VALUES($1,$2) ON CONFLICT(id) DO UPDATE SET public_key_pem=EXCLUDED.public_key_pem', [input.deviceId, input.publicKeyPem]);
      return send(res, 201, { ok: true, deviceId: input.deviceId });
    }

    const deviceProtected = req.url.startsWith('/v1/device/');
    let deviceAuth = null;
    if (deviceProtected) {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const rawBody = Buffer.concat(chunks).toString('utf8');
      deviceAuth = await verifyDeviceRequest(req, rawBody);
      if (!deviceAuth.ok) return send(res, deviceAuth.status, { ok: false, error: deviceAuth.error });
      req.bodyRaw = rawBody;
    }

    if (!deviceProtected && !authorized(req)) return send(res, 401, { ok: false, error: 'UNAUTHORIZED' });

    if (req.method === 'POST' && req.url === '/v1/commands') {
      const input = await body(req);
      if (!validCommand(input)) return send(res, 400, { ok: false, error: 'INVALID_COMMAND' });
      return send(res, 201, { ok: true, command: await createCommand(input) });
    }

    if (req.method === 'POST' && req.url === '/v1/commands/claim-next') {
      const input = await body(req);
      const command = await claimNextCommand(typeof input.actor === 'string' && input.actor.trim() ? input.actor.trim() : 'worker');
      return send(res, 200, { ok: true, command });
    }

    if (req.method === 'POST' && req.url === '/v1/device/commands/claim-next') {
      const command = await claimNextCommand(`device:${deviceAuth.deviceId}`);
      return send(res, 200, { ok: true, command });
    }

    const deviceMatch = req.url.match(/^\/v1\/device\/commands\/([^/]+)\/(execute|succeed|fail)$/);
    if (req.method === 'POST' && deviceMatch) {
      const target = { execute: 'EXECUTING', succeed: 'SUCCEEDED', fail: 'FAILED' }[deviceMatch[2]];
      const input = req.bodyRaw ? JSON.parse(req.bodyRaw || '{}') : {};
      const result = await transition(decodeURIComponent(deviceMatch[1]), target, `device:${deviceAuth.deviceId}`, input.detail || {});
      if (!result) return send(res, 404, { ok: false, error: 'NOT_FOUND' });
      if (result.rejected) return send(res, 409, { ok: false, error: 'INVALID_TRANSITION', ...result });
      return send(res, 200, { ok: true, command: result });
    }

    const match = req.url.match(/^\/v1\/commands\/([^/]+)\/(claim|execute|succeed|fail)$/);
    if (req.method === 'POST' && match) {
      const target = { claim: 'CLAIMED', execute: 'EXECUTING', succeed: 'SUCCEEDED', fail: 'FAILED' }[match[2]];
      const result = await transition(decodeURIComponent(match[1]), target, 'api');
      if (!result) return send(res, 404, { ok: false, error: 'NOT_FOUND' });
      if (result.rejected) return send(res, 409, { ok: false, error: 'INVALID_TRANSITION', ...result });
      return send(res, 200, { ok: true, command: result });
    }
    return send(res, 404, { ok: false, error: 'NOT_FOUND' });
  } catch (error) {
    const known = error?.message === 'DATABASE_UNCONFIGURED';
    return send(res, known ? 503 : 500, { ok: false, error: known ? error.message : 'INTERNAL_ERROR' });
  }
});

export { server, allowedTransitions, validCommand };
