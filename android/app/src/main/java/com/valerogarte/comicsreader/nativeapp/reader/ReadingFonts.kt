package com.valerogarte.comicsreader.nativeapp.reader

import android.content.Context
import java.io.File
import java.security.MessageDigest

data class ReadingFont(val id: String, val label: String, val assetPath: String)

/** Offline reading families; legacy system aliases and imported fonts stay independent. */
object ReadingFonts {
    const val DEFAULT_ID = "literata"

    val families: List<ReadingFont> = listOf(
        ReadingFont("literata", "Literata", "fonts/Literata-Regular.ttf"),
        ReadingFont("lora", "Lora", "fonts/Lora-Regular.ttf"),
        ReadingFont("merriweather", "Merriweather", "fonts/Merriweather-Regular.ttf"),
        ReadingFont("source-serif-4", "Source Serif 4", "fonts/SourceSerif4-Regular.ttf"),
        ReadingFont("noto-serif", "Noto Serif", "fonts/NotoSerif-Regular.ttf"),
        ReadingFont("eb-garamond", "EB Garamond", "fonts/EBGaramond-Regular.ttf"),
        ReadingFont("libre-baskerville", "Libre Baskerville", "fonts/LibreBaskerville-Regular.ttf"),
        ReadingFont("crimson-pro", "Crimson Pro", "fonts/CrimsonPro-Regular.ttf"),
        ReadingFont("atkinson-hyperlegible", "Atkinson Hyperlegible", "fonts/AtkinsonHyperlegible-Regular.ttf"),
        ReadingFont("source-sans-3", "Source Sans 3", "fonts/SourceSans3-Regular.ttf"),
        ReadingFont("noto-sans", "Noto Sans", "fonts/NotoSans-Regular.ttf"),
        ReadingFont("lexend", "Lexend", "fonts/Lexend-Regular.ttf"),
        ReadingFont("nunito-sans", "Nunito Sans", "fonts/NunitoSans-Regular.ttf"),
        ReadingFont("roboto-mono", "Roboto Mono", "fonts/RobotoMono-Regular.ttf"),
    )

    private val byId = families.associateBy { it.id }
    private val checksums = mapOf(
        "literata" to "4898f4ede0ecd3de6c93370e35ce3149bb479bf398f4d9c5ef81459201ae95d1",
        "lora" to "10bafe9d7df7e5c13ca2fcb9ae50930959a294a952c04119a416a987c3cc9236",
        "merriweather" to "769dbfb2ab483e2460facaf61d4467265540ee70263274ca6b3891db10d7f93a",
        "source-serif-4" to "febc0eff134477d71e69eae94e6d7aa3a2c9604b93bbf2469fe8b3a2d7c69f73",
        "noto-serif" to "f6f78be4aca8094231232829a510aa09dc49bd417c12d25bd32079089d9b1814",
        "eb-garamond" to "48269eec73ce09af5eba600c3c14e92b44c0babc467066d42da8119b61be4e7a",
        "libre-baskerville" to "f58af6a0ad57cb8927595064547f8fe15db56cab964320f3de1be0ee7e3f5c1d",
        "crimson-pro" to "eb2c006cf6c4699f01ffe047df5e7c9f3e4ff26654421eb76d77001d5f202fe5",
        "atkinson-hyperlegible" to "283f995b0882f2f77f76c318200d2efbd4ed299c96d65b0bc0a92cb4c5fd15bb",
        "source-sans-3" to "a9321eb1914334d8033f520794acf429ca26bf934566ea88848b9cced3fb6e2a",
        "noto-sans" to "5330b12f57d360c7f48ca12503c98215f90868c0dc0885451fd4b119622d2d78",
        "lexend" to "865f97f60f31c6885c94f6a7ce2ed7aaed273abbb262c4db4ab969bbbf922e9b",
        "nunito-sans" to "672e5ee758c931a5f415638a35465825c4f427a6660805143ad57d32f28a4aee",
        "roboto-mono" to "5a45a4351e26708b2c6c1c3f9a4038614ac3cf0cbad8a5208e24bf7037eb2b05",
    )
    private val resolved = HashMap<String, File>()

    fun find(id: String): ReadingFont? = byId[id]

    /** System fallback for saved aliases; custom files are provided separately by the caller. */
    fun systemFamily(id: String): String = when (id) {
        "serif" -> "serif"
        "mono", "monospace" -> "monospace"
        else -> "sans-serif"
    }

    /**
     * Blocking: call from IO. A selected bundled face is copied once to a stable private path
     * for engines that require files. Unknown IDs, system aliases and `custom` return null.
     */
    @Synchronized
    fun resolve(context: Context, id: String): File? {
        val font = find(id) ?: return null
        val checksum = checksums.getValue(id)
        val directory = File(context.applicationContext.filesDir, "reading-fonts")
        val destination = File(directory, "$id-${checksum.take(12)}.ttf")
        resolved[destination.absolutePath]?.takeIf { it.isFile }?.let { return it }
        check(directory.isDirectory || directory.mkdirs()) { "No se puede crear la carpeta de fuentes" }
        if (!destination.isFile || sha256(destination) != checksum) {
            val part = File.createTempFile("$id-", ".part", directory)
            try {
                context.assets.open(font.assetPath).use { input ->
                    part.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
                }
                check(sha256(part) == checksum) { "La fuente incluida no es válida" }
                check(part.renameTo(destination)) { "No se puede guardar la fuente de lectura" }
            } finally { part.delete() }
        }
        // Keep one version per family after an application upgrade; current paths are immutable.
        directory.listFiles()?.filter {
            it.isFile && it != destination && it.name.startsWith("$id-") && it.extension == "ttf"
        }?.forEach { it.delete() }
        resolved[destination.absolutePath] = destination
        return destination
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16384)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
