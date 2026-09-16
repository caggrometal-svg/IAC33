# IAC33 — Quality Gates

Cada gate debe demostrar su condición antes de permitir la siguiente fase.

| Gate | Condición mínima |
|---|---|
| G0 | Repo limpio, arquitectura documentada y trazabilidad activa |
| G1 | APK base compila e instala desde cero |
| G2 | Core, navegación, persistencia, errores y diagnóstico probados |
| G3 | Conectividad y Offline Real probados |
| G4 | IA con contrato interno y fallos controlados |
| G5 | Dominios funcionales probados individualmente |
| G6 | Backend/API/control plane con estados y auditoría |
| G7 | GPT ↔ app autenticado y validado |
| G8 | OTA íntegra, firmada, aplicable y reversible |
| G9 | E2E completo, incluyendo recuperación y rollback |
| G10 | APK Master congelada y declarada estable |

## Regla de bloqueo
Un fallo crítico bloquea el avance. Un warning no se convierte en éxito por ausencia de errores visibles.

## Evidencia
Cada gate debe conservar commit, pruebas ejecutadas, resultado y fecha.
