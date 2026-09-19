CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  public_key_pem TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at TIMESTAMPTZ,
  app_version TEXT
);
CREATE INDEX IF NOT EXISTS devices_last_seen_idx ON devices(last_seen_at);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS app_version TEXT;

CREATE TABLE IF NOT EXISTS commands (
  id TEXT PRIMARY KEY,
  type TEXT NOT NULL,
  payload JSONB NOT NULL,
  idempotency_key TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL CHECK (status IN ('PENDING','CLAIMED','EXECUTING','SUCCEEDED','FAILED','EXPIRED','REJECTED')),
  expires_at TIMESTAMPTZ NOT NULL,
  target_device_id TEXT REFERENCES devices(id) ON DELETE SET NULL,
  claimed_by TEXT,
  claimed_at TIMESTAMPTZ,
  executed_at TIMESTAMPTZ,
  result JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE commands ADD COLUMN IF NOT EXISTS type TEXT;
ALTER TABLE commands ADD COLUMN IF NOT EXISTS payload JSONB;
ALTER TABLE commands ADD COLUMN IF NOT EXISTS idempotency_key TEXT;
ALTER TABLE commands ADD COLUMN IF NOT EXISTS status TEXT;
ALTER TABLE commands ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE commands ADD COLUMN IF NOT EXISTS target_device_id TEXT;
ALTER TABLE commands ADD COLUMN IF NOT EXISTS claimed_by TEXT;
ALTER TABLE commands ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;
ALTER TABLE commands ADD COLUMN IF NOT EXISTS executed_at TIMESTAMPTZ;
ALTER TABLE commands ADD COLUMN IF NOT EXISTS result JSONB;
ALTER TABLE commands ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ DEFAULT now();
ALTER TABLE commands ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now();
UPDATE commands
SET idempotency_key = 'legacy-' || id
WHERE idempotency_key IS NULL;
ALTER TABLE commands ALTER COLUMN idempotency_key SET NOT NULL;

CREATE INDEX IF NOT EXISTS commands_pending_idx ON commands(status, created_at);
CREATE INDEX IF NOT EXISTS commands_expiry_idx ON commands(expires_at);
CREATE INDEX IF NOT EXISTS commands_target_device_idx ON commands(target_device_id, status, created_at);

CREATE TABLE IF NOT EXISTS command_audit (
  id BIGSERIAL PRIMARY KEY,
  command_id TEXT NOT NULL REFERENCES commands(id) ON DELETE CASCADE,
  from_status TEXT,
  to_status TEXT NOT NULL,
  actor TEXT NOT NULL,
  detail JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS command_audit_command_idx ON command_audit(command_id, created_at);

CREATE TABLE IF NOT EXISTS device_nonces (
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  nonce TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (device_id, nonce)
);
CREATE INDEX IF NOT EXISTS device_nonces_expiry_idx ON device_nonces(expires_at);

CREATE TABLE IF NOT EXISTS media_assets (
  id TEXT PRIMARY KEY,
  object_key TEXT NOT NULL UNIQUE,
  mime_type TEXT NOT NULL,
  size_bytes BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS media_assets_created_idx ON media_assets(created_at);

CREATE TABLE IF NOT EXISTS media_jobs (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  status TEXT NOT NULL,
  provider TEXT,
  asset_id TEXT,
  error_code TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS media_jobs_status_idx ON media_jobs(status, created_at);
