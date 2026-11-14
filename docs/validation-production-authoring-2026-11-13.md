# Pruebas de autoría MCP en Hop Web de producción

Fecha: 2026-11-13. URL: `https://10.32.112.2/hop_web/ui`. Plugin 2.3.0, Hop 2.20, proyecto `ANALYZER_HOP`. Se utilizó únicamente el perfil MCP `apache_hop_web`; no se abrió la carpeta compartida del proyecto.

## Resultado

**20 comprobaciones aprobadas, 0 fallidas.** Se crearon un pipeline y un workflow temporales con el prefijo `mcp_web_test_20261113_mwff44yg`. Se revirtieron mediante MCP y el catálogo final confirmó cero definiciones con ese prefijo. Live Sync continuó conectado. No se ejecutó ETL ni se modificaron definiciones de negocio.

- Pipeline: preparación guiada vacía, append asíncrono, dos componentes Dummy, conexión habilitada, parámetro nombrado y distribución automática.
- Edición: renombrado instantáneo de un componente y actualización de su conexión; copia protegida y restauración del hash original.
- Workflow: tres acciones Dummy de prueba, ramas condicionales de éxito y fallo, distribución automática sin cruces ni solapamientos reportados.
- Interfaz: el cliente Web confirmó apertura de ambas definiciones, recarga del pipeline tras editarlo y cierre de ambas después de revertirlas.
- Protecciones: rechazo de hash de plan incorrecto, reutilización de plan aplicado, aplicación de borrador cancelado, acceso fuera del proyecto y rollback con hash incorrecto. La sesión siguió usable después de esos errores esperados.

## Alcance y observaciones

No se encontraron fallos nuevos en los casos ejercitados; no se modificó el código fuente del MCP para estas pruebas. La evidencia de la interfaz procede de acknowledgements del adaptador nativo Web. No se capturaron pantallas ni se midieron de forma independiente la velocidad o suavidad de la animación en esta ejecución. No se reinició Tomcat ni se probó su apagado; la observación anterior `Graphic is disposed` durante cierre de RAP conserva su estado pendiente de comparación.

Estas son pruebas funcionales remotas; no se repitió la compilación de 217 pruebas ya aprobada para el mismo binario. Los componentes Dummy sirven como fixtures de autoría y no representan una prueba funcional de cargas o bases de datos. El catálogo sin definiciones de prueba no implica eliminar registros internos de auditoría y backups protegidos administrados por el MCP.

Evidencia completa: `D:\dev\build_hop_web\mcp\remote-rap\20261113\production-authoring-tests.json`, con argumentos, resultados, identificadores de transacción, hashes y las 20 comprobaciones evaluadas.
