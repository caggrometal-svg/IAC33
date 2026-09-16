CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  public_key_pem TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS devices_last_seen_idx ON devices(last_seen_at);

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
