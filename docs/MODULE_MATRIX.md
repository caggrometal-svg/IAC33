# IAC33 — Module Matrix

| Module | Responsibility | Depends on | Remote required |
|---|---|---|---|
| core | lifecycle/config/logging/errors/version | none | no |
| data | persistence/repositories | core | no |
| network | connectivity/sync/queue | core,data | optional |
| ai | AI abstraction/router | core,network | optional |
| memory | conversation/memory | core,data,ai | no |
| location | GPS abstraction | core | no |
| seismic | Chile seismic data/analysis | core,data,network | optional |
| c33 | research/content/projects | core,data,ai,network | optional |
| media | media workflows | core,data | optional |
| control | remote command plane | core,network,data | yes |
| update | OTA lifecycle | core,network,control | yes for delivery |
| ui | presentation/navigation | public feature contracts | no |

Infrastructure implementations are replaceable. UI never owns persistence, networking, provider credentials, update verification, or command execution.
