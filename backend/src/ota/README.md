# IAC33 OTA Bridge

OTA updates are controlled by the signed manifest and verified by the Android client before installation. The control plane must remain fail-closed: invalid signatures, expired manifests, replayed releases, or mismatched digests are rejected.
