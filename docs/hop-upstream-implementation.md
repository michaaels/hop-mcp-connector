# Implementación de compatibilidad con los cambios de Apache Hop

Trabajo local del 16 de octubre de 2026, basado en el [análisis del 15 de octubre](apache-hop-upstream-analysis-2026-10-15.md). La base estable continúa siendo Hop 2.19.0 y el perfil de compatibilidad usa 2.20.0-SNAPSHOT. Los cambios se registran en `Unreleased`; no constituyen una nueva publicación.

## Cambios implementados

- **Lectura web:** únicamente `status`, `pipelineStatus` y `workflowStatus` bajo el contexto nativo `/hop`, con prefijos de aplicación/proxy opcionales. GET y HEAD se autorizan antes de enviar HTTP. Se rechazan endpoints desconocidos o con efectos, `getExecInfo`, rutas ambiguas y cabeceras de reescritura. Las pruebas de cliente y STDIO verifican que los rechazos no alcanzan el servidor HTTP local.
- **Relaciones de ejecuciones:** se conserva `parent_id` y se añaden propietario, lanzador y procedencia. La API opcional `getLaunchedById` se consulta sin enlazar el artefacto estable a Hop 2.20. Un registro antiguo con solo padre permanece `unresolved`, incluso en un runtime nuevo. Una API presente que falla devuelve un error tipado sin detalles nativos sensibles.
- **Descendientes:** una única consulta de IDs nativos, hasta 1.000 documentos más la raíz, sustituye las búsquedas repetidas por nodo. Se mantienen protección contra ciclos, máximo de 200 resultados y profundidad 32; se informa de truncación del escaneo y del vínculo recorrido. Las ubicaciones nativas pueden hacer trabajo interno adicional: este límite no garantiza el coste de su almacenamiento o driver.
- **XML:** una regresión comprueba que habilitar DOCTYPE en la configuración de Hop no habilita DTD, entidades externas ni XInclude en el parser del conector.
- **Componentes reales en el perfil 2.20:** creación y recarga nativas de Watch Files y OpenFormula, esquema de inyección y campos de salida de OpenFormula. Se conservan los nuevos valores de reintento de Pipeline Executor durante una edición semántica con SHA esperado. Esos campos no aparecen en su esquema legacy de inyección; no se incorporó una escritura XML alternativa para hacerlos editables.
- **JDBC real en el perfil 2.20:** parada explícita y timeout de Table Input y Database Join con H2. Cada prueba espera a que la consulta esté ejecutándose, comprueba el estado terminal, consulta las sesiones JDBC para confirmar que terminó y ejecuta otra pipeline mediante el mismo servicio. Las dependencias de plugins y H2 tienen alcance Maven `test`.
- **Vida del worker nativo:** la cancelación del `Future` que espera el MCP ya no libera por sí sola el cupo de ejecución asíncrona. `native_worker_active` informa si el worker del conector está en cola o ejecutándose. La operación permanece en `stopping` y conserva su cupo hasta que terminen tanto la espera/control como ese worker; después se fija `finished_at` y el estado terminal. Los checkpoints entre lectura, creación del motor, preparación y arranque comprueban la cancelación antes de avanzar. No fuerzan el fin de una llamada nativa que ya esté bloqueada.

Los esquemas de salida y los diagnósticos comparten la nueva interpretación de relaciones. Las pruebas comprueban registros antiguos y modernos, propietario/lanzador distintos, lanzador sin documento, ciclos y límites de escaneo, nodos y profundidad. Son fixtures de repositorio; no certifican todas las implementaciones de ubicación de ejecución ni migraciones de almacenamiento.

## Límites de cancelación observados

Las pruebas JDBC cubren la consulta de ejecución y su timeout global, en el snapshot de Hop 2.20 resuelto por Maven y con H2. No prueban cancelación universal, todos los drivers, ejecuciones JDBC simultáneas ni una distribución completa de Hop.

Durante el desarrollo, una consulta larga sin parámetros también se ejecutó en la obtención nativa de campos (`Database.getQueryFieldsFallback`) y en callbacks de información de ejecución. Esa consulta de metadatos siguió activa después de que el MCP informara la parada y requirió cerrar la base de prueba. Estos cambios no cancelan ese statement nativo adicional. El fixture JDBC definitivo usa una consulta parametrizada cuyo rango es vacío cuando Hop descubre campos con valores NULL; solo la fila de entrada real activa la consulta larga. Así se comprueba la cancelación del statement de ejecución sin confundirla con la consulta de metadatos.

La corrección posterior del gestor evita declarar terminada una operación asíncrona cuando su worker nativo rastreado sigue activo. Cinco pruebas nuevas verifican workers que ignoran interrupciones, conservación del límite de cuatro operaciones, reutilización posterior del cupo, timeout pendiente, cancelación antes de arrancar y fallo al enviar una tarea. También se valida el esquema de estado. Si Hop crea otros threads o consultas fuera del worker rastreado, el campo `native_worker_active` no los observa. Una parada solicitada, un estado terminal o ese campo en `false` no garantizan por sí solos que todo trabajo JDBC nativo haya concluido. La ejecución síncrona tampoco adquiere por este cambio el seguimiento de operaciones asíncronas.

## Linter pendiente

No se añadió `hop_lint` ni un flag nuevo. La revisión de la API nativa identificó tres impedimentos para conectarla directamente:

1. `LintRun` construye un índice de todo el proyecto incluso al analizar un archivo. `HopLinter.findLintableFiles` recorre y materializa archivos sin los límites de entradas, archivos y bytes del MCP. Una prevalidación local no limita el procesamiento nativo posterior.
2. `HopLinter.shouldIncludeNativeChecks` consulta un singleton global y retorna `true` si no puede leerlo. No ofrece aquí una opción por invocación que asegure la ausencia de checks con acceso externo sin permiso explícito.
3. `runPolicyRulesInternal` captura errores, los registra y devuelve los hallazgos acumulados. Esa salida no permite distinguir de forma fiable un análisis completo de uno parcialmente fallido.

La integración futura necesita un backend acotado, configuración por invocación, control explícito de checks, propagación de errores y cancelación verificable. Cambiar temporalmente el singleton, ejecutar `hop lint` como subproceso o truncar solo la respuesta no satisface ese contrato. La herramienta sigue siendo candidata para una versión menor después de resolverlo.

Fuentes nativas revisadas, fijadas al mismo commit del análisis: [LintRun](https://github.com/apache/hop/blob/7d6bccec7cd5fa4fe5f709596a5f045fccbe0f04/plugins/misc/lint/src/main/java/org/apache/hop/lint/LintRun.java), [HopLinter](https://github.com/apache/hop/blob/7d6bccec7cd5fa4fe5f709596a5f045fccbe0f04/plugins/misc/lint/src/main/java/org/apache/hop/lint/HopLinter.java) y [LintProjectIndex](https://github.com/apache/hop/blob/7d6bccec7cd5fa4fe5f709596a5f045fccbe0f04/plugins/misc/lint/src/main/java/org/apache/hop/lint/LintProjectIndex.java).

## Validación inicial (16 de octubre de 2026)

Los registros e informes locales se conservan en `outputs/hop-upstream-implementation-20261015/`. Los builds posteriores a la corrección del worker tienen prefijo `lifecycle-hop219` y `lifecycle-hop220`, con logs, resúmenes JSON e informes Surefire separados. Los logs anteriores corresponden a la primera etapa de implementación.

| Comprobación | Resultado |
| --- | --- |
| Hop 2.20, `mvn -B -P hop-2.20 fmt:format clean verify` | BUILD SUCCESS; 155 pruebas, 0 fallos, 0 errores, 3 omitidas |
| Hop 2.19, `mvn -B clean verify` | BUILD SUCCESS; 155 pruebas, 0 fallos, 0 errores, 10 omitidas |
| ZIP de compatibilidad, `check-marketplace-zip.py` | Correcto: layout, licencia/aviso, Jandex, exclusión de runtime Hop y SBOM según el verificador del proyecto |
| ZIP estable, `check-marketplace-zip.py` | Correcto con las mismas comprobaciones; el paquete final de `target/` corresponde a Hop 2.19 |
| Junctions de Windows, `check-windows-junction-tests.py` | Las tres pruebas requeridas se ejecutaron y pasaron |
| Guardas de verificación, `test-verification-gates.py` | 16 pruebas correctas |

Las tres omisiones del perfil 2.20 son las pruebas de symlinks `rejectsSymbolicLinkBackupRoot`, `walkerDoesNotFollowSymbolicLinks` y `aliasesCannotExposeExternalOrInternalFiles`. Las pruebas de junctions no sustituyen la cobertura pendiente de symlinks en Linux.

En Hop 2.19 se omiten esas mismas tres pruebas y las siete pruebas nuevas de componentes/JDBC exclusivas del perfil 2.20. Las pruebas requeridas de junctions se ejecutaron y pasaron en ambas compilaciones.

Los componentes Hop resueltos corresponden al snapshot Maven **2.20.0-20261015.215637-1**. Ese identificador no prueba correspondencia exacta entre los binarios y el commit fijado para analizar fuentes. No se ejecutaron en este trabajo una instalación limpia completa, la batería externa de conformidad oficial ni los trabajos remotos de CI.

## Revalidación para PR (20 de octubre de 2026)

Se revisaron nuevamente los cambios completos antes de preparar el PR, manteniendo la base de `main` en `d80e5d9`. Los registros de esta ejecución están en `outputs/pr-upstream-validation-20261020/`, con informes Surefire y resúmenes separados por perfil.

- Hop 2.20: `mvn -B -U -P hop-2.20 clean verify` terminó con BUILD SUCCESS; 155 pruebas, 0 fallos, 0 errores y las mismas 3 omisiones de symlinks. Maven resolvió los componentes Hop como **2.20.0-20261020.200202-1**; esta ejecución incorpora el snapshot nuevo y no reutiliza únicamente los resultados del día 16.
- Hop 2.19: `mvn -B clean verify` terminó con BUILD SUCCESS; 155 pruebas, 0 fallos, 0 errores y las mismas 10 omisiones documentadas. El paquete final de `target/` corresponde a este perfil estable.
- Ambos paquetes pasaron `check-marketplace-zip.py` y ambas compilaciones pasaron las tres pruebas requeridas por `check-windows-junction-tests.py`. Se comprobó también que no se incluyen los plugins de prueba/H2 en el ZIP, que el JAR auxiliar contiene solo las dos clases de aceptación previstas y que se generaron ambos SBOM.
- `test-verification-gates.py`: 16 pruebas correctas. `git diff --check` no detectó errores de whitespace y se validaron los enlaces locales de la documentación.

Estas comprobaciones locales no sustituyen los jobs de Linux, instalación limpia y conformidad externa del PR. Su resultado remoto se conserva en los checks de GitHub asociados al commit revisado. El linter y los límites de cancelación descritos arriba no cambian con esta revalidación.

## Corrección de la CI posterior al merge (21 de octubre de 2026)

La [CI de main del commit ef76396](https://github.com/michaaels/hop-mcp-connector/actions/runs/39525373237) falló en el job Linux `build`: `HopMcpServerStdioTest.completesHandshakeAndServesConsecutiveToolCallsOverStdio` recibió `java.io.IOException: Pipe broken` desde `PipedInputStream.read`, al esperar la respuesta de `hop_capabilities`. Los jobs Windows y Hop 2.20 del mismo run aprobaron. Los checks del PR anterior también habían aprobado; el fallo apareció en la ejecución posterior al merge.

El canal de pruebas dependía de la vida del hilo que escribió por última vez. Java considera roto un `PipedInputStream` si ese escritor termina y el lector espera más datos. La regresión `responsesSurviveWriterThreadExitAndHandoff` reproduce esa condición: el escritor termina, el lector espera y un segundo escritor entrega otra respuesta. Con el canal anterior falló localmente con `Write end dead`; con el canal NIO pasó. Fuentes del contrato del JDK: [PipedInputStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/PipedInputStream.html) y [Pipe](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/Pipe.html).

La corrección sustituye únicamente los canales de las pruebas STDIO por `java.nio.channels.Pipe` y adapta `ResponsePump` a `InputStream`/`OutputStream`. Se mantienen las comprobaciones de timeout, EOF y limpieza de lectores. La implementación de producción no cambia. Los logs de reproducción y validación están en `outputs/ci-stdio-fix-20261020/`; esa carpeta conserva también la reproducción iniciada antes del cambio de fecha.

La batería específica STDIO pasó sus 8 pruebas. Ambos `clean verify` completos terminaron con BUILD SUCCESS: 156 pruebas por perfil, sin fallos ni errores; 10 omisiones en Hop 2.19 y 3 en Hop 2.20, con las mismas causas documentadas anteriormente. Ambos ZIP y las guardas de junctions de Windows aprobaron. También se comprobaron los SBOM, la exclusión de dependencias de prueba del ZIP y el contenido del JAR auxiliar. La integración exige además los checks remotos del PR y la comprobación de la CI posterior al merge en `main`.

### Diagnóstico posterior del transporte SDK

El [PR #21](https://github.com/michaaels/hop-mcp-connector/pull/21) aprobó sus checks, pero la [CI posterior de main](https://github.com/michaaels/hop-mcp-connector/actions/runs/39552871093) volvió a fallar: esta vez el mismo lector esperó hasta timeout. El cambio de canal no resolvía por sí solo el bloqueo; la desaparición del escritor también podía ser una consecuencia del cierre del transporte.

Se añadieron diagnósticos acotados y se revisaron las fuentes exactas del SDK 2.0.1. El [run de diagnóstico](https://github.com/michaaels/hop-mcp-connector/actions/runs/39553888041) capturó `ErrorCallbackNotImplemented: Failed to enqueue message` en `StdioMcpSessionTransport.sendMessage`, seguido del timeout. El SDK usa `tryEmitNext` sobre su sink de salida y propaga el rechazo como error de la sesión. Coinciden respuestas de handlers asíncronos y errores de validación devueltos directamente por el hilo de entrada. Las pruebas locales de Linux, incluso la batería completa, habían aprobado; un resultado positivo aislado no descartaba esta carrera.

La corrección definitiva actualiza el SDK a 2.1.0 mediante el [PR de dependencias #18](https://github.com/michaaels/hop-mcp-connector/pull/18). Sus [notas oficiales](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.1.0) incluyen la corrección de concurrencia STDIO; se revisó también `StdioServerTransportProvider` en ese tag. El SDK sustituye el intento único de admisión por `emitNext` con tratamiento de contención acotado a 100 ms. El proveedor, la codificación y las sesiones siguen siendo nativos; no se incorpora un transporte propio ni se reejecutan herramientas. La revisión de `ProtocolVersions` confirma que la base sigue siendo 2025-11-25, sin soporte de 2026-07-28.

La cobertura incorpora una prueba STDIO con 32 peticiones consecutivas sin esperar sus respuestas, mezclando resultados correctos y errores de esquema y comprobando que la sesión sigue utilizable. Los diagnósticos de timeout se limitan a 64 threads y 32 frames por thread; el stdout capturado se redacta antes de limitarlo a 16.384 caracteres.

Con el SDK 2.1.0, ambos `clean verify` completos terminaron con BUILD SUCCESS: 157 pruebas por perfil, sin fallos ni errores. Hop 2.20 en Windows omitió las tres pruebas de symlinks y aprobó las tres guardas de junctions. Hop 2.19 en Linux omitió las dos pruebas exclusivas de junctions de Windows y las siete pruebas de componentes/JDBC del perfil 2.20; las cuatro guardas obligatorias de confinamiento Linux se ejecutaron y aprobaron. La batería STDIO ejecutó nueve pruebas en ambos perfiles. Se comprobaron los SBOM y la exclusión de dependencias de prueba del ZIP; el ZIP estable aprobó además `check-marketplace-zip.py`. Los logs y resúmenes definitivos tienen prefijo `sdk210-` en la carpeta de evidencia. Los checks remotos del PR y la CI posterior al merge se consultan por separado antes de declarar completada la integración.
