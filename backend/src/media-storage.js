import { DeleteObjectCommand, GetObjectCommand, PutObjectCommand, S3Client } from '@aws-sdk/client-s3';
import { Pool } from 'pg';

const enabled = String(process.env.IAC33_MEDIA_PERSISTENCE || 'false').toLowerCase() === 'true';
const bucket = String(process.env.IAC33_MEDIA_BUCKET || '').trim();
const endpoint = String(process.env.AWS_ENDPOINT_URL_S3 || '').trim();
const region = String(process.env.AWS_REGION || 'us-east-2').trim();
const accessKeyId = String(process.env.AWS_ACCESS_KEY_ID || '').trim();
const secretAccessKey = String(process.env.AWS_SECRET_ACCESS_KEY || '').trim();
const databaseUrl = String(process.env.DATABASE_URL || '').trim();

function normalizeDatabaseUrl(value) {
  if (!value) return value;
  try {
    const parsed = new URL(value);
    if (!/(localhost|127\.0\.0\.1)(:|\/|$)/i.test(parsed.hostname)) parsed.searchParams.set('sslmode', 'verify-full');
    return parsed.toString();
  } catch { return value; }
}

const pool = databaseUrl ? new Pool({ connectionString: normalizeDatabaseUrl(databaseUrl), connectionTimeoutMillis: 2000, idleTimeoutMillis: 10000, max: 3 }) : null;
const s3 = enabled && bucket && endpoint && accessKeyId && secretAccessKey ? new S3Client({ region, endpoint, forcePathStyle: true, credentials: { accessKeyId, secretAccessKey } }) : null;
let readyPromise = null;

export function mediaPersistenceEnabled() { return Boolean(enabled && pool && s3); }

async function initSchema() {
  if (!pool) return;
  await pool.query('CREATE TABLE IF NOT EXISTS media_assets (id TEXT PRIMARY KEY, object_key TEXT NOT NULL UNIQUE, mime_type TEXT NOT NULL, size_bytes BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())');
  await pool.query('CREATE INDEX IF NOT EXISTS media_assets_created_idx ON media_assets(created_at)');
  await pool.query('CREATE TABLE IF NOT EXISTS media_jobs (id TEXT PRIMARY KEY, kind TEXT NOT NULL, status TEXT NOT NULL, provider TEXT, asset_id TEXT, error_code TEXT, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL)');
  await pool.query('CREATE INDEX IF NOT EXISTS media_jobs_status_idx ON media_jobs(status, created_at)');
}

export async function initMediaPersistence() {
  if (!mediaPersistenceEnabled()) return;
  if (!readyPromise) readyPromise = initSchema().catch((error) => { readyPromise = null; throw error; });
  await readyPromise;
}

export function mediaObjectKey(id, extension) {
  return 'generated/' + encodeURIComponent(id) + '.' + String(extension || 'bin').replace(/^\\./, '');
}

export async function persistAsset({ id, buffer, mimeType, extension, createdAt }) {
  if (!mediaPersistenceEnabled()) return null;
  await initMediaPersistence();
  const objectKey = mediaObjectKey(id, extension);
  await s3.send(new PutObjectCommand({ Bucket: bucket, Key: objectKey, Body: buffer, ContentType: mimeType, CacheControl: 'private, max-age=3600' }));
  await pool.query('INSERT INTO media_assets(id,object_key,mime_type,size_bytes,created_at) VALUES($1,$2,$3,$4,to_timestamp($5/1000.0)) ON CONFLICT(id) DO UPDATE SET object_key=EXCLUDED.object_key,mime_type=EXCLUDED.mime_type,size_bytes=EXCLUDED.size_bytes', [id, objectKey, mimeType, buffer.length, createdAt || Date.now()]);
  return { id, objectKey, mimeType, size: buffer.length, createdAt: createdAt || Date.now() };
}

export async function readPersistedAsset(id) {
  if (!mediaPersistenceEnabled()) return null;
  await initMediaPersistence();
  const result = await pool.query('SELECT id,object_key,mime_type,size_bytes,extract(epoch from created_at)*1000 AS created_ms FROM media_assets WHERE id=$1', [id]);
  if (!result.rowCount) return null;
  const row = result.rows[0];
  try {
    const object = await s3.send(new GetObjectCommand({ Bucket: bucket, Key: row.object_key }));
    const bytes = object.Body?.transformToByteArray ? Buffer.from(await object.Body.transformToByteArray()) : Buffer.from(await new Response(object.Body).arrayBuffer());
    return { id: row.id, filePath: null, mimeType: row.mime_type, size: Number(row.size_bytes), createdAt: Number(row.created_ms), data: bytes };
  } catch { return null; }
}

export async function persistMediaJob(job) {
  if (!mediaPersistenceEnabled()) return;
  await initMediaPersistence();
  await pool.query('INSERT INTO media_jobs(id,kind,status,provider,asset_id,error_code,created_at,updated_at) VALUES($1,$2,$3,$4,$5,$6,to_timestamp($7/1000.0),to_timestamp($8/1000.0)) ON CONFLICT(id) DO UPDATE SET kind=EXCLUDED.kind,status=EXCLUDED.status,provider=EXCLUDED.provider,asset_id=EXCLUDED.asset_id,error_code=EXCLUDED.error_code,updated_at=EXCLUDED.updated_at', [job.id,job.kind,job.status,job.provider,job.assetId,job.errorCode || null,job.createdAt,job.updatedAt]);
}

export async function readPersistedJob(id) {
  if (!mediaPersistenceEnabled()) return null;
  await initMediaPersistence();
  const result = await pool.query('SELECT id,kind,status,provider,asset_id,error_code,extract(epoch from created_at)*1000 AS created_ms,extract(epoch from updated_at)*1000 AS updated_ms FROM media_jobs WHERE id=$1', [id]);
  if (!result.rowCount) return null;
  const row = result.rows[0];
  return { id:row.id,kind:row.kind,status:row.status,provider:row.provider,assetId:row.asset_id,errorCode:row.error_code,createdAt:Number(row.created_ms),updatedAt:Number(row.updated_ms) };
}

export async function markInterruptedMediaJobs() {
  if (!mediaPersistenceEnabled()) return;
  await initMediaPersistence();
  await pool.query("UPDATE media_jobs SET status='FAILED', error_code='MEDIA_PROCESS_RESTART', updated_at=now() WHERE status IN ('QUEUED','RUNNING')");
}