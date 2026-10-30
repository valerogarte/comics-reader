# Fuentes de lectura incluidas

Las 14 familias se distribuyen con la aplicación y funcionan sin conexión.
Literata es la opción predeterminada para las instalaciones nuevas.

Los archivos son TTF estáticos Regular 400 publicados por el servicio oficial
Google Fonts. Se conservan sus bytes originales, sin convertir, renombrar ni
subconjuntar las fuentes dentro de este proyecto. La respuesta oficial servida
a Android antiguo contiene los caracteres latinos, incluidos los del español;
los caracteres de otros alfabetos dependen de las alternativas del motor.
No hay fuentes variables ni descargas de fuentes durante el uso de la app.

`MANIFEST.json` registra para cada familia el origen CSS, la URL exacta del TTF,
la huella SHA-256, el peso, el tamaño y la licencia original. El agente de
usuario empleado también queda registrado para reproducir la respuesta TTF.
Las 14 fuentes suman **636 120 bytes**, sin contar las licencias.

Cada archivo de `licenses/` conserva el copyright y el texto íntegro de la
licencia SIL Open Font License 1.1 de su familia. Los textos se obtuvieron del
repositorio oficial `google/fonts`, fijado al commit que figura en el manifiesto.

La incorporación se revisó estáticamente con fontTools 4.68.0: peso 400, estilo
recto, ausencia de la tabla variable `fvar` y caracteres españoles presentes.
fontTools no forma parte de la compilación ni de la aplicación.

`ReadingFonts.resolve()` copia únicamente la familia seleccionada a
`filesDir/reading-fonts` cuando un motor necesita una ruta de archivo. Reutiliza
esa copia y conserva una versión por familia. Las opciones anteriores `sans`,
`serif`, `mono` y las fuentes importadas permanecen disponibles.
