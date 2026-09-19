# Legacy Recovery Notes

El repositorio Andrew2.0 fue eliminado, pero el servicio Render andrew2-api continúa registrado y operativo. Su runtime y despliegues constituyen una fuente operativa que debe tratarse como legado temporal.

La recuperación no debe asumir que un servicio LIVE equivale a que todo su código histórico esté disponible para extracción. Cada capacidad debe ser reconstruida o trasladada únicamente cuando exista evidencia técnica suficiente.

## Criterio de aceptación

Una capacidad se considera recuperada cuando:
1. existe en IAC33;
2. no depende de Andrew2.0;
3. tiene configuración externa segura;
4. tiene una prueba automatizable;
5. funciona en Render IAC33;
6. queda cubierta por una ruta E2E cuando corresponda.

## Retención del legado

andrew2-api no debe eliminarse mientras existan capacidades no verificadas o diferencias funcionales pendientes.
