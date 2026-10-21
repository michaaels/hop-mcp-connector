# Cambios recientes de Apache Hop y evolución del MCP

Fecha de análisis: 15 de octubre de 2026.

Este documento conserva el análisis previo a los cambios. La implementación posterior y sus pruebas se describen en [estado de implementación](hop-upstream-implementation.md).

La siguiente etapa debería empezar por cerrar la separación entre lectura web y ejecución, adaptar la información de ejecuciones de Hop 2.20 y comprobar la cancelación con JDBC real. La incorporación del linter nativo tiene valor, pero requiere una versión menor y una integración opcional con límites verificables. La base estable del conector debe seguir en Hop 2.19.0.

## Alcance y evidencia

- Conector analizado: `d80e5d9b68fc19895f03679df2762b532e48406e`, versión 2.2.6.
- Fuente de Hop fijada en [`7d6bccec7cd5fa4fe5f709596a5f045fccbe0f04`](https://github.com/apache/hop/tree/7d6bccec7cd5fa4fe5f709596a5f045fccbe0f04), del 15 de octubre a las 18:42:45 UTC. Su POM declara `2.20.0-SNAPSHOT` y Java 21.
- Se revisaron los mensajes de 170 commits entre el 30 de septiembre y ese corte y los cambios de archivos de 16 PR seleccionados por su relación con el MCP. Esto es una revisión dirigida; no una auditoría completa de todos los cambios de Hop.
- La comparación de GitHub con `2.19.0-rc1` informa 588 commits y devuelve como máximo 300 archivos. No se trató esa lista truncada como un inventario completo. Se paginaron los commits de la ventana reciente y los archivos de los PR seleccionados.
- La [página oficial de descargas](https://hop.apache.org/download/) sigue identificando 2.19.0 como versión estable. La etiqueta de la publicación correspondiente en GitHub se llama `2.19.0-rc1`; no se confundió el nombre de esa etiqueta con una nueva versión estable 2.20.
- Las respuestas originales, el manifiesto, los informes de pruebas y los registros se conservan localmente en `outputs/hop-upstream-analysis-20261015/`, fuera del paquete distribuible.

## Prioridades

| Prioridad | Cambio o hallazgo | Consecuencia para el MCP | Trabajo propuesto |
| --- | --- | --- | --- |
| P1 | GET de Hop Server puede iniciar ejecuciones; Hop Web refuerza su autorización por endpoint | La herramienta web actual no garantiza lectura aunque anuncie `readOnlyHint=true` | Restringir rutas por su operación y probar denegación antes de realizar la petición |
| P1 | Nuevo significado de `parentId`, más `launchedById` y enlaces a hijos | El historial puede perder la distinción entre propietario y lanzador o presentar árboles ambiguos | Adaptador de capacidades y pruebas con registros de ambas versiones |
| P1 | Cancelación de Table Input y Database Join | El resultado y la latencia de parar una ejecución dependen también del motor y del driver | Pruebas de cancelación y timeout con consultas JDBC bloqueadas |
| P2 | Linter nativo con API sin interfaz gráfica, contexto de proyecto y políticas comunes | Permite ofrecer diagnósticos y sugerencias más precisos | Herramienta opcional `hop_lint` en una versión menor, después de verificar límites |
| P2 | Cambios de XML y XSLT | Hop permite opciones de procesamiento que el lector del MCP debe seguir rechazando | Regresiones que prueben que variables de Hop no relajan nuestro parser |
| P2 | OpenFormula, Watch Files y reintentos de Pipeline Executor | Más componentes disponibles, con diferencias de esquema y de ejecución | Pruebas de descubrimiento, inyección, recarga y cancelación por componente |
| P2 | Tipos personalizados de pruebas unitarias | El modelo nativo deja de usar un enum fijo | Diseñar una futura integración de pruebas nativas sin confundirla con `hop_test_definition` |
| P3 | Nuevos conectores VFS, motores y mejoras del asistente AI | Amplían lo que puede estar instalado en Hop | Mantener descubrimiento dinámico y añadir fixtures selectivos |

Las prioridades son una valoración de este análisis, no una clasificación publicada por Apache.

## 1. Lectura web y autorización: corrección previa a nuevas funciones

El [PR #8889](https://github.com/apache/hop/pull/8889) hace que Hop Web autorice sobre el camino que realmente despacha el contenedor. El mapeador normaliza segmentos y parámetros de matriz y mantiene denegación por defecto para endpoints desconocidos. Conviene incorporar esas variantes a nuestras pruebas de rutas.

Además aparece una carencia concreta del conector actual:

- [`HopMcpService.webRequest`](../src/main/java/io/github/michaaels/hop/mcp/HopMcpService.java) comprueba `--allow-web-api` y GET/HEAD, pero no autoriza según la operación del endpoint.
- [`HopWebClient.request` y `resolve`](../src/main/java/io/github/michaaels/hop/mcp/HopWebClient.java) confinan la URL y limitan la respuesta, pero permiten cualquier ruta dentro de la base configurada.
- [`HopMcpServer.annotations`](../src/main/java/io/github/michaaels/hop/mcp/HopMcpServer.java) anuncia la herramienta como lectura e idempotente.
- En el código de [Hop 2.19](https://github.com/apache/hop/blob/2.19.0-rc1/engine/src/main/java/org/apache/hop/www/StartPipelineServlet.java) y en el [corte de desarrollo](https://github.com/apache/hop/blob/7d6bccec7cd5fa4fe5f709596a5f045fccbe0f04/engine/src/main/java/org/apache/hop/www/StartPipelineServlet.java), `/hop/startPipeline` se atiende en `doGet` y puede terminar en `pipeline.execute()`.

Por tanto, si la base configurada incluye ese endpoint y las credenciales remotas permiten la acción, una petición web puede iniciar una pipeline sin que el conector exija `--allow-execution`. Es una conclusión por inspección de ambos códigos. No se ejecutó ninguna operación contra un servidor remoto ni se afirma una explotación demostrada.

La corrección propuesta es admitir únicamente endpoints de lectura conocidos, con comprobación de ruta normalizada y denegación de rutas desconocidas antes de enviar HTTP. Cambiar solo la anotación no corrige la separación de permisos. Las capacidades futuras de ejecución remota necesitarían autorización explícita y otra herramienta o contrato claramente definido.

Criterios de aceptación: rechazar inicio, parada, eliminación y ejecución mediante GET/HEAD; comprobar variantes de ruta y parámetros; conservar lecturas documentadas, credenciales protegidas, redirects desactivados y límites de respuesta. Un servidor local debe confirmar que una petición prohibida nunca le llega. La cobertura debe incluir la capa de servicio y STDIO.

## 2. Historial y relaciones entre ejecuciones

Los PR [#8850](https://github.com/apache/hop/pull/8850) y [#8865](https://github.com/apache/hop/pull/8865) corrigen detalles y relaciones de las ejecuciones. En el modelo nuevo:

- `parentId` identifica la pipeline o workflow propietario.
- `launchedById` identifica el transform o acción que lanzó la ejecución.
- `childExecutionIds` conserva enlaces desde el lanzador.
- `IExecutionInfoLocation.listExecutions(IExecutionSelector)` permite trabajar con resúmenes; la ubicación de base de datos tiene una implementación que evita cargar cada documento completo. El método por defecto sí puede cargar documentos, por lo que su mera existencia no garantiza menor coste.

Nuestro [`HopExecutionRepository`](../src/main/java/io/github/michaaels/hop/mcp/HopExecutionRepository.java) devuelve únicamente `parent_id` en `summary`, carga documentos durante `historyAtLocation` y obtiene hijos con `findExecutions`. El mismo `parent_id` puede tener distinta semántica en registros antiguos y nuevos. Como la API nueva también encuentra ejecuciones por propietario o lanzador, recorrer ambos vínculos puede producir caminos duplicados o profundidades ambiguas; el conjunto de visitados evita ciclos, pero no define por sí solo la relación que se presenta.

Propuesta: mantener el dato nativo existente y añadir información explícita sobre propietario, lanzador y procedencia de la relación cuando esté disponible. No reinterpretar silenciosamente los registros antiguos ni inventar un padre que no pueda resolverse. Usar un adaptador opcional, probado contra las dos APIs, para conservar la compilación y carga en Hop 2.19. No introducir llamadas incondicionales a métodos exclusivos de 2.20 en el artefacto común.

El optimizador de historial debe conservar el tope de escaneo y la paginación estable. Tanto `listExecutions` como `findExecutions` pueden hacer trabajo mayor que el número de filas finalmente devueltas; limitar el resultado después de una llamada no acota su coste interno. Se necesita medir cada ubicación antes de sustituir la implementación actual.

Criterios de aceptación: fixtures de registros 2.19 y 2.20, propietario/lanzador distintos, lanzador todavía no persistido, registros sin padre, ciclos, límite de profundidad, truncación, estados activos y ubicación grande. El esquema MCP y los diagnósticos deben usar la misma interpretación.

## 3. Cancelación y timeout con JDBC

El [PR #8846](https://github.com/apache/hop/pull/8846) corrige la cancelación de consultas en Table Input y Database Join. El cambio evita omitir la cancelación cuando el transform ya está marcado como detenido y elimina una espera de lock que podía bloquear la parada. Una excepción provocada por la cancelación puede terminar como parada limpia, sin incrementar errores.

[`HopNative.executePipeline`](../src/main/java/io/github/michaaels/hop/mcp/HopNative.java) llama a `engine.stopAll()` durante interrupciones y timeout; por ello recibe directamente el comportamiento del motor instalado. Esa llamada es síncrona: no debe asumirse que el timeout de espera garantiza el tiempo máximo total si la parada o el driver se bloquean. Las pruebas actuales de parada de pipelines no demuestran cancelación de todas las consultas JDBC.

Propuesta: comprobar cancelación real con Table Input y Database Join, diferenciar solicitud de parada, parada confirmada, timeout y fallo, y medir liberación de workers y conexiones. Incluir ejecuciones simultáneas y una llamada MCP posterior para detectar que el servicio no quedó bloqueado. Probar 2.19 y el snapshot identificado, sin prometer que el conector corrige internamente un motor antiguo.

El [PR #8544](https://github.com/apache/hop/pull/8544) añade reintentos a Pipeline Executor. Su timeout es por intento y el retraso entre intentos queda fuera. El presupuesto total del MCP debe seguir siendo global; no reiniciarlo al observar un intento nuevo ni reintentar automáticamente una escritura no idempotente.

## 4. Linter nativo: principal función nueva candidata

Los PR [#8739](https://github.com/apache/hop/pull/8739), [#8742](https://github.com/apache/hop/pull/8742) y [#8829](https://github.com/apache/hop/pull/8829) mejoran `hop lint` y añaden `LintRun`, una API sin interfaz gráfica que comparte políticas con el comando y la acción Run Linter. Hay contexto de proyecto, reglas configurables, baseline y umbrales. La configuración inválida debe producir error; un filtro de visualización no debe ocultar errores para decidir el resultado.

El [PR #8737](https://github.com/apache/hop/pull/8737) evita que los hallazgos incluyan contraseñas leídas por las reglas. Al integrar resultados, el MCP debe conservar además su propia redacción, incluidos mensajes de excepción y contexto.

El conector no tiene una herramienta de lint ni dependencia de ese plugin. `hop_validate` revisa estructura; `hop_deep_check` ejecuta checks nativos con acceso externo potencial. No son equivalentes al nuevo linter.

Contrato propuesto para una versión 2.3.x:

- Nombre `hop_lint`, como capacidad opcional, ausente de `tools/list` sin su autorización. El nombre de un posible flag queda por definir en la implementación.
- Entrada: archivo o ámbito bajo la raíz, configuración local validada, severidad y paginación. Sin argumentos de shell ni selección libre de comandos.
- Salida: `checker`, versión/capacidades del backend, resultado de la política, recuentos por severidad, hallazgos redactados, regla, ubicación, `count_complete`, `has_more` y truncación.
- Configuración inválida, plugin ausente e incompatibilidad deben tener errores tipados distintos. No presentar como éxito un análisis que no pudo ejecutarse.
- No ejecutar checks que resuelvan campos o contacten sistemas externos sin `--allow-deep-check`. La posibilidad de desactivar esos checks debe verificarse en el backend, no inferirse por el nombre «lint».

`LintRun` puede construir un índice de todo el proyecto y buscar configuración en carpetas ascendentes. No debe conectarse directamente sin revisar esos recorridos. Hay que garantizar los límites del conector antes y durante el procesamiento, controlar las rutas referenciadas y acotar la configuración y los mensajes. Un timeout que interrumpe una espera no garantiza que el backend haya dejado de trabajar. Si la API no permite cumplir esos límites, la herramienta debe permanecer sin habilitar hasta disponer de un adaptador adecuado.

Pruebas necesarias: plugin presente/ausente, aislamiento entre proyectos, reglas inválidas, secretos en hallazgos, baseline, umbrales, campos no resueltos, ausencia de acceso externo sin permiso, symlinks, proyecto grande, cancelación y contrato STDIO. No iniciar `hop lint` como subproceso ni empaquetar Hop o el plugin linter dentro del ZIP del conector.

## 5. XML, componentes e inyección

El [PR #8947](https://github.com/apache/hop/pull/8947) mejora XML, XSLT e includes mediante VFS. La factoría de Hop sigue considerando su variable de permiso para DOCTYPE. Nuestro [`HopXml.parse`](../src/main/java/io/github/michaaels/hop/mcp/HopXml.java) debe conservar su política independiente: DTD, entidades externas, esquemas externos y XInclude desactivados. Los includes de datos de un transform no autorizan includes en un archivo de definición leído por el MCP.

El [PR #8813](https://github.com/apache/hop/pull/8813) decodifica valores binarios diferidos en `HopMetadataInjector`. No implica que debamos copiar ese arreglo: [`HopComponentAuthoring`](../src/main/java/io/github/michaaels/hop/mcp/HopComponentAuthoring.java) utiliza `BeanInjector` y construye filas con `ValueMetaString` y valores de texto. Es otro camino de entrada. Debe haber una regresión de ida y vuelta con Unicode, listas y nulos antes de afirmar cobertura común.

Los nuevos componentes relevantes son:

- [Watch Files, PR #8788](https://github.com/apache/hop/pull/8788): plugin `WatchFiles`, motor Local, estado persistente y duración máxima opcional. Probar esquema, exclusión de motores no soportados y parada con duración finita. Sus archivos de estado y operaciones de mantenimiento no deben incorporarse a la gestión de backups del MCP. Los proveedores VFS pueden tardar en terminar llamadas en curso.
- [OpenFormula, PR #8913](https://github.com/apache/hop/pull/8913): plugin `OpenFormula`, distinto de `Formula`; las importaciones Kettle/PDI se orientan al nuevo componente. Probar que catálogo, inyección tabular, campos de salida y recarga distinguen ambos IDs. La presencia de anotaciones de metadata no demuestra por sí sola que todo el esquema sea editable por el MCP.
- [Pipeline Executor, PR #8544](https://github.com/apache/hop/pull/8544): nuevos valores de reintento y retraso, que deben conservarse al cargar y guardar una definición sin tocar esos campos.

El descubrimiento debe seguir basándose en `PluginRegistry` y las APIs nativas. No hacen falta herramientas MCP diferentes para cada transform. La disponibilidad y la capacidad real de inyección dependen de los plugins instalados.

## 6. Pruebas nativas y otras oportunidades

El [PR #8676](https://github.com/apache/hop/pull/8676) reemplaza el enum `TestType` por un `String` en `PipelineUnitTest`, permitiendo tipos personalizados. El código actual del conector no depende directamente de ese enum. No se ha encontrado una rotura directa por ese cambio.

`hop_test_definition` combina validación estructural, deep check y ejecución opcional. No invoca las pruebas nativas de datasets de Hop. Una futura herramienta de pruebas unitarias debería seleccionar un test registrado, aceptar los tipos que exponga el runtime y distinguir sus resultados de la ejecución ordinaria. Requiere permiso de ejecución y, cuando corresponda, de acceso externo.

Los commits recientes incluyen SMB/Git VFS, mejoras de almacenamiento, lakehouse, Beam y conectores de grafos. Son motivos para ampliar fixtures de metadata y detección de plugins; no para permitir URLs VFS arbitrarias en las herramientas locales de archivos. La raíz local y la redacción deben mantenerse.

El [PR #8870](https://github.com/apache/hop/pull/8870) mejora el asistente AI, sus sesiones y el contexto de propuestas. Es una integración diferente del servidor MCP. Para coordinarla en el futuro, las propuestas deben seguir pasando por los objetos semánticos de Hop y por preview, SHA esperado, backup protegido, recarga y rollback del conector. Este análisis no demuestra una conexión MCP nativa nueva en ese asistente.

## Secuencia de desarrollo propuesta

1. **Corrección de seguridad web:** cerrar el acceso a endpoints con efectos y añadir pruebas de servicio/STDIO. Es un candidato a parche porque reduce una autorización existente; no incorpora ejecución remota nueva.
2. **Compatibilidad y observabilidad:** adaptador 2.19/2.20 para relaciones de ejecuciones, esquemas coherentes y pruebas de JDBC real. Mantener la base estable 2.19 y describir 2.20 como compatibilidad con un snapshot identificado.
3. **Nueva versión menor:** integrar `hop_lint` opcional una vez demostrados autorización, límites, redacción, aislamiento y errores del backend. Añadirlo a capabilities, documentación y pruebas completas de herramientas nuevas.
4. **Cobertura de componentes:** fixtures de OpenFormula, Watch Files y Pipeline Executor; después, valorar una herramienta explícita de pruebas unitarias nativas.

Las estimaciones de prioridad no implican que esas funciones estén implementadas. Este trabajo añade el análisis y verifica el código existente; no cambia el runtime ni publica una versión.

## Validación realizada

- Hop 2.20: se ejecutó `clean verify` con `-U -P hop-2.20` contra `hop-core`, `hop-engine` y `hop-ui` resueltos como **2.20.0-20261015.215637-1**. El segundo intento terminó con `BUILD SUCCESS`: 135 pruebas, 0 fallos, 0 errores y 3 omitidas. El primero terminó durante descarga por timeout de conexión, antes de compilar.
- El ZIP del build de compatibilidad pasó `check-marketplace-zip.py`: layout, avisos legales, Jandex, ausencia de runtime Hop y SBOM según las comprobaciones del proyecto.
- El timestamp Maven identifica el snapshot descargado; no prueba que sus binarios correspondan exactamente al commit Git fijado para la revisión de fuentes.
- Hop 2.19 estable: `clean verify` terminó con `BUILD SUCCESS`: 135 pruebas, 0 fallos, 0 errores y 3 omitidas. Su ZIP también pasó `check-marketplace-zip.py`. La salida final de `target/` corresponde a este build estable.
- Las tres pruebas omitidas en ambas ejecuciones corresponden a enlaces simbólicos (`rejectsSymbolicLinkBackupRoot`, `walkerDoesNotFollowSymbolicLinks` y `aliasesCannotExposeExternalOrInternalFiles`). Estos resultados no certifican esas rutas omitidas.
- No se ejecutaron aquí la batería externa de conformidad oficial, una instalación limpia completa de Hop 2.20, consultas JDBC bloqueadas reales ni los nuevos plugins de 2.20 en una distribución completa. Compilar y pasar las pruebas actuales no demuestra esas coberturas.
