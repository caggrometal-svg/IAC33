# IAC33 — Control Plane Contract v1

Remote commands are requests, not direct code execution.

Required fields: commandId, deviceId, commandType, schemaVersion, payload, createdAt, expiresAt, idempotencyKey, authorization context.

Lifecycle:
PENDING → CLAIMED → EXECUTING → SUCCEEDED | FAILED | EXPIRED | REJECTED

Duplicate idempotency keys never execute twice. Expired commands are rejected. Payloads are schema-validated. Privileged operations require authorization. Every transition is audited. Arbitrary remote code execution is prohibited.
