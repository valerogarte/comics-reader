# Revisión funcional de Comics Reader Android

Fecha: 29 de octubre de 2026. Versión: **2.1.1, código 4**. El APK validado se identifica por su huella SHA-256 en este informe.

## Entorno

- Aplicación Android nativa: Kotlin, Compose, SQLite y motores JNI.
- Emuladores dedicados x86_64. Android 16 / API 36 y Android 7.0 / API 24, versión mínima declarada.
- Servidor SSH/SFTP real de pruebas con Paramiko, dos usuarios y archivos generados. Ambos puertos escuchan exclusivamente en loopback. No se utilizan credenciales del NAS.
- Las pruebas de repositorio y controlador tienen archivos, preferencias y bases de datos independientes. Las de interfaz usan estados controlados; los recorridos SAF utilizan la actividad de producción, el selector Android y almacenamiento privado reales.
- No se prueba en el dispositivo físico ni se conecta con el NAS real, siguiendo la indicación del usuario.

## Resultados

**Todos los casos ejecutados pasan:** 80 en Android 16, 78 en Android 7 y 18 del cliente SFTP en JVM. Son **176 comprobaciones**, contando las repeticiones entre versiones. Las dos instalaciones tienen la misma huella del APK entregado.

| Suite | Qué comprueba | Android 16 | Android 7 |
| --- | --- | --- | --- |
| Datos y SFTP | 16 casos de persistencia, cifrado, catálogo, descargas, concurrencia y recuperación | **16/16** | **16/16** |
| Lectura | 38 casos con los 35 sufijos admitidos y variantes de compresión | **38/38** | **38/38** |
| Interfaz Compose | 16 casos de navegación, configuración, filtros, estanterías y lector | **16/16** | **16/16** |
| Flujos completos | 8 casos con el controlador, repositorio, lectura y SFTP reales | **8/8** | **8/8** |
| Actividad y SAF | 2 recorridos con selector real, volumen, recreación y cambio de tema | **2/2** | Requiere API 29+ |
| Cliente SFTP en JVM | 18 comprobaciones adicionales del protocolo y cancelaciones | 18/18 | Mismo cliente Java |

Compilación e instrumentación generadas correctamente. **Lint: 0 errores y 23 advertencias**, conservadas en [lint.txt](lint.txt). Firma del APK, alineación ZIP de 16 KiB y segmentos ELF de las 12 bibliotecas de 64 bits verificados: [resultado del paquete](apk-checks.json), [firma](apk-signature.txt).

SHA-256 del APK instalado en ambos emuladores y entregado:

```text
53f50986ebfd7c7cdb3a6b1b9006742c68a22789e9c44555a0d177e4a35301d0
```

[APK privado 2.1.1](../../../android/app/build/outputs/apk/debug/app-debug.apk). Sus permisos locales son privados porque incorpora el perfil NAS inicial.

Los registros completos y la identificación de cada emulador están en [resultados Android 16](final/results-api36.json), [resultados Android 7](final/results-api24.json), [entorno API 36](final/environment-api36.json) y [entorno API 24](final/environment-api24.json).

## Procesos cubiertos

| Proceso | Comprobaciones |
| --- | --- |
| Inicio y almacenamiento | Biblioteca SQLite privada; perfil inicial sembrado una sola vez; migración del perfil anterior; eliminación de todos los servidores conservada tras reiniciar. |
| Importación | Selector SAF real; copia privada; formatos válidos e inválidos; deduplicación y recuento de libros nuevos; conservación del original. |
| Libros y estanterías | Filtros de lectura y cuadrícula/lista al volver del lector; creación y navegación; descargas asociadas a sus carpetas padre; permanencia en Libros al eliminar una estantería. |
| Catálogo SFTP | Navegación por carpetas, carpeta superior y Atrás; fichas y miniaturas mediante rangos limitados; ausencia de cómics completos en almacenamiento durante el catálogo; reutilización de caché sin conexión; sincronización explícita que actualiza portada y metadatos. |
| Descargas | Descarga individual y todos los archivos de la carpeta actual; idempotencia; indicador de descarga/tick; importación y estantería automática; separación de servidores con rutas idénticas. |
| Varios servidores | Añadir, editar, validar, cancelar y eliminar; persistencia de nombres/IDs; operaciones paralelas; cambiar un perfil mantiene las operaciones independientes de los otros. |
| Lectura | Apertura, primera y última página, índice inválido, metadatos, portada declarada o primera página, tamaño de letra, fuentes, paginación y posición conservada. |
| Controles del lector | Botones, slider, gestos, dirección de lectura, zoom, volumen, estados de carga/error y pantalla encendida; cierre y progreso persistido; cancelaciones sin publicar páginas de otro libro. |
| Apariencia y configuración | Cerrar con cruz/Atrás; guardar o descartar borrador; fuente TTF e imagen por SAF; conservación de biblioteca al recrear la actividad; cambio de modo claro/oscuro con el lector abierto. |
| Borrado | Eliminación definitiva de libro, portada y progreso; original remoto conservado; oferta para borrar estantería vacía o conservarla; sin papelera. |
| Recuperación y seguridad | Rechazo de claves SSH cambiadas; confinamiento de rutas y enlaces; lista cifrada dañada sustituible mediante guardado explícito; residuos de proceso terminado eliminados sin borrar archivos referenciados o descargas activas. |

## Fallos encontrados y corregidos

- DOCX válido sin relaciones opcionales: alternativa de texto nativo.
- XLSX con celdas `inlineStr`: lectura nativa de celdas, con contenido visible.
- Vista previa cancelada que todavía iniciaba una lectura: comprobación previa de cancelación.
- Anticipación de bloques por JSch fuera del rango pedido: peticiones SFTP con el tamaño exacto.
- Respuesta SFTP detenida sin plazo de recepción: límite efectivo de 15 segundos y cancelación comprobable.
- Listados, consulta de atributos y descargas detenidos: plazo por inactividad de 30 segundos y cierre del canal afectado; los bloques recibidos renuevan el plazo de una descarga larga. La cancelación interrumpe también la lectura bloqueada.
- Signos `?` y `*` interpretados como patrones: tratamiento literal de rutas.
- Apertura de caché que creaba un archivo de claves SSH: retorno de la caché antes de crear el cliente.
- Configuración SFTP ilegible que ocultaba Libros o impedía guardar una nueva lista: biblioteca independiente y recuperación mediante guardado explícito.
- Residuos tras terminar el proceso durante una copia: reconciliación inicial por referencias SQLite, una vez por proceso/directorio.
- Recuento de importaciones que incluía duplicados: mensajes con el número real de libros nuevos.
- SFTP no abría en Android 7 porque JSch utilizaba `java.time.format.DateTimeFormatter`, ausente en ese sistema: activada la compatibilidad de APIs Java con `desugar_jdk_libs 2.1.5`. La primera ejecución API 24 obtuvo 7/16 en datos y conservó 38/38 en lectura. El registro está en `before-api24-desugaring/`. Configuración según la [documentación oficial de Android](https://developer.android.com/studio/write/java8-support#library-desugaring).
- Página del lector que conservaba colores antiguos tras cambiar el modo del sistema: comparación con las opciones de la sesión retenida y recreación conservando la posición.
- Navegación Atrás en SFTP, conservación de filtros/scroll al regresar del lector, controles durante cargas, teclas de volumen y accesibilidad de controles.

Las ejecuciones iniciales también detectaron errores en las propias muestras y automatizaciones: raíz `/.` de la fixture SFTP, catálogo CAJ sintético incorrecto, espera de doble toque y selección de documentos por la caja de búsqueda. Se corrigieron por separado; sus fallos iniciales están conservados en `initial-runs/`.

## Evidencias y reproducción

- [Cobertura de datos y SFTP](data-coverage.md).
- [Cobertura de lectura y procedencia de muestras](reader-coverage.md).
- [Cobertura de interfaz y actividad real](ui-coverage.md).
- [Resultado SFTP JVM](sftp-jvm-results.txt).
- [Herramientas y comandos](../../../tools/qa/README.md).

Las muestras externas del lector pertenecen exclusivamente al APK de instrumentación y conservan sus licencias. Las pruebas no se incluyen en el APK normal de la aplicación.

## Capturas

- [Libros con un PDF importado mediante SAF](captures/saf-books-api36.png).
- [Lector nativo y controles](captures/saf-reader-api36.png).
- [Biblioteca vacía en Android 7](captures/home-api24.png), con [arranque de MainActivity correcto](launch-api24.txt).

Las capturas corresponden a archivos generados para las pruebas. No contienen libros ni datos de conexión del usuario. No se observaron cierres fatales de la aplicación en los registros de estas ejecuciones. El servidor y los emuladores temporales se detienen al finalizar.

## Límites

Las pruebas verifican casos concretos y archivos representativos. No garantizan todas las variantes de archivos dañados, códecs, DRM, fallos de disco o cortes de alimentación. La recuperación de residuos prepara el estado dejado por una muerte del proceso; no simula una pérdida real de alimentación.

DOC/PPT/XLSX y varios formatos de texto tienen fidelidad limitada; OFD avanzado y familias CAJ HN/C8 no quedan validados por completo. Algunas portadas remotas necesitan la descarga explícita; PDF remoto por rangos requiere Android 8+. Los detalles figuran en la matriz del lector y en el README principal.

Las bibliotecas ARM64/ARMv7 se compilan e incluyen en el APK; las ejecuciones de esta revisión utilizan x86_64. Quedan fuera el hardware del usuario, la conexión y permisos de su NAS real y las particularidades de su fabricante de Android.
