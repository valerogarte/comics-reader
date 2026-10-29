# Verificación de interfaz, actividad y selector Android

## Alcance y estado

APK final comprobado en ambos emuladores, SHA-256: `53f50986ebfd7c7cdb3a6b1b9006742c68a22789e9c44555a0d177e4a35301d0`.

| Suite | Entorno | Resultado final | Tiempo | Registro |
| --- | --- | --- | --- | --- |
| Interfaz Compose | Android 16 / API 36, x86_64 | **16/16 correctos** | 58,837 s | [ui-api36.txt](final/ui-api36.txt) |
| Interfaz Compose | Android 7.0 / API 24, x86_64 | **16/16 correctos** | 26,283 s | [ui-api24.txt](final/ui-api24.txt) |
| Actividad y SAF reales | Android 16 / API 36, x86_64 | **2/2 correctos** | 26,020 s | [activity-api36.txt](final/activity-api36.txt) |

Los dos casos SAF requieren API 29 o superior y se omiten en API 24. El conjunto completo de la revisión pasa **80/80 casos en API 36** y **78/78 en API 24**, sobre el mismo APK; índices de resultados: [API 36](final/results-api36.json) y [API 24](final/results-api24.json). No se ejecutan pruebas en el dispositivo físico ni contra el NAS del usuario.

Código: [NativeUiInstrumentedTest.kt](../../../android/app/src/androidTest/java/com/valerogarte/comicsreader/NativeUiInstrumentedTest.kt) y [NativeMainActivityInstrumentedTest.kt](../../../android/app/src/androidTest/java/com/valerogarte/comicsreader/NativeMainActivityInstrumentedTest.kt).

## Matriz de 16 casos Compose

Todos los casos de esta tabla usan `ComponentActivity`, los componentes Compose de producción y `RecordingActions`, una implementación de `AppActions` con estado controlado. No abren conexiones SFTP, motores de documentos ni la base de datos de producción. Cada fila pasa en las ejecuciones finales de API 36 y API 24.

| Nº | Caso instrumentado | Comprobación | API 36 y API 24 |
| --- | --- | --- | --- |
| 1 | `emptyLibraryOffersImportAndFooterWithoutTrash` | Biblioteca vacía, total, filtro Todos, tamaño de portada, acción de importar y ausencia de Papelera. | Pasa |
| 2 | `settingsCloseAndSystemBackDiscardChanges` | Cruz y Atrás cierran configuración; el cambio de tema sin guardar se descarta. | Pasa |
| 3 | `serverEditorValidatesEmptyFieldsAndCancelsWithoutSaving` | Campos obligatorios, host sin esquema/ruta, puerto válido y raíz absoluta; cancelar no añade el servidor. | Pasa |
| 4 | `serverDraftAddsEditsDeletesAndCommitsOnlyOnGlobalSave` | Añadir, editar y borrar servidores en borrador; aviso de conservación de libros descargados; sólo Guardar confirma la lista. | Pasa |
| 5 | `appliedServerDraftIsDiscardedWhenSettingsAreClosed` | Cerrar configuración descarta un borrado aplicado dentro del editor pero no guardado globalmente. | Pasa |
| 6 | `externalFontAndBackgroundUpdatesPreserveSettingsDraft` | Acciones de importar fuente/fondo y recepción de nuevas rutas sin perder el tema del borrador. | Pasa |
| 7 | `drawerSwitchesIndependentSftpSourcesAndBackReturnsToBooks` | Menú con dos servidores, selección activa, contenido del servidor elegido y Atrás a Libros. | Pasa |
| 8 | `sftpParentSyncDownloadsAndGreenTickUseSelectedSource` | Carpeta superior, sincronizar, descargar individual/todos, indicador Descargado y Atrás a la carpeta padre. | Pasa |
| 9 | `filtersLayoutAndScrollStateSurviveReaderRoundTrip` | Filtros Sin leer/Leyendo/Finalizados, total de libros y conservación de filtro/vista al cerrar el lector. | Pasa |
| 10 | `selectionCreatesShelfAndPermanentDeletionOffersEmptyShelfRemoval` | Selección múltiple, añadir a estantería, confirmación de borrado definitivo y posibilidad de conservar la estantería vacía. | Pasa |
| 11 | `emptyShelfCanBeRemovedWithoutDeletingOtherBooks` | Aceptar borrar una estantería vacía sin quitar los demás libros del estado. | Pasa |
| 12 | `drawerCreatesAndOpensLocalShelf` | Crear estantería desde el menú, abrirla, mostrar su estado vacío y volver a Libros con Atrás. | Pasa |
| 13 | `readerSliderAndBoundaryButtonsExposeAccessibleState` | Slider etiquetado, cambio de página y botones anterior/siguiente deshabilitados en los extremos. | Pasa |
| 14 | `readerTapSwipeRtlZoomAndLoadingGuard` | Toques laterales, desplazamiento, dirección RTL, zoom por doble toque, ajustar página y bloqueo de navegación durante carga. | Pasa |
| 15 | `readerSettingsScreenFlagAndBackAreLifecycleSafe` | Opciones de texto, cambio de pantalla encendida, bandera de ventana y cierre con Atrás. | Pasa |
| 16 | `messagesAreVisibleAboveSettingsAndReader` | Mensajes y errores visibles y cerrables por encima de configuración o lector. | Pasa |

Los callbacks y el estado permiten verificar navegación y presentación. La persistencia, aislamiento real entre servidores y eliminación de archivos se comprueban en las suites de datos y flujos completos. El caso 9 no fuerza una lista larga con desplazamiento distinto de cero; el caso 14 comprueba zoom por doble toque, sin simular un gesto de pinza. Los indicadores accesibles se verifican por semántica, sin medir su color mediante capturas.

## Dos recorridos con MainActivity y SAF reales

Estos casos lanzan `MainActivity` mediante `ActivityScenario`. Las acciones se realizan desde la interfaz y el selector de documentos Android; utilizan el `LibraryViewModel`, repositorio, almacenamiento privado y renderizado nativo de producción. La lectura del estado y de SQLite sirve para esperar y comprobar resultados; no sustituye los botones ni las selecciones por callbacks simulados.

| Caso instrumentado | Recorrido y comprobaciones | Resultado final |
| --- | --- | --- |
| `realSafImportReadRecreateFontBackgroundAndSettingsClose` | Crea PDF de tres páginas, PNG y TTF propios; importa PDF por DocumentsUI; abre el lector nativo; avanza por botón y teclas de volumen; conserva página tras recrear; cierra y verifica progreso en SQLite; conserva vista de lista y filtro Leyendo; importa fuente y fondo por SAF; cierra configuración con cruz y Atrás. | **Pasa en API 36**, incluido el último Atrás después de esperar el foco del diálogo. |
| `reflowableReaderFollowsSystemNightModeAcrossActivityRecreation` | Importa TXT largo por SAF; guarda tema Sistema y fondo Blanco desde la UI; abre página 2; cambia el modo nocturno mediante `cmd uimode night`; recrea la actividad conservando ViewModel; exige el nuevo color del bitmap y la misma página/paginación; vuelve a claro y comprueba de nuevo. | **Pasa en API 36**, después de corregir el defecto real del bitmap que conservaba colores anteriores. |

Los archivos se publican con `ContentResolver` en `MediaStore.Downloads`, bajo `Download/ComicsReaderQA/`, con nombre UUID propio y sin permisos generales de almacenamiento. El PDF y la imagen se generan localmente; la TTF se copia de una fuente disponible en el sistema del emulador.

### Protecciones y restauración

- `@SdkSuppress(minSdkVersion = 29)` y comprobación adicional de versión antes de crear los archivos.
- Sólo se habilitan cuando la huella o el hardware identifica un emulador y se pasa `-e qaAllowUiSmoke true`. No se ejecutan en el dispositivo físico.
- Antes de actuar se guardan los identificadores de libros existentes, una huella SHA-256 de la configuración SFTP, las preferencias de apariencia y las rutas privadas de fuente/fondo.
- El cierre borra únicamente libros nuevos identificados como propios, sus documentos MediaStore y los archivos de apariencia añadidos por la prueba. Restaura las claves de apariencia conocidas sin vaciar las preferencias y comprueba que siguen los libros anteriores y la misma huella de servidores.
- El caso nocturno restaura el modo del sistema original en `finally`, también cuando falla una aserción.
- No se navega al NAS ni se cambian sus perfiles. Las contraseñas y el contenido de los perfiles no se escriben en el registro.
- Las capturas `files/qa-evidence/saf-books.png` y `saf-reader.png` corresponden sólo a Libros y al lector, antes de abrir configuración. Exportaciones: [Libros API 36](captures/saf-books-api36.png), [lector API 36](captures/saf-reader-api36.png) e [inicio API 24](captures/home-api24.png).

## Hallazgos de producto y ajustes de automatización

| Tipo | Hallazgo y evidencia | Cambio y estado |
| --- | --- | --- |
| Producto | Un lector de texto con tema Sistema conservaba el bitmap y las opciones de la sesión anterior tras cambiar el modo claro/oscuro y recrear la actividad. El caso SAF nocturno falló exactamente al esperar el fondo oscuro. | El controlador compara con las `DocumentOptions` usadas al crear la sesión retenida; `MainActivity.onCreate` notifica la configuración y se reabre el documento ajustable conservando la posición. Regresión correcta en el APK final de API 36. Registros: [fallo previo](initial-runs/activity-night-and-picker-api36.txt) y [repetición final](final/activity-api36.txt). |
| Automatización | La primera suite de 14 casos consultaba el resultado de un toque antes de terminar la espera interna que distingue toque y doble toque. | Espera explícita de la página solicitada. La segunda ejecución de 16 casos pasó completa. Registro inicial: [ui-api36.txt](initial-runs/ui-api36.txt). |
| Automatización | La búsqueda de la TTF con `By.text(filename)` coincidía con el propio campo `search_src_text` y no seleccionaba un documento. El PDF ya se había importado y leído correctamente. | Navegación por raíces a Downloads/ComicsReaderQA y selección limitada a `dir_list`, sin búsqueda global ni historial. La siguiente ejecución alcanzó la importación real de fuente. Registro inicial: [activity-api36.txt](initial-runs/activity-api36.txt). |
| Automatización | El selector `image/*` presenta miniaturas sin texto de título. Una PNG viva reprodujo un nodo cuyo `content-description` empieza por el nombre del archivo; buscar sólo texto no podía localizarlo. | La selección dentro de `dir_list` admite texto o `descStartsWith(filename)`. Se comprobó la PNG de diagnóstico en el índice MediaStore y se eliminó exclusivamente su URI; no se modificó el selector de producción. La importación de imagen pasa en el recorrido final. |
| Automatización | El último Atrás se inyectaba inmediatamente después de abrir configuración, sin esperar el foco Android de su ventana. Una apertura y un Back físico manuales cerraron el diálogo correctamente en API 36. | Antes del Back se espera el estado abierto, el botón Cerrar visible y el foco de una ventana distinta de la Activity mediante `WindowInspector`. Sin cambio de producción; el recorrido completo pasa en el APK final. |
| Análisis estático | Lint rechazó `dispatchKeyEvent` de AndroidX por API restringida y `@RequiresApi` en una clase de pruebas. | Volumen implementado mediante `onKeyDown`/`onKeyUp` del framework; prueba anotada con `@SdkSuppress`. El recorrido real ya alcanzó las páginas esperadas con volumen. Resultado final de lint corresponde al informe general de la revisión. |

Las ejecuciones finales de ambas suites están completas. Los fallos históricos se conservan por separado y las limitaciones de alcance se indican junto a cada grupo de pruebas.
