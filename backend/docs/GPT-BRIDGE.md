# GPT ↔ IAC33 command bridge

The GPT-facing control path is intentionally fail-closed. Commands must use an authenticated control request, an allowlisted command type, a bounded JSON payload, a future expiry within 24 hours, and an idempotency key.

Execution remains asynchronous: the control plane creates a `PENDING` command; the device claims it and advances it through the existing audited lifecycle. GPT does not receive direct device credentials or direct device access.

Supported command types: `sync`, `update`, `device.action`.
