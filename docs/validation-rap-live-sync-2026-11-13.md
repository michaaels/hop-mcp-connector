# MCP Live Sync en Hop Web/RAP

Implementación revisada el 13 de noviembre de 2026. Las pruebas interactivas iniciales se realizaron el 12 de noviembre sobre una instancia local desechable de Hop Web **2.20.0**, Tomcat **10.1.59** y Java **21**, enlazada únicamente a `127.0.0.1:18082`. El 13 de noviembre se instaló el paquete en el servidor de `https://10.32.112.2/hop_web/ui`, con Tomcat detenido por el usuario; después de que el usuario lo arrancara, se validaron la interfaz y las operaciones MCP descritas al final de este informe.

## Cambios

- Publicar presentaciones cuando haya una sesión `desktop` o `web` activa. Los dos adaptadores consumen la misma cola y los mismos borradores nativos protegidos.
- Utilizar los editores nativos de pipelines y workflows dentro de Hop, con controles de velocidad, pausa y salto al resultado existentes. No se añade una aplicación ni un renderizador de diagramas.
- Temporizador Web de 100 ms, Desktop de 33 ms y pausa/espera de 250 ms. Redibujar el lienzo solo cuando cambie el paso o la interpolación. Estos intervalos son configuración, no resultados de un benchmark de FPS o tráfico.
- Cambiar el menú y los títulos de diálogos a **MCP Live Sync**, manteniendo el identificador del plugin, las coordenadas Maven y `hop mcp`.
- Durante la destrucción del shell, dejar que SWT libere sus pestañas hijas. Eliminar primero las referencias a las vistas evita que el cierre del MCP intente retirar pestañas con imágenes RAP ya liberadas.

Se conservan los permisos, los límites, la protección de pestañas con cambios sin guardar, las huellas SHA-256, las copias protegidas y el rollback. No se añaden dependencias Maven ni herramientas MCP.

## Verificación

La compilación `fmt:format clean verify` registró **201 pruebas: 0 fallos, 0 errores y 3 omitidas** por enlaces en Windows. Las pruebas de componentes nativos Hop y cancelación JDBC se ejecutaron sin omisiones. Un caso nuevo comprueba publicación con una sesión exclusivamente Web, lectura del borrador protegido, rechazo de un cliente desconocido y evento terminal instantáneo.

Una repetición previa falló con `AccessDeniedException` de Windows al mover un archivo temporal en la prueba del límite de backups. Se conservó ese resultado en `verify-access-denied.log` y `access-denied-test.txt`; se repitió la compilación completa en un directorio temporal nuevo, sin cambiar ni omitir la prueba. No se ha determinado qué proceso retenía el archivo.

| Caso interactivo en RAP | Resultado observado |
| --- | --- |
| Nombre del menú y diálogo | `MCP Live Sync` visible; activación para el proyecto aislado |
| Preparación sin operaciones | Pestaña nativa con estado de espera; archivo real todavía ausente |
| Bloque de 13 operaciones | Creación progresiva de seis transformaciones Dummy y cinco hops |
| Pausar/continuar | Se detuvo la presentación y continuó desde el mismo paso |
| Añadir un bloque mientras estaba pausada | Tres operaciones adicionales, misma presentación y nueva revisión |
| Aplicar mientras estaba pausada | Archivo guardado; la presentación permaneció pausada y protegida |
| Velocidad 2× | Selección visible y continuación de los pasos pendientes |
| Finalización | Apertura del pipeline nativo editable; acknowledgement `opened` |
| Rollback mediante MCP | Eliminación del pipeline de prueba y cierre de su pestaña; acknowledgement `closed` |

Las llamadas de preparación/aplicación regresaron antes de completar la presentación. Se conservaron sus respuestas y capturas bajo `D:\dev\build_hop_web\mcp\rap-live-sync\20261112`. No se ejecutaron pipelines ni ETL de negocio.

El candidato de las capturas tiene SHA-256 `553f9024d01a120588f6bf5ba7565181594d1168a7996c6724a02f0646be834a`. El paquete final incorpora además la corrección de cierre descrita arriba y tiene su propio checksum contiguo.

### Limitaciones y novedad encontrada

Al retomar la sesión el 13 de noviembre, Chrome MCP devolvió `Transport closed`. Inicialmente quedaron pendientes las comprobaciones interactivas de workflows, instantáneo desde el selector/MCP y cancelación en RAP. Posteriormente se completaron con un navegador aislado controlado por CDP, sin instalar paquetes adicionales ni modificar la configuración de Chrome MCP; los detalles están en la sección de validación del servidor.

El log registró `Graphic is disposed` durante la destrucción del shell tras terminar la sesión, con una llamada desde `HopGuidedView.close` a la retirada de pestañas de Hop. Se corrigió el listener de cierre para dejar esa destrucción a SWT y se recompiló. La corrección no tiene aún una repetición interactiva de caducidad de sesión; no se presenta como validada visualmente. También hubo un error PKIX del notificador de versiones de Hop al consultar GitHub, ajeno a las operaciones MCP probadas.

## Estructura y tamaño

El sondeo común separa la orquestación de la lectura/publicación de presentaciones. El grafo previo registraba complejidad ciclomática 12 y cognitiva 36 para `pollSafely`. Tras la extracción, `pollSafely` tiene 5/9 y `pollPresentations` 6/13; el alcance combinado es **11/22** usando el mismo analizador. La extracción por sí sola no prueba una mejora de rendimiento.

No se agregaron dependencias ni nuevas clases de producción. Los tamaños exactos, las pruebas, Jandex, el SBOM y el SHA-256 del paquete están registrados en `package-verification.json` junto al ZIP. La medición de líneas y el diff frente al respaldo previo están en `source-review.json` y `source-review.diff`.

## Instalación

Paquete local de desarrollo: `D:\dev\build_hop_web\mcp\rap-live-sync\20261113\hop-mcp-connector-2.2.6-rap-live-sync.zip`. No se ha publicado una versión.

Detener el servidor MCP y cerrar Desktop o detener Tomcat antes de reemplazar el plugin. Instalar el contenido del ZIP en la carpeta efectiva de plugins de Hop, arrancar los procesos y activar **MCP Live Sync** en la sesión Web para el mismo proyecto usado por el servidor MCP.

### Instalación en el servidor — 13 de noviembre

El usuario detuvo Tomcat. Se confirmó la parada en el log, la respuesta HTTP 503 y la disponibilidad exclusiva de los JAR antes del reemplazo. Se instaló el paquete verificado en `\\10.32.112.2\hop_config\HOP_DATA\plugins-custom\misc\hop-mcp-connector`. Los 16 archivos coincidieron por SHA-256 con el ZIP; el JAR instalado tiene SHA-256 `076114e571d303128dd577866595d3f1362645cbfe20eda6a1a05090c05a4ebf`.

La instalación anterior se conservó fuera de la carpeta de plugins, en `\\10.32.112.2\hop_config\HOP_TOOLS\hop-runtime\mcp-install\20261113-093921\previous-plugin`. El manifiesto completo está en `server-installation.json` junto al ZIP. No se arrancó Tomcat ni se actualizó el cliente local `C:\hop` durante esta instalación.

### Validación posterior al arranque en el servidor

El log confirmó `Server startup` a las 09:41 del 13 de noviembre y el acceso HTTPS devolvió HTTP 200. El JAR siguió coincidiendo con el checksum instalado. Se abrió una sesión de Chrome aislada, con perfil bajo `D:\dev\build_hop_web`, contra la URL real del servidor; no se utilizó la sesión habitual del usuario.

El MCP de prueba fue el paquete nuevo, cargado mediante un directorio privado de plugins y configuración; se ejecutó `hop mcp --root \\10.32.112.2\hop_config\HOP_PROJECTS\ANALYZER_HOP --allow-mutation` sin autorizar ejecución. La configuración habitual de Codex y la instalación `C:\hop` no se cambiaron. El proyecto MCP habitual sigue siendo `D:\dev\pentaho\PENTAHO_PROCESS_HOP` en modo de solo lectura: esa conexión no representa la sesión Web `ANALYZER_HOP` usada para la prueba.

| Comprobación en el servidor actualizado | Resultado |
| --- | --- |
| Menú y diálogo | **MCP Live Sync** visible y activación para `ANALYZER_HOP` |
| Puente | Una sesión `web` activa confirmada por `hop_live_ui_status` |
| Pipeline guiado | Preparación vacía y bloque posterior con tres Dummy y dos hops, dibujados en el editor nativo |
| Pausa y guardado | Presentación pausada; aplicación MCP conservó la pausa con estado `Guardado` |
| Selector Instantáneo | Terminó la presentación pausada y abrió el pipeline nativo editable |
| MCP `presentation=instant` | Aplicó otro pipeline mientras su presentación estaba pausada y abrió el resultado editable |
| Workflow | Dos acciones DUMMY con hop condicional falso; se mostró la conexión naranja y se abrió la definición nativa tras aplicar |
| Cancelación visible | Se abrió una presentación vacía, se canceló por MCP y su pestaña desapareció sin crear archivo |
| Rollback | Los tres archivos aplicados se eliminaron mediante `hop_rollback_mutation`; acknowledgements `opened` y `closed` con `client_type=web` |
| Limpieza | Cero archivos `mcp_rap_*20261113*` restantes; cierre explícito de MCP Live Sync de prueba y del cliente/navegador aislados |
| Original | `Hourly_Process/U2020_GSM.hwf` conservó SHA-256 `b36012a5248ec9fb3a4a360f950ed4885fb6d740b321a0748bdfc5c26795782b` |
| Errores | Cero excepciones JavaScript capturadas; los dos errores de destrucción del shell registrados antes de la instalación no aumentaron durante la prueba |

Una petición de movimiento enviada por el cliente de prueba utilizó `name` donde la operación requiere `component`: el MCP la rechazó con `INVALID_INPUT` sin modificar el plan. No se identificó un defecto nuevo del conector ni se cambió su código como consecuencia de esa petición inválida.

Las capturas `server-*.png`, respuestas `server-check-client-results.json`, checksums y comprobación de limpieza `server-ui-verification.json` están junto al ZIP. No se ejecutó ningún ETL. La caducidad automática de una sesión con un borrador activo sigue pendiente de una repetición específica; el cierre normal de MCP Live Sync sí se comprobó. Para el uso habitual, activar **MCP Live Sync** en la sesión del usuario y utilizar el paquete nuevo del MCP contra el mismo proyecto.
