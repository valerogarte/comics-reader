# Verificación de datos, almacenamiento y SFTP

## Alcance y resultados

`NativeDataInstrumentedTest` contiene **16 casos**. Usa `QaApplication` para aislar archivos, SQLite y preferencias; las conexiones SSH utilizan únicamente la fixture local. No modifica la biblioteca del usuario ni contacta con el NAS.

APK final comprobado en ambos emuladores, SHA-256: `53f50986ebfd7c7cdb3a6b1b9006742c68a22789e9c44555a0d177e4a35301d0`.

| Ejecución final | Resultado Data | Duración | Evidencia |
| --- | --- | --- | --- |
| Android API 36 | **16/16 correctos** | 29,193 s | [data-api36.txt](final/data-api36.txt), [entorno](final/environment-api36.json) |
| Android API 24 | **16/16 correctos** | 14,050 s | [data-api24.txt](final/data-api24.txt), [entorno](final/environment-api24.json) |
| JVM, JSch 2.28.8 y fixture SSH independiente | **18/18 correctos** | No medida en el registro | [sftp-jvm-results.txt](sftp-jvm-results.txt) |

Las ejecuciones Android incluyen configuración dañada, recuperación de residuos y cancelación de una descarga bloqueada. El conjunto completo de suites también terminó correctamente: **80/80 en API 36** y **78/78 en API 24**, donde se omiten los dos casos de Activity que requieren API 29. Resúmenes: [API 36](final/results-api36.json) y [API 24](final/results-api24.json).

### Historial de fallos corregidos

- Primera ejecución Android API 36: 13/14 correctos. La fixture devolvía `/.` como raíz canónica; se corrigió a `/` y la prueba pasó a usar una entrada listada. [Registro inicial](initial-runs/data-api36.txt).
- Primera ejecución API 24: 7/16 correctos. JSch 2.28.8 requería `java.time.format.DateTimeFormatter`, ausente en esa versión de Android. Se activó la compatibilidad de bibliotecas Java mediante desugaring. [Registro anterior al ajuste](before-api24-desugaring/data-api24.txt).
- Ejecución API 24 tras desugaring: 13/16 correctos. Tres pruebas usaban `.use()` sobre `SQLiteOpenHelper`, que en API 24 no implementa `AutoCloseable`; se sustituyó únicamente en el test por un helper con `try/finally` y `close()`. [Registro intermedio](initial-runs/data-after-desugaring-api24.txt).
- Los resultados finales de la tabla corresponden al APK con desugaring y al APK de pruebas con el helper compatible. No quedan fallos en estas suites.

## Matriz de 16 casos

| Nº | Caso instrumentado | Comprobación principal |
| --- | --- | --- |
| 1 | `encryptedProfilesRoundTripKeepStableIdsWithoutPlaintextSecrets` | Cifrado de perfiles, identificadores estables y ausencia de contraseñas/nombres en texto claro. |
| 2 | `defaultNasSeedsOnceAndExplicitEmptyListNeverReseeds` | Perfil inicial una sola vez; eliminar todos persiste una lista vacía. |
| 3 | `emptyBuildDefaultLeavesRoomForSeedInFutureBuild` | Una compilación sin perfil inicial permite incorporarlo posteriormente. |
| 4 | `legacySingleProfileMigratesWithDifferentNasAndPreservesSourceFingerprint` | Migra el perfil anterior, conserva su caché y añade un NAS diferente. |
| 5 | `matchingLegacyNasIsNotDuplicatedAndGetsDefaultLabel` | El perfil inicial no duplica una fuente ya existente. |
| 6 | `duplicateSourcesOrProfileIdsAreRejectedAtomically` | Rechaza duplicados sin sustituir la lista válida anterior. |
| 7 | `explicitSaveRecoversCorruptEncryptedProfilesWithoutRemovingLibraryCacheOrPins` | Error de carga visible; guardado explícito válido sustituye datos corruptos y conserva libros, progreso, estantería, caché y claves SSH. Una lista nueva inválida conserva los datos corruptos anteriores. |
| 8 | `firstRepositoryReconcilesKilledProcessResiduesAndLaterRepositoriesPreserveActiveFiles` | Limpia temporales y archivos finales huérfanos antes de la primera operación; conserva referencias SQLite y claves SSH; otra instancia no limpia una descarga activa. |
| 9 | `cachedFoldersStayOfflineAndSeparateIdenticalRemotePathsBySource` | Carpetas en caché sin conexión, aislamiento por fuente y ausencia de nuevos archivos de claves al leer caché. |
| 10 | `localImportIsPrivateDeduplicatedAndPermanentDeleteKeepsEmptyShelfUntilAccepted` | Importación privada, deduplicación, progreso y borrado definitivo; conserva la estantería vacía hasta la decisión posterior. |
| 11 | `realSftpBrowsingCachesOnlyPreviewsAndSyncReplacesCoverWithoutLeaks` | Navegar guarda únicamente fichas/portadas; caché sin red; sincronización renueva metadatos/imagen y elimina miniaturas antiguas. |
| 12 | `realSftpExplicitDownloadsAreIdempotentAndBulkOnlyDownloadsCurrentFolder` | Descarga solicitada, sin duplicación; lote de la carpeta actual; estantería por ruta y portadas locales independientes. |
| 13 | `twoRealProfilesKeepSameRemotePathAndDownloadLinksIndependent` | Dos servidores con igual ruta conservan fichas, descargas y claves independientes. |
| 14 | `differentServersDownloadInParallelAndEditingOtherServerKeepsConnectionAlive` | Descargas paralelas entre servidores; editar B mantiene la transferencia/conexión de A. |
| 15 | `realSftpRejectsTraversalEscapingSymlinksAndInvalidRangesWithoutRemoteWrites` | Confinamiento, enlaces externos, límites de rangos, un byte real transferido y plazo ante respuesta detenida; cancelación de descarga bloqueada antes de 1,5 s con limpieza y sesión reutilizable; cero escrituras SFTP. |
| 16 | `changedHostKeyIsRejectedAfterRepositoryRecreationAndLegacyPinIsCopied` | Conserva claves previas y rechaza una clave SSH cambiada después de recrear el repositorio. |

## Hallazgos y correcciones

P2 indica un defecto funcional o de almacenamiento que debe corregirse; P3, un defecto de la herramienta de prueba. Los siguientes cambios están implementados y los 16 casos de datos han pasado sobre el APK final en API 36 y API 24.

| Severidad | Hallazgo | Corrección y evidencia |
| --- | --- | --- |
| P2 | La configuración cifrada dañada permitía recuperar los libros locales, pero impedía guardar servidores nuevos. | Carga estricta y error inicial conservados; guardado explícito puede sustituir la lista dañada sin eliminar biblioteca/caché/claves. Regresión 7 y flujo del controlador. |
| P2 | Una muerte del proceso podía dejar cómics temporales o renombrados sin referencia, ocupando disco. | Reconciliación una vez por directorio y proceso; solo archivos generados sin referencia SQLite. Regresión 8. No simula una muerte real: prepara sus residuos. |
| P2 | El flujo de lectura JSch anticipaba bloques mayores que el rango solicitado. | Intercambio SFTP de lectura exacta; JVM verifica un byte solicitado y un byte transferido, sin anticipar 32 KiB. |
| P2 | Una respuesta SFTP de rango detenida podía bloquear la operación sin plazo efectivo. | Plazo de recepción real de 15 s; JVM inyecta 300 ms frente a un servidor que demora 1,2 s y verifica terminación antes de 1,5 s. |
| P2 | Listar, consultar STAT o descargar podían quedar esperando SFTP mientras SSH seguía respondiendo; el cierre de un flujo interrumpido podía drenar peticiones pendientes. | Vigilancia por canal de 30 s sin progreso, reiniciada con cada bloque descargado; cancelación del repositorio interrumpe el bloqueo y cierra el canal antes de cerrar el flujo. JVM verifica descarga/listado/STAT detenidos, descarga larga con progreso y cancelación sin cerrar la sesión compartida. |
| P2 | JSch interpretaba `?` como comodín en nombres válidos. | Escape de rutas literales en las API que expanden comodines; JVM verifica `who?.txt` con `whoA.txt` presente. |
| P2 | SFTP fallaba en API 24 porque JSch 2.28.8 requiere `java.time.format.DateTimeFormatter`. | `coreLibraryDesugaringEnabled` y `desugar_jdk_libs 2.1.5` aportan la API Java necesaria; las pruebas finales verifican navegación, descargas y cancelación reales en API 24. |
| P3 | La fixture devolvía `/.` como raíz canónica y provocaba un rechazo correcto del confinamiento. | Raíz corregida a `/`; la prueba paralela usa una entrada de carpeta listada, sin fabricar su ruta. |
| P3 | Tres pruebas lanzaban `ClassCastException` al tratar `SQLiteOpenHelper` como `AutoCloseable` en API 24. | Helper de test con `try/finally` y cierre explícito; sin cambios de producción. Las tres regresiones pasan en ambas API. |

## Aislamiento, confidencialidad y reproducción

- Código y sockets en escucha revisados: SSH `127.0.0.1:22991` y control `127.0.0.1:22992`. El servidor solo autentica `qaA`/`qaB` con contraseña de ejemplo; genera sus propias claves y archivos. Las escrituras SFTP están denegadas.
- Escaneo de `docs/qa` y `tools/qa` frente a los valores privados del perfil inicial: ninguna coincidencia del host ni de la contraseña reales. Las coincidencias del usuario corresponden al identificador público existente de la aplicación. No hay direcciones IPv4 distintas de loopback/emulador en estas evidencias.
- `allowBackup=false`, `fullBackupContent=false` y reglas XML excluyen copias en nube y transferencias entre dispositivos, evitando trasladar preferencias cifradas sin la clave de AndroidKeyStore. El XML y su referencia en el manifiesto se han validado estructuralmente. Véase [documentación oficial Android](https://developer.android.com/identity/data/autobackup).
- Comandos y protocolo: [tools/qa/README.md](../../../tools/qa/README.md). Ejecutar Data, Workflow y JVM en secuencia: comparten una fixture que reinician. El ejecutor Android exige seleccionar un emulador y guarda resultados por suite.

Estas pruebas no verifican NAS real, pérdidas de alimentación reales, todos los fallos de disco ni variantes ARM en dispositivo físico. El borrado normal, cancelación cooperativa y recuperación de residuos se comprueban por separado.
