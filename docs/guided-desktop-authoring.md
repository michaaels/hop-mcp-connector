# Construcción guiada en el editor nativo de Hop

Implementación local de desarrollo, actualizada el 13 de noviembre de 2026. El único objetivo soportado es Hop 2.20.x, compilado contra 2.20.0. Requiere Java 21 y **MCP Live Sync** activado en Hop Desktop o Hop Web/RAP para el mismo proyecto del servidor. El servidor debe tener `--allow-mutation`; esta función no requiere `--allow-execution`.

## Experiencia

El MCP abre una pestaña dentro de Hop con el editor real `HopGuiPipelineGraph` o `HopGuiWorkflowGraph`. Los iconos, las acciones, las transformaciones y los hops los dibuja Hop. La barra MCP de esa pestaña permite seleccionar **Auto**, **0.5×**, **1×**, **2×**, **Instantáneo**, **Pausar/Continuar** y **Saltar al resultado**.

La GUI consume los pasos mientras el cliente prepara los siguientes bloques. Las llamadas MCP regresan sin esperar a que termine la presentación. Si no hay más pasos preparados, muestra «Esperando el siguiente bloque del modelo…». Auto ajusta la permanencia según el número de pasos pendientes; no garantiza continuidad si el siguiente bloque tarda más que el disponible.

Los cambios de configuración usan metadatos nativos completos. El paso «Configurar …» identifica el componente afectado; no se simula escritura de cada campo ni se abren y cierran automáticamente sus diálogos. Mientras se presenta el borrador, la edición manual, el guardado y la ejecución de esa pestaña están bloqueados. Al aplicar y finalizar la presentación, Hop abre o recarga la definición guardada en su editor normal, donde están disponibles sus diálogos nativos.

## Uso por MCP

No se añaden herramientas. Se amplían las herramientas de planes existentes con argumentos opcionales.

1. Anunciar la preparación:

   ```json
   {"path":"ejemplo.hpl","presentation":"guided","operations":[]}
   ```

   Enviar a `hop_prepare_correction_plan`.

2. Añadir un bloque usando el `plan_id` devuelto anteriormente:

   ```json
   {
     "path":"ejemplo.hpl",
     "previous_plan_id":"<plan_id anterior>",
     "operations":[
       {"operation":"add_component","name":"Entrada","plugin_id":"Dummy","x":80,"y":80},
       {"operation":"add_component","name":"Salida","plugin_id":"Dummy","x":280,"y":80},
       {"operation":"add_hop","from":"Entrada","to":"Salida"},
       {"operation":"auto_layout"}
     ]
   }
   ```

   Cada bloque crea otro plan inmutable; el anterior pasa a `superseded`. Utilizar siempre el último identificador y su huella. Se mantiene un máximo acumulado de 50 operaciones, el mismo archivo y la misma huella base.

3. Aplicar el último plan mediante `hop_apply_correction_plan`:

   ```json
   {"plan_id":"<último plan>","plan_sha256":"<su huella>"}
   ```

   Para terminar inmediatamente, incluir `"presentation":"instant"`. También puede elegirse `instant` desde la preparación inicial; en ese caso no se crea la presentación. Al omitir el argumento y no continuar un plan guiado se conserva el comportamiento anterior.

4. Cancelar un plan todavía preparado mediante `hop_correction_plan_status`:

   ```json
   {"plan_id":"<último plan>","cancel":true}
   ```

La cancelación impide aplicarlo. Pausar o cerrar la presentación no cancela el plan ni revierte archivos ya guardados. El rollback conserva su contrato existente.

`presentation.ui_notified` confirma la publicación al puente local, no la finalización del dibujo. Sin una sesión Desktop o Web activa las operaciones semánticas siguen disponibles y ese indicador es `false`.

## Integridad y límites

- Preparar no escribe la definición real. Aplicar conserva preview, SHA-256, copia protegida cuando corresponde, reemplazo con indicador de atomicidad y recarga nativa. Las pestañas reales con cambios sin guardar siguen protegidas frente a recargas.
- El evento visual contiene únicamente geometría saneada, operaciones y una huella. Configuraciones, SQL, scripts y credenciales no se incluyen en ese paquete ni se devuelven para reproducir la animación.
- Para mostrar los componentes configurados se conserva una instantánea nativa privada en `.hop-mcp/drafts/`. Está fuera de las herramientas de lectura del proyecto, se verifica su SHA-256 y se rechazan enlaces y rutas de control no válidas. El lector aplica el parser XML seguro antes de cargar los objetos Hop.
- Máximo de 4 MiB por instantánea, 32 MiB de almacenamiento y 100 instantáneas. Se reutiliza un archivo por presentación; las revisiones no crean copias acumulativas. Los borradores de más de una hora se depuran durante una publicación posterior.
- Máximo de 128 KiB por evento visual, 200 componentes, 1.000 conexiones y cuatro vistas activas. Los eventos tradicionales mantienen su límite de 16 KiB.
- El trabajo de lectura del borrador se hace fuera del hilo SWT; la carga de metadatos y la presentación se despachan al hilo de la GUI correspondiente a la sesión. El temporizador solicita actualizaciones cada 33 ms en Desktop o 100 ms en Web mientras hay pasos, y cada 250 ms en pausa/espera; no es una medición de FPS. El lienzo solo se redibuja cuando cambia el paso o su interpolación.
- Se reutilizan las bibliotecas de Hop. No se añaden dependencias Maven, aplicaciones externas ni servicios de red para la presentación.

## Instalación local

El paquete RAP para Hop 2.20 está en `D:\dev\build_hop_web\mcp\rap-live-sync\20261113\hop-mcp-connector-2.2.6-rap-live-sync.zip`, con archivo SHA-256 contiguo. Es una compilación local de desarrollo; no se ha publicado una versión de Marketplace. Su objetivo y verificación están en [el informe RAP](validation-rap-live-sync-2026-11-13.md).

Cerrar Hop y detener el proceso MCP que usa esa instalación antes de sustituir el plugin. Extraer el ZIP en la raíz de la instalación Hop, reiniciar Hop y el servidor MCP, y activar **MCP Live Sync** para el proyecto. En Hop Web, detener Tomcat antes de sustituir el plugin en su carpeta efectiva de plugins y reiniciar Tomcat después; actualizar también el plugin del servidor MCP. Ambos procesos deben usar la nueva compilación y el mismo proyecto. La validación usó una instalación de plugin y un proyecto aislados bajo `D:\dev\build_hop_web`; no instaló este paquete sobre la sesión habitual ni modificó los flujos de negocio.

La evidencia y las limitaciones de las pruebas están en [el informe de validación](validation-guided-desktop-2026-11-12.md).
