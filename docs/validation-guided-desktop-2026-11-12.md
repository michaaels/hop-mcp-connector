# Validación de construcción nativa guiada — 2026-11-12

Este informe conserva las comprobaciones anteriores al cambio de base. El objetivo actual es [Hop 2.20 exclusivamente](hop220-only-2026-11-12.md); los resultados históricos de 2.19 no amplían el soporte vigente.

## Resultado verificado

| Comprobación | Resultado |
| --- | --- |
| Java 21, `clean verify`, baseline Hop 2.19.0 | 200 pruebas detectadas, 0 fallos, 0 errores, 10 omitidas |
| Java 21, `-P hop-2.20 -Dhop.version=2.20.0 clean verify` | 200 pruebas detectadas, 0 fallos, 0 errores, 3 omitidas |
| Formato Java y Jandex | Correctos en ambas compilaciones |
| ZIP de distribución | 20 entradas, LICENSE/NOTICE presentes y ningún JAR de Hop incorporado |
| Hop Desktop real 2.20 | Pestaña nativa, componentes y hops reales, preparación, bloques, pausa, salto y aplicación instantánea |
| Workflow nativo | Conexión condicional negativa conservada, visible con la rama naranja de Hop |
| Cancelación desde MCP | La pestaña de borrador se cierra y el archivo propuesto no se crea |

Los registros finales están en `D:\dev\build_hop_web\mcp\guided-ui\20261112\baseline.log` y `hop220.log`. Se construyó con Java 21 y caché Maven bajo `D:\dev\build_hop_web`. La prueba gráfica usó proyectos aislados y el CLI se inició con `--allow-mutation`, sin habilitar ejecución. No se ejecutaron ETL de negocio ni se cambiaron tablas.

Se amplió la prueba STDIO existente para preparar, continuar, aplicar instantáneamente y cancelar planes mediante JSON-RPC, validando los esquemas. Las pruebas nuevas cubren revisiones, precondiciones, cancelación, límites, pausa e interpolación, instantáneas privadas y lectura con huella, caducidad y cuota, configuración nativa y ramas de pipelines/workflows.

## Evidencia gráfica y tiempos observados

Directorio de evidencia: `D:\dev\build_hop_web\mcp\guided-ui\20261112`.

- `native-playing.png`: el editor de Hop muestra el paso 2 de 13 y su componente real. El bloque MCP ya había regresado en aproximadamente 113 ms.
- `native-paused.png`, `native-paused-stable.png`: la presentación permanece pausada.
- `native-saved-paused.png`: el archivo ya fue aplicado; la presentación sigue pausada y lo indica como «Guardado». Aplicar regresó en aproximadamente 58 ms.
- `native-applied.png`: resultado guardado en el editor normal, con sus seis componentes conectados.
- `native-workflow-final-playing.png` y `native-workflow-final-applied.png`: workflow antes y después de aplicar; conserva la rama condicional negativa.
- `native-instant-before.png`: todavía se muestra el paso 2 de 6. `native-instant-after.png`: la solicitud MCP `presentation=instant` ya entregó el resultado al editor normal. La llamada de aplicación tardó aproximadamente 37 ms.
- `native-final-preparing.png`: vista nativa vacía esperando otro bloque. `native-final-cancelled.png`: cancelación confirmada y presentación retirada, sin crear `native-cancel.hpl`.

Las primeras capturas de pipeline corresponden al candidato anterior a la corrección de callbacks de toolbar; `native-workflow-final-*`, `native-instant-*` y `native-final-*` corresponden al candidato con esa corrección, cuyos resultados están en `runtime-925a2843ff84-results.json`.

El binario definitivo se volvió a probar después de excluir las pestañas temporales de la restauración de sesión. `final-native-preparing.png`, `final-native-playing.png` y `final-native-applied.png` muestran preparación, el paso 2 de 6 y el resultado editable. El bloque regresó en aproximadamente 34 ms y aplicar instantáneamente tardó aproximadamente 31 ms. `runtime-0ffad7745377-results.json` registra esas llamadas. `final-gui-verification.json` confirma cero errores GUI de callbacks, persistencia de la pestaña real y ausencia de pestañas temporales en los 50 archivos de auditoría examinados; el workflow sin aplicar tampoco se creó.

Las capturas y JSON anteriores conservan su propia fecha. Estos tiempos son muestras locales, no un benchmark ni una garantía de fluidez/FPS.

## Correcciones encontradas durante el desarrollo

1. Se sustituyó el prototipo de ventana y dibujo separados por editores nativos de Hop dentro de sus pestañas. El renderizador gráfico propio quedó retirado.
2. Hop devuelve sus conexiones de pipeline como lista inmutable. La presentación ahora retira conexiones mediante los métodos nativos, cubiertos por pruebas que reconstruyen varios fotogramas de pipelines y workflows.
3. Las toolbars nativas registran inicialmente la clase concreta, mientras sus callbacks identifican la clase base. El adaptador de construcción añade los alias de clase durante el registro de esta GUI y restaura mapas normales al terminar. Esto conserva los bloqueos de guardado/ejecución del borrador sin modificar las bibliotecas de Hop ni usar reflexión. En el candidato final no aparecieron errores GUI de resolución de esos callbacks.
4. Las instantáneas completas se leen fuera del hilo de la GUI y se verifican por huella. Los paquetes de presentación mantienen únicamente geometría saneada.
5. Aplicar publica el estado de aplicación antes del evento de archivo, para que una presentación pendiente pueda terminar sin ser sustituida inmediatamente por la recarga. La protección de pestañas con cambios manuales conserva su comportamiento.
6. El handler de borrador no presenta una ruta de archivo a la persistencia de sesión de Hop. Así, cerrar durante la preparación no registra una ruta temporal inexistente para reabrirla después. Las pestañas de definiciones aplicadas se recuerdan normalmente.

## Observaciones pendientes y alcance

Durante dos ejecuciones previas de compatibilidad aparecieron incidencias intermitentes en pruebas existentes: `HopExecutionManagerTest.timeoutRemainsStoppingWithItsResultUntilTheNativeWorkerExits` observó `result` ausente al comprobar el estado de parada; `HopJdbcCancellationTest.cancelsAnExecutingDatabaseJoinQueryThroughTheMcpService` informó un error al cerrar el contexto de JUnit. La primera observación es compatible con una transición concurrente de estado y resultado, pero su causa no se confirmó en este trabajo. No se preservó el detalle interno de la segunda incidencia. La ejecución completa final pasó; no se modificaron esas pruebas ni el motor de ejecución para obtener ese resultado. Conviene investigar su reproducibilidad en otro entorno antes de atribuirles una corrección.

El notificador de nuevas versiones de Hop registró un error PKIX al consultar GitHub en esta red. No impidió la presentación local y no se relajó TLS para ocultarlo.

La interfaz completa se probó en Hop 2.20 Desktop; la compilación baseline no equivale a una prueba gráfica en Hop 2.19. Hop Web mantiene su sincronización existente y no incorpora esta presentación. No se certificaron FPS bajo carga, un catálogo de todos los diálogos de plugins ni animación campo por campo. La configuración se carga mediante metadatos Hop y los diálogos normales se utilizan después de aplicar.

## Impacto medido

Comparación del código Java modificado contra la copia `baseline-src` anterior al desarrollo, con el mismo contador de líneas no vacías: 6.886 → 8.347 líneas de código principal en el conjunto de archivos afectado. Se incorporan cinco clases principales con responsabilidades de protocolo visual, cola, vista, instantánea privada y adaptación del editor nativo. La ampliación implementa comportamiento nuevo; no se presenta como una reducción de complejidad o de tamaño.

JAR instalado usado como referencia: 477.832 bytes. JAR final baseline: 528.135 bytes, incremento de 50.303 bytes, aproximadamente 49 KiB. El ZIP completo ocupa 6.182.463 bytes. No se añadieron dependencias ni herramientas MCP. El catálogo conserva las herramientas existentes, con argumentos opcionales en tres herramientas de planes.

El grafo de código se reindexó después de las modificaciones. Medición actual: `prepare` tiene complejidad 7 y cognitiva 8; `nativeCallbacks`, 4 y 4; la proyección de workflow, 5 y 7, según codebase-memory-mcp. No hay una medición homogénea anterior de estas funciones para afirmar una disminución. La orquestación de preparación sigue en un método de 80 líneas medido por ese analizador; conserva sus validaciones y efectos en orden, y no se fragmentó en envoltorios triviales.

SHA-256 del ZIP verificado: `0ffad7745377873ac9df5bec7c2953c1eca7433b0f566ae43904a3e388fc8648`. SHA-256 del JAR baseline: `540b207924dcc58343dcdd8afc4073ee6d7a9e5ff1b63e7ad379227c7eaba165`. El archivo `package-verification.json` registra la inspección del paquete; `metrics.json` registra el alcance y conteo de líneas.
