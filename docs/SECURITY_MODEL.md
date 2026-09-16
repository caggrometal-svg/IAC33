# IAC33 — Security Model

## Trust zones
1. Android UI: untrusted input surface.
2. Native core: trusted verifier/orchestrator.
3. Local data: protected persistence.
4. Backend: authenticated control/data service.
5. Control plane: privileged, allowlisted operations.
6. Release system: signed artifacts only.

## Mandatory controls
- TLS for network transport.
- No provider/API secrets in APK, source, logs or OTA payloads.
- Android Keystore for device-bound cryptographic material.
- Signed update manifests and artifacts with digest verification.
- Replay protection using nonce/timestamp/idempotency key.
- Least-privilege command scopes.
- Input/schema validation at every trust boundary.
- Audit trail for privileged operations.
- Rate limits, timeouts and bounded resource use.
- Automatic rollback after failed activation/health confirmation.

## GPT control plane
GPT may propose or request only typed, allowlisted operations. The backend validates authorization, schema, target version, compatibility, risk and idempotency before execution. The app never executes arbitrary natural-language instructions as code.

## Secrets
Secrets are injected only into trusted server/runtime environments. Secret material is redacted from diagnostics. Rotation is supported without changing the APK.

## Supply chain
CI verifies dependency lockfiles, reproducible build metadata where practical, test gates, artifact hashes and release provenance. A release cannot be promoted when a required gate fails.
