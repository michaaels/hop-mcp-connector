# Validación de conexión remota Hop Web — 2026-11-13

## Resultado y alcance

Implementación local 2.3.0 para Hop 2.20.0 y Java 21. El agente recibe la URL normal de Hop Web y se conecta a la sesión que activó **MCP Live Sync**. Con la sesión abierta y Live Sync apagado devuelve estado `inactive`; no utiliza un proyecto local como alternativa. No requiere compartir la carpeta del proyecto.

**Plugin 2.3.0 instalado y conexión validada en producción.** Se instaló con Tomcat detenido, después de que el usuario confirmó la parada. Se verificaron los 16 archivos instalados contra el paquete y se conservó el plugin anterior en `\\10.32.112.2\hop_config\HOP_TOOLS\hop-runtime\mcp-install\20261113-143507\previous-plugin`. El instalador no arrancó Tomcat. Después del arranque realizado por el usuario, el perfil `apache_hop_web` de Codex conectó usando únicamente `https://10.32.112.2/hop_web/ui`.

## Compilación y medidas

`fmt:format clean verify`: **217 pruebas, 0 fallos, 0 errores, 3 omisiones** de pruebas de enlaces en Windows. Se ejecutaron las pruebas de componentes nativos y JDBC. Los resultados no certifican otros sistemas operativos o controladores de producción.

| Medida | Antes | Después |
| --- | ---: | ---: |
| JAR del conector, bytes | 526780 | 563523 |
| ZIP, bytes | 6181209 | 6216593 |
| Nuevas dependencias de ejecución incluidas | — | 0 |
| Herramientas iniciales del perfil remoto | No aplicable | 4 |
| Catálogo inicial remoto serializado, bytes | No aplicable | 2195 |

Las cuatro herramientas son `hop_web_connect`, `hop_web_status`, `hop_web_tools` y `hop_web_call`. Los esquemas nativos se descubren cuando se necesitan, mediante páginas filtradas. El transporte HTTP reutiliza el registro, los esquemas y los handlers existentes. No se midió una reducción de complejidad ni una mejora de velocidad de ejecución; se registran tamaños observables y pruebas funcionales.

Paquete final: `D:\dev\build_hop_web\mcp\remote-rap\20261113\hop-mcp-connector-2.3.0-web-remote.zip`.

SHA-256 ZIP: `89575e0c8b5757a7b636f2826db69bb1a5c7c96c03471ea4552232a0069db4a5`.

SHA-256 JAR: `6d228b2fef0e0c36d9ba8954c4ded6f1da50f537b400faa9bb414fc73ee9b6e3`.

Verificados Jandex, LICENSE, NOTICE, SBOM y ausencia de bibliotecas Hop, RAP, Servlet y de pruebas dentro del paquete. RAP y Servlet son dependencias proporcionadas por el servidor.

## Prueba integrada en RAP

Entorno desechable: Hop Web 2.20.0, RAP 4.8, Tomcat 10.1.59, Java 21, puerto de loopback 18082, proyecto local de prueba sin conexiones de producción. Chrome DevTools MCP no estaba disponible; se utilizó un Chrome propio desechable mediante CDP, sin instalar paquetes adicionales.

1. Con Hop Web abierto y Live Sync apagado, `hop_web_connect` devolvió `inactive`.
2. Al activar el menú nativo, apareció el diálogo con una URL limpia, sin `jsessionid` ni tokens manuales. La conexión mediante esa URL habilitó inspección y edición, conservando la ejecución deshabilitada.
3. A través del agente se descubrieron herramientas y se preparó un pipeline con dos componentes y su conexión. Se comprobó la presentación guiada, pausa y aplicación instantánea en el editor nativo.
4. Se creó también un workflow con dos acciones, conexión condicional y distribución automática. El editor nativo mostró la conexión de fallo y el resultado editable.
5. Se revirtieron ambas definiciones mediante sus hashes y herramientas nativas. Se canceló otro borrador visible. El catálogo final devolvió cero definiciones de prueba restantes.
6. Al apagar Live Sync, tanto estado como reconexión devolvieron `inactive`, sin cambiar de proyecto ni recurrir a archivos locales.

Las pruebas automatizadas adicionales cubren selección ambigua de sesiones, permanencia en la sesión elegida, validación de URL/origen, límites de cuerpo y concurrencia, revocación, errores de esquema, recuperación y plazos de recepción de respuestas.

El paquete candidato usado en la prueba visual fue `7d7343d3b42de2f09f74604b46a8e034ced52b3d05dd97462134a2702db6f699`. Posteriormente se ajustaron las instrucciones del perfil del agente y su cálculo de presupuesto de salida. El paquete final pasó las 217 pruebas y el arranque real del perfil registrado; no se repitió la sesión visual completa con ese paquete final.

## Perfil instalado y HTTPS

Perfil `apache_hop_web` registrado en `C:\Users\msancher\.codex\config.toml`, conservando la conexión local existente. Usa una instalación aislada del plugin bajo `D:\dev\build_hop_web\mcp\agent`, sin reemplazar el JAR instalado en `C:\hop`.

El proceso se arrancó utilizando exactamente el comando y entorno registrados: respondió como `hop-web-agent` 2.3.0 y publicó las cuatro herramientas, con catálogo de 2195 bytes.

El certificado público de `10.32.112.2` se configuró como confianza adicional únicamente en este cliente. Se conserva la validación del nombre del servidor; no se modificaron almacenes de confianza del sistema ni se desactivó TLS. La comprobación HTTPS contra producción superó TLS y devolvió `protocol_error`, porque el plugin 2.2.6 aún no ofrece el endpoint nuevo.

La activación de Live Sync no añade autenticación: se aplican los controles del sitio/proxy. Si Hop Web permite acceso anónimo, su MCP activo también es alcanzable sin credenciales adicionales. Los identificadores MCP seleccionan sesiones; no son credenciales.

## Pendientes y límites observados

- Conexión 2.3.0 validada en producción: con Live Sync apagado respondió `inactive`; tras la activación por el usuario respondió `connected`. Se descubrió e invocó `hop_config` remotamente y se comprobó de nuevo el estado conectado. El proyecto fue `E:\APACHE_HOP\HOP_PROJECTS\ANALYZER_HOP`, transporte `streamable_http`, edición habilitada y ejecución deshabilitada. No se modificaron definiciones ni se ejecutó ETL en esta comprobación. Evidencias: `server-installation.json` y `production-connection-verification.json`.
- Antes de abrir por primera vez una GUI RAP, el handler todavía no está registrado: la conexión puede indicar `protocol_error`. El estado `inactive` se verificó con Hop Web ya abierto y Live Sync apagado.
- Se pulsó el botón nativo de copiar URL, pero Chrome sin interfaz rechazó la lectura del portapapeles por permisos; no se certifica su contenido mediante esa lectura.
- Durante la destrucción de la sesión RAP se observó `Graphic is disposed` en `ToolBar.layoutItems/destroyItem` y `HopGuiPipelineGraph.dispose`, sin frames MCP. No ocurrió durante la edición. No se corrigió ni se atribuye de forma concluyente a Hop: queda comparar con Hop 2.20 sin el plugin.
- No se ejecutó ETL ni se modificaron flujos de negocio. Los procesos desechables se cerraron. Antes del despliegue se verificaron sin cambios el JAR de producción 2.2.6 y el workflow de referencia `Hourly_Process/U2020_GSM.hwf`; el único reemplazo posterior fue el directorio del plugin, conservando su copia anterior.

Evidencias locales: `verify.log`, `impact.json`, `package-verification.json`, `registered-agent-verification.json`, `agent-results.json`, `agent-catalog.json`, `browser-errors.json`, capturas `url-agent-*.png` y `url-only-active-dialog.png`, bajo `D:\dev\build_hop_web\mcp\remote-rap\20261113`.
