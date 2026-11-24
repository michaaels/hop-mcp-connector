# MCP Connector for Apache Hop

[English](README.md) | **Español**

Conecta tu asistente de IA con Apache Hop para entender proyectos,
crear pipelines y workflows, y trabajar en el editor que ya conoces.

**Apache Hop 2.20.x · Java 21 · Plugin nativo**

[Descargar](https://github.com/michaaels/hop-mcp-connector/releases/latest)
· [Instalación](#instalación)
· [Novedades](RELEASE_NOTES.md)
· [Documentación](#documentación)

<picture>
  <source media="(prefers-reduced-motion: reduce)" srcset="docs/images/mcp-demo.png">
  <img src="docs/images/mcp-demo.gif" alt="El asistente consulta un proyecto local, construye un flujo en Desktop y edita un proyecto de Hop Web mediante MCP.">
</picture>

*Ilustración de los tres modos de uso. En 2.6.0, copia el enlace para el modo Web con **Connect Agent**.*
[Ver imagen estática](docs/images/mcp-demo.png).

## Qué puedes hacer

- **Entender un proyecto.** Buscar archivos, explicar flujos, consultar
  metadatos y ver dependencias e impacto de los cambios.
- **Crear y modificar flujos.** Añadir componentes, conectar pasos,
  configurar propiedades compatibles y ordenar el lienzo automáticamente.
- **Trabajar a la vista.** Abrir los cambios en Hop y seguir su construcción
  guiada, con pausa, velocidad ajustable y aplicación instantánea.
- **Revisar y diagnosticar.** Validar la estructura y, con los permisos
  correspondientes, ejecutar flujos locales y consultar estados y logs.
- **Revisar antes de guardar.** Previsualizar cambios y disponer de copias
  protegidas y rollback para las modificaciones aplicadas.

## Tres formas de usarlo

| Modo | Qué te ofrece | Cómo empezar |
| --- | --- | --- |
| **MCP normal** | Trabaja sobre un proyecto local desde tu cliente MCP, sin abrir el editor. Consulta por defecto; edición y ejecución opcionales. | `hop mcp --root <proyecto>` |
| **Live Sync Desktop** | Sigue los cambios en el editor nativo o muestra el flujo terminado. | Abre **MCP Connect** y elige **Live Sync** o **Result Only**. Conecta el MCP local al mismo proyecto y añade `--allow-mutation` para editar. |
| **Live Sync Web** | Trabaja en el proyecto de tu ventana de Hop Web. | Abre **MCP Connect**, elige el modo de presentación y pulsa **Connect Agent** en el panel del icono MCP. Pasa el enlace copiado al agente `--remote-ui`. |

La conexión Web usa Streamable HTTP sobre HTTPS. El enlace autoriza a un
agente en la ventana que lo generó y vence a los dos minutos. El agente
puede consultar y editar ese proyecto. El perfil Web mantiene deshabilitadas
la ejecución de ETL y las comprobaciones profundas.

[Guía de Desktop y construcción guiada](docs/guided-desktop-authoring.md)
· [Guía de conexión Web](docs/remote-hop-web.md)

## Pídeselo al agente

> Explícame este workflow y qué pipelines dependen de él.

> Crea un pipeline para leer un CSV, seleccionar sus campos y guardar
> el resultado. Muéstrame la construcción en Hop.

> Revisa los cambios antes de aplicarlos y ordena el lienzo.

> Conéctate a mi ventana de Hop Web con el enlace que copié desde Connect Agent.

Las funciones disponibles dependen de los plugins instalados en Hop,
las propiedades que exponen y los permisos habilitados.

## Instalación

Necesitas **Apache Hop 2.20.x**, **Java 21** y un cliente compatible con MCP.

1. Descarga el ZIP del plugin desde [Releases](https://github.com/michaaels/hop-mcp-connector/releases/latest)
   o genera `target/hop-mcp-connector-2.6.0.zip` con `mvn -B clean verify`.
2. Cierra Hop y el proceso MCP. En Web, detén Tomcat antes de sustituir
   el plugin. Extrae el ZIP en la raíz de la instalación de Hop:
   quedará en `plugins/misc/hop-mcp-connector/`.
3. Reinicia Hop y registra el comando en tu cliente MCP.

Para consultar un proyecto local:

```sh
hop mcp --root /ruta/al/proyecto
```

Para permitir crear y modificar sus flujos:

```sh
hop mcp --root /ruta/al/proyecto --allow-mutation
```

En Windows utiliza `C:\hop\hop.bat`; en Linux, `/opt/hop/hop.sh`.
El cliente debe iniciar el proceso y comunicarse por STDIO.

<details>
<summary>Ejemplo de configuración de un cliente MCP en Windows</summary>

Sustituye las rutas por tu instalación y tu proyecto. El ejemplo habilita
consulta; añade `--allow-mutation` a `args` para permitir edición.

```json
{
  "mcpServers": {
    "apache-hop": {
      "command": "C:\\Windows\\System32\\cmd.exe",
      "args": [
        "/d", "/c", "C:\\hop\\hop.bat", "mcp",
        "--root", "C:\\proyectos\\mi-proyecto"
      ]
    }
  }
}
```

Para el agente remoto de Hop Web, registra otro servidor con los argumentos
`mcp --remote-ui`, sin `--root` ni permisos locales. Instala también el
plugin de la misma versión en el Hop del servidor Web. Consulta la
[guía Web](docs/remote-hop-web.md) para HTTPS y configuración completa.

</details>

## Tú decides los permisos

El MCP local comienza en modo de consulta. Habilita solo lo que necesites:

| Opción local | Permite |
| --- | --- |
| `--allow-mutation` | Crear y editar pipelines y workflows. |
| `--allow-execution` | Ejecutar flujos y consultar su ejecución. |
| `--allow-deep-check` | Comprobaciones nativas que pueden contactar sistemas externos. |

Los cambios usan los objetos nativos de Hop, con vista previa, comprobación
de versión del archivo, recarga y rollback. Live Sync protege las pestañas
con cambios sin guardar. Las lecturas y respuestas tienen límites y
ocultan valores sensibles reconocidos.

## Documentación

[Referencia técnica y herramientas](docs/reference.md)
· [Live Sync y su indicador](docs/live-sync-indicator.md)
· [Sesiones Web](docs/web-agent-sessions.md)
· [Cobertura de Hop 2.20 y límites](docs/hop-2.20-coverage-2026-11-13.md)
· [Historial](CHANGELOG.md)
· [Seguridad](SECURITY.md)

Para compilar desde el código fuente: `mvn -B clean verify` con Java 21.

Proyecto independiente de la Apache Software Foundation.
Apache Hop y Apache son marcas de la ASF; su mención indica compatibilidad.
Licencia [Apache 2.0](LICENSE). Consulta los [avisos y atribuciones](NOTICE).
