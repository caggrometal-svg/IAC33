# IAC33 — Data Model v1

## Local

AppConfig, Conversation, Message, MemoryItem, OfflineOperation, DiagnosticEvent, UpdateState, ModuleState.

## Server

device, command, command_attempt, release, release_artifact, device_update, audit_event, health_snapshot, sync_cursor.

## State discipline

Every remotely initiated operation has an idempotency key, lifecycle state, timestamps and audit record. Terminal states are immutable except through a new compensating event.
