# Validación real de MCP en Hop Web — 2026-11-12

Este informe conserva los resultados de la compilación anterior. La implementación posterior de presentación guiada en RAP y el nombre **MCP Live Sync** están documentados en [la validación del 13 de noviembre](validation-rap-live-sync-2026-11-13.md); no implica que el servidor de producción ya esté actualizado.

## Resultado

En [Hop Web](https://10.32.112.2/hop_web/ui), **la creación, edición semántica, apertura y recarga nativas funcionan mediante MCP sobre Hop 2.20.0**. La presentación guiada con velocidad y pausa continúa limitada a Desktop.

| Comprobación | Evidencia observada |
| --- | --- |
| Aplicación | Título `Apache Hop Web - 2.20.0`, proyecto `ANALYZER_HOP` |
| Plugin | Entrada `MCP Connector for Apache Hop live synchronization...` en el menú de acciones |
| Conexión | Activación correcta para `E:\APACHE_HOP\HOP_PROJECTS\ANALYZER_HOP`; MCP confirmó un cliente `web` activo |
| Crear por MCP | Pipeline con tres componentes Dummy y dos conexiones, abierto automáticamente en una pestaña nativa |
| Configuración nativa | Diálogo de Dummy abierto por Chrome MCP y cancelado sin guardar cambios |
| Editar por MCP | Renombrado del componente central; la pestaña mostró `Proceso MCP Web - actualizado` sin recarga manual del navegador |
| Acknowledgements | `opened`, `reloaded`, `reloaded`, `closed`, todos con `client_type=web` |
| Rollback | Se revirtió el renombrado y después la creación; el archivo desapareció y su pestaña se cerró automáticamente |
| Entorno original | Regresó la pestaña `U2020_GSM`; su archivo conservó exactamente el SHA-256 inicial |
| Navegador | Sin errores ni advertencias en la consola durante la prueba |

La creación y el renombrado se hicieron con `hop_prepare_correction_plan` y `hop_apply_correction_plan`; las dos reversiones con `hop_rollback_mutation`. Las interacciones con el navegador se hicieron con Chrome MCP. No se ejecutó el pipeline de prueba ni ningún ETL de producción.

## Proyecto y conexión utilizados

El MCP habitual de Codex está configurado para `D:\dev\pentaho\PENTAHO_PROCESS_HOP`, en modo de solo lectura. Ese proyecto no corresponde al que esta sesión web tiene abierto.

Para comprobar el circuito completo se inició temporalmente otro proceso MCP con el paquete compilado para 2.20 y raíz `\\10.32.112.2\hop_config\HOP_PROJECTS\ANALYZER_HOP`, habilitando únicamente mutación para la prueba. La correspondencia con la ruta local del servidor permitió compartir los eventos con Hop Web. No se modificó la conexión habitual de Codex.

El puente de sincronización utiliza el sistema de archivos del proyecto y el adaptador RAP con server push. No requiere `--allow-web-api`; ese permiso corresponde al cliente REST de lectura, que es una función distinta.

La sincronización quedó activada en esta sesión del navegador. Para trabajar posteriormente sobre este mismo proyecto remoto desde una conexión MCP permanente, su raíz debe corresponder al proyecto remoto y sus permisos deben configurarse según el trabajo autorizado.

## Animación: límite confirmado

Se solicitó `presentation=guided`. El archivo se aplicó y el evento semántico llegó al navegador, pero `presentation.ui_notified` fue `false`. La GUI presentó el resultado completo, sin la barra de velocidad, pausa o reproducción progresiva.

Esto coincide con el código actual: `HopLiveUiSync.pollSafely` procesa presentaciones únicamente para `desktop`; `HopLiveUiEventBroker.publishPresentation` exige una sesión Desktop para publicar esas presentaciones. Los eventos normales de apertura y recarga sí se procesan en Web.

Para disponer de la misma experiencia animada se necesita portar y validar la cola y sus controles en el adaptador RAP, incluyendo el ritmo de actualizaciones y su entrega al navegador. Los resultados de esta prueba no certifican esa capacidad.

## Observaciones para mejorar

- El menú compacto de Hop oculta las etiquetas tradicionales de menú fuera del área visible. Se accedió mediante el logotipo superior y el buscador de acciones. El icono no tenía un nombre accesible útil; se añadió una etiqueta temporal solo en el DOM para dirigir el clic automatizado y se retiró al terminar.
- La preparación devolvió `NOT_FOUND` mientras no existía la carpeta de destino. Después de crear una carpeta de prueba vacía, la preparación funcionó. Un diagnóstico específico de carpeta padre inexistente facilitaría esta situación.
- El pipeline se abrió correctamente, pero durante las capturas el árbol de archivos no incorporó automáticamente la carpeta temporal. Conviene evaluar un refresco acotado del directorio padre al crear una definición.
- La prueba confirma el comportamiento del plugin cargado en Tomcat, pero no verificó por hash su JAR efectivo. El cliente MCP temporal sí utilizó el paquete local actual para Hop 2.20.

Estas observaciones documentan el alcance y posibles mejoras; esta validación no cambió el código funcional ni desplegó otro plugin en Tomcat.

## Limpieza y evidencia

La única definición creada fue `_mcp_validation/web_native_20261112_1625b668.hpl`. Ambas transacciones se revirtieron mediante MCP y después se retiró su carpeta vacía con una eliminación no recursiva. La verificación confirmó `original_workflow_unchanged=true`, `test_definition_removed=true` y `rollback_count=2`.

Evidencia local: `D:\dev\build_hop_web\mcp\web-validation\20261112`.

- `web-opened.png` y `web-opened.txt`: pipeline recibido en el editor nativo.
- `web-native-dialog.png`: diálogo nativo de configuración de Dummy.
- `web-reloaded.png` y `web-reloaded.txt`: componente renombrado y recargado.
- `web-restored.png` y `web-restored.txt`: pestaña original restaurada tras las reversiones.
- `results.json`: llamadas y respuestas MCP de la prueba.
- `verification.json`: comprobaciones de entrega, rollback, limpieza y conservación del archivo original.

No se validaron ejecución ETL, conexiones de bases de datos ni todos los diálogos de plugins. Las operaciones del pipeline de prueba no utilizaron credenciales ni conexiones externas.
