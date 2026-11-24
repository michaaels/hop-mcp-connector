# Indicador nativo de MCP Live Sync

Desde la versión 2.6.0, **MCP Connect** permite elegir **Live Sync** o **Result Only**. En Hop Web, la activación muestra el icono MCP en el pie derecho. El icono mide como máximo 16 px dentro de un control de 24 px que conserva la altura de la barra de estado.

| Estado | Aspecto | Significado |
| --- | --- | --- |
| Disponible | Gris | Servicio activo, sin agente conectado. |
| Conectado | Negro | Un agente tiene una conexión vigente con esta sesión. |
| Trabajando | Negro con opacidad suave | Operación MCP o presentación nativa en curso; las solicitudes breves dejan una señal visible durante un ciclo. |
| Servicio apagado | Sin icono | El menú desactivó Live Sync o terminó la sesión. |

El icono abre el panel de actividad. **Connect Agent** copia el enlace de un solo uso y **Disconnect Agent** revoca la conexión. El menú **MCP Disconnect** apaga el servicio y retira el icono. El control admite Enter o espacio con foco.

El tooltip muestra el estado, el UUID de la propia conexión, el agente y su versión, la operación actual, el tiempo transcurrido y la última actividad. Si falta la identidad, aparece «No informado». El nombre del agente es una etiqueta que declara el cliente.

La pulsación conserva el icono inmóvil y varía entre aproximadamente 61 % y 100 % de opacidad en 2,4 segundos. Los pings de presencia no producen pulsaciones ni actualizan la última actividad. La animación no representa el razonamiento del modelo ni un porcentaje de progreso.

Una desconexión invalida el vínculo anterior. Una operación nativa ya aceptada puede terminar; mientras termina no se admite otro agente. Las herramientas pendientes comprueban su vínculo antes de ejecutarse. No hay reconexión automática. El menú conserva el cierre completo del servicio.

El emparejamiento y la presencia por agente corresponden a Hop Web. Hop Desktop usa la presentación nativa con el MCP local.

Se incluye el [SVG solicitado](https://upload.wikimedia.org/wikipedia/commons/f/fe/Model_Context_Protocol_logo.svg), adaptado a negro y gris con atribución y licencia MIT. No se descarga durante el uso ni se abre otra aplicación. La animación modifica solo la opacidad y no amplía permisos ETL.

El control, sus imágenes y temporizador pertenecen a la sesión. Al apagar se restaura el espacio reservado en el pie. En RAP, el tooltip marcado usa el servicio público JavaScriptExecutor para evitar que su reposicionamiento intercepte el clic; el contenido se escapa.

Véase [sesiones y presencia Web](web-agent-sessions.md) y [validación 2.4.0](validation-web-sessions-2026-11-13.md). Los informes [2.3.1](validation-live-indicator-2026-11-13.md) y [2.3.2](validation-compact-indicator-2026-11-13.md) conservan sus resultados y comportamiento históricos.
