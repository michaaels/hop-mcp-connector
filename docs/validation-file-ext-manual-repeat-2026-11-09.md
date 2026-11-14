# Validación local: parámetros, Repeat y copia de filas

Fecha: 2026-11-09, America/Guayaquil. Cambios locales no publicados, versión
de desarrollo 2.2.6. Cliente Apache Hop 2.20.0 y Java 21.

## Cambios aplicados mediante MCP

Proyecto: `D:\dev\pentaho\PENTAHO_PROCESS_HOP`.

- `file_exists.hpl`: el filtro fijo de extensiones fue reemplazado por un
  transform JavaScript que compara tokens de `file_ext` separados por comas.
  Normaliza espacios, mayúsculas y prefijos `.`/`*.`. Una lista no vacía es
  restrictiva; una lista vacía mantiene `bil,adf,hdr,json`. No interpreta los
  tokens como expresiones regulares. Si ninguna extensión coincide, falla la
  comprobación de archivos para permitir el reintento.
- El filtro conserva el nombre `Filter rows (extension)`, pero ahora su plugin
  es `ScriptValueMod`. Sus dos salidas usan copia de filas (`distribute=false`):
  una conserva las filas de archivos en el resultado y otra detecta un flujo
  filtrado vacío. Detect empty stream solo emite una fila cuando su entrada
  está vacía; por eso la comprobación es una rama separada.
- `execute_analisis.hwf`: selecciona el pipeline de `manual_process` cuando
  contiene una ruta `.hpl`; acepta rutas absolutas/URI o relativas al workflow.
  Un valor vacío selecciona `process_analisis.hpl`. La selección se realiza
  después de comprobar los archivos y mantiene la restricción de calendario.
- La acción Repeat ejecuta el nuevo `check_files_retry.hwf`. Se eliminaron
  RETRY, Wait for y Dummy 2 del padre, y la acción file_exists se movió al hijo.
  El hijo controla los intentos y usa End repeat. El procesamiento y los
  correos permanecen en el padre, por lo que no se repiten con cada intento.
- Se conserva una espera de 1800 segundos y `MAX_LOOP_COUNT=5`: un intento
  inicial y hasta cinco reintentos adicionales. Con cero hay un solo intento.
  Se valida que el máximo sea entero, entre 0 y 1000. `LOOP_COUNT` permanece
  declarado por compatibilidad, pero ya no controla el ciclo.
- Se limpian las filas de entrada antes de comenzar el ciclo, para que las
  filas del catálogo no se mezclen con los archivos devueltos al procesamiento.
- `orchestrate_analisis.hpl`: el lookup de la rama de lotes ahora recupera
  también `file_ext` y `manual_process` desde el catálogo; la rama normal ya
  conservaba ambos campos.
- Se eliminaron dos destinos predeterminados obsoletos de Switch / case en
  file_exists que no tenían una conexión de salida válida.

Los cuatro archivos se escribieron con `hop_mutate_definition`, después de
previsualización y con SHA-256 para los archivos existentes. El MCP confirmó
recarga nativa y reemplazo atómico en las cuatro escrituras. Las tres
modificaciones de archivos existentes tienen respaldo protegido del MCP.
Hay además una copia externa persistente de los cinco archivos originales en
`D:\dev\build_hop_web\mcp\changes\20261109-114722\original`.

La sesión conectada a Codex permanece de solo lectura. Se abrió una sesión
MCP temporal con `--allow-mutation` para estos cambios. La ejecución se habilitó
únicamente en la sesión del proyecto de pruebas aislado, con archivos
sintéticos, correos sustituidos por Dummy y sin procesamiento de producción.

## Correcciones en el código fuente del MCP

1. `HopDefinitionMutator`, `HopMcpServer` y `HopSemanticCapabilities`: permiten
   `distribute` como booleano de primer nivel de add_component/update_component
   para pipelines. La implementación llama a la API nativa
   `TransformMeta.setDistributes`, preserva el valor omitido al actualizar y
   rechaza su uso en workflows u operaciones no relacionadas. Admite un cambio
   que solo modifique esta propiedad. La prueba comprueba creación, preview sin
   escritura, precondición, entradas inválidas, recarga y rollback por hash.
2. `HopRouting`: la validación estructural ahora rechaza Repeat sin filename o
   run_configuration, y un delay literal negativo, fraccionario, inválido o que
   desborde su conversión a milisegundos. Conserva valores con variables sin
   evaluarlas. Sin variable de terminación advierte que debe existir End repeat
   en el hijo; no rechaza ese patrón válido ni ejecuta el archivo hijo.

No se agregaron herramientas ni se habilitaron permisos de forma global.

## Evidencia

| Comprobación | Resultado |
| --- | --- |
| Hop 2.19, Java 21, clean verify | 168 tests, 0 fallos/errores, 10 omitidos |
| Hop 2.20.0 final, Java 21, clean verify | 168 tests, 0 fallos/errores, 3 omitidos |
| Comprobaciones JavaScript puras | 16 aprobadas; listas CSV y selección de ruta |
| Ejecuciones locales aisladas mediante MCP | 8 escenarios aprobados |
| Proyecto real, validación estructural mediante MCP | 6 definiciones válidas, sin errores ni advertencias |
| Repeat incompleto, comprobación mediante MCP instalado | Rechazado con errores de filename/run_configuration |
| Conexión final con la configuración de Codex | 24 herramientas, catálogo de 92.559 bytes, EOF exit 0 |
| ZIP local | LICENSE/NOTICE, Jandex y SBOM; sin JAR de runtime Hop ni tests |

Las ocho ejecuciones verifican: éxito inmediato; éxito tras dos fallos;
selección manual; agotamiento tras dos reintentos; cero reintentos; rechazo de
archivos cuya extensión no está en la lista; CSV normalizado y lista vacía con
valores por defecto. Se comprueban el número y orden de intentos, la condición
de éxito/error, el pipeline elegido y el transporte de las filas al pipeline
de procesamiento. Se inyecta una fila obsoleta para verificar su limpieza.
Las esperas fueron cero únicamente en las copias de prueba.

El build estable offline omite SBOM; el build final de compatibilidad genera y
valida CycloneDX JSON/XML. La evidencia y los planes están bajo
`D:\dev\build_hop_web\mcp\changes\20261109-114722`.
No se ejecutó el ETL real ni se probaron recursos externos de producción.

Artefacto: `target/hop-mcp-connector-2.2.6.zip`, 6.110.719 bytes.
SHA-256: `B1FA6005C4186C110E2843FA8107FF452A9097013851126C822A4A3E5676CA21`.
El JAR instalado en `C:\hop` coincide con el compilado final:
`F660849BCE002A513372F2ACEA8C79C40F177354CCB01F27907B4F84E84E3850`.

Referencia de comportamiento nativo:
[Repeat, Apache Hop](https://hop.apache.org/manual/latest/workflow/actions/repeat.html).
