# Validación del indicador MCP Live Sync — 2026-11-13

## Entrega

Desarrollo local **2.3.1**, Hop **2.20.0**, Java **21**. Icono MCP verde sólido en la esquina inferior derecha cuando Live Sync está activo; pulsación de opacidad durante actividad real, tooltip con estado y proyecto, apagado con clic y retirada del indicador. La prueba de teclado confirmó apagado con Enter después de establecer el foco en el widget nativo RAP.

**Plugin 2.3.1 instalado en producción con Tomcat detenido**, después de la confirmación del usuario. Se comprobaron la parada, los bloqueos y los hashes de los 16 archivos instalados. El plugin anterior 2.3.0 se conservó en `\\10.32.112.2\hop_config\HOP_TOOLS\hop-runtime\mcp-install\20261113-160502\previous-plugin`. El instalador no arrancó Tomcat; queda el arranque del usuario y la comprobación visual en su sesión de producción. Evidencia del despliegue: `server-installation.json`. La actualización del indicador no requiere cambiar las herramientas ni la configuración del agente remoto existente.

Paquete: `D:\dev\build_hop_web\mcp\live-indicator\20261113\hop-mcp-connector-2.3.1-live-indicator.zip`.

SHA-256 ZIP: `534e413681604f842d3ec2f85898c970fd93c30534f3de9b1311df8ca2fb0ca3`.

SHA-256 JAR: `0d7a6888ac33b171f59cec0f08ec0a3d9f4b12d0a91faeb83a2523a535b21966`.

## Verificación

`fmt:format clean verify`: **219 pruebas, 0 fallos, 0 errores, 3 omisiones** de enlaces en Windows. Incluye componentes nativos/JDBC, actividad HTTP y validación del markup mediante el validador real de RAP 4.8. Se verificaron estructura del ZIP, Jandex, logo, licencia, NOTICE, SBOM y ausencia de bibliotecas Hop/RAP/Servlet y de pruebas dentro del paquete.

**12 comprobaciones adicionales de validación aprobadas**, registradas en `validation-summary.json`. La comprobación visual se realizó con el paquete final en un Tomcat 10.1.59 desechable, Hop Web 2.20 y RAP 4.8, mediante Chrome/CDP propio en loopback. La producción no participó en estas pruebas de interfaz.

| Comportamiento observado | Resultado |
| --- | --- |
| Render del logo verde, sin punto adicional | 173 píxeles de tinta medidos; todos verdes |
| Estado en reposo | Opacidad máxima 255/255 |
| Pulsación tras una solicitud MCP real | Opacidad observada 0,61–1,00, con 26 frames de opacidad variable; final sólido |
| Tooltip | Estado Activo/Procesando, proyecto `MCP_RAP_TEST` e indicación de apagado |
| Clic con tooltip visible sobre el icono | Hit test llega al Canvas; MCP devuelve `inactive`; el indicador desaparece |
| Redimensionado 1440×950 → 1024×720 | Icono conserva margen derecho de 4 px y anclaje inferior |
| Desactivar y volver a activar | Un único indicador, sin duplicados |
| Enter con foco nativo RAP | MCP devuelve `inactive`; indicador retirado |
| Errores JavaScript | Ninguno reportado en la sesión final |

La animación permanece inmóvil y sigue un ciclo configurado de 2,4 segundos; no muestra un porcentaje de progreso ni el razonamiento interno del modelo. Las solicitudes cortas conservan señal de actividad para que resulte perceptible. En escritorio se observan reproducción y eventos nativos, no consultas desde un proceso STDIO independiente. Se compiló la integración común con SWT, pero **no se repitió una prueba visual del escritorio**. La navegación completa por Tab y la barra espaciadora no se certificaron en esta sesión; se verificaron foco nativo y Enter.

## Correcciones encontradas durante el desarrollo

1. El tooltip nativo RAP podía cubrir el icono e interceptar el clic. Se corrigió únicamente su hit testing con una clase de markup específica y una regla CSS acotada, insertada mediante el servicio público de RAP. Se verificó que el resto de la página conserva `pointer-events: auto`.
2. RAP rechaza atributos HTML `data-*` en el texto del tooltip. Se sustituyó el marcador inicial por el atributo `class` admitido, se escapó el texto variable y se añadió una regresión contra el validador real de RAP. Un fallo de inicialización posterior a cargar el control también libera sus recursos y restaura el pie.
3. La primera ejecución encontró un bloqueo de borrado de un directorio temporal de Windows en una prueba preexistente. No se modificó esa prueba; las ejecuciones completas posteriores terminaron sin ese error.

No se ejecutó ETL ni se modificaron definiciones de negocio. Los procesos desechables se cerraron. No se cambió el alcance de autorización del MCP ni se añadieron herramientas.

## Impacto medido

| Medida | Antes (2.3.0) | Después (2.3.1) |
| --- | ---: | ---: |
| JAR, bytes | 563523 | 574700 |
| ZIP, bytes | 6216593 | 6227258 |
| Nuevas dependencias de ejecución | — | 0 |
| Herramientas iniciales del agente | 4 | 4 |
| Catálogo inicial, bytes | 2195 | 2195 |

Se añadieron dos clases pequeñas: una posee el control/imagen/timer nativo y otra aísla la adaptación del tooltip Web. Los cambios existentes se limitan al ciclo de vida de Live Sync, la consulta del contador de solicitudes y el estado de presentación. No se midió reducción de complejidad ni rendimiento general; los tamaños y resultados anteriores son observables. La revisión completa está en `source-review.diff` y `source-review.json`.

Evidencias bajo `D:\dev\build_hop_web\mcp\live-indicator\20261113`: `verify.log`, `package-verification.json`, `validation-summary.json`, `green-pulse-samples.json`, `green-indicator-pixels.json`, `green-clickable-with-tooltip.json`, `green-click-stop-result.json`, `green-keyboard-stop-result.json`, `agent-results.json`, `browser-errors.json` y capturas `green-indicator-*.png`.
