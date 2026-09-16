# IAC33 — OTA Contract v1

An OTA release is immutable.

Required manifest: schemaVersion, releaseId, appVersion, createdAt, minimumSupportedVersion, artifact reference, size, SHA-256 digest, signature algorithm, signature, key identifier, rollback reference.

Client sequence:
CHECK → DOWNLOAD → SIZE CHECK → DIGEST CHECK → SIGNATURE CHECK → STAGE → SELF-TEST → APPLY → HEALTH CHECK → CONFIRM

Failure before confirmation retains the previous known-good state. No unverified artifact is applied.
