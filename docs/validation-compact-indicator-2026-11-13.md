# Indicador compacto y revisión de varias sesiones — 2026-11-13

## Verificación de producción

La URL `https://10.32.112.2/hop_web/ui` conectó mediante el perfil MCP existente y respondió a `hop_config` con versión **2.3.1**, proyecto `ANALYZER_HOP`, transporte HTTP, edición habilitada y ejecución deshabilitada. `hop_live_ui_status` reportó una sesión Web activa. Un segundo cliente de prueba envió únicamente una inicialización MCP sin identificador de sesión y recibió HTTP 200 e identificador de vinculación, compatible con una única conexión disponible en ese momento. Se verificó TLS usando el certificado público de confianza adicional existente, sin desactivarlo ni acceder a una carpeta de proyecto compartida.

## Corrección de tamaño

Desarrollo **2.3.2**: logo de 24 a **16 px**, control de 40 px a **24 px de ancho**, centrado en la altura natural de la barra. Se eliminó el aumento de altura de la barra; el control usa sus anclajes superior e inferior. Se preservan verde, opacidad, tooltip y apagado. Es una modificación localizada en `HopLiveIndicator`.

`fmt:format clean verify`: **219 pruebas, 0 fallos, 0 errores, 3 omisiones** de enlaces en Windows. No se añadieron pruebas que simplemente reproduzcan constantes de tamaño.

Prueba visual del paquete final en Hop Web 2.20/RAP 4.8/Tomcat 10.1.59 aislado, mediante Chrome MCP:

- Barra antes y después: **25,99609375 px CSS**, sin aumento de altura; se restauró también su ancho completo tras desactivar.
- Control medido: aproximadamente **24 × 26 px CSS**. Tinta verde: **14 × 16 píxeles** dentro de la imagen de 16 px.
- Pulsación real tras `hop_config`: **0,61–1,00** de opacidad; terminó sólida.
- Clic: cerró Live Sync, el agente devolvió `inactive` y desapareció el indicador.

El navegador de prueba usaba escala de presentación; se compararon medidas CSS antes/después en el mismo contexto. No se ejecutó ETL ni se modificaron flujos de negocio. Se cerraron el cliente y el Tomcat desechables.

Paquete: `D:\dev\build_hop_web\mcp\live-indicator\compact-20261113\hop-mcp-connector-2.3.2-live-indicator.zip`.

SHA-256: `7388ce5aa3a8a28b3c8bb1da401b6c092dded99b8c5871e5ae3231b6b0af3577`.

**Instalación pendiente:** el servidor de producción conserva 2.3.1. El instalador preparado requiere Tomcat detenido y conserva copia anterior. Evidencia: `package-verification.json`, `visual-validation.json`, `agent-results.json`, `unbound-production-probe.json` y `compact-active.png` bajo la carpeta del paquete.

## Alcance actual con varias instancias

El control es parcial. `HopWebRemoteEndpoint.select` conserva las solicitudes vinculadas mediante `Mcp-Session-Id`. Si una conexión nueva no selecciona sesión y hay varias activas, responde **409**; si la sesión vinculada desaparece responde **404**, sin elegir otra automáticamente. La prueba automatizada `multipleActiveSessionsRequireExplicitSelectionAndKeepExistingCallsBound` pasó en el build anterior y en el actual.

Limitaciones verificadas por lectura de código, sin provocar cambios de negocio en varias ventanas:

1. `HopWebConnectionDialog` muestra/copia la misma URL normal, mientras `HopWebUrl.requirePublicUi` rechaza parámetros de sesión en el perfil URL del agente. El protocolo HTTP admite selección explícita, pero el flujo sencillo de conexión carece de un selector útil para el usuario cuando hay ambigüedad. El mensaje que pide copiar una URL específica resulta insuficiente.
2. `HopLiveUiSync.pollPresentations` y la lectura de eventos de `HopLiveUiEventBroker` utilizan el proyecto y el momento de activación, sin destinatario de sesión. Varias ventanas con Live Sync sobre el mismo proyecto pueden recibir los mismos borradores/eventos; no existe aislamiento visual completo. Los archivos de ese proyecto son compartidos por definición.

Estas limitaciones se documentan como trabajo pendiente independiente del ajuste de tamaño. Esta entrega **no añade un selector ni filtrado de eventos por sesión**. No se debe describir la vinculación HTTP como aislamiento completo entre ventanas. Una pestaña abierta o una captura con un icono visible no sustituye a comprobar cuántas sesiones están actualmente registradas en el servidor.
