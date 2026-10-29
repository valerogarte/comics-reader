cmake_minimum_required(VERSION 3.22.1)
project(comics_djvu LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Keep the JPEG decoder up to date as DjVu supports JPEG-backed page layers.
# Upstream libjpeg-turbo explicitly requires an independent CMake build.
include(ExternalProject)
set(JPEG_BUILD "${CMAKE_CURRENT_BINARY_DIR}/jpeg")
file(MAKE_DIRECTORY "${JPEG_BUILD}")
ExternalProject_Add(jpeg_build
    SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vendor/libjpeg-turbo"
    BINARY_DIR "${JPEG_BUILD}"
    CMAKE_ARGS
        "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
        "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}"
        "-DANDROID_ABI=${ANDROID_ABI}"
        "-DANDROID_PLATFORM=android-24"
        "-DANDROID_STL=c++_static"
        "-DCMAKE_BUILD_TYPE=Release"
        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
        "-DENABLE_SHARED=OFF"
        "-DENABLE_STATIC=ON"
        "-DWITH_TURBOJPEG=OFF"
        "-DWITH_TOOLS=OFF"
        "-DWITH_TESTS=OFF"
        "-DWITH_SIMD=OFF"
    BUILD_COMMAND "${CMAKE_COMMAND}" --build <BINARY_DIR> --target jpeg-static --parallel 4
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS "${JPEG_BUILD}/libjpeg.a"
)
add_library(reader_jpeg STATIC IMPORTED)
set_target_properties(reader_jpeg PROPERTIES IMPORTED_LOCATION "${JPEG_BUILD}/libjpeg.a")
add_dependencies(reader_jpeg jpeg_build)

file(GLOB DJVU_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/vendor/djvulibre/*.cpp")
add_library(reader_djvu STATIC ${DJVU_SOURCES})
add_dependencies(reader_djvu jpeg_build)
target_include_directories(reader_djvu PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/djvulibre"
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/libjpeg-turbo/src"
    "${JPEG_BUILD}"
)
target_compile_definitions(reader_djvu PRIVATE HAVE_CONFIG_H=1 _GNU_SOURCE=1)
target_compile_options(reader_djvu PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden -Wno-register)
target_link_libraries(reader_djvu PRIVATE reader_jpeg)

add_library(comics_djvu SHARED djvu_jni.cpp)
target_include_directories(comics_djvu PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/vendor/djvulibre")
target_link_libraries(comics_djvu PRIVATE reader_djvu reader_jpeg jnigraphics log)
target_link_options(comics_djvu PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384" "-Wl,--exclude-libs,ALL")

# libmobi reconstructs MOBI7/KF8 text, HUFF/CDIC, styles and resources. DRM code
# is deliberately excluded; ZIP creation uses Android's java.util.zip.
file(GLOB MOBI_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/vendor/libmobi/src/*.c")
add_library(reader_mobi STATIC ${MOBI_SOURCES})
target_include_directories(reader_mobi PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/vendor/libmobi/src")
target_compile_definitions(reader_mobi PRIVATE HAVE_CONFIG_H=1)
target_compile_options(reader_mobi PRIVATE -fvisibility=hidden)
target_link_libraries(reader_mobi PRIVATE z)
add_library(comics_mobi SHARED mobi_jni.cpp)
target_link_libraries(comics_mobi PRIVATE reader_mobi z)
target_link_options(comics_mobi PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384" "-Wl,--exclude-libs,ALL")
