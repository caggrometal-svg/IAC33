# IAC33 — Verification Matrix

A feature is incomplete until its gate has executable evidence.

| Area | Required verification |
|---|---|
| Core | lifecycle, cancellation, errors, config validation |
| Storage | migration, corruption recovery, transaction rollback |
| Network | online/degraded/offline, timeout, retry, queue replay |
| AI | routing, provider failure, rate limit, timeout, no-provider mode |
| Memory | persistence, search, deletion, export/restore, isolation |
| GPS | permission denial/grant, unavailable provider, stale location |
| Seismic | source validation, deduplication, normalization, uncertainty |
| C33 | project recovery, evidence provenance, asset integrity |
| Control | auth, schema, replay, idempotency, authorization, audit |
| OTA | hash, signature, compatibility, interrupted download, rollback |
| Security | secret scan, transport, permission boundaries, redaction |
| Android | clean build, install, startup, rotation/process death, offline |
| E2E | GPT request -> backend -> command -> app -> result -> audit |

## Blockers
Critical failures block release. A green UI indicator is never sufficient evidence; the underlying test/result must be retained.

## Required evidence
Commit SHA, test command, environment, result, artifact/release ID, timestamp and diagnostic/trace ID when applicable.

## Recovery tests
Every stateful subsystem must be tested after forced process termination, network loss and restart. OTA must additionally test interruption during download, staging and activation.
