# Indicador nativo de MCP Live Sync

Desarrollo 2.4.0 para Hop 2.20.x y Java 21. En Hop Web, **Herramientas → MCP Live Sync** activa el servicio de la sesión y muestra el logo MCP en el pie derecho. El logo mide como máximo 16 px, dentro de un control de 24 px que conserva la altura original de la barra de estado.

| Estado | Aspecto | Significado |
| --- | --- | --- |
| Disponible | Gris | Servicio activo, sin agente conectado. |
| Conectado | Negro | Un agente tiene una conexión vigente con esta sesión. |
| Trabajando | Negro con opacidad suave | Operación MCP o presentación nativa en curso; las solicitudes breves dejan una señal visible durante un ciclo. |
| Servicio apagado | Sin icono | El menú desactivó Live Sync o terminó la sesión. |

**Clic en el icono:** desconecta solamente al agente. Queda gris y disponible para una nueva conexión explícita. **Seleccionar de nuevo el menú MCP Live Sync:** apaga el servicio completo y retira el icono. El control admite Enter o espacio con foco.

El tooltip muestra únicamente estado, agente y versión informados por el cliente, operación actual, tiempo transcurrido y última actividad. No repite proyecto, archivo ni instrucciones de clic. Si falta la identidad, aparece «No informado». La identidad es informativa, no una autenticación.

La pulsación conserva el icono inmóvil y varía entre aproximadamente 61 % y 100 % de opacidad en 2,4 segundos. Los pings de presencia no producen pulsaciones ni actualizan la última actividad. La animación no representa el razonamiento del modelo ni un porcentaje de progreso.

Una desconexión invalida el vínculo anterior. Una operación nativa ya aceptada puede terminar; mientras termina no se admite otro agente. Las herramientas pendientes comprueban su vínculo antes de ejecutarse. No hay reconexión automática. El menú conserva el cierre completo del servicio.

La presencia por agente y selección de sesión corresponden a Hop Web. El proceso STDIO local de escritorio no publica presencia entre procesos y ese comportamiento no está certificado en escritorio. Se conserva la presentación nativa existente.

Se incluye el [SVG solicitado](https://upload.wikimedia.org/wikipedia/commons/f/fe/Model_Context_Protocol_logo.svg), adaptado a negro y gris con atribución y licencia MIT. No se descarga durante el uso ni se abre otra aplicación. La animación modifica solo la opacidad y no amplía permisos ETL.

El control, sus imágenes y temporizador pertenecen a la sesión. Al apagar se restaura el espacio reservado en el pie. En RAP, el tooltip marcado usa el servicio público JavaScriptExecutor para evitar que su reposicionamiento intercepte el clic; el contenido se escapa.

Véase [sesiones y presencia Web](web-agent-sessions.md) y [validación 2.4.0](validation-web-sessions-2026-11-13.md). Los informes [2.3.1](validation-live-indicator-2026-11-13.md) y [2.3.2](validation-compact-indicator-2026-11-13.md) conservan sus resultados y comportamiento históricos.
