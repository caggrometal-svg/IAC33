import http from 'node:http';
import crypto from 'node:crypto';
import { Pool } from 'pg';

const port = Number(process.env.PORT || 3000);
const controlToken = process.env.CONTROL_TOKEN || '';
const databaseUrl = process.env.DATABASE_URL || '';
const pool = databaseUrl ? new Pool({ connectionString: databaseUrl, ssl: { rejectUnauthorized: false } }) : null;

const allowedTransitions = new Map([
  ['PENDING', new Set(['CLAIMED', 'EXPIRED', 'REJECTED'])],
  ['CLAIMED', new Set(['EXECUTING', 'FAILED', 'EXPIRED'])],
  ['EXECUTING', new Set(['SUCCEEDED', 'FAILED'])]
]);

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

async function body(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  if (!chunks.length) return {};
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

function validCommand(input) {
  return input && typeof input.id === 'string' && input.id.length > 0 && input.id.length <= 128 &&
    typeof input.type === 'string' && /^[A-Za-z0-9._:-]{1,64}$/.test(input.type) &&
    input.payload && typeof input.payload === 'object' &&
    typeof input.idempotencyKey === 'string' && /^[A-Za-z0-9._:-]{8,128}$/.test(input.idempotencyKey) &&
    Number.isFinite(Date.parse(input.expiresAt));
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

async function transition(id, target, actor, detail = {}) {
  if (!pool) throw new Error('DATABASE_UNCONFIGURED');
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const current = await client.query('SELECT status FROM commands WHERE id=$1 FOR UPDATE', [id]);
    if (!current.rowCount) { await client.query('ROLLBACK'); return null; }
    const from = current.rows[0].status;
    if (!allowedTransitions.get(from)?.has(target)) { await client.query('ROLLBACK'); return { rejected: true, from, target }; }
    const result = await client.query('UPDATE commands SET status=$2, updated_at=now(), claimed_at=CASE WHEN $2=\'CLAIMED\' THEN now() ELSE claimed_at END, executed_at=CASE WHEN $2 IN (\'SUCCEEDED\',\'FAILED\') THEN now() ELSE executed_at END, result=CASE WHEN $2 IN (\'SUCCEEDED\',\'FAILED\') THEN $3::jsonb ELSE result END WHERE id=$1 RETURNING id,status', [id, target, JSON.stringify(detail)]);
    await client.query('INSERT INTO command_audit(command_id,from_status,to_status,actor,detail) VALUES($1,$2,$3,$4,$5)', [id, from, target, actor, detail]);
    await client.query('COMMIT');
    return result.rows[0];
  } catch (error) { await client.query('ROLLBACK'); throw error; }
  finally { client.release(); }
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/health') return send(res, 200, { ok: true, service: 'iac33-backend', database: Boolean(pool) });
    if (!authorized(req)) return send(res, 401, { ok: false, error: 'UNAUTHORIZED' });

    if (req.method === 'POST' && req.url === '/v1/commands') {
      const input = await body(req);
      if (!validCommand(input)) return send(res, 400, { ok: false, error: 'INVALID_COMMAND' });
      return send(res, 201, { ok: true, command: await createCommand(input) });
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

if (process.env.NODE_ENV !== 'test') server.listen(port, () => console.log(`IAC33 backend listening on ${port}`));

export { allowedTransitions, validCommand };
