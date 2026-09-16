# IAC33 — Security Baseline

- TLS for every remote connection.
- No provider/API secrets in APK source, resources or build artifacts.
- Secrets only in protected runtime/server configuration.
- Least-privilege Android permissions.
- Remote commands authenticated, authorized, schema-validated and audited.
- OTA artifacts verified by size, SHA-256 digest and cryptographic signature before staging.
- Rollback state protected against partial writes.
- Logs never contain secrets or tokens.
- Release artifacts immutable.
- Security failures fail closed.
