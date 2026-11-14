# Factibilidad: construcción visual guiada y concurrente en Hop Desktop

El desarrollo posterior se documenta en [construcción guiada nativa](guided-desktop-authoring.md) y su [validación](validation-guided-desktop-2026-11-12.md). Este documento conserva el análisis previo.

## Conclusión

Es factible mostrar un plan por etapas y presentar movimientos fluidos mientras el modelo sigue preparando los siguientes bloques. La presentación debe ejecutarse de forma asíncrona en Hop GUI, consumiendo una cola acotada, y trabajar sobre una representación de borrador separada de la definición real. No basta con insertar pausas entre llamadas MCP.

Este documento es un análisis y una propuesta. No se implementó animación ni se modificaron flujos o permisos para realizarlo. El alcance solicitado es Hop GUI de escritorio; no certifica esta experiencia en Hop Web.

## Base comprobada en el código

- `HopLiveUiSync.applyToUi` abre o recarga la definición después de recibir un evento de cambio guardado. Ese reemplazo completo explica la aparición instantánea observada.
- `HopSemanticEventSink.publish` está diseñado para publicar sin esperar el trabajo de la interfaz. La GUI ya procesa eventos con un planificador independiente y despacho al hilo SWT.
- `HopLiveUiEventBroker.publish` transmite ruta, tipo e identificadores/huellas. Aunque el evento interno contiene `changes`, el formato de intercambio actual no serializa esas operaciones. No existe una cola de pasos visuales ni estados de preparación publicados a la GUI.
- `HopCorrectionPlanManager.prepare` ya conserva planes inmutables con preview nativo, huella del archivo base, límite de planes y caducidad. Se puede reutilizar esa infraestructura sin volver mutables los planes existentes: cada nuevo bloque produciría una revisión de borrador.
- En el JAR real de Hop 2.20 se comprobaron `paintControl`, `getPipelineMeta`/`getWorkflowMeta`, `setPipelineMeta`/`setWorkflowMeta` y `redraw`. Son puntos de apoyo para una vista nativa; no constituyen un animador listo para usar. La integración precisa del dibujo y su compatibilidad con 2.19 requieren un prototipo.

## Funcionamiento propuesto

```text
Modelo:  anuncia tarea → prepara bloque A → prepara bloque B → valida el resultado
GUI:                     presenta A ─────────→ presenta B ─────→ muestra resultado
Archivo: conserva el estado original ─────────────────────────→ aplicación protegida
```

1. **Inicio visible.** Una llamada temprana del cliente anuncia el objetivo público: «Preparando lector, filtro y salida». Hasta disponer de pasos concretos, la GUI muestra un indicador indeterminado. El MCP no puede detectar que el modelo está razonando antes de recibir una llamada, ni conocer de antemano cuánto tardará.
2. **Bloques de plan.** El modelo envía pasos públicos y estructurados conforme los decide, por ejemplo crear componentes, conectarlos y distribuirlos. Las revisiones se vinculan al mismo borrador y a la huella del archivo de partida.
3. **Cola independiente.** La llamada devuelve el control después de registrar/validar el bloque; no espera a que termine su presentación. El modelo puede continuar analizando mientras Hop reproduce la cola con temporizadores del hilo gráfico, sin bloquearlo.
4. **Vista de borrador.** La GUI resalta el paso actual, muestra nodos/conexiones de previsualización y mueve las figuras de forma interpolada. El flujo real y sus pestañas con cambios sin guardar permanecen protegidos. La vista indica expresamente que es un borrador y no permite ejecutarlo como ETL.
5. **Aplicación final.** Al completar el plan, se valida y aplica el resultado mediante el mecanismo nativo existente: precondición SHA-256, respaldo, escritura protegida y recarga. No se guarda un archivo parcialmente construido en cada fotograma. La autorización de aplicación sigue el contrato MCP existente; la animación no debe introducir confirmaciones obligatorias por cada paso.

Lo que se muestra es un plan operativo y sus revisiones, no el razonamiento interno del modelo. La reproducción de una modificación ya guardada debe identificarse como tal, para distinguirla de la previsualización previa a aplicar.

## Velocidad y continuidad

Se propone un búfer inicial pequeño, por ejemplo tres a cinco pasos disponibles, y controles `Auto`, velocidad manual, pausa y omitir presentación. Esos valores son un punto de partida de diseño, no mediciones de usabilidad.

- Con varios bloques pendientes, el modo automático puede acelerar moderadamente o agrupar cambios relacionados.
- Si queda poco contenido, puede alargar suavemente la presentación dentro de un límite elegido por el usuario.
- Si la cola se vacía, muestra «Preparando el siguiente bloque» o «Esperando el siguiente bloque». No inventa acciones ni un porcentaje de razonamiento completado.
- Una investigación o consulta larga puede superar cualquier búfer razonable. Ajustar la velocidad ayuda, pero no garantiza una animación ininterrumpida.
- El progreso puede expresarse como pasos del borrador ya presentados. El total definitivo solo se anuncia cuando el plan está cerrado; no debe confundirse con progreso de ejecución ETL o de guardado.

Ejemplo inicial de experiencia: movimientos de 250–400 ms y una permanencia breve que permita leer el nombre del paso, con velocidad ajustable. Debe validarse visualmente; no se promete una tasa de fotogramas ni un tiempo total sin medirlos. El animador no necesita una llamada MCP ni una escritura por fotograma.

## Integridad y concurrencia

- Mantener identificadores y números de revisión por borrador; no mezclar pasos de tareas diferentes ni reproducir estados antiguos como si fueran actuales.
- Las operaciones posteriores del modelo deben depender del estado del borrador validado, no de que un fotograma ya se haya dibujado.
- Mantener separados los estados de preparación, validación, aplicación y presentación. Una confirmación visual no demuestra que un cambio pendiente ya esté guardado.
- Acotar cola, eventos y memoria; agrupar o sustituir revisiones visuales obsoletas si el productor avanza más deprisa. Conservar los límites actuales de operaciones, respuestas y archivos.
- Transmitir únicamente la información gráfica necesaria: nombres saneados, tipos de plugin, coordenadas, conexiones y estados. No introducir SQL, scripts, valores de conexión o credenciales en el puente gráfico.
- Si el archivo cambia durante la preparación, rechazar la aplicación obsoleta con el control SHA existente y volver a preparar; no sobrescribir silenciosamente cambios del usuario.
- Pausar la presentación no equivale a cancelar la tarea del modelo. Cancelar un borrador debe impedir su aplicación posterior. Si el cambio ya se guardó, detener la animación no lo deshace; la reversión sigue el contrato nativo de rollback.
- Una desconexión, cierre de pestaña o error de dibujo no debe dejar un archivo real incompleto. Si no hay una GUI conectada, conservar el comportamiento normal del MCP.

## Implementación gradual sugerida

| Etapa | Contenido | Complejidad cualitativa |
| --- | --- | --- |
| 1 | Actividad visible, lista de pasos, progreso de presentación y cola asíncrona con velocidad/pausa | Media: amplía protocolo de eventos, estado de tarea y panel nativo |
| 2 | Borrador visual con aparición de componentes, conexiones y desplazamientos interpolados | Media-alta: necesita representación separada, dibujo, selección/zoom y gestión de recursos SWT |
| 3 | Ajuste automático según la cola y refinamiento de agrupación, foco y movimiento reducido | Media: depende de medir la experiencia de las etapas anteriores |

Conviene ampliar cuidadosamente las herramientas de preparación/aplicación y el estado del puente existentes, evitando duplicarlas con una herramienta para cada movimiento. El diseño final del contrato debe mantener compatibilidad, esquemas acotados y las mismas autorizaciones. No hay evidencia de que sea necesario cambiar el motor ETL, añadir bibliotecas pesadas o incorporar un segundo modelo.

## Validación necesaria antes de anunciarla disponible

Prueba real de escritorio con el modelo produciendo bloques mientras la GUI presenta otros; variación de velocidad; cola vacía/llena; revisiones y eventos duplicados; pausa/continuación; cancelación antes/después de aplicar; cierre de la GUI; archivo modificado por el usuario; pestaña con cambios sin guardar; error de validación y rollback. Verificar que los estados intermedios no se guardan ni pueden ejecutarse desde el borrador, que la interfaz sigue respondiendo y que no crecen de forma ilimitada la cola o los recursos gráficos. Mantener pruebas de protocolo/seguridad y compilación con Hop 2.19 y 2.20.
