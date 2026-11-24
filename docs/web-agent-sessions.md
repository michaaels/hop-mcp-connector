# Sesiones y agentes de Hop Web

Para Hop 2.20.x con Java 21, el perfil `--remote-ui` se empareja con una sola ventana a la vez.

1. En Hop Web, selecciona el proyecto y activa **MCP Connect** con **Live Sync** o **Result Only**. El icono gris indica que puedes emparejar un agente con esa ventana.
2. Abre el icono y pulsa **Connect Agent**. Pasa al agente el enlace copiado. Vence a los dos minutos y admite un solo uso. Volver a pulsar **Connect Agent** invalida el enlace anterior.
3. El icono pasa a negro al establecerse la conexión. El UUID aparece en la URL, en el tooltip y en el panel. Conserva el mismo valor al abrir otra definición o cambiar el proyecto visible.
4. **Disconnect Agent** revoca la credencial y retira el UUID. El icono vuelve a gris y el panel vuelve a ofrecer **Connect Agent**. La próxima conexión genera otro UUID. En el menú, **MCP Disconnect** apaga el servicio de la ventana.

La URL general y la URL del navegador con el UUID muestran la misma instrucción de emparejamiento. Para cambiar el proyecto autorizado, apagar y volver a activar el MCP en el nuevo proyecto. La ventana se elige al copiar su enlace.

El cliente renueva presencia cada diez segundos. La conexión vence después de 45 segundos de inactividad sin renovación. Si el agente se desconecta durante una escritura admitida, la ventana conserva la reserva hasta que termine la operación. El agente que se conecte después debe volver a preparar sus propios planes.

Las ventanas de una misma instancia de Hop Web comparten los archivos del servidor. El campo `other_edit_in_progress` indica si otro agente tiene una reserva en el proyecto. Cuando dos agentes intentan editar el mismo archivo, el chat y la ventana que quedó bloqueada muestran la ruta relativa afectada. El estado Web ofrece `active_file` de la propia ventana y su enlace de conexión nunca forma parte de las respuestas MCP.

El tooltip muestra agente, versión, operación, tiempo y última actividad. El nombre viene declarado por el cliente MCP y sirve como etiqueta. El enlace de un solo uso demuestra que quien se empareja dispone de la autorización copiada desde la ventana. Los controles de acceso a la interfaz de Hop Web los define el sitio.
