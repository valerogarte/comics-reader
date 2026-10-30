# Auditoría de Comics Reader Android

30 de octubre de 2026. Versión **2.1.17, código 20**. Revisión local de la aplicación nativa. Base Git: 3ce5b8100df8ebd9d45f19780923cb230dcbe829; las correcciones están en el árbol de trabajo de main.

## Resultado

**199 comprobaciones finales correctas:** 90 en Android 16/API 36, 88 en Android 7/API 24 y 21 del transporte SFTP en JVM. El total incluye la repetición de casos entre versiones. Ambos emuladores ejecutaron exactamente el mismo APK.

| Suite | Android 16 | Android 7 |
| --- | --- | --- |
| Datos y SFTP | 18/18 | 18/18 |
| Motores de lectura | 42/42 | 42/42 |
| Interfaz Compose | 18/18 | 18/18 |
| Recorridos completos | 10/10 | 10/10 |
| Actividad, SAF y recreación | 2/2 | Requiere API 29+ |
| Cliente SFTP real en JVM | 21/21 | Mismo cliente Java |

Compilación e instrumentación correctas. Lint: **0 errores y 19 avisos**, principalmente estilo Kotlin, iconos, actualización del wrapper y uso conservador del espacio libre. Se retiraron cuatro recursos sin uso. [Avisos completos](lint.txt).

[APK privado 2.1.17](../../../android/app/build/outputs/apk/validated/comics-reader-2.1.17.apk). SHA-256:

~~~text
14fe3a064acf1d4592b474196865618ddf29e00d457133e1e91d373b854b53b8
~~~

Firma v2 válida. Las 18 bibliotecas nativas están empaquetadas correctamente; las 12 de 64 bits cumplen alineación ELF y ZIP de 16 KiB. [Comprobaciones del APK](apk-checks.json) y [firma](apk-signature.txt).

## Correcciones

| Área | Problema y corrección |
| --- | --- |
| Sesiones SFTP | Contraseñas distintas con el mismo hash reutilizaban sesión o bloqueo de autenticación. Ahora se comparan todas las credenciales. Dos regresiones reproducen esa colisión. |
| Plazos SFTP | Un listado activo podía caducar mientras recibía entradas o resolvía enlaces. Renueva el plazo por progreso y sigue interrumpiendo solicitudes detenidas. Regresión con ocho enlaces y respuestas activas que superan el plazo total. |
| Caché de portadas | Una imagen antigua impedía aplicar un extractor nuevo. Sincronizar respeta la versión 5 y reconstruye imágenes desaparecidas; las visitas normales siguen usando la caché. |
| Caché de carpetas | Desaparecer o reemplazar un directorio dejaba descendientes y miniaturas huérfanos. Se purga su subárbol respetando hermanos, otros servidores y libros descargados. Prueba con %, guion bajo y emoji. |
| Portada frontal | Se podía elegir back_cover y no reconocer cover01. Se prioriza la frontal, se excluyen contraportadas de la heurística y se respeta ComicInfo. Orden independiente del idioma del dispositivo. |
| Páginas auxiliares | Los archivos AppleDouble de macOS se contaban como páginas. Se excluyen de la lectura local y remota. |
| ZIP remoto | Comentarios largos ocultaban el directorio central. Búsqueda progresiva hasta el límite ZIP y validación de cabeceras, con lecturas parciales. |
| XML y documentos | Las etiquetas intercaladas cambiaban el orden del texto y DOCX/XLSX perdían espacios. Se conserva orden y separación, también en metadatos remotos. |
| Cancelación | Extracción y descompresión podían continuar tras interrumpirse la tarea. Sus bucles comprueban la cancelación. |
| Importaciones | Se descartaba un segundo lote SAF/ACTION_VIEW mientras se copiaba el primero. Los lotes se procesan en cola. |
| Miniaturas en pantalla | Cambiar la ruta podía mostrar temporalmente la portada anterior. Cada imagen queda asociada a su origen. |
| Estado del lector | El estado visual restaurado podía publicar una posición anterior a la petición retenida. Horizontal y vertical alinean la posición antes de confirmarla; el vertical conserva el desplazamiento interior cuando coincide la página. |

## Validación funcional

Las suites cubren SQLite y archivos privados, importación y duplicados, borrado definitivo y estanterías vacías, varios servidores, descargas independientes, caché sin conexión, sincronización explícita, lectura por rangos sin transferir cómics completos, claves SSH cambiadas, rutas y enlaces confinados, formatos representativos, fuentes, fondos, configuración, filtros, Atrás y progreso.

Se añadieron diez casos Android y tres comprobaciones JVM. La interfaz recorre los seis efectos horizontales con avance, retroceso y cancelación parcial, además de scroll vertical y cambio a horizontal. La actividad real usa DocumentsUI, volumen y recreación; confirma las páginas en SQLite y conserva posición y paginación al cambiar el modo nocturno.

El primer intento de interfaz perdió foco por un ANR de System UI durante presión de memoria del entorno: [captura](initial/system-ui.png) y [registro](initial/ui-system-focus-api36.txt). Tras detener compiladores y estabilizarlo, la suite completa pasó. Las pruebas de actividad se adaptaron al autoguardado y al reloj de animación de Compose: esperan asentamiento y confirmación real. Se conservan los [resultados iniciales](initial/activity-before-api36.txt) y la [espera anterior sin impulsar el reloj](initial/activity-clock-api36.txt).

## Evidencias

- [Resultados API 36](final/results-api36.json) y [API 24](final/results-api24.json), con registros individuales en la misma carpeta.
- [Entorno API 36](final/environment-api36.json), [API 24](final/environment-api24.json) y [estado del código y hashes](source-state.json).
- [Compilación](build.txt), [instrumentación final](test-build.txt), [SFTP JVM](sftp-jvm.txt).
- Capturas inspeccionadas: [Libros por SAF](captures/saf-books-api36.png), [lector tras recreación](captures/saf-reader-api36.png) e [inicio en Android 7](captures/home-api24.png).
- Arranque adicional de MainActivity en Android 7: [Status ok](launch-api24.txt), sin errores en el registro de cierres.
- [Comandos de QA](../../../tools/qa/README.md). Las suites que comparten la fixture se ejecutan secuencialmente.

## Alcance

Emuladores x86_64 dedicados y servidor SSH/SFTP local con dos usuarios ficticios. No se utilizó el dispositivo físico ni se conectó con el NAS real. ARM64 y ARMv7 se compilan y empaquetan, pero no se ejecutaron sobre hardware ARM.

Los casos y archivos son representativos: no garantizan toda variante dañada, DRM, CAJ/OFD avanzado, pérdida de alimentación o particularidad de fabricantes. La portada por nombre es una heurística; se mantiene la primera página como alternativa. Las limitaciones de formatos y lectura parcial figuran en el README principal.
