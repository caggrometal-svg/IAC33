# IAC33 — Contracts

Version: 1

These contracts are the stable boundaries between modules. Implementations may change; contracts change only through an explicit versioned decision.

## Modules

- core: lifecycle, configuration, diagnostics, security primitives, versioning
- data: local persistence and repositories
- network: connectivity state, requests, retry and offline queue
- ai: model abstraction, routing and provider-independent execution
- memory: conversations and long-term memory
- location: permission and location abstraction
- seismic: Chile seismic ingestion, normalization and analysis
- c33: research, projects and content workflows
- media: image/video/audio operations
- update: manifest, verification, apply and rollback
- control: authenticated remote commands and audit

## Boundary rules

1. UI depends on feature interfaces, never concrete infrastructure.
2. Features depend on core contracts, never on another feature's internals.
3. Remote data is untrusted until validated.
4. Updates are immutable artifacts identified by release ID and content digest.
5. Commands are idempotent and auditable.
6. Offline operations remain durable until synchronization succeeds.
