# Conectar un agente a Hop Web

Desarrollo **2.6.0**, para Apache Hop **2.20.x** y Java **21**.

En la ventana de Hop Web que quieres usar, abre el icono MCP, pulsa **Connect Agent** y pasa el enlace copiado al agente. El MCP convierte ese enlace en la conexión con esa ventana y opera sobre el proyecto del servidor. La URL habitual del navegador lleva a la instrucción para copiar el enlace.

```text
Agente → MCP ligero (--remote-ui) → HTTPS → Hop Web / RAP → proyecto del servidor
                                              ↓
                                      editor nativo de la sesión
```

El agente trabaja por HTTPS a través de Hop Web. El único paso de emparejamiento es copiar el enlace desde el editor. Mantén el enlace privado hasta que el agente se conecte.

## Configuración del agente, una sola vez

Actualizar el plugin en la instalación Hop que ejecuta el MCP del agente. Registrar un servidor adicional para conservar cualquier conexión local existente:

```toml
[mcp_servers.apache_hop_web]
command = 'C:\Windows\System32\cmd.exe'
args = ['/d', '/c', 'C:\hop\hop.bat', 'mcp', '--remote-ui']
cwd = 'C:\hop'
startup_timeout_sec = 120
tool_timeout_sec = 60
```

El modo remoto no requiere `--root`, metadatos locales ni permisos de ejecución local. No debe combinarse con `--root`, `--allow-mutation`, `--allow-execution`, `--allow-deep-check` o `--allow-web-api`. El comando normal `hop mcp` conserva su comportamiento anterior.

Codex usa `~/.codex/config.toml`; reiniciar su conexión/extensión después de registrar el servidor. Véase la [documentación oficial de MCP en Codex](https://learn.chatgpt.com/docs/extend/mcp?surface=cli).

El perfil del agente publica solamente:

| Herramienta | Función |
| --- | --- |
| `hop_web_connect` | Empareja la ventana mediante el enlace de un solo uso |
| `hop_web_status` | Comprueba si el agente sigue conectado a su ventana |
| `hop_web_tools` | Descubre por nombre una página de herramientas nativas y sus esquemas |
| `hop_web_call` | Invoca una herramienta nativa descubierta con sus argumentos |

El catálogo nativo se solicita por páginas cuando se necesita. El perfil del agente conserva las cuatro herramientas antes y después de conectarse.

Si el certificado HTTPS del servidor no es de confianza, configurar una vez `--remote-ca-certificate <archivo público .cer/.pem>` en este perfil. El certificado adicional se aplica únicamente a ese cliente HTTP; no se desactiva TLS ni se modifica el almacén de certificados del sistema. La comprobación del nombre del servidor se conserva. Las credenciales y controles de acceso existentes del sitio/proxy siguen aplicándose.

## En Hop Web

1. Instalar la versión 2.6.0 del plugin con Tomcat detenido y volver a arrancarlo. Actualizar a la misma versión el plugin que usa el perfil remoto del agente.
2. Abrir Hop Web y seleccionar el proyecto.
3. Activar **MCP Connect** y escoger **Live Sync** o **Result Only**.
4. Abrir el panel desde el icono gris y pulsar **Connect Agent**. Pegar en el agente el enlace copiado, que vence a los dos minutos y admite un solo uso.

El agente emparejado puede consultar y editar las definiciones del proyecto de la ventana. El emparejamiento también funciona con Hop Web sin cuentas y mantiene los controles de acceso del sitio. El perfil Web comprende inspección y edición semántica.

El icono abre el panel de actividad. **Disconnect Agent** revoca la conexión y retira su UUID de la URL. **MCP Disconnect** apaga el servicio para la ventana. Para cambiar de ventana, copiar un enlace desde la ventana de destino. Véase [sesiones y presencia de agentes](web-agent-sessions.md).

Para un proxy que necesite una URL pública concreta, se admite la variable de Hop `HOP_MCP_PUBLIC_UI_URL`, por ejemplo `https://hop.example.org/hop_web/ui`. Configurar la URL base del editor.

## Operaciones y presentación

Una vez conectado, inspeccionar `hop_config` y la definición objetivo mediante `hop_web_call`. Para crear de forma visible, preparar planes con `presentation: guided`, añadir bloques con `previous_plan_id` y aplicar el plan final. Se conservan pausa, velocidades, **Instantáneo** y el argumento `presentation: instant` solicitado por el agente.

Las operaciones siguen usando los objetos nativos, preview, SHA-256, backups protegidos, recarga nativa y rollback. Las pestañas con cambios sin guardar permanecen protegidas. El proyecto y el puente interno `.hop-mcp` se manejan en el servidor; el agente no abre esos archivos.

El servicio usa el servlet RAP existente, sin otra aplicación ni puerto. Hay un máximo de ocho sesiones activas, dos solicitudes simultáneas por sesión y ocho globales. Las solicitudes y respuestas MCP conservan un presupuesto de 512 KiB; el cliente limita tanto los bytes recibidos como el tiempo de recepción del cuerpo y no sigue redirecciones.

El perfil `--remote-ui` se encarga de transmitir la autorización durante la inicialización. Revisar que el proxy permita los encabezados MCP y de emparejamiento si aplica una lista explícita de encabezados permitidos.
