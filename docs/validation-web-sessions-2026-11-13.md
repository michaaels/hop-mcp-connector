# Validación local 2.4.0: sesiones y presencia Web

Fecha: 2026-11-13. Apache Hop 2.20.0, Java 21, Tomcat 10.1.59 y RAP 4.8. Desarrollo local; sin publicación, tag ni cambio del plugin de producción.

## Resultado

La compilación completa final terminó con BUILD SUCCESS a las 17:29:47 -05. Surefire contabilizó 228 pruebas: 225 ejecutadas, cero fallos, cero errores y tres omisiones de enlaces en Windows. Se ejecutaron las pruebas nativas de componentes y JDBC. El formato Java pasó. El paquete conserva Jandex, licencias y SBOM; no contiene las bibliotecas de Hop, RAP, Servlet ni las dependencias de prueba.

Se probaron presencia exclusiva, renovación sin actividad visual, expiración, revocación, drenaje de operaciones admitidas, rechazo de solicitudes pendientes con vínculo antiguo, selección explícita y aislamiento de destinatarios para eventos y presentaciones. El SDK conserva la validación MCP y las respuestas nativas. Las solicitudes iniciales malformadas o demasiado grandes no dejan un agente conectado.

La revisión final detectó una carrera entre la limpieza de una petición antigua y la reconexión de otro agente. La desconexión HTTP y la limpieza por error comprueban ahora el vínculo esperado de forma atómica. Una prueba adicional confirma que un vínculo antiguo o nulo no puede revocar al reemplazo. El cierre explícito desde la interfaz mantiene su comportamiento.

## Prueba nativa en navegador

Se arrancó una instancia desechable en loopback, con proyecto vacío y dos contextos de navegador independientes. Se usaron dos procesos reales Hop CLI/STDIO con el primer candidato 2.4.0 (ZIP dcfe669e53e638b9618a39d749724091d22d0703b56b198f55bc7e5b21e0640f) y sin carpeta de proyecto en el agente. Después se añadió la protección de limpieza por vínculo y se repitió la compilación completa para el paquete final indicado abajo. No se ejecutó ETL ni se contactaron bases de datos.

| Caso observado | Resultado |
| --- | --- |
| Activar Live Sync en ambas ventanas | Iconos grises; diálogo distingue las sesiones con identificador corto. |
| Conectar por URL con dos sesiones | Respuesta ambiguous con dos candidatos; ninguna selección automática. |
| Elegir explícitamente la ventana A | Solo A queda negra; B sigue gris. |
| Elegir B con otro agente | Ambas quedan conectadas, cada una con su identidad. |
| Crear un pipeline A y workflow B mediante planes nativos | Preview, aplicación, recarga nativa y presentación instantánea correctos. |
| Preparar un borrador C con ambos agentes conectados | Solo A muestra su borrador; B conserva su propio archivo. |
| Tooltip A | Agente de prueba A, versión 1, operación, duración y última actividad; sin proyecto, archivo ni instrucciones de clic. |
| Pulsación | 201 muestras iniciales, opacidad mínima 0,61 y máxima 1; conserva la posición del icono. |
| Clic en icono A con tooltip encima | A informa disconnected y queda disponible; B mantiene connected. Se retira el borrador A pendiente. |
| Conectar otra vez por URL | Lista ambos proyectos/archivos activos y el agente conectado a B; exige nueva selección. |
| Reconectar explícitamente A | Conexión nueva a la misma ventana, sin reconexión automática del vínculo revocado. |
| Salir del agente B | La ventana conserva el servicio y vuelve a disponible/gris. |
| Menú MCP Live Sync en A | El servicio se apaga, desaparece el icono y el agente recibe inactive. |
| Menú MCP Live Sync en B | Apagado completo confirmado por diálogo nativo. |

Se canceló C y se revirtieron A y B por MCP con transacción y SHA-256; quedaron cero archivos .hpl/.hwf de prueba. Las pestañas no se guardaron mediante manipulación XML. Se cerraron ambos navegadores de prueba y únicamente el Tomcat desechable propio.

El ZIP final 92456782bb9acb44bc437061e481fe70f2c6c4b80d53c3a1a21868863a345ee6 se cargó después en otra instancia RAP desechable. Se repitió el recorrido disponible/gris → conectado/negro → clic/desconectado con icono conservado → reconexión explícita → menú/inactive sin icono. Pasó completo. Se cerraron el agente y el navegador y se detuvo únicamente esa instancia propia. Evidencia adicional: final-agent-A-results.json y final-browser-evidence.json. El primer intento de conexión se adelantó al procesamiento del clic de activación y devolvió inactive; una llamada explícita posterior, con el diálogo de activación ya confirmado, conectó correctamente.

El control mide 24 × 26 píxeles lógicos en esta instalación; el logo permanece limitado a 16 px. El borde negro que puede aparecer al enfocar el control es el foco nativo, independiente del logo gris. La prueba anterior de 2.3.2 conserva la comparación de altura del pie antes/después.

## Alcance y estructura

Frente a la instantánea 2.3.2, el código principal pasa de 60 a 63 archivos Java y de 838.926 a 866.732 bytes. Las tres clases nuevas separan actividad, concesión del agente y lectura acotada del initialize HTTP. Se mantienen cuatro herramientas ligeras y no se añaden dependencias de ejecución. El catálogo inicial medido ocupa 2.280 bytes. Esto mide tamaño, no demuestra una mejora de latencia ni una reducción de complejidad global; no se realizó un benchmark de rendimiento.

Los archivos de un proyecto común siguen compartidos: la prueba certifica destinatarios de presentación, no aislamiento de datos. Una operación nativa ya admitida puede terminar después de desconectar; el siguiente agente espera a que termine. La identidad es la declarada por el cliente, no autenticación. No se certificó presencia entre procesos de escritorio ni un nuevo recorrido de teclado en esta prueba Web.

## Entrega

- Paquete: D:/dev/build_hop_web/mcp/sessions/20261113/hop-mcp-connector-2.4.0-web-sessions.zip
- SHA-256 ZIP: 92456782bb9acb44bc437061e481fe70f2c6c4b80d53c3a1a21868863a345ee6
- SHA-256 JAR: 759e41f23cfc5c584ea1e29a5ebc4cd63d8a021a1ad89bb884c5b7eab13ef87b
- Tamaño ZIP: 6.251.200 bytes; JAR: 599.358 bytes.
- Evidencia: verify.log, package-verification.json, agent-A-results.json, agent-B-results.json, browser-evidence.json, capturas y review.json en la misma carpeta.

El perfil apache_hop_web de Codex apunta a la instalación privada 2.4.0-92456782bb9a, bajo D:/dev/build_hop_web/mcp/agent. Se conserva el certificado público verificado y el resto de perfiles. Las copias íntegras anteriores de configuración están exclusivamente en la carpeta privada .codex/backups. Se verificó el comando configurado con initialize, tools/list y status: versión 2.4.0, cuatro herramientas, selector session_id e inicio desconectado. No se intentó conectar ese smoke a producción. Reiniciar Codex para recargar el proceso y su esquema.

Producción conserva el JAR 2.3.1 con SHA-256 0d7a6888ac33b171f59cec0f08ec0a3d9f4b12d0a91faeb83a2523a535b21966. No se apagó ni reinició su Tomcat. El instalador install-server.ps1 está preparado: exige parada registrada y bloqueo exclusivo de los JAR, compara manifiestos, respalda fuera de plugins y restaura ante fallo. Falta instalar con Tomcat detenido por su responsable y validar después del arranque real. La prueba local no equivale a esa validación de producción.
