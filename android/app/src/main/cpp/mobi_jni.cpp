#include <jni.h>
#include "mobi.h"

#include <cstdio>
#include <memory>
#include <stdexcept>
#include <string>
#include <sys/stat.h>

namespace {

constexpr size_t MaxText = 64 * 1024 * 1024;
constexpr size_t MaxOutput = 256 * 1024 * 1024;
constexpr size_t MaxParts = 10000;

void throwIo(JNIEnv *env, const char *message) {
    if (!env->ExceptionCheck()) {
        jclass type = env->FindClass("java/io/IOException");
        if (type) env->ThrowNew(type, message);
    }
}

void checkResult(MOBI_RET status) {
    if (status == MOBI_FILE_ENCRYPTED || status == MOBI_DRM_UNSUPPORTED)
        throw std::runtime_error("El libro está protegido por DRM y no se puede abrir");
    if (status != MOBI_SUCCESS) throw std::runtime_error("No se ha podido reconstruir el libro MOBI/AZW");
}

class PartSink {
public:
    PartSink(JNIEnv *jni, jobject value) : env(jni), sink(value) {
        jclass type = env->GetObjectClass(sink);
        add = env->GetMethodID(type, "addPart", "(Ljava/lang/String;Ljava/nio/ByteBuffer;)V");
        env->DeleteLocalRef(type);
        if (!add) throw std::runtime_error("No se ha podido preparar el libro para su lectura");
    }

    bool write(const std::string &name, const MOBIPart *part) {
        if (!part || !part->size) return true;
        if (!part->data || part->size > MaxText || bytes > MaxOutput - part->size || ++count > MaxParts)
            throw std::runtime_error("El contenido del libro supera el límite de lectura de 256 MB");
        bytes += part->size;
        jstring filename = env->NewStringUTF(name.c_str());
        jobject buffer = env->NewDirectByteBuffer(part->data, static_cast<jlong>(part->size));
        if (!filename || !buffer || env->ExceptionCheck()) return false;
        // The direct buffer is consumed synchronously by the platform ZIP
        // writer; its memory is owned by libmobi and is never retained in Java.
        env->CallVoidMethod(sink, add, filename, buffer);
        env->DeleteLocalRef(buffer);
        env->DeleteLocalRef(filename);
        return !env->ExceptionCheck();
    }

private:
    JNIEnv *env;
    jobject sink;
    jmethodID add;
    size_t bytes = 0;
    size_t count = 0;
};

std::string partName(const char *prefix, const MOBIPart *part) {
    const MOBIFileMeta metadata = mobi_get_filemeta_by_type(part->type);
    char name[96];
    std::snprintf(name, sizeof(name), "OEBPS/%s%05zu.%s", prefix, part->uid, metadata.extension);
    return name;
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_valerogarte_comicsreader_nativeapp_reader_NativeMobi_unpack(JNIEnv *env, jobject, jstring path, jobject sinkObject) {
    try {
        const char *filename = env->GetStringUTFChars(path, nullptr);
        if (!filename) return;
        struct stat info{};
        const bool valid = stat(filename, &info) == 0 && S_ISREG(info.st_mode) && info.st_size > 0 && info.st_size <= static_cast<off_t>(MaxOutput);
        std::unique_ptr<FILE, decltype(&fclose)> file(valid ? std::fopen(filename, "rb") : nullptr, fclose);
        env->ReleaseStringUTFChars(path, filename);
        if (!valid) throw std::runtime_error("El archivo MOBI/AZW está vacío o supera el límite de 256 MB");
        if (!file) throw std::runtime_error("No se ha podido abrir el archivo MOBI/AZW");
        std::unique_ptr<MOBIData, decltype(&mobi_free)> book(mobi_init(), mobi_free);
        if (!book) throw std::runtime_error("No hay memoria suficiente para abrir el libro");
        // libmobi selects the KF8 part of hybrid MOBI7/KF8 books by default.
        checkResult(mobi_load_file(book.get(), file.get()));
        file.reset();
        if (mobi_is_encrypted(book.get()) || (book->next && mobi_is_encrypted(book->next)))
            throw std::runtime_error("El libro está protegido por DRM y no se puede abrir");
        if (mobi_is_replica(book.get()))
            throw std::runtime_error("Este libro usa el formato Print Replica y necesita una copia PDF o EPUB");
        const size_t textSize = mobi_get_text_maxsize(book.get());
        if (!textSize || textSize > MaxText)
            throw std::runtime_error("El texto del libro supera el límite de lectura de 64 MB");
        std::unique_ptr<MOBIRawml, decltype(&mobi_free_rawml)> raw(mobi_init_rawml(book.get()), mobi_free_rawml);
        if (!raw) throw std::runtime_error("No hay memoria suficiente para reconstruir el libro");
        checkResult(mobi_parse_rawml(raw.get(), book.get()));
        if (!raw->markup) throw std::runtime_error("El libro no contiene páginas de lectura válidas");
        PartSink sink(env, sinkObject);
        bool hasOpf = false;
        for (const MOBIPart *part = raw->markup; part; part = part->next)
            if (!sink.write(partName("part", part), part)) return;
        // Flow zero is the original raw markup, already reconstructed above.
        for (const MOBIPart *part = raw->flow ? raw->flow->next : nullptr; part; part = part->next)
            if (!sink.write(partName("flow", part), part)) return;
        for (const MOBIPart *part = raw->resources; part; part = part->next) {
            const bool opf = part->type == T_OPF;
            hasOpf = hasOpf || (opf && part->size > 0);
            if (!sink.write(opf ? "OEBPS/content.opf" : partName("resource", part), part)) return;
        }
        if (!hasOpf) throw std::runtime_error("No se ha podido reconstruir el índice del libro");
    } catch (const std::exception &error) { throwIo(env, error.what()); }
    catch (...) { throwIo(env, "No se ha podido convertir el libro MOBI/AZW para su lectura"); }
}
