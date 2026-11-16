# Cobertura del MCP frente a Apache Hop 2.20.0

Fecha: 13 de noviembre de 2026. MCP 2.4.0, commit `1f6653121ad88007d9a48955853a4bbdaf32dc41`.

## Resultado

**La compatibilidad con Hop 2.20.0 está validada, pero no está certificada la cobertura funcional completa de la entrega.** El MCP descubre dinámicamente los componentes instalados y delega su ejecución a Hop. Esto no implica que pueda editar todas sus opciones, ejecutar todos sus motores o probar todos los cambios del producto.

La auditoría inicial identificó tres problemas reproducibles en el commit de main indicado, límites de autoría y pruebas pendientes de plugins opcionales. Los tres problemas están corregidos y probados en la rama local `codex/audit-hop220-20261113`, según la sección de correcciones. El linter sigue aplazado por decisión previa. No se ha publicado una nueva versión ni integrado esta rama en GitHub.

## Fuentes y alcance fijado

- [Distribución oficial del código fuente](https://downloads.apache.org/hop/2.20.0/apache-hop-2.20.0-src.tar.gz) y su [SHA-512](https://downloads.apache.org/hop/2.20.0/apache-hop-2.20.0-src.tar.gz.sha512): descargados y comprobados. Código extraído en `D:\dev\hop-2.20.0`; 25.757 archivos regulares. El alias documental `CLAUDE.md -> AGENTS.md` se materializó como copia para Windows; el archivo descargado permanece intacto. No se verificó la firma PGP.
- [Release oficial](https://github.com/apache/hop/releases/tag/2.20.0-rc1): GitHub identifica la entrega estable como `2.20.0` y conserva el nombre de tag `2.20.0-rc1`, con `prerelease=false`. Se usa su commit `f7b464dbf123de6832f79dc0e53a0673066c9d51`, no el `main` posterior. El POM, `Variables.java` y `Pipeline.java` del archivo oficial coinciden byte a byte con ese commit.
- Comparación Git completa desde la entrega anterior, `2.19.0-rc1` / `46436154ae1a1e940861d485559819360c2af86e`: **667 commits**, 344 agrupaciones de módulos/rutas raíz. Los nombres de los tags identifican las entregas; no significan que se haya probado un snapshot.
- El [anuncio oficial](https://hop.apache.org/blog/2026/11/hop-2.20.0/) se utilizó como índice de áreas y cambios de comportamiento. Las conclusiones del MCP se sustentan además en sus fuentes, en las APIs de la entrega y en las comprobaciones descritas abajo.

El inventario de commits es completo para ese intervalo. Dos commits no cambian el árbol de archivos y se identifican como `SIN_CAMBIO_DE_ARBOL`. Su clasificación por rutas es un triaje, no una revisión manual de todas las líneas ni una certificación de ejecución de cada cambio. La revisión detallada se concentra en registro/inyección, metadatos, variables, ejecución, mappings, linter y límites de las integraciones Web.

Inventarios consultables:

- [667 commits con hash completo, fuente, módulos y clasificación](hop-2.20-commits-2026-11-13.csv).
- [344 agrupaciones de módulos y alcance del triaje](hop-2.20-modules-2026-11-13.csv).
- [281 componentes de la distribución limpia y resultado de descubrimiento de esquema](hop-2.20-components-2026-11-13.csv).

## Evidencia ejecutada

| Comprobación | Resultado y límite |
| --- | --- |
| `mvn -B clean verify`, Java 21, Hop 2.20.0 | 228 casos, 0 fallos, 0 errores, 3 omisiones de symlinks en Windows |
| Guardas Windows de junctions | Las tres pruebas requeridas ejecutadas y correctas |
| Verificación de Marketplace ZIP | Layout, versiones, licencia/aviso, Jandex y restricciones del verificador correctos; generados SBOM JSON/XML |
| Cliente oficial `apache-hop-client-2.20.0.zip` | Descargado y verificado contra SHA-512; plugin recién compilado instalado en una copia aislada |
| Descubrimiento por STDIO | 208 transforms y 73 acciones; 281 consultas `hop_component_schema` correctas; cierre por EOF con código 0 |
| Metadatos | 33 tipos descubiertos; esto no certifica la lectura de todas sus estructuras ni sus conexiones |
| Catálogo de herramientas con mutación habilitada | 135.313 bytes en la respuesta observada; ejecución y deep checks no habilitados en el proceso de descubrimiento |
| Inyección nativa | Anunciada por 276 de los 281 componentes. En transforms: 179 con propiedades escalares y 120 con grupos tabulares; en acciones: 73 y 21, respectivamente. Son capacidades de esquema, no ejecuciones funcionales |
| Sondas dirigidas de configuración y metadatos | Reprodujeron los tres hallazgos descritos debajo, usando solo valores sintéticos |

La [CI del mismo commit de main](https://github.com/michaaels/hop-mcp-connector/actions/runs/43543083614) también terminó correctamente: build Linux, seguridad Windows, aceptación en una instalación limpia de Hop 2.20 y baseline de conformidad MCP. La CI verde y el baseline de conformidad no sustituyen las pruebas de funcionalidades que no contienen.

Evidencia local: `outputs/hop220-audit-20261113/`, con procedencia de fuentes/runtime, inventario Git, `verify.log`, `coverage-summary.json` y las sondas reproducibles. `before-fixes/` conserva la sonda, el catálogo y el resumen originales; `focused-probe.json`, `catalog-probe.json` y `build-summary.json` registran las comprobaciones posteriores a la corrección. Los intentos iniciales del cliente de auditoría se corrigieron para usar los argumentos documentados `kind=pipeline|workflow` y el ID canónico `id`; no se contabilizan sus errores de entrada como defectos del MCP.

## Hallazgos reproducidos

### P1 — Redacción transitiva de variables de configuración

`HopRunConfigurationService.addVariable` detecta el nombre secreto y las referencias directas, pero pierde esa condición cuando otro alias consume el valor ya resuelto. La sonda de una configuración nativa con dos aliases devolvió el valor sintético mediante un alias, aunque ocultó correctamente la variable secreta original. Resultado: `run_configuration_alias_exposes_dummy=true`.

Esto es un defecto del conector auditado, no una vulnerabilidad atribuida a Apache Hop. Es relevante para resolutores de secretos y herencia de variables. Se adaptó la protección de la rama local anterior al código 2.4.0, añadiendo cobertura de aliases Windows, sustituciones de aliases y expresiones opacas. La sonda posterior retorna `run_configuration_alias_exposes_dummy=false`. La corrección permanece local.

Fuente MCP: `HopRunConfigurationService.java:126`. Contexto nativo: `core/src/main/java/org/apache/hop/core/variables/Variables.java` y `DescribedVariable` de la entrega.

### P1 — Valores secretos en filas de metadatos

`HopMetadataService.projectValue` redacta por la clave de la propiedad, pero una `DescribedVariable` representa el nombre sensible y su valor en campos separados. La lectura de la configuración nativa conservó el valor sintético en esa fila: `metadata_variable_row_exposes_dummy=true`, aunque el resultado anuncia `redaction_applied=true`.

Se adaptó la corrección que reconoce `DescribedVariable`; las pruebas de servicio y STDIO comprueban que la fila no expone el valor sintético. La sonda posterior retorna `metadata_variable_row_exposes_dummy=false`. No se usaron credenciales de usuarios ni se contactaron servicios de secretos. La corrección permanece local.

Fuente MCP: `HopMetadataService.java:262` (`projectValue`) y proyección de propiedades. Fuente nativa: `PipelineRunConfiguration.configurationVariables`.

### P2 — Esquema de salida de metadatos insuficiente

La configuración nativa proyecta `configurationVariables` como una lista de objetos. `HopMcpServer.metadataValueSchema` solo permite escalares dentro de listas y objetos. El validador JSON Schema del SDK rechazó la salida del servicio nativo: `native_run_configuration_matches_advertised_metadata_schema=false`.

Se adaptó un esquema compartido de profundidad finita que refleja la proyección real y conserva sus límites. Las pruebas cubren objetos/listas, profundidad 8, 100 elementos más marcador de truncación, 128 propiedades anidadas y las tres identidades añadidas a la raíz: 128 propiedades nativas más 3 identidades. Esto corrige el esquema; no aumenta lo que procesa el servicio. La sonda posterior retorna `native_run_configuration_matches_advertised_metadata_schema=true`. Este hallazgo dependía de la estructura del objeto y la corrección permanece local.

Fuente MCP: `HopMcpServer.java:1825`. Evidencia: diagnóstico del validador en `focused-probe.json`.

## Matriz de las áreas relevantes

Estados: **verificado** significa únicamente el escenario indicado; **nativo** significa comportamiento delegado a Hop; **parcial** identifica una limitación; **pendiente** requiere trabajo o evidencia adicional. Ninguna fila implica certificar todas las opciones del área.

| Área de Hop 2.20 | Tratamiento en el MCP actual | Evidencia o pendiente |
| --- | --- | --- |
| Registro de transforms y acciones | Verificado para el cliente limpio | 281 descubrimientos y esquemas correctos; depende de los plugins instalados |
| Nuevas propiedades inyectables | Parcial, por contrato | Se exponen escalares y un nivel tabular; se excluyen propiedades sensibles/estructurales y colecciones anidadas |
| Vector y tipos de campos | Descubrimiento verificado | `Vector` aparece en los valores permitidos nativos; falta prueba del recorrido de datos y de sus destinos |
| Watch Files | Esquema y regresión nativa existente | 27 propiedades descubiertas; pruebas existentes de creación/recarga, sin certificar todos los backends VFS o modos de seguimiento |
| OpenFormula | Esquema y regresión nativa existente | Grupo `FORMULAS`, creación/recarga y campos de salida cubiertos por pruebas selectivas |
| SSH: timeout de comando | Round trip nativo probado localmente | `commandTimeOut` y `timeOut` se preservan por separado tras creación y recarga; no se abrió una conexión SSH |
| Calculator: valores legacy | Regresión nativa incorporada localmente | Una edición semántica conserva longitud/precisión -1 cuando faltaban en el XML legacy |
| MetaInject: condición de streams | Migración nativa probada localmente | Carga legacy, precedencia del enum actual y escritura de tipo/código canónicos |
| Pipeline Executor: reintentos | Parcial | La prueba existente conserva valores al editar; las opciones de reintento no aparecen en su esquema de autoría observado |
| Espera de ejecuciones anidadas | Descubrimiento verificado | `wait_timeout` aparece en ambos executors y en las acciones `PIPELINE`/`WORKFLOW`; faltan escenarios de timeout anidado y sus resultados |
| Parámetros y valores por defecto | Parcial | Crear/eliminar declaraciones está cubierto; no se certificó toda la semántica nueva de paso explícito/herencia de cada componente |
| Multi Mapping | Parcial, límite concreto | Se descubren mappings de entrada/salida, pero no sus colecciones anidadas `INPUT_RENAMES`/equivalentes; no puede afirmarse autoría completa |
| Database value validation y SQL Server bulk loader | Descubrimiento verificado | `DatabaseValueValidation` y `MsSqlServerBulkLoader` presentes; sin ejecución contra bases externas |
| Git Input | Descubrimiento verificado | `GitInput` presente; sin consultas a proveedores Git ni validación de todas sus operaciones |
| Text Chunker, Embed Text, Structured Extract | Descubrimiento verificado | Componentes presentes y esquemas correctos; no se invocaron modelos ni APIs de IA |
| AI Provider | Tipo descubierto, lectura parcial | Esquema anidado corregido localmente; faltan pruebas específicas de los proveedores y modelos |
| Mask Fields y Masking Pattern | Descubrimiento verificado, comportamiento nativo | Grupo de campos y tipo de metadato presentes; no se probó persistencia/HMAC, migración ni concurrencia entre procesos |
| Naming Scheme y otros metadatos nuevos | Tipo descubierto | `naming-scheme` presente; el MCP no ofrece un CRUD general de metadatos |
| Graph database connections | Tipo descubierto, ejecución pendiente | `graph-database-connection` presente; no se probaron migraciones Neo4j ni clientes Bolt, AGE, Gremlin o FalkorDB |
| Cambios de claves de metadatos | Delegación nativa revisada | El proveedor nativo usa `HopMetadataUtil.matchesKey`; falta una batería MCP de todos los directorios legacy y proyectos heredados |
| Lakehouse e Iceberg | Plugin opcional pendiente | `SparkLakeTableInput`/`SparkLakeTableOutput` no estaban instalados en el cliente limpio observado; IDs reales revisados en fuentes |
| pgvector | Plugin opcional pendiente | `PgVectorSearch`/`PgVectorUpsert` ausentes del cliente limpio; faltan instalación, round trip y pruebas PostgreSQL |
| dbt | Plugin opcional pendiente | Acción `DBT` ausente del cliente limpio; faltan esquema con plugin instalado y ejecución controlada |
| Spark/YARN y Beam IO | Ejecución nativa sin certificación MCP completa | No se desplegaron clústeres ni los conectores externos; no equivalen al motor local probado |
| Linter, CLI y acción Run Linter | Aplazado | No existe `hop_lint`; `RUN_LINTER` no estaba instalado. Persisten recorrido/índice global y configuración global de native checks en la API revisada |
| HTTP y endpoints nuevos de Hop Server | Alcance restringido intencionalmente | El proxy del MCP sigue limitado a lecturas autorizadas; no se amplió a toda la API `/hop/api/v1` |
| Hop Web: permisos, OAuth2 y sesiones | Adaptador y pruebas selectivas | La CI cubre fixtures HTTP/sesión; no se revalidó todo un despliegue con roles, OAuth2 y proxy real |
| Live Sync y edición guiada Desktop/RAP | Adaptación existente con evidencia delimitada | Pruebas y reportes previos del proyecto; no se repitieron todas las interacciones manuales durante esta auditoría |
| Exploradores, perspectiva de BD, paletas y AI Assistant nativo | Función de la UI de Hop | No se convierten automáticamente en herramientas MCP; no son una API de control general del escritorio |
| VFS SMB/FTP/Git y namespaces | Metadatos descubiertos y ejecución nativa | No se probaron todos los backends; los archivos del proyecto MCP siguen confinados al root local |
| Resolutores de variables y secretos | Redacción corregida localmente; backends pendientes | No se habilitó importación global del entorno ni se contactaron Vault/AWS; expresiones opacas se ocultan conservadoramente |
| Ejecuciones: padre/lanzador y descendientes | Adaptado y probado selectivamente | API nativa 2.20, fixtures, límites de recorrido y procedencia de relaciones; no todas las ubicaciones de almacenamiento |
| `PipelineCompleted` y fin nativo | Regresión incorporada localmente | El MCP espera el callback y recoge su error en el resultado; probado con el motor real |
| Cancelación y JDBC | Pruebas existentes selectivas | H2 con Table Input/Database Join; no garantiza cancelar todos los statements auxiliares, drivers o threads creados por Hop |
| Text File Input paralelo, JSON, fechas, DDL y resultados de acciones | Nativo con pruebas MCP incompletas | Se heredan las correcciones del runtime; requieren casos de negocio si se quiere certificar sus cambios semánticos desde MCP |
| XML, STDIO, autorización y límites | Verificado en escenarios del proyecto | No se flexibilizaron flags, rutas, XML ni presupuestos para obtener compatibilidad |
| Dependencias, build, Docker, traducciones y documentación de Hop | Dependencia o función upstream | Inventariadas por rutas; no necesitan una herramienta por commit, ni son por sí solas pruebas funcionales del MCP |

Los cinco componentes sin inyección nativa anunciada son `RowsToResult`, `DetectEmptyStream`, `Dummy`, `FilesFromResult` y `MappingOutput`; varios carecen de opciones que necesiten edición. Además, `GetTableNames` y `StringOperations` no devolvieron campos editables en el esquema observado. Esto debe interpretarse por componente, no como siete fallos automáticos.

## Correcciones locales y validación posterior

Los cambios de producción se limitan a la redacción de configuraciones/metadatos y al esquema de salida. No añaden herramientas ni permisos. La versión Maven permanece en 2.4.0 y el único target sigue siendo Hop 2.20.0.

- La primera ejecución dirigida reprodujo los fallos antes de aplicar las correcciones: 28 casos, 8 fallos y 0 errores. Se conservan `focused-before.log` y `focused-before-reports/`.
- La ejecución dirigida posterior aprobó 32 casos sin fallos, errores ni omisiones. Incluye las regresiones de privacidad, esquema, STDIO y componentes nativos.
- El build completo de Windows posterior aprobó 245 casos, sin fallos ni errores, con 3 omisiones de symlinks. Pasaron el verificador de ZIP y las tres guardas de junctions. Logs: `fixes-verify.log`.
- El build completo de Linux posterior aprobó 245 casos, sin fallos ni errores, con 2 omisiones exclusivas de junctions de Windows. Pasaron las cuatro guardas obligatorias de confinamiento Linux y la verificación del ZIP. Se usaron Java 21, Maven 3.9.11, 512 MiB para el proceso de pruebas y 384 MiB para Maven; los 117 archivos de entrada Maven/fuentes/recursos coinciden byte a byte con el candidato Windows. Logs: `fixes-linux-verify.log` y `candidate-source-sha256.json`.
- La instalación del plugin corregido en el cliente oficial aislado aprobó las llamadas STDIO de lectura de metadatos y resolución con aliases Unix/Windows: se ocultan los valores sintéticos y se conserva un valor público. Se repitieron correctamente las 281 consultas de esquema; catálogo de 138.459 bytes y cierre por EOF con código 0.
- El esquema de metadatos usa 9 definiciones compartidas, de profundidad finita. Las pruebas comprueban que ocupa menos de 32 KiB y sigue admitiendo los errores tipados del protocolo.
- Los tres plugins adicionales de regresión son dependencias Maven `test`; no se introducen bibliotecas de runtime de Hop en el ZIP.
- En ambos paquetes se verificaron Jandex, SBOM JSON/XML, exclusión de dependencias Hop/RAP/Servlet y de pruebas, y el contenido limitado a los dos helpers previstos del JAR de aceptación. Las 16 pruebas de guardas de verificación también aprobaron.

## Trabajo restante para cerrar la cobertura

1. Integrar las correcciones locales y sus regresiones después de revisar sus checks remotos; estas comprobaciones locales no significan que se haya ejecutado CI sobre el candidato.
2. Ampliar los casos de metadatos reales de AI Provider y otros plugins, más allá de la configuración nativa y los límites estructurales ya probados.
3. Mantener las regresiones añadidas de Calculator, SSH, MetaInject y `PipelineCompleted` en el build ordinario del target único 2.20.
4. Definir expresamente el alcance de autoría para colecciones anidadas, mappings, reintentos y configuración de motores. El soporte de esquema de un componente no debe anunciarse como configuración completa.
5. Instalar en runtimes descartables los plugins opcionales priorizados y añadir pruebas de round trip. Probar ejecución real de lakehouse/pgvector/dbt/Spark/Beam solo con sus dependencias y escenarios controlados.
6. Añadir casos de migración de metadatos, relaciones de ejecución y cambios de comportamiento relevantes a los proyectos del usuario. Mantener el linter aplazado hasta resolver su contrato acotado y sus permisos.

Esta revisión no solicita un cambio de permisos ni publica un artefacto. Deja un inventario trazable y separa lo probado de lo delegado y pendiente; no justifica una declaración de cobertura al 100 %.
