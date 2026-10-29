# Verificación del motor de lectura nativo

## Entorno y alcance

Suite: `NativeReaderInstrumentedTest`, con generadores propios en `ReaderFixtures.kt` y muestras públicas de prueba. Ejecuciones mediante instrumentación Android en emuladores x86_64, API 36 (Android 16) y API 24 (Android 7.0). No usa dispositivos físicos, la biblioteca del usuario ni conexiones al NAS.

Esta matriz contiene **38 casos instrumentados, todos correctos en ambas versiones de Android**: 10,816 segundos en API 36 y 2,593 segundos en API 24. Ambas ejecuciones finales utilizaron el mismo APK, SHA-256 `53f50986ebfd7c7cdb3a6b1b9006742c68a22789e9c44555a0d177e4a35301d0`.

La primera ejecución, con 35 casos, obtuvo 31 correctos y cuatro fallos. Se corrigieron la cancelación previa a una vista remota, el DOCX sin relaciones opcionales, las celdas XLSX `inlineStr` y una construcción incorrecta de la muestra CAJ. Las ejecuciones finales verificaron las correcciones y añadieron las variantes CHM/LZX, DjVu/JB2 y alias de archivo.

No equivale a verificar todos los archivos o variantes existentes de cada formato. La prueba comprueba salida visible y contratos del motor; no compara cada página con una referencia editorial.

## Comprobaciones comunes

Los casos de lectura que usan `assertReader` comprueban:

- Apertura local, número de páginas positivo y, cuando procede, número exacto y capacidad de ajustar el texto.
- Primera página con contenido visible, última página renderizable y rechazo de índices fuera de rango. Una última página legítimamente vacía se admite.
- Inspección de título, páginas y portada o primera página visible. Los títulos esperados se contrastan en PDF, EPUB, cómics, DOC, PPT, CHM y OFD.
- Cierre del documento y ausencia de archivos temporales nuevos en `cache/document-work`.

El progreso persistido pertenece a las pruebas del repositorio/controlador. Esta suite verifica el acceso a primera y última página y los límites del índice; no afirma por sí sola persistencia del progreso al reiniciar la aplicación.

## Matriz de 38 casos

| Nº | Caso instrumentado | Archivo y cobertura concreta |
| --- | --- | --- |
| 1 | `pdfPageRenderingAndMetadata` | PDF propio de dos páginas, xref e información de título; renderizado y metadatos. |
| 2 | `epubReflowsAndHasCover` | EPUB propio con OPF, título/autor, portada declarada y capítulo XHTML. |
| 3 | `cbzComicPagesAndMetadata` | CBZ/ZIP propio, dos imágenes y ComicInfo. |
| 4 | `cbtTarComicPages` | CBT/TAR USTAR propio, dos imágenes y ComicInfo. |
| 5 | `cbrStoredRarComicPages` | CBR/RAR4 propio con entradas almacenadas y CRC reales. No cubre todos los compresores RAR/RAR5 ni archivos sólidos. |
| 6 | `cb7NativeSevenZipComicPages` | CB7/7z real generado por el escritor nativo libarchive. |
| 7 | `txtNativeReader` | TXT UTF-8 propio, varios párrafos con caracteres acentuados. |
| 8 | `markdownNativeReader` | Markdown propio con encabezado y texto. |
| 9 | `htmlNativeReader` | HTML propio con encabezado y párrafos. |
| 10 | `xhtmlNativeReader` | XHTML propio con espacio de nombres. |
| 11 | `xmlGenericTextReader` | XML genérico propio leído como texto, sin depender de un contenedor EPUB/FB2. |
| 12 | `fb2NativeReader` | FictionBook XML propio con contenido y metadatos. |
| 13 | `rtfNativeTextReader` | RTF propio con separación de párrafos. |
| 14 | `mhtmlNativeTextReader` | MIME multipart propio y HTML quoted-printable UTF-8; comprueba también conservación de tildes y ñ. |
| 15 | `docxNativeOfficeReader` | DOCX mínimo válido sin `word/_rels/document.xml.rels`; ejercita la alternativa nativa de texto. |
| 16 | `pptxNativeOfficeReader` | PPTX mínimo propio con diapositiva y relaciones. |
| 17 | `xlsxNativeOfficeReader` | XLSX propio con celda `inlineStr`; exige contenido visible. |
| 18 | `docBinaryPieceTableReader` | DOC97 OLE2 propio con tabla CLX de fragmentos de texto y SummaryInformation. |
| 19 | `pptBinaryTextRecordReader` | PPT97 OLE2 propio con registros TextCharsAtom y SummaryInformation. |
| 20 | `ofdNativeCanvasReader` | OFD propio de una página con texto y vectores. |
| 21 | `xpsFixedPageReader` | XPS propio de una página con trazados de color. |
| 22 | `oxpsFixedPageReader` | OpenXPS propio de una página con trazados de color. |
| 23 | `djvuNativeJpegLayerReader` | DjVu IFF propio con INFO y capa JPEG BGjp. |
| 24 | `djvuNativeJb2LayerReader` | DjVu JB2/Sjbz real de patrón original codificado con `cjb2`. |
| 25 | `chmNativeCompiledHtmlReader` | CHM ITSF/ITSP/PMGL propio, almacenamiento sin compresión y página HTML. |
| 26 | `chmLzxCompressedNsisManualReader` | CHM real con compresión LZX del manual NSIS. |
| 27 | `cajNativeRustConversionReader` | Contenedor CAJ propio con objetos PDF de dos páginas, convertido con el núcleo Rust. No verifica HN/C8/KDH reales. |
| 28 | `mobiCp1252NativeReconstruction` | Muestra pública libmobi, texto cp1252 y reconstrucción EPUB nativa. |
| 29 | `mobiHuffmanNativeReconstruction` | Muestra pública libmobi con HUFF/CDIC e híbrido KF7/KF8. |
| 30 | `azw3ActualKf8NativeReconstruction` | Muestra pública con cabecera real KF8/v8 importada como AZW3; no es un EPUB renombrado. |
| 31 | `azwHybridNativeReconstruction` | Muestra pública KF7/KF8 real importada como AZW. |
| 32 | `supportedFilenameAliasesUseTheirActualNativeFormats` | HTM, DJV, ZIP, TAR, RAR y 7Z con bytes reales del formato correspondiente; seis aperturas. |
| 33 | `explicitComicCoverAndMissingCoverFallback` | Selección de portada por ComicInfo, autor/editorial y primera página si el índice declarado no existe. |
| 34 | `remotePdfAndEpubReadRangesAndNeverCompleteTheFile` | PDF y EPUB con fuente de rangos local simulada: portada visible, peticiones ≤4 MiB, transferencia total ≤16 MiB y menor que el archivo. En API 24/25 el PDF debe devolver ficha básica sin portada ni lecturas, acorde con la ausencia de ProxyFD. |
| 35 | `rangeFailureAndCancellationDoNotWriteRemoteBooks` | Fallo de transporte y cancelación anterior a la inspección; ficha básica, cero lecturas si estaba cancelado y ningún documento remoto temporal. |
| 36 | `invalidFilesAndDrmFailWithoutTemporaryLeaks` | Archivos deliberadamente inválidos PDF, EPUB, CBZ, CBR, CB7, OFD, CAJ, MOBI, DjVu, CHM, DOC y PPT; muestra DRM rechazada; sin temporales nuevos. |
| 37 | `kdzSessionDeletesItsExtractedBook` | KDZ ZIP propio con EPUB interior; lectura, inspección y borrado del archivo temporal al cerrar. |
| 38 | `reflowSettingsChangePaginationAndBackground` | RTF con tamaños 14/32, familias serif/mono, más páginas con letra grande y color de papel exacto. |

Los casos y alias cubren los **35 sufijos declarados**: CBZ, CBT, CBR, CB7, ZIP, TAR, RAR, 7Z, PDF, EPUB, MOBI, AZW, AZW3, FB2, XPS, OXPS, DOCX, PPTX, XLSX, TXT, MD, HTML, HTM, XML, XHTML, MHTML, RTF, DOC, PPT, KDZ, CHM, OFD, CAJ, DJVU y DJV. El número de sufijos no indica cobertura completa de los códecs ni de las versiones de cada formato.

## Procedencia de muestras

- `ReaderFixtures.kt` genera en el emulador los archivos originales descritos en la matriz. No reutiliza documentos del usuario ni pruebas de la antigua aplicación web.
- Las muestras MOBI proceden del [directorio de pruebas de libmobi v0.12](https://github.com/bfabiszewski/libmobi/tree/v0.12/tests/samples), con licencia LGPL-3.0-or-later incluida en `LIBMOBI-LICENSE.txt`. Se usan `sample-cp1252.mobi`, `sample-unicode-huffdic.mobi`, `sample-obfuscated-fonts.mobi`, `sample-unicode-uncompressed.mobi` y `sample-drm-v1.mobi`. Las muestras multimedia y NCX incluidas como recursos no tienen un caso específico en esta suite.
- `nsis-manual.chm` procede de [pylibmspack, commit 6abd5acebe8a13d322e5ed1902a2e2c4a7330dd9](https://github.com/bwhitn/pylibmspack/blob/6abd5acebe8a13d322e5ed1902a2e2c4a7330dd9/tests/fixtures/sample.chm), con licencia NSIS zlib/libpng incluida en `NSIS-LICENSE.txt`.
- `generated-jb2.djvu` es un patrón original de 180 × 270 píxeles, codificado con DjVuLibre `cjb2 3.5.28`, `-lossless -dpi 300`. No contiene páginas de libros ajenos.

Los SHA-256 de todos los recursos externos y del patrón JB2 constan en `android/app/src/androidTest/assets/reader/README.md`. Son recursos exclusivos del APK de instrumentación.

## Límites comprobables y pendientes

- DOC, PPT, XLSX, RTF, MHTML y CHM presentan texto paginado nativo. No conservan todo el diseño. XLSX muestra valores o fórmulas almacenados y no calcula fórmulas ni reproduce gráficos. DOCX puede usar esa alternativa si faltan relaciones opcionales que el motor de documentos requiere.
- OFD admite texto, imágenes, vectores y plantillas básicos; esta muestra no verifica firmas, patrones, degradados o todos los efectos gráficos.
- CAJ usa el núcleo `caj2pdf-core 0.8.1`; esta prueba valida el contenedor CAJ generado, no todas las familias HN/C8/KDH ni fuentes especializadas. No se omiten páginas para dar una lectura aparentemente válida.
- El PDF remoto por rangos requiere API 26. En API 24/25 conserva ficha básica; la lectura local sigue disponible.
- Las fichas remotas CHM, DjVu, CAJ, XPS y OXPS pueden carecer de portada hasta la descarga solicitada por el usuario. KDZ remoto busca una portada independiente, sin extraer su libro interior completo.
- Sólo PDF y EPUB tienen en esta suite una aserción de portada remota con presupuesto de rangos. El resto de motores remotos requiere la cobertura correspondiente del repositorio o pruebas futuras; no se afirma validación con un NAS real.
- No se han ejecutado variantes ARM en dispositivo ni comparaciones visuales de todas las páginas. Las ejecuciones API 24 y API 36 cubren los JNI x86_64.

## Evidencias

- [API 36 final: 38/38 correctos](final/reader-api36.txt), 10,816 segundos, `OK (38 tests)`.
- [API 24 final: 38/38 correctos](final/reader-api24.txt), 2,593 segundos, `OK (38 tests)`.
- Entornos y SHA-256 del APK: [API 36](final/environment-api36.json), [API 24](final/environment-api24.json).
- [Primera ejecución](initial-runs/reader-api36.txt), 35 casos / 4 fallos, anterior a las correcciones. Se conserva para documentar los defectos encontrados.
- [Histórico anterior a la corrección de compatibilidad Java de SFTP en API 24](before-api24-desugaring/): el motor de lectura ya superaba 38 casos en ambos sistemas con el APK `0c8f905e3a45cc558968a9da435be02248d2a05678e9dbec924d9d478cb29191`. Ese APK fue sustituido y no constituye la entrega final.
