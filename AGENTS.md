# Comics Reader Android

La aplicación es exclusivamente Android nativa: Kotlin, Jetpack Compose, SQLite y motores JNI. No introducir WebView, navegador, JavaScript ni puentes Capacitor.

## Estructura

- `android/app/src/main/java/.../MainActivity.kt`: entrada Android y selectores SAF.
- `nativeapp/LibraryViewModel.kt`: estado y acciones.
- `nativeapp/ui/`: pantallas Compose.
- `nativeapp/data/`: persistencia y caché.
- `nativeapp/reader/`: lectura, metadatos y portadas.
- `sftp/SftpClient.java`: conexión SSH, listado, rangos y descargas explícitas.
- `android/app/src/main/cpp/`: motores nativos y fuentes de terceros.

## Reglas del producto

- Biblioteca sencilla: Libros, estanterías, SFTP, lector y estadísticas del progreso guardado.
- Navegar SFTP guarda únicamente fichas y miniaturas. No descargar libros completos durante el catálogo. Consultar la caché hasta sincronización explícita.
- Admitir múltiples servidores con operaciones y claves SSH independientes. Pasar siempre el perfil de origen a las operaciones; no inferirlo de la selección actual durante una descarga.
- El perfil NAS inicial procede de `android/sftp-default.local.json`, ignorado por Git. Nunca emitir ni versionar sus credenciales. Sembrar solo una vez; respetar su eliminación posterior.
- Una descarga SFTP añade el libro a Libros y a la estantería de sus carpetas padre.
- Las estanterías muestran únicamente archivos descargados.
- El borrado local es definitivo. No existe papelera. Preguntar si se quiere eliminar una estantería que quede vacía.
- Ajustes limitados a apariencia, fuentes y SFTP.
- Configuración desde la cabecera; el pie del menú lateral abre Estadísticas de lectura.
- Datos en almacenamiento privado Android; credenciales cifradas con Android Keystore. Nunca registrar contraseñas.
- No borrar ni sobrescribir trabajo ajeno para simplificar cambios.

## Compilación

`./build-android.sh` genera el APK debug sin Node. No añadir ni ejecutar pruebas salvo petición expresa del usuario. Una compilación correcta no demuestra funcionamiento en un dispositivo: indicar siempre el alcance de la comprobación.

El proyecto contiene únicamente la aplicación Android nativa y sus herramientas de compilación, pruebas y documentación.
