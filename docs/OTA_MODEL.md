# IAC33 — OTA Model

## Principle
OTA is designed from day zero but is not treated as arbitrary remote native-code execution.

## Master APK
Contains: trusted core, module contracts, security verifier, recovery engine, local persistence, OTA client and minimal UI shell.

## OTA payload classes
- configuration
- signed policies/prompts
- datasets
- content/assets
- migrations explicitly supported by the installed core
- sandboxed runtime resources

Each payload has releaseId, semantic version, minimumCoreVersion, maximumCoreVersion, schemaVersion, SHA-256 digest, size, signature and rollback metadata.

## Activation protocol
CHECK -> DOWNLOAD -> HASH -> SIGNATURE VERIFY -> COMPATIBILITY VERIFY -> STAGE -> SNAPSHOT -> ACTIVATE -> HEALTH CHECK -> CONFIRM.

Any failure before CONFIRM leaves the previous known-good version active. Recovery must survive process death and device restart.

## Release channels
`stable`, `candidate`, `development`. Production defaults to stable.

## Important boundary
Changes to Kotlin/native Android executable code cannot be silently replaced by ordinary in-app OTA. Those changes require a signed Android package update. The architecture therefore maximizes OTA coverage while keeping the trusted core stable.
