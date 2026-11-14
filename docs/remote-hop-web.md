# Hop Web desde el agente: una URL y MCP Live Sync activo

Desarrollo local **2.4.0**, para Apache Hop **2.20.x** y Java **21**. No se ha publicado una versión de Marketplace.

El uso previsto es pedirle al agente: **«Conéctate a Hop Web https://10.32.112.2/hop_web/ui»**. El MCP convierte internamente esa URL en el endpoint MCP, comprueba la sesión activa y opera sobre su proyecto en el servidor. Si MCP Live Sync está apagado, informa que no está activo. No busca ni modifica un proyecto local como alternativa.

```text
Agente → MCP ligero (--remote-ui) → HTTPS → Hop Web / RAP → proyecto del servidor
                                              ↓
                                      editor nativo de la sesión
```

No se necesita compartir la carpeta del proyecto ni introducir tokens manualmente. Chrome MCP es opcional para observar y manejar controles del navegador; la conexión de Hop no depende de él.

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
| `hop_web_connect` | Recibe la URL normal de Hop Web; devuelve conectado, inactivo o el problema de conexión |
| `hop_web_status` | Comprueba si la sesión seleccionada sigue disponible |
| `hop_web_tools` | Descubre por nombre una página de herramientas nativas y sus esquemas |
| `hop_web_call` | Invoca una herramienta nativa descubierta con sus argumentos |

El catálogo nativo se solicita por páginas cuando se necesita. El perfil queda disponible en el agente aunque la página Web o Live Sync estén apagados, de modo que puede informar su estado.

Si el certificado HTTPS del servidor no es de confianza, configurar una vez `--remote-ca-certificate <archivo público .cer/.pem>` en este perfil. El certificado adicional se aplica únicamente a ese cliente HTTP; no se desactiva TLS ni se modifica el almacén de certificados del sistema. La comprobación del nombre del servidor se conserva. Las credenciales y controles de acceso existentes del sitio/proxy siguen aplicándose.

## En Hop Web

1. Instalar el plugin 2.4.0 con Tomcat detenido y volver a arrancarlo.
2. Abrir Hop Web y seleccionar el proyecto.
3. Activar **Herramientas → MCP Live Sync**. El diálogo nativo confirma la activación y permite copiar la URL normal para indicársela al agente.

La activación autoriza consultar y editar el proyecto de esa sesión a través del endpoint MCP, conforme al acceso de red y la protección del sitio Web. No es un inicio de sesión adicional: si el sitio permite acceso anónimo, su endpoint MCP activo también es alcanzable sin otra credencial. Nunca se habilitan por esta conexión la ejecución de ETL, las comprobaciones profundas ni el proxy de otros servicios Web.

Al seleccionar otra vez el menú Live Sync o terminar la sesión se cierra el servicio asociado. El icono inferior solo desconecta al agente y queda gris, disponible para reconectar explícitamente. Una conexión existente nunca cambia silenciosamente de ventana o proyecto. Si hay varias sesiones, `hop_web_connect` devuelve sus proyectos y archivos abiertos; el agente pregunta cuál usar y repite la llamada con el `session_id` elegido. Véase [sesiones y presencia de agentes](web-agent-sessions.md).

Para un proxy que necesite una URL pública concreta, se admite la variable de Hop `HOP_MCP_PUBLIC_UI_URL`, por ejemplo `https://10.32.112.2/hop_web/ui`. No incluir contraseñas, cookies, `jsessionid` ni parámetros de sesión del navegador en esa variable.

## Operaciones y presentación

Una vez conectado, inspeccionar `hop_config` y la definición objetivo mediante `hop_web_call`. Para crear de forma visible, preparar planes con `presentation: guided`, añadir bloques con `previous_plan_id` y aplicar el plan final. Se conservan pausa, velocidades, **Instantáneo** y el argumento `presentation: instant` solicitado por el agente.

Las operaciones siguen usando los objetos nativos, preview, SHA-256, backups protegidos, recarga nativa y rollback. Las pestañas con cambios sin guardar permanecen protegidas. El proyecto y el puente interno `.hop-mcp` se manejan en el servidor; el agente no abre esos archivos.

El servicio usa el servlet RAP existente, sin otra aplicación ni puerto. Hay un máximo de ocho sesiones activas, dos solicitudes simultáneas por sesión y ocho globales. Las solicitudes y respuestas MCP conservan un presupuesto de 512 KiB; el cliente limita tanto los bytes recibidos como el tiempo de recepción del cuerpo y no sigue redirecciones.

Los clientes que admiten MCP HTTP directamente pueden registrar `https://10.32.112.2/hop_web/ui?servicehandler=hop-mcp`. Para el uso por instrucciones como «conéctate a esta URL», se recomienda el perfil ligero anterior: puede informar si Live Sync está apagado antes de conectarse al servidor remoto.
