# IAC33 — Module Matrix

## Dependency direction
`app -> feature -> domain contracts -> core`; infrastructure implements contracts. No feature imports another feature implementation.

| Module | Responsibility | May depend on |
|---|---|---|
| core | lifecycle, result/error, config, logging, security, versioning | stdlib/platform abstractions |
| data-local | durable local storage, migrations, repositories | core |
| data-sync | queue, synchronization, conflict handling | core, data-local, network contracts |
| network | connectivity, HTTP policy, offline queue transport | core |
| ai | AI contract, routing, failover, policy, quotas | core, network contracts |
| memory | conversation and persistent memory | core, data-local |
| location | permissions and location provider | core, Android location API |
| seismic | Chile event ingestion/normalization/analysis | core, network, data-local |
| c33 | research/projects/evidence/content | core, data-local, media contracts |
| media | media abstraction, jobs, assets | core, data-local |
| control | command validation, execution policy, audit | core, network, data-local |
| update | manifest, digest/signature, staging, activation, rollback | core, network, data-local |
| diagnostics | health, metrics, traces, crash/recovery evidence | core, data-local |
| app | Compose UI, navigation, dependency wiring | all public contracts; never infrastructure internals |

## Runtime isolation
AI, seismic, media and remote commands execute as bounded jobs. Long work cannot block the UI thread. Each job has cancellation, timeout, retry policy and durable status.

## Plugin boundary
Plugins are capability descriptors plus signed, versioned implementations permitted by an allowlist. A plugin cannot access arbitrary filesystem, credentials, location or control-plane commands.

## Native/OTA boundary
The Master APK contains the trusted execution core and verifier. OTA-updatable material is limited to signed configuration, datasets, prompts/policies, UI/content assets and explicitly sandboxed runtime resources. Native executable changes require an Android package update.
