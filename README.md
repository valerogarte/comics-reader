# Comics Reader para Android

Aplicación nativa en Kotlin y Jetpack Compose. La interfaz, la lectura y el almacenamiento funcionan con APIs de Android y bibliotecas nativas.

## Uso

- **Libros:** importa archivos con el selector de Android. La aplicación conserva una copia privada y el progreso de lectura.
- **Estanterías:** organiza los libros descargados. Las descargas SFTP crean automáticamente la estantería con las carpetas padre relativas a la carpeta raíz configurada: raíz `/mnt/NAS/Comics` y libro `DC/Sandman/01.cbz` → `DC/Sandman`. Los archivos de la propia raíz usan el nombre del servidor. Al actualizar, se corrigen las estanterías automáticas antiguas identificables, conservando libros y progreso; se respetan las estanterías manuales o mixtas y las de servidores ya no configurados.
- **Servidores SFTP:** añade, edita o elimina tantas conexiones como necesites desde Configuración. Cada una aparece por nombre en el menú y conserva su carpeta abierta, catálogo y descargas. Las conexiones y las descargas de servidores distintos funcionan en paralelo.
- **Biblioteca SFTP:** abrir una carpeta guarda su catálogo, metadatos y miniaturas. Las siguientes visitas usan esa caché; **Sincronizar** vuelve a consultar la carpeta del servidor seleccionado. El botón Atrás de Android sube a la carpeta superior; desde la raíz del servidor vuelve a Libros.
- **Descargas:** toca un libro remoto para descargarlo; cuando esté descargado, vuelve a tocarlo para leerlo. También puedes usar su icono o **Descargar todos** en la carpeta actual. Las subcarpetas no se descargan automáticamente.
- **Lector:** en los ajustes del lector puedes elegir **Horizontal**, deslizando hacia los lados para pasar de página, o **Scroll vertical**, con las páginas una debajo de otra. Las elecciones se conservan para las siguientes lecturas. Ambos modos cargan las páginas bajo demanda y comparten una caché limitada en memoria. Un toque muestra u oculta el título, el botón de volver, las opciones y el indicador de páginas; el selector permite saltar a otra página en ambos modos. Un doble toque amplía la página o restaura el tamaño; también puedes ampliar con dos dedos. El indicador de carga aparece únicamente si la espera supera un segundo.
- **Efecto de transición:** los ajustes del lector horizontal ofrecen **Sin efecto**, **Fundido**, **Slider**, **Pasar página**, **Revelar** y **Zoom suave**. Slider acompaña el movimiento del dedo mostrando la siguiente página por el lateral; Pasar página simula el giro de una hoja con perspectiva y sombra. Los efectos respetan la dirección de lectura. El progreso se confirma cuando la página queda asentada: cancelar un deslizamiento conserva la posición anterior.
- **Borrado:** elimina definitivamente la copia local y su progreso. El original SFTP permanece en el servidor. Al vaciar una estantería se ofrece eliminarla.
- **Estadísticas de lectura:** acceso al pie del menú lateral. Resume el total de libros, sin leer, leyendo y finalizados, además del progreso de páginas según las posiciones guardadas. Los datos corresponden a los libros de la biblioteca actual y se actualizan al regresar del lector, importar, descargar o borrar.
- **Configuración:** acceso desde el icono del header. Su menú interno organiza los ajustes en **Apariencia**, **Tipografía**, **Lectura** y **Servidores SFTP**. La flecha y el botón Atrás vuelven desde cada apartado al menú; la cruz cierra Configuración y Atrás desde el menú también la cierra. Los cambios de apariencia y fuentes se aplican y guardan automáticamente, sin botón Guardar. Añadir o editar un servidor SFTP se guarda al confirmar su formulario; el borrado se guarda al confirmarlo. Quitar un servidor conserva los libros descargados. Si eliminas todos, la lista permanece vacía.
- **Tipografías:** selector con vista previa tanto en Configuración como en los ajustes de libros con texto adaptable. Incluye sin conexión Literata, Lora, Merriweather, Source Serif 4, Noto Serif, EB Garamond, Libre Baskerville, Crimson Pro, Atkinson Hyperlegible, Source Sans 3, Noto Sans, Lexend, Nunito Sans y Roboto Mono. [Literata](https://github.com/googlefonts/literata), diseñada para lectura prolongada y libros digitales, es la predeterminada cuando aún no se ha elegido una fuente. Las elecciones guardadas y las fuentes importadas se conservan. Cambiar la tipografía modifica el texto adaptable; los cómics de imágenes y los PDF con diseño fijo conservan su contenido original.

El catálogo remoto no guarda archivos completos. Las fichas aparecen tras listar la carpeta y las portadas y metadatos se completan progresivamente, con un máximo de tres libros procesándose a la vez entre todos los servidores. Cada libro reutiliza su canal de lectura y los CBZ/ZIP acceden directamente al índice, los metadatos y la imagen de portada.

Las vistas previas transfieren únicamente rangos parciales, con un límite de 16 MiB por libro, y reutilizan en memoria los fragmentos ya leídos. Las lecturas SFTP canalizan hasta cuatro bloques de 240 KiB y ajustan el tamaño de paquete SSH para reducir las esperas de red. Si una portada no se puede obtener dentro de ese límite, se conserva la ficha sin descargar el documento entero. Las carpetas guardadas siguen abriéndose desde la caché hasta que se pulsa **Sincronizar**.

En los archivos CBR con RAR4 o RAR5 no sólido, cuando su estructura lo permite, se leen las cabeceras por separado y solo se extraen la portada y los metadatos necesarios. Si un RAR5 nombra claramente su primera página, la vista previa se detiene ahí en lugar de recorrer todas las páginas; el recuento remoto puede quedar pendiente hasta descargar el cómic. Los fallos temporales de conexión se reintentan al sincronizar; los formatos que no pueden obtenerse con la lectura parcial se conservan en caché hasta que cambie el archivo o el extractor.

Cada carpeta SFTP muestra, cuando se puede consultar, el número de archivos que contiene directamente, de cualquier formato, sin sumar los de sus subcarpetas. El recuento se completa en segundo plano, se guarda en caché y se actualiza con **Sincronizar**. Si el servidor no permite obtenerlo, aparece «Recuento no disponible».

## Datos

La aplicación usa SQLite y el directorio privado de Android `files/native-library`. Los libros y las miniaturas no se guardan en carpetas temporales. Las credenciales SFTP se cifran con Android Keystore y la clave del servidor queda registrada para detectar cambios.

La versión 2.1 conserva los datos nativos y migra la conexión SFTP anterior a la lista de servidores. Cambiar el nombre o contraseña mantiene el catálogo; cambiar la dirección, usuario o carpeta raíz utiliza el catálogo de esa nueva ubicación.

### NAS inicial

Este APK privado incluye el perfil **NAS**, tomado de la configuración local existente, con carpeta inicial `/`. Puedes editarlo o eliminarlo como cualquier otro servidor. Se incorpora una sola vez y no reaparece si lo borras.

La compilación obtiene ese perfil de `android/sftp-default.local.json`, excluido de Git y con permisos privados. Sus campos son `name`, `host`, `port`, `username`, `password` y `rootPath`. Si ese archivo no existe, el proyecto permite configurar los servidores desde la aplicación. El APK que incluye credenciales iniciales debe mantenerse privado; el cifrado de Android protege la configuración guardada en el dispositivo, no los valores incluidos al compilarlo.

Android elimina los datos privados al desinstalar la aplicación o borrar sus datos desde los ajustes del sistema.

## Compilar

Compilación en Linux: JDK 17 o 21, Android SDK 36, Build Tools 35.0.0, NDK `28.2.13676358`, CMake `3.22.1`, Rust 1.88 o posterior y un compilador C del sistema. Gradle Wrapper descarga la versión declarada por el proyecto. Los motores C/C++ y CAJ se compilan al generar el APK.

Instala los destinos Rust de Android una vez:

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

```sh
./build-android.sh
```

También puedes abrir `android/` directamente en Android Studio y generar el APK. Resultado:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Android mínimo: 7.0 (API 24); incluye ARM64, ARMv7 y x86_64. Las bibliotecas de 64 bits utilizan alineación de 16 KiB.

## Formatos

| Contenido | Formatos |
| --- | --- |
| Cómics de imágenes | CBZ, CBR, CB7, CBT y archivos ZIP, RAR, 7z y TAR equivalentes |
| Libros | EPUB, PDF, MOBI, AZW, AZW3, FB2, KDZ, XPS, OXPS, DjVu/DJV |
| Texto y documentos | TXT, Markdown, HTML, XHTML, XML, MHTML, RTF, CHM, DOCX, DOC, PPTX, PPT, XLSX, OFD, CAJ |

Los archivos protegidos por DRM no se abren. DOC, PPT, XLSX, RTF, MHTML y CHM usan texto paginado nativo: no conservan todo el diseño original. XLSX muestra los valores de las celdas; no calcula fórmulas ni reproduce gráficos. DOCX usa el motor de documentos y recurre al texto paginado si faltan archivos opcionales de relaciones que ese motor requiere. OFD reproduce texto, imágenes, vectores y plantillas básicos; no implementa todas sus características gráficas avanzadas. El motor CAJ cubre varias familias del formato; algunas variantes HN/C8 y fuentes especiales pueden producir un error de compatibilidad.

Las portadas remotas dependen de que puedan extraerse con lecturas parciales. Si el formato, su compresión o el límite de lectura lo impiden, se muestra su título y ficha. Esto no inicia una descarga completa. Véase la documentación de los motores en `android/app/src/main/cpp/vendor/README.md` y `android/app/src/main/rust/README.md`.

En Android 7 las fichas PDF remotas no incluyen portada; la lectura local funciona normalmente. La vista previa PDF por rangos requiere Android 8 o posterior. Algunos contenedores, como CHM, DjVu, XPS y CAJ, pueden mostrar su portada únicamente después de descargar el libro.

## Código

- `android/app/src/main/java/com/valerogarte/comicsreader/MainActivity.kt`: entrada y selector de archivos Android.
- `nativeapp/LibraryViewModel.kt`: acciones de biblioteca y ciclo de vida del lector.
- `nativeapp/ui/`: interfaz Compose.
- `nativeapp/data/`: SQLite, archivos privados, caché SFTP y configuración cifrada.
- `nativeapp/reader/`: motores de lectura y extracción de portadas.
- `sftp/SftpClient.java`: conexión SSH y operaciones de lectura remota.

Licencia AGPL-3.0; véase `LICENSE`. Las bibliotecas y sus licencias se enumeran en `THIRD_PARTY.md`.
