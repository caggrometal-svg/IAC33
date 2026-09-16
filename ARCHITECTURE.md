# IAC33 — Arquitectura base

## Objetivo
Construir una aplicación Android modular, resistente, verificable y preparada desde el inicio para actualizaciones OTA y un canal controlado GPT ↔ aplicación.

## Capas

1. **Core** — ciclo de vida, configuración, logging, errores, versionado y contratos.
2. **Features** — IA, conectividad, memoria, GPS, sismicidad, C33 y multimedia.
3. **Data** — almacenamiento local, sincronización y repositorios.
4. **Backend** — API, control plane, comandos, versiones y observabilidad.
5. **Update system** — manifiesto, integridad, firma, aplicación atómica y rollback.

## Reglas

- Ningún feature puede acoplarse directamente a otro feature.
- Los módulos se comunican mediante interfaces/contratos definidos en Core.
- Las credenciales privadas permanecen fuera del APK.
- Toda operación remota debe poder auditarse.
- Las actualizaciones deben validarse antes de aplicarse.
- Un fallo de actualización no debe dejar inutilizable la aplicación.
- Offline no es un error: es un estado operativo soportado.
- La IA no dependerá estructuralmente de un único proveedor.
- Las estimaciones sísmicas serán probabilísticas y nunca se presentarán como predicciones deterministas.

## Secuencia de construcción

### F0 — Fundación
Repositorio, identidad, arquitectura, contratos, versionado, reglas de calidad.

### F1 — APK base
Proyecto Android limpio, Kotlin/Compose, núcleo mínimo ejecutable, navegación y diagnóstico.

### F2 — Plataforma
Persistencia local, configuración, logging, recuperación, módulos y conectividad.

### F3 — IA
Interfaz interna de IA, router, proveedores, failover, modo sin proveedor y arquitectura para modelos locales.

### F4 — Datos y dominios
Memoria, GPS, sismicidad Chile, C33 y multimedia.

### F5 — Backend y control plane
API, base de datos, comandos, estados, sincronización y auditoría.

### F6 — GPT ↔ IAC33
Canal autenticado, comandos, validación, pruebas y autorización de cambios.

### F7 — OTA
Manifest, versiones, integridad, firma, descarga, aplicación, confirmación y rollback.

### F8 — Calidad y Master
Pruebas unitarias/integración/E2E, seguridad, rendimiento, offline, OTA y creación única de APK Master estable.

## Criterio de avance
No se avanza de fase por existencia de código. Se avanza únicamente cuando el componente tiene una prueba reproducible y un estado verificable.