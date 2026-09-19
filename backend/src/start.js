import { readFile } from 'node:fs/promises';
import { server } from './server.js';
import { Pool } from 'pg';

const port = Number(process.env.PORT || 3000);
const databaseUrl = process.env.DATABASE_URL || '';
const databaseNeedsSsl = databaseUrl && !/(localhost|127\.0\.0\.1)(:|\/|$)/i.test(databaseUrl);
const STARTUP_DB_TIMEOUT_MS = 2_000;
const STARTUP_DB_RETRIES = 5;
const STARTUP_DB_BACKOFF_MS = 500;

const startupPool = databaseUrl
  ? new Pool({
      connectionString: normalizeDatabaseUrl(databaseUrl),
      
      connectionTimeoutMillis: STARTUP_DB_TIMEOUT_MS,
      idleTimeoutMillis: 10_000,
      max: 1
    })
  : null;

function shutdown(signal) {
  console.log(`IAC33 backend received ${signal}; shutting down`);
  server.close((error) => {
    if (error) {
      console.error('IAC33 backend shutdown failed', error);
      process.exitCode = 1;
    }
    startupPool?.end().catch(() => {});
    process.exit();
  });
  setTimeout(() => process.exit(1), 25_000).unref();
}

async function ensureDatabaseSchema() {
  if (!startupPool) return;
  const schemaUrl = new URL('../schema.sql', import.meta.url);
  const schemaSql = await readFile(schemaUrl, 'utf8');
  await startupPool.query('BEGIN');
  try {
    await startupPool.query(schemaSql);
    await startupPool.query('COMMIT');
  } catch (error) {
    await startupPool.query('ROLLBACK').catch(() => {});
    throw error;
  }
}

async function warmDatabase() {
  if (!startupPool) {
    console.warn('IAC33 database warmup skipped: DATABASE_URL is not configured');
    return;
  }
  for (let attempt = 1; attempt <= STARTUP_DB_RETRIES; attempt += 1) {
    try {
      await startupPool.query({ text: 'SELECT 1', statement_timeout: STARTUP_DB_TIMEOUT_MS });
      await ensureDatabaseSchema();
      await startupPool.query({ text: 'SELECT 1', statement_timeout: STARTUP_DB_TIMEOUT_MS });
      console.log(`IAC33 database ready and schema verified on startup attempt ${attempt}`);
      return;
    } catch (error) {
      console.error(`IAC33 database startup attempt ${attempt}/${STARTUP_DB_RETRIES} failed`, error?.message || error);
      if (attempt < STARTUP_DB_RETRIES) {
        const delay = STARTUP_DB_BACKOFF_MS * (2 ** (attempt - 1));
        await new Promise((resolve) => setTimeout(resolve, delay));
      }
    }
  }
  console.error('IAC33 database warmup exhausted; HTTP server remains alive and /ready will report not_ready');
}

server.on('error', (error) => {
  console.error('IAC33 backend server error', error);
  process.exitCode = 1;
});

process.once('SIGTERM', () => shutdown('SIGTERM'));
process.once('SIGINT', () => shutdown('SIGINT'));

server.listen(port, '0.0.0.0', () => {
  console.log(`IAC33 backend listening on ${port}`);
  void warmDatabase();
});
