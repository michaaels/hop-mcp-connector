# Entrega de MCP Connector 2.6.0 para Hop Web

La propuesta requiere Apache Hop 2.20.x y Java 21. El perfil remoto del agente y el plugin en Tomcat deben actualizarse juntos. El proyecto es independiente de Apache Hop.

Esta entrega incorpora los cambios de la conversación sobre el commit de GitHub `6c3f72b57ec5a9acd8382e373f39a674a1207b78` de la versión 2.4.0. Conserva la documentación bilingüe, las ilustraciones, los workflows de CI y las pruebas nativas y de metadatos que ya estaban en esa revisión.

## Experiencia de usuario

En la ventana de destino, habilitar **MCP Connect**, elegir **Live Sync** o **Result Only** y abrir el icono MCP. **Connect Agent** copia un enlace de un solo uso con vigencia de dos minutos. Pegar el enlace en el agente. El UUID aparece cuando la conexión queda activa y permanece al cambiar de archivo. **Disconnect Agent** revoca la conexión y retira el UUID de la URL. **MCP Disconnect** apaga el servicio de la ventana.

## Aislamiento y privacidad

- La URL general y las llamadas sin permiso válido reciben la misma indicación de emparejamiento con cero, una o varias ventanas activas.
- El permiso de emparejamiento tiene 256 bits aleatorios, viaja en el fragmento del enlace copiado y se envía al servicio solo durante la inicialización. El cliente excluye ese fragmento de su URL HTTP y de los resultados que muestra. El servidor consume el permiso en una operación sincronizada.
- El UUID de la interfaz y la credencial revocable son distintos y se renuevan en cada emparejamiento. Una ventana admite un agente a la vez. Las peticiones posteriores comprueban la credencial y, cuando se proporciona, el UUID de esa misma conexión.
- La coordinación de ediciones se mantiene en la memoria de una instancia de Hop Web. El aviso de cruce muestra únicamente el archivo en conflicto. El estado del agente muestra su propio archivo abierto y un indicador de otra edición en curso en su proyecto. Las confirmaciones del editor se filtran por ventana.
- El enlace autoriza a quien lo posee. Compartirlo solo con el agente previsto. Este cambio protege la conexión MCP. El acceso a la interfaz nativa sigue sujeto a la configuración del despliegue de Hop Web.

## Compilación y entrega

Con Java 21, ejecutar `mvn -B clean verify`. Entregar `target/hop-mcp-connector-2.6.0.zip` y los SBOM `target/bom.json` y `target/bom.xml`. El ZIP del plugin incluye licencias y el índice Jandex. Los JAR de Hop y RAP los proporciona el runtime.

En la PC con acceso a GitHub, incorporar los cambios al repositorio, revisar la comparación y ejecutar la compilación antes de abrir el pull request. Para una propuesta al proyecto oficial, discutir primero el alcance con la comunidad según su [guía de contribución de código](https://hop.apache.org/community/contribution-guides/code-contribution-guide/). Las notas históricas de 2.5 se conservan como referencia. La guía de usuario para esta versión está en [Conectar un agente a Hop Web](remote-hop-web.md).

La compilación de la copia fusionada pasó 248 pruebas con Java 21. Se omitieron tres pruebas que requieren crear enlaces simbólicos en Windows. El paquete fusionado arrancó en el cliente Hop 2.20 y publicó las cuatro herramientas remotas por STDIO. La URL base devolvió la instrucción de emparejamiento. En el candidato anterior a la fusión, dos ventanas RAP en Hop Web 2.20.0 y Tomcat 10.1.59 permitieron verificar el portapapeles, el uso único concurrente, el rechazo de credenciales entre ventanas, el aviso de cruce sin identificadores ajenos y la rotación del UUID. El ZIP fusionado contiene 13 archivos JAR y el SBOM registra 210 componentes.

Para aplicar la actualización en un checkout de la revisión indicada, verificar primero el parche y aplicarlo en una rama de trabajo.

```bash
git apply --check /ruta/al/hop-mcp-connector-2.6.0-from-github.patch
git apply /ruta/al/hop-mcp-connector-2.6.0-from-github.patch
mvn -B clean verify
```

## Instalación

Con Tomcat detenido, respaldar la carpeta actual del plugin y reemplazarla por la del ZIP. Actualizar el plugin usado por el perfil `--remote-ui` del agente. Reiniciar Tomcat y recargar la conexión MCP en el agente. Para revertir, restaurar ambos paquetes previos. En un proxy con listas explícitas de encabezados, conservar `Hop-Mcp-Pairing`, `Hop-Mcp-Pairing-Used`, `Mcp-Session-Id` y `Hop-Ui-Session-Id`.
