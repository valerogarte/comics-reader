#include <jni.h>
#include <android/bitmap.h>
#include "ddjvuapi.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <thread>

namespace {

using Clock = std::chrono::steady_clock;

struct Session {
    ddjvu_context_t *context = nullptr;
    ddjvu_document_t *document = nullptr;
    ~Session() {
        if (document) {
            ddjvu_job_stop(ddjvu_document_job(document));
            ddjvu_document_release(document);
        }
        if (context) ddjvu_context_release(context);
    }
};

void throwIo(JNIEnv *env, const char *message) {
    if (!env->ExceptionCheck()) {
        jclass type = env->FindClass("java/io/IOException");
        if (type) env->ThrowNew(type, message);
    }
}

Session &sessionFrom(jlong handle) {
    if (!handle) throw std::runtime_error("Documento DjVu cerrado");
    return *reinterpret_cast<Session *>(handle);
}

bool interrupted(JNIEnv *env) {
    jclass type = env->FindClass("java/lang/Thread");
    if (!type) return true;
    jmethodID currentMethod = env->GetStaticMethodID(type, "currentThread", "()Ljava/lang/Thread;");
    jmethodID interruptedMethod = env->GetMethodID(type, "isInterrupted", "()Z");
    if (!currentMethod || !interruptedMethod) { env->DeleteLocalRef(type); return true; }
    jobject thread = env->CallStaticObjectMethod(type, currentMethod);
    const bool result = !thread || env->CallBooleanMethod(thread, interruptedMethod) == JNI_TRUE;
    if (thread) env->DeleteLocalRef(thread);
    env->DeleteLocalRef(type);
    return result || env->ExceptionCheck();
}

void drainMessages(ddjvu_context_t *context) {
    // Local filename decoding uses DjVuLibre's internal file loader. Messages
    // must still be consumed so the decoder queue never grows per page.
    for (int count = 0; count < 1024 && ddjvu_message_peek(context); ++count) ddjvu_message_pop(context);
}

template<typename Status>
void waitDecoded(JNIEnv *env, Session &session, Status status) {
    const auto deadline = Clock::now() + std::chrono::seconds(30);
    for (;;) {
        drainMessages(session.context);
        const int value = status();
        if (value == DDJVU_JOB_OK) return;
        if (value >= DDJVU_JOB_FAILED) throw std::runtime_error("No se ha podido decodificar el documento DjVu");
        if (interrupted(env)) throw std::runtime_error("Lectura DjVu cancelada");
        if (Clock::now() >= deadline) throw std::runtime_error("Se ha agotado el tiempo de lectura del documento DjVu");
        std::this_thread::sleep_for(std::chrono::milliseconds(8));
    }
}

struct Page {
    ddjvu_page_t *value;
    explicit Page(ddjvu_page_t *page) : value(page) {}
    ~Page() {
        if (value) { ddjvu_job_stop(ddjvu_page_job(value)); ddjvu_page_release(value); }
    }
};

struct Format {
    ddjvu_format_t *value;
    explicit Format(ddjvu_format_t *format) : value(format) {}
    ~Format() { if (value) ddjvu_format_release(value); }
};

struct Pixels {
    JNIEnv *env;
    jobject bitmap;
    void *data = nullptr;
    Pixels(JNIEnv *jni, jobject image) : env(jni), bitmap(image) {
        if (AndroidBitmap_lockPixels(env, bitmap, &data) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("No se ha podido acceder a la imagen de la página DjVu");
        if (!data) {
            AndroidBitmap_unlockPixels(env, bitmap);
            throw std::runtime_error("La imagen de la página DjVu está vacía");
        }
    }
    ~Pixels() { AndroidBitmap_unlockPixels(env, bitmap); }
};

jobject createBitmap(JNIEnv *env, int width, int height) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmapClass || !configClass) return nullptr;
    jfieldID configField = env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jmethodID create = env->GetStaticMethodID(bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (!configField || !create) return nullptr;
    jobject config = env->GetStaticObjectField(configClass, configField);
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, create, width, height, config);
    env->DeleteLocalRef(config);
    env->DeleteLocalRef(configClass);
    env->DeleteLocalRef(bitmapClass);
    return bitmap;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_valerogarte_comicsreader_nativeapp_reader_NativeDjvu_open(JNIEnv *env, jobject, jstring path) {
    try {
        auto session = std::make_unique<Session>();
        session->context = ddjvu_context_create("ComicsReader");
        if (!session->context) throw std::runtime_error("No se ha podido iniciar el lector DjVu");
        const char *filename = env->GetStringUTFChars(path, nullptr);
        if (!filename) return 0;
        session->document = ddjvu_document_create_by_filename_utf8(session->context, filename, 0);
        env->ReleaseStringUTFChars(path, filename);
        if (!session->document) throw std::runtime_error("No se ha podido abrir el archivo DjVu");
        waitDecoded(env, *session, [&] { return ddjvu_document_decoding_status(session->document); });
        const int count = ddjvu_document_get_pagenum(session->document);
        if (count <= 0 || count > 100000) throw std::runtime_error("El documento DjVu no contiene páginas válidas");
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception &error) { throwIo(env, error.what()); }
    catch (...) { throwIo(env, "No se ha podido abrir el archivo DjVu"); }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_valerogarte_comicsreader_nativeapp_reader_NativeDjvu_pageCount(JNIEnv *env, jobject, jlong handle) {
    try { return ddjvu_document_get_pagenum(sessionFrom(handle).document); }
    catch (const std::exception &error) { throwIo(env, error.what()); }
    return 0;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_valerogarte_comicsreader_nativeapp_reader_NativeDjvu_render(JNIEnv *env, jobject, jlong handle, jint index, jint requestedWidth) {
    try {
        Session &session = sessionFrom(handle);
        const int count = ddjvu_document_get_pagenum(session.document);
        if (index < 0 || index >= count) throw std::runtime_error("Página fuera del documento DjVu");
        ddjvu_pageinfo_t info{};
        waitDecoded(env, session, [&] { return ddjvu_document_get_pageinfo(session.document, index, &info); });
        if (info.width <= 0 || info.height <= 0 || static_cast<int64_t>(info.width) * info.height > 100000000LL)
            throw std::runtime_error("Las dimensiones de la página DjVu superan el límite de lectura");
        Page page(ddjvu_page_create_by_pageno(session.document, index));
        if (!page.value) throw std::runtime_error("No se ha podido abrir la página DjVu");
        waitDecoded(env, session, [&] { return ddjvu_page_decoding_status(page.value); });
        const int naturalWidth = ddjvu_page_get_width(page.value);
        const int naturalHeight = ddjvu_page_get_height(page.value);
        if (naturalWidth <= 0 || naturalHeight <= 0) throw std::runtime_error("Página DjVu sin dimensiones válidas");
        const double factor = std::min(
            static_cast<double>(std::clamp(static_cast<int>(requestedWidth), 180, 2048)) / naturalWidth,
            4096.0 / naturalHeight);
        const int width = std::max(1, static_cast<int>(std::lround(naturalWidth * factor)));
        const int height = std::max(1, static_cast<int>(std::lround(naturalHeight * factor)));
        jobject bitmap = createBitmap(env, width, height);
        if (!bitmap || env->ExceptionCheck()) return nullptr;
        AndroidBitmapInfo bitmapInfo{};
        if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS || bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
            throw std::runtime_error("No se ha podido crear la imagen de la página DjVu");
        // Android's native RGBA_8888 pixel order is R,G,B,A on all target ABIs.
        unsigned int masks[] = {0x000000ff, 0x0000ff00, 0x00ff0000, 0xff000000};
        Format format(ddjvu_format_create(DDJVU_FORMAT_RGBMASK32, 4, masks));
        if (!format.value) throw std::runtime_error("No se ha podido configurar la imagen DjVu");
        ddjvu_format_set_row_order(format.value, 1);
        ddjvu_format_set_y_direction(format.value, 1);
        ddjvu_rect_t rect{0, 0, static_cast<unsigned int>(width), static_cast<unsigned int>(height)};
        Pixels pixels(env, bitmap);
        const int rendered = ddjvu_page_render(page.value, DDJVU_RENDER_COLOR, &rect, &rect, format.value, bitmapInfo.stride, static_cast<char *>(pixels.data));
        if (!rendered) throw std::runtime_error("No se ha podido dibujar la página DjVu");
        drainMessages(session.context);
        return bitmap;
    } catch (const std::exception &error) { throwIo(env, error.what()); }
    catch (...) { throwIo(env, "No se ha podido dibujar la página DjVu"); }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_valerogarte_comicsreader_nativeapp_reader_NativeDjvu_close(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Session *>(handle);
}
