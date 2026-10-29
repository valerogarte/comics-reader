# Native DjVu dependencies

The source files are included locally so APK compilation does not fetch code.

## DjVuLibre 3.5.30.1

- Upstream: https://sourceforge.net/p/djvu/djvulibre-git/
- Pristine source archive distributed by Debian:
  https://deb.debian.org/debian/pool/main/d/djvulibre/djvulibre_3.5.30.1.orig.tar.xz
- Archive SHA-256: `e38eae20427fa7b17787482f1dd00e88b173913d59f5f8bc5175ee40a02872f2`
- Included: the unmodified `libdjvu` C++ sources and headers, authors, notices,
  README and release notes. Android configuration is in `../config.h`.
- License: GPL-2.0-or-later; see `djvulibre/COPYING` and `COPYRIGHT`.

## libjpeg-turbo 3.2.1

- Upstream source archive:
  https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.2.1/libjpeg-turbo-3.2.1.tar.gz
- Archive SHA-256: `c27ad72a68da151177929aa969ae773a2da5ac2af9ce946079314fdac8cece7f`
- Used by DjVuLibre for JPEG page layers. Static library only; command line
  tools, TurboJPEG, tests and architecture assembly are disabled.
- The optional tools, Java/JNA bindings, tests, documentation, SIMD and PNG
  source directories are omitted. The retained upstream files are unmodified.
- Licenses: see `libjpeg-turbo/LICENSE.md` and `README.ijg`.

The JNI library uses NDK r28c and explicit 16 KiB ELF page alignment. Native
rendering is serialised per document, has decode timeouts and output limits,
and does not use JNA, JavaScript or WebView.

## libmobi 0.12

- Latest stable upstream release:
  https://github.com/bfabiszewski/libmobi/releases/tag/v0.12
- Source archive:
  https://github.com/bfabiszewski/libmobi/releases/download/v0.12/libmobi-0.12.tar.gz
- Archive SHA-256: `9a6fb2c56b916f8fa8b15e0c71008d908109508c944ea1d297881d4e277bf7e7`
- Included: unmodified library C sources and headers, authors, notices and
  release notes. Encryption and the bundled miniz implementation are omitted.
- Android uses its system zlib, built-in XML writer and platform ZIP writer.
- License: LGPL-3.0-or-later; see `libmobi/COPYING`.
