# Sesiones y agentes de Hop Web

Desarrollo 2.4.0, exclusivamente Hop 2.20.x y Java 21. El agente usa el perfil `--remote-ui` y la URL normal de Hop Web; no necesita la carpeta compartida del proyecto.

1. Activar **Herramientas → MCP Live Sync** en la ventana deseada. El icono gris indica disponibilidad. El diálogo muestra un identificador corto de esa sesión.
2. Llamar `hop_web_connect` con `url`. Si solo hay una sesión disponible, se conecta a ella.
3. Si hay varias, la respuesta `state: ambiguous` contiene hasta ocho candidatos con `session_id`, proyecto, archivo activo y agente conectado. El agente debe presentar las opciones y preguntar al usuario cuál desea usar; nunca debe adivinar.
4. Llamar de nuevo con la misma `url` y el `session_id` elegido. El icono de esa ventana queda negro. Una sesión ocupada informa `SESSION_BUSY`; no se sustituye al agente conectado.

El archivo es una ruta relativa al proyecto vinculado al activar el servicio. Puede estar vacío si no hay un editor de archivo activo. Los borradores internos no se publican como archivos del usuario. El identificador corto ayuda a distinguir ventanas con el mismo proyecto y archivo. Cambiar el proyecto visible no reasigna silenciosamente el proyecto del servicio: apagar y activar Live Sync para cambiar su vínculo.

El identificador estable de la ventana y el vínculo revocable del agente son distintos. Clic en el icono revoca solo el vínculo y vuelve a gris; el servicio sigue aceptando una conexión explícita nueva. El vínculo anterior no puede volver a usar herramientas. El menú vuelve a apagar el servicio entero. Cerrar el agente libera su vínculo cuando el cliente puede enviar el cierre.

El cliente ligero renueva presencia cada 10 segundos, sin pulsar el icono. La concesión vence tras 45 segundos sin renovación; la siguiente consulta del estado actualiza esa situación. Las operaciones activas admitidas antes de la desconexión terminan conforme al ciclo de vida existente; no se acepta otro agente hasta que finalicen. Las peticiones pendientes de despacho comprueban nuevamente su vínculo antes de invocar herramientas. No se promete cancelar una escritura nativa ya iniciada.

Los eventos MCP y las presentaciones guiadas Web se dirigen al identificador de su ventana. Dos sesiones que apuntan al mismo proyecto **siguen compartiendo los mismos archivos del servidor**; la separación de presentaciones no crea copias ni aislamiento de datos. Los eventos locales antiguos sin destinatario conservan su comportamiento previo.

El tooltip muestra agente/versión, operación, tiempo y última actividad. La identidad procede de `initialize.params.clientInfo` del cliente MCP y puede faltar: no acredita identidad ni sustituye los controles del sitio. El perfil ligero la transmite al servicio. Las decisiones de acceso siguen dependiendo del despliegue Web, HTTPS y validaciones de origen/host.

Se mantienen cuatro herramientas ligeras: `hop_web_connect`, `hop_web_status`, `hop_web_tools` y `hop_web_call`. El catálogo nativo se descubre por páginas. Las escrituras conservan preview, planes con SHA-256, backups protegidos, recarga nativa y rollback. Esta conexión no habilita ejecución ETL, comprobaciones profundas ni proxy Web.
