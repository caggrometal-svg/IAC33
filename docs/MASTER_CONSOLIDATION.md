# IAC33 — Master Consolidation

## Objetivo

IAC33 es el repositorio maestro. Las capacidades operativas recuperables del antiguo Andrew2.0 se incorporan aquí sin mantener una dependencia de Andrew2.0.

## Fuentes operativas identificadas

### 1. IAC33
- GitHub: caggrometal-svg/IAC33
- Rama base: main
- Backend Render: iac33-backend
- URL: https://iac33-backend.onrender.com
- Health: /health

### 2. Runtime legado conservado en Render
- Servicio: andrew2-api
- URL: https://andrew2-api.onrender.com
- Estado observado: LIVE
- Repositorio configurado históricamente: caggrometal-svg/Andrew2.0
- Rama histórica: iac33-integration-next

Este servicio se conserva temporalmente como fuente operativa de referencia hasta completar la recuperación y validación.

## Capacidades a consolidar

- Router IA y selección/failover de proveedores
- Contexto web y conectividad
- Diagnóstico de proveedores
- Bridge V3 / OTA
- Control plane y cola de comandos
- Multimedia
- Health/status
- Compatibilidad Android
- Pruebas E2E y CI/CD

## Regla de consolidación

No se copiarán secretos, credenciales ni claves privadas al repositorio.

No se elimina el runtime legado hasta demostrar que IAC33 reproduce las capacidades necesarias mediante pruebas verificables.

## Estado inicial

- Repositorio maestro: IAC33
- Rama de consolidación: master-consolidation
- Recuperación fuente: en curso
- Migración funcional: pendiente por módulo
- Validación Render: pendiente por módulo
- Retirada Andrew2.0: bloqueada hasta completar validación
