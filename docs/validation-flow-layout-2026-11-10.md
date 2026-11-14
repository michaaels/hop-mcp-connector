# Distribución visual de flujos mediante MCP

Se añadió `auto_layout` a la operación semántica existente `hop_mutate_definition`, tanto para pipelines como para workflows. Se usa al terminar de crear un flujo; en flujos existentes se revisa primero el preview. El motor dispone componentes por etapas de izquierda a derecha, separa ramas de fallo, alinea ramas y busca reducir cruces y conexiones que atraviesen otros componentes. Conserva la lógica y las conexiones nativas de Hop.

No se añadieron herramientas, dependencias, pasos de negocio ni permisos. El JAR del baseline verificado se instaló con respaldo en `C:\hop\plugins\misc\hop-mcp-connector`. Los procesos MCP y GUI abiertos deben reiniciarse para cargar el nuevo código; los permisos de autoría existentes siguen siendo necesarios.

El JAR pasó de 462.919 a 477.832 bytes: 14.913 bytes adicionales, sin nuevas bibliotecas. Se verificaron la estructura del ZIP, LICENSE/NOTICE, el índice Jandex, la ausencia de JARs del runtime Hop, los SBOM y la identidad entre el JAR instalado y el baseline comprobado.

## Prueba sobre copias de flujos reales

Se copiaron, sin editar el XML, dos definiciones de `D:\dev\pentaho\PENTAHO_PROCESS_HOP` a un proyecto privado. Todas las modificaciones de esas copias se realizaron mediante la operación semántica nativa del MCP. El servidor de prueba tenía autoría habilitada y ejecución deshabilitada. No se ejecutó ETL ni SQL.

| Flujo | Componentes | Cruces habilitados antes → después | Retrocesos antes → después | Solapamientos finales |
| --- | ---: | ---: | ---: | ---: |
| execute_analisis.hwf | 10 | 0 → 0 | 1 → 0 | 0 |
| orchestrate_analisis.hpl | 21 | 3 → 3 | 2 → 0 | 0 |

Ambas copias pasaron la recarga y la validación estructural nativas. `hop_definition_diff` devolvió comparación completa, sin truncamiento, e identidad semántica: sin cambios en componentes, conexiones, condiciones, parámetros, configuración o referencias. Esto incluye las 133 filas del Data Grid de reprocesos. Los originales activos se compararon por SHA-256 y permanecieron idénticos.

El algoritmo no eliminó los tres cruces del orquestador. Se informa esta limitación; no se afirma un mínimo global ni se cambia la lógica para mejorar el dibujo. El resultado incluye `review_required` cuando persisten problemas geométricos medidos. Las notas conservan su posición, y los enlaces desactivados no participan de las métricas.

## Validación y alcance

Java 21, `fmt:format clean verify` sobre Hop 2.19 y perfil de compatibilidad Hop 2.20.0 final: 183 pruebas por perfil, cero fallos y cero errores en las ejecuciones finales; 10 y 3 omitidas respectivamente. Seis pruebas nuevas cubren cruces, ramas de fallo, repetición determinista, ciclos, uniones, componentes desconectados, límites, conexiones que atraviesan componentes, preservación de parámetros/configuración y preview/aplicación/SHA/rollback nativos. La prueba STDIO existente ejerce la nueva operación y valida su respuesta contra el esquema anunciado.

Durante una compilación intermedia se observó un NullPointerException en la prueba preexistente `HopExecutionManagerTest.timeoutRemainsStoppingWithItsResultUntilTheNativeWorkerExits`, al consultar `result` tras observar el estado `stopping`. Las pruebas de distribución pasaron también en esa ejecución. No se modificó ni debilitó esa prueba; las ejecuciones completas posteriores de ambos perfiles pasaron. La observación queda registrada para investigar la publicación concurrente de estado/resultado por separado.

El perfil de compatibilidad produjo advertencias al limpiar un directorio temporal del formateador; Maven terminó correctamente. Las pruebas de XML inválido/DTD imprimen errores deliberados como parte de los casos negativos, con aserciones correctas.

El motor aplica límites de 200 componentes y 1.000 conexiones, cuatro barridos de ordenamiento y un número acotado de mejoras locales. No se ha medido un benchmark comparativo de latencia o memoria bajo concurrencia. Es una capacidad nueva; no existe una métrica anterior de complejidad del algoritmo. Los bucles de geometría se mantienen locales y acotados, y no se modificó el mecanismo de escritura transaccional existente.

## Evidencias

Carpeta `D:\dev\build_hop_web\mcp\flow-layout\20261110`:

- `baseline.log`, `hop220.log`: compilaciones y pruebas finales.
- `original-hashes.json`: huellas de los originales activos.
- `layout-plan.json`, `layout-result.json`: preview, aplicación, validación y comparación semántica mediante MCP.
- `project/before`, `project/after`: copias de prueba.
- `comparacion-flujos.html`: comparación visual interactiva de las coordenadas nativas; representación del lienzo, no captura de Hop GUI.
- `before-layout.jar`, `baseline-plugin.jar`, `baseline-plugin.zip`: respaldo y artefactos del baseline.

Contrato y uso: [flow-layout.md](flow-layout.md).
