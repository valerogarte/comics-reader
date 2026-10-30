# QA nativa de datos y SFTP

Los archivos de este directorio prueban el cliente Java real contra un servidor SSH/SFTP local con datos generados. No conectan con el NAS ni utilizan sus credenciales. Los usuarios `qaA` y `qaB`, con contraseña `fixture-password`, son exclusivamente de la fixture.

## Arrancar la fixture

Se necesita Python 3 con soporte para `venv` y `pip`:

```bash
python3 -m venv /tmp/comics-native-data-qa-venv
/tmp/comics-native-data-qa-venv/bin/pip install -r tools/qa/requirements.txt
/tmp/comics-native-data-qa-venv/bin/python tools/qa/sftp_fixture.py
```

El proceso permanece en primer plano. Escucha en `127.0.0.1:22991` (SSH) y `127.0.0.1:22992` (control). Los datos generados se guardan en `/tmp/comics-native-data-qa/files`. Detén el proceso con Ctrl+C al terminar.

## Prueba JVM

Desde la raíz del repositorio, con un JDK y JSch 2.28.8 disponibles:

```bash
QA_JDK=/ruta/al/jdk
QA_JSCH_JAR=/ruta/a/jsch-2.28.8.jar
mkdir -p /tmp/comics-native-data-qa/classes
"$QA_JDK/bin/javac" -cp "$QA_JSCH_JAR" -d /tmp/comics-native-data-qa/classes android/app/src/main/java/com/valerogarte/comicsreader/sftp/SftpClient.java tools/qa/SftpSmoke.java
"$QA_JDK/bin/java" -cp "/tmp/comics-native-data-qa/classes:$QA_JSCH_JAR" SftpSmoke
```

El resultado esperado es `Native SftpClient real SSH checks passed: 21`. Comprueba listados, rangos, confinamiento de rutas y enlaces, descarga explícita, limpieza al cancelar, cambios durante la transferencia y rechazo de claves SSH distintas. También comprueba que pedir un byte transfiere un byte, que los signos de interrogación de un nombre se tratan literalmente y que contraseñas con el mismo `hashCode` no comparten sesión ni bloqueo de autenticación.

Las respuestas detenidas de descarga, listado y STAT tienen un plazo real por canal. La regresión inyecta 300 ms frente a respuestas demoradas 1,2 s; también verifica que una descarga y un listado con progreso pueden durar más de ese plazo y que interrumpir una descarga bloqueada conserva la sesión SSH para otro canal. En producción se permiten 30 s sin progreso por canal, reiniciados al recibir datos o completar solicitudes, y 15 s totales por lectura exacta de rango.

## Instrumentación Android

La clase `com.valerogarte.comicsreader.nativeapp.data.NativeDataInstrumentedTest` usa `QaApplication` para aislar preferencias, base de datos y archivos. Debe ejecutarse en el emulador dedicado. No borra la biblioteca real.

El emulador usa `10.0.2.2` para acceder a los puertos de la fixture del host. Estos valores pueden cambiarse con los argumentos de instrumentación `sftpQaHost`, `sftpQaPort` y `sftpQaControlPort`.

Compila los dos APK desde `android` con `./gradlew assembleDebug assembleDebugAndroidTest`. Después, desde la raíz del repositorio, instálalos y selecciona expresamente el emulador:

```bash
QA_ADB=/ruta/al/android-sdk/platform-tools/adb
QA_EMULATOR=emulator-5580
"$QA_ADB" -s "$QA_EMULATOR" install --no-streaming -r android/app/build/outputs/apk/debug/app-debug.apk
"$QA_ADB" -s "$QA_EMULATOR" install --no-streaming -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 tools/qa/run_android_qa.py --adb "$QA_ADB" --serial "$QA_EMULATOR" --output /tmp/comics-native-qa-results --suites data
```

El ejecutor rechaza identificadores que no empiecen por `emulator-`. Para ejecutar todas las suites en secuencia, omite `--suites data`; también están disponibles `reader`, `ui`, `workflow` y `activity`. Guarda un registro de texto por suite y un resumen JSON, y devuelve un error al primer fallo. La suite `activity` se omite por debajo de API 29 porque su muestra usa MediaStore.

`--no-streaming` permite instalar también en las imágenes Android antiguas. El ejecutor calcula en el host la huella del APK instalado, sin depender de utilidades disponibles en el dispositivo. Conserva resultados de otras suites del mismo entorno y APK para poder repetir una suite sin mezclar versiones.

La suite Data contiene **18 casos**. Las revisiones y sus resultados se documentan en `docs/qa/`.

Ejecuta estas pruebas, `NativeWorkflowInstrumentedTest` y el smoke JVM de forma secuencial: comparten la fixture y reinician sus datos y estadísticas.

`activity` usa la actividad de producción, botones de volumen y el selector real de documentos. Requiere API 29 o posterior, comprueba además que sea un emulador y crea archivos propios en MediaStore Downloads. Conserva los perfiles SFTP existentes y restaura la apariencia al finalizar. Las otras suites usan almacenamiento privado de prueba y un controlador/interfaz aislados. Las muestras externas del lector y sus licencias están descritas en `android/app/src/androidTest/assets/reader/README.md`.

## Control y archivos remotos

El control recibe una línea JSON y devuelve una línea JSON por conexión TCP. Operaciones:

- `{"op":"stats"}`: conexiones, listados, lecturas y escrituras intentadas por usuario.
- `{"op":"reset"}`: datos versión 1, clave SSH original y estadísticas vacías.
- `{"op":"mutate","user":"qaA","version":2}`: cambia títulos, portadas y fecha de los cómics de prueba.
- `{"op":"deny","user":"qaA","value":true}`: deniega acceso para probar caché sin conexión.
- `{"op":"delay","user":"qaA","seconds":0.015}`: ralentiza las lecturas de un usuario.
- `{"op":"delay","user":"qaA","operation":"list","seconds":1.2}`: ralentiza una operación concreta; `operation` admite `read`, `list`, `stat` y `realpath`. Cero segundos elimina la demora.
- `{"op":"rotate"}`: cambia la clave SSH de las conexiones nuevas.

Ambos usuarios tienen raíz configurada `/library`. La ruta relativa `/Comics/DC/Sandman` contiene `a.cbz` y `b.cbz`. Sus títulos son `qaA-v1-book1`, etc. También existen `/slow.txt`, `/readme.txt` y un enlace `escape` que apunta fuera de `/library` y debe ser rechazado por la aplicación. El servidor SFTP deniega todas las escrituras; únicamente el control modifica sus propios datos de prueba.
