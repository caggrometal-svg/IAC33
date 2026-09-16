# IAC33 — Power Base

This document defines the non-negotiable engineering baseline for the complete application.

## Runtime qualities
- Offline-first operation.
- Durable jobs and synchronization.
- Cancellation and bounded retries.
- Structured diagnostics and trace IDs.
- Deterministic state machines for commands and updates.
- Crash-safe recovery.
- Feature isolation and versioned contracts.

## Intelligence
- Provider-independent AI abstraction.
- Router with health, latency, quota and failure signals.
- Local-model capability reserved as a first-class backend.
- No-provider mode remains functional.
- AI outputs retain model/provider provenance.
- No claim of universally unlimited free inference; the system must degrade gracefully when external capacity is unavailable.

## Data
- Local durable source of truth for offline operation.
- Explicit synchronization and conflict policy.
- Provenance for research and seismic data.
- Versioned schemas and migrations.
- Export and restore.

## Control
- GPT communicates through typed commands, not arbitrary code.
- Every privileged operation is authenticated, authorized, validated, idempotent and audited.
- Dry-run/validation precedes mutation where supported.
- Dangerous operations require explicit policy approval.

## Updates
- Signed immutable releases.
- Digest verification before activation.
- Compatibility checks.
- Staged activation.
- Health confirmation.
- Automatic rollback.
- Release history and audit evidence.

## Quality
- No bypass of gates.
- No "green" status without executable evidence.
- Failure paths are tested as seriously as success paths.
- Production configuration is separate from development configuration.
- Observability is part of every module, not an afterthought.

## Build target
The final system is a stable Android Master APK plus a maximized OTA-updatable runtime/content layer, a resilient backend/control plane, durable local data, and independently replaceable AI/data providers. Native-core changes remain subject to Android package update constraints.
