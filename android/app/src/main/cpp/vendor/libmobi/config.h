#pragma once

// Android/Bionic configuration. Use platform zlib and the built-in XML writer.
#define USE_XMLWRITER 1
#define USE_ZLIB 1
#define PACKAGE_VERSION "0.12"
#define HAVE_ATTRIBUTE_NORETURN 1
#define HAVE_STDINT_H 1
#define HAVE_STDBOOL_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_UNISTD_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_STRDUP 1
#define HAVE_MEMMOVE 1
#define HAVE_MEMSET 1
#define MOBI_INLINE inline
