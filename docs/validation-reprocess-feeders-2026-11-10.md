# Reprocesos Feeders: resultado real y propagación de errores

## Ejecución manual del usuario

Registro del 2026-11-10, de 11:47:59 a 12:39:42. El proceso recibió las 133 fechas del Data Grid `Lotes / reprocesos` y descartó correctamente la ruta automática del catálogo.

| Resultado | Fechas |
| --- | ---: |
| Commit del workflow de carga | 105 |
| Rollback por archivo requerido ausente | 28 |
| Resultado final mostrado como OK antes de la corrección | 133 |

La revisión completa del registro reemplaza la lectura inicial, incorrecta, que interpretó los 133 OK como cargas correctas.

Las 28 fechas incompletas tienen los CSV GSM y UMTS, pero carecen de `LTE Crossed Feeder.csv`. La comprobación fue sobre las tres rutas exactas esperadas en la carpeta compartida, sin modificar archivos. El usuario confirmó que deben exigirse siempre GSM, UMTS y LTE. Se conservan los tres archivos como requeridos y la validación SQL que exige tres tecnologías con datos; no se habilita carga parcial.

Consulta de solo lectura ejecutada mediante una pipeline nativa por MCP en `10.32.112.109`:

| Tabla bd_gestion.gul_crossed_feeder_daily | Fechas distintas | Filas |
| --- | ---: | ---: |
| GSM | 105 | 465.031 |
| UMTS | 105 | 1.681.845 |
| LTE | 105 | 2.782.550 |
| Total | 105 | 4.929.426 |

El SQL local `feeders/sql/publish.sql`, guardado a las 11:47:25, apunta a `bd_gestion`; esto explica que la tabla de pruebas `bd_tablas_temporales.gul_crossed_feeder_daily` conserve solamente las 47.907 filas del 2026-10-21. Se preservó esa configuración local. La investigación posterior hizo únicamente consultas de lectura sobre la tabla publicada.

## Corrección del workflow mediante MCP

En `execute_analisis.hwf`, las ramas de error acababan en `Dummy`. Al estar desactivada su salida de correo, el Dummy era terminal y restablecía el resultado a éxito, ocultando los 28 fallos.

Se eliminó ese Dummy y se conectaron las cinco ramas de fallo a la acción existente `Abort job`. El mensaje identifica análisis y fecha. Los correos desactivados permanecen desactivados. La modificación empleó preview, precondición SHA-256, respaldo protegido y recarga nativa. El workflow pasa de 11 a 10 acciones; no se añadieron scripts.

Se ejecutó únicamente la fecha incompleta `2026-05-07` a través de `hop_execute` sobre `execute_analisis.hwf`: devolvió `ok=false`, `error_count=1`, sin timeout. El lector falló antes de publicar y la transacción hizo rollback. No se volvió a ejecutar el lote de 133 fechas. Sus filas se conservaron y se comprobaron al finalizar.

## Mejora del conector

`HopRouting.validateTerminalFailureRecovery` añade un aviso estructural cuando una rama de error habilitada de una acción PIPELINE, WORKFLOW o SQL acaba en un DUMMY sin salida habilitada. No confunde una condición EVAL falsa con un fallo de ejecución. Es un aviso: no modifica ni ejecuta flujos, no amplía autorizaciones y conserva los límites de diagnósticos y el saneamiento de nombres.

Pruebas nuevas cubren el correo desactivado, la salida habilitada hacia Abort, una condición falsa y una rama de fallo desactivada. Java 21 / baseline Hop 2.19: 177 pruebas, cero fallos, cero errores, diez omitidas. El formateo y `clean verify` finalizaron correctamente. Esta modificación no incorpora nuevas API de Hop; no se repitió el perfil de compatibilidad 2.20. El JAR generado se instaló con respaldo en `C:\hop\plugins\misc\hop-mcp-connector`; los servidores y GUI ya abiertos necesitan reiniciarse para cargarlo. No se publicó una versión.

Validación estructural final por MCP: `execute_analisis.hwf`, `orchestrate_analisis.hpl` y `feeders_stage_csv.hpl`, válidos sin avisos. La inspección del lector confirma los tres `file_required=Y`.

Incidencia auxiliar: una conexión privada de verificación con MariaDB JDBC rechazó el fetch size de streaming usado por TableInput. Se desactivó `STREAM_RESULTS` únicamente en los metadatos privados de verificación. No se cambió la conexión del proyecto y este problema no explica los fallos del lector CSV.

## Evidencias locales

Carpeta `D:\dev\build_hop_web\mcp\feeders-reprocess\20261110`:

- `fechas-sin-LTE.csv`: las 28 fechas pendientes de completar en origen.
- `manual-log-analysis.json`, `source-file-preflight.json`: análisis completo del registro y presencia de archivos.
- `fix-failure-routing-results.json`: preview y aplicación semántica.
- `test-failed-date-result.json` y su `.progress.log`: reproducción del error correctamente propagado.
- `production-counts-result.json`: consulta nativa de solo lectura y conteos publicados.
- `final-checks-result.json`: validaciones y configuración del lector.
- `current-batch.json`: las 133 filas del usuario conservadas.

Build: `D:\dev\build_hop_web\mcp\feeders-parameters\20261110\mcp-verify.log`. Las evidencias se mantienen fuera del paquete público para evitar divulgar metadatos o archivos operativos.
