# Native entry points and CHM resource cleanup (the parser has no public close API).
-keep class com.artifex.mupdf.fitz.** { *; }
-keepclassmembers class org.jchmlib.ChmFile { java.io.RandomAccessFile rf; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
