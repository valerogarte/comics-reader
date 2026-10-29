package com.valerogarte.comicsreader.nativeapp.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class NativeReaderInstrumentedTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var fixtures: ReaderFixtures
    private lateinit var engine: NativeDocumentEngine

    @Before fun createFixtures() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "reader-qa-${System.nanoTime()}").apply { check(mkdirs()) }
        fixtures = ReaderFixtures(directory)
        engine = NativeDocumentEngine(context)
    }
    @After fun removeFixtures() { directory.deleteRecursively() }

    @Test fun pdfPageRenderingAndMetadata() { assertReader(fixtures.pdf(), expectedTitle = "PDF QA", expectedPages = 2) }
    @Test fun epubReflowsAndHasCover() { assertReader(fixtures.epub(), expectedTitle = "EPUB QA", reflowable = true) }
    @Test fun cbzComicPagesAndMetadata() { assertReader(fixtures.comic("cbz"), expectedTitle = "Comic QA", expectedPages = 2) }
    @Test fun cbtTarComicPages() { assertReader(fixtures.comic("cbt"), expectedTitle = "Comic QA", expectedPages = 2) }
    @Test fun cbrStoredRarComicPages() { assertReader(fixtures.comic("cbr"), expectedTitle = "Comic QA", expectedPages = 2) }
    @Test fun cb7NativeSevenZipComicPages() { assertReader(fixtures.comic("cb7"), expectedTitle = "Comic QA", expectedPages = 2) }
    @Test fun txtNativeReader() { assertReader(fixtures.file("book.txt",fixtures.paragraphs), reflowable = true) }
    @Test fun markdownNativeReader() { assertReader(fixtures.file("book.md","# Native Markdown QA\n\n${fixtures.paragraphs}"), reflowable = true) }
    @Test fun htmlNativeReader() { assertReader(fixtures.file("book.html","<html><head><title>HTML QA</title></head><body><h1>HTML QA</h1><p>${fixtures.paragraphs}</p></body></html>"), reflowable = true) }
    @Test fun xhtmlNativeReader() { assertReader(fixtures.file("book.xhtml","<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>XHTML QA</title></head><body><p>${fixtures.paragraphs}</p></body></html>"), reflowable = true) }
    @Test fun xmlGenericTextReader() { assertReader(fixtures.file("book.xml","<?xml version=\"1.0\"?><book><title>XML QA</title><chapter>${fixtures.paragraphs}</chapter></book>"), reflowable = true) }
    @Test fun fb2NativeReader() { assertReader(fixtures.fb2(), reflowable = true) }
    @Test fun rtfNativeTextReader() { assertReader(fixtures.file("book.rtf","{\\rtf1\\ansi Native RTF QA\\par ${fixtures.paragraphs.replace("\n","\\par ")}}"), reflowable = true) }
    @Test fun mhtmlNativeTextReader() {
        val html="<html><body><h1>MHTML QA</h1><p>${fixtures.paragraphs}</p></body></html>"
        val encoded=html.toByteArray(Charsets.UTF_8).joinToString("") { "=%02X".format(it.toInt() and 255) }.chunked(72).joinToString("=\r\n")
        val document="MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=QA-BOUNDARY\r\n\r\n--QA-BOUNDARY\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n$encoded\r\n--QA-BOUNDARY--\r\n"
        assertTrue(MimeHtml.text(document).contains("áéíóú ñ"))
        assertReader(fixtures.file("book.mhtml",document), reflowable = true)
    }
    @Test fun docxNativeOfficeReader() { assertReader(fixtures.docx(), reflowable = true) }
    @Test fun pptxNativeOfficeReader() { assertReader(fixtures.pptx(), reflowable = true) }
    @Test fun xlsxNativeOfficeReader() { assertReader(fixtures.xlsx(), reflowable = true) }
    @Test fun docBinaryPieceTableReader() { assertReader(fixtures.legacyOffice(),expectedTitle="DOC QA",reflowable=true) }
    @Test fun pptBinaryTextRecordReader() { assertReader(fixtures.legacyOffice(ppt=true),expectedTitle="PPT QA",reflowable=true) }
    @Test fun ofdNativeCanvasReader() { assertReader(fixtures.ofd(),expectedTitle="OFD QA",expectedPages=1) }
    @Test fun xpsFixedPageReader() { assertReader(fixtures.xps(),expectedPages=1) }
    @Test fun oxpsFixedPageReader() { assertReader(fixtures.xps(oxps=true),expectedPages=1) }
    @Test fun djvuNativeJpegLayerReader() { assertReader(fixtures.djvu(),expectedPages=1) }
    @Test fun djvuNativeJb2LayerReader() { assertReader(sample("generated-jb2.djvu"),expectedPages=1) }
    @Test fun chmNativeCompiledHtmlReader() { assertReader(fixtures.chm(),expectedTitle="CHM QA",reflowable=true) }
    @Test fun chmLzxCompressedNsisManualReader() { assertReader(sample("nsis-manual.chm"),reflowable=true) }
    @Test fun cajNativeRustConversionReader() { assertReader(fixtures.caj(),expectedPages=2) }
    @Test fun mobiCp1252NativeReconstruction() { assertReader(sample("sample-cp1252.mobi"),reflowable=true) }
    @Test fun mobiHuffmanNativeReconstruction() { assertReader(sample("sample-unicode-huffdic.mobi"),reflowable=true) }
    @Test fun azw3ActualKf8NativeReconstruction() { assertReader(sample("sample-obfuscated-fonts.mobi","book.azw3"),reflowable=true) }
    @Test fun azwHybridNativeReconstruction() { assertReader(sample("sample-unicode-uncompressed.mobi","book.azw"),reflowable=true) }

    @Test fun supportedFilenameAliasesUseTheirActualNativeFormats() {
        assertReader(fixtures.file("alias.htm","<html><body><h1>HTM QA</h1><p>Native HTML filename alias.</p></body></html>"),reflowable=true)
        assertReader(fixtures.djvu().copyTo(File(directory,"alias.djv")),expectedPages=1)
        for((comic,alias) in listOf("cbz" to "zip","cbt" to "tar","cbr" to "rar","cb7" to "7z")) {
            assertReader(fixtures.comic(comic).copyTo(File(directory,"alias.$alias")),expectedTitle="Comic QA",expectedPages=2)
        }
    }

    @Test fun explicitComicCoverAndMissingCoverFallback() {
        val selected=engine.inspectLocal(fixtures.comic("cbz"))
        try {
            assertEquals("QA Author",selected.author)
            assertEquals("QA Publisher",selected.publisher)
            assertTrue("ComicInfo should select page 1",Color.blue(selected.cover!!.getPixel(10,10))>200)
        } finally { selected.cover?.recycle() }
        val fallback=engine.inspectLocal(fixtures.comic("cbz",invalidCover=true))
        try { assertTrue("Invalid cover index should use first page",Color.red(fallback.cover!!.getPixel(10,10))>200) }
        finally { fallback.cover?.recycle() }
    }

    @Test fun remotePdfAndEpubReadRangesAndNeverCompleteTheFile() {
        for(file in listOf(fixtures.pdf(),fixtures.epub())) {
            val requests=ArrayList<Pair<Long,Int>>()
            RandomAccessFile(file,"r").use { input ->
                val metadata=engine.inspectRemote(file.name,file.length(),RangeSource { offset,length ->
                    assertTrue("Bounded source request",length in 1..4*1024*1024)
                    assertTrue("Source range must fit",offset>=0 && offset+length<=file.length())
                    synchronized(requests) { requests+=offset to length }
                    ByteArray(length).also { input.seek(offset);input.readFully(it) }
                })
                try {
                    if(file.extension=="pdf" && Build.VERSION.SDK_INT<26) {
                        assertEquals("API 24/25 PDF keeps the basic file title",baseTitle(file.name),metadata.title)
                        assertNull("Proxy file descriptors require API 26",metadata.cover)
                        assertTrue("Unsupported remote PDF preview must not request its source",requests.isEmpty())
                    } else {
                        assertNotNull("Remote ${file.extension} first cover",metadata.cover)
                        assertVisible(requireNotNull(metadata.cover))
                        assertTrue("At least one partial read",requests.isNotEmpty())
                    }
                    assertTrue("Never transfer the complete source",requests.sumOf { it.second.toLong() }<file.length())
                    assertTrue("16 MiB total budget",requests.sumOf { it.second.toLong() }<=16L*1024*1024)
                } finally { metadata.cover?.recycle() }
            }
        }
    }

    @Test fun rangeFailureAndCancellationDoNotWriteRemoteBooks() {
        val before=workFiles()
        val metadata=engine.inspectRemote("unavailable.cbz",1024*1024,RangeSource { _,_ ->throw IOException("QA range failure") })
        assertEquals("unavailable",metadata.title);assertNull(metadata.cover)
        var reads=0
        try {
            Thread.currentThread().interrupt()
            val cancelled=engine.inspectRemote("cancelled.pdf",1024*1024,RangeSource { _,length -> reads++;ByteArray(length) })
            assertNull(cancelled.cover)
        } finally { Thread.interrupted() }
        assertEquals("Interrupted preview must not invoke its network source",0,reads)
        assertEquals("Remote inspections must not spool source documents",before,workFiles())
    }

    @Test fun invalidFilesAndDrmFailWithoutTemporaryLeaks() {
        val before=workFiles()
        for(extension in listOf("pdf","epub","cbz","cbr","cb7","ofd","caj","mobi","djvu","chm","doc","ppt")) {
            val file=fixtures.file("invalid.$extension","This is deliberately not a $extension file")
            var failed=false
            try { engine.open(file).use { session -> session.renderPage(0,180).recycle() } }
            catch (_:Exception) { failed=true }
            assertTrue("Invalid $extension should be rejected",failed)
        }
        var drmFailed=false
        try { engine.open(sample("sample-drm-v1.mobi")).close() } catch (_:Exception) { drmFailed=true }
        assertTrue("DRM-protected sample must be rejected",drmFailed)
        assertEquals("Native conversion failures must remove temporaries",before,workFiles())
    }

    @Test fun kdzSessionDeletesItsExtractedBook() {
        val before=workFiles()
        val epub=fixtures.epub()
        val kdz=fixtures.zip("package.kdz",mapOf("book.epub" to epub.readBytes()))
        assertReader(kdz,reflowable=true)
        assertEquals(before,workFiles())
    }

    @Test fun reflowSettingsChangePaginationAndBackground() {
        val file=fixtures.file("options.rtf","{\\rtf1\\ansi ${fixtures.paragraphs.replace("\n","\\par ")}}")
        val paper=Color.rgb(245,230,195)
        engine.open(file,options=DocumentOptions(14f,"serif",backgroundColor=paper)).use { small ->
            engine.open(file,options=DocumentOptions(32f,"mono",backgroundColor=paper)).use { large ->
                assertTrue(small.isReflowable);assertTrue(large.isReflowable)
                assertTrue("Larger type should create more pages",large.pageCount>small.pageCount)
                val page=large.renderPage(0,360)
                try { assertEquals(paper,page.getPixel(0,0));assertVisible(page) } finally { page.recycle() }
            }
        }
    }

    private fun assertReader(file:File,expectedTitle:String?=null,expectedPages:Int?=null,reflowable:Boolean?=null) {
        val before=workFiles()
        engine.open(file).use { session ->
            assertTrue("${file.name} pages",session.pageCount>0)
            expectedPages?.let { assertEquals(file.name,it,session.pageCount) }
            reflowable?.let { assertEquals(file.name,it,session.isReflowable) }
            val first=session.renderPage(0,360)
            try { assertVisible(first) } finally { first.recycle() }
            if(session.pageCount>1) {
                val last=session.renderPage(session.pageCount-1,240)
                // A real book may deliberately end with a blank page; it must still render safely.
                try { assertTrue(last.width>0 && last.height>0 && !last.isRecycled) } finally { last.recycle() }
            }
            var boundsRejected=false
            try { session.renderPage(session.pageCount,360).recycle() } catch (_:Exception) { boundsRejected=true }
            assertTrue("Out-of-range page must be rejected",boundsRejected)
        }
        val metadata=engine.inspectLocal(file)
        try {
            assertTrue("${file.name} title",metadata.title.isNotBlank())
            expectedTitle?.let { assertEquals(file.name,it,metadata.title) }
            assertTrue("${file.name} inspected page count",metadata.pageCount>0)
            assertNotNull("${file.name} cover or first page",metadata.cover)
            assertVisible(requireNotNull(metadata.cover))
        } finally { metadata.cover?.recycle() }
        assertEquals("${file.name} session resources must be cleaned",before,workFiles())
    }

    private fun assertVisible(bitmap:Bitmap) {
        assertFalse(bitmap.isRecycled)
        assertTrue(bitmap.width>0 && bitmap.height>0)
        val first=bitmap.getPixel(0,0)
        var different=0
        // Inspect every pixel; thin glyph strokes must not be missed by a sparse sampling grid.
        val row=IntArray(bitmap.width)
        for(y in 0 until bitmap.height) {
            bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
            different+=row.count { it!=first }
        }
        assertTrue("Rendered page must contain visible content ($different pixels)",different>10)
    }

    private fun sample(name:String,target:String=name):File = File(directory,target).apply {
        InstrumentationRegistry.getInstrumentation().context.assets.open("reader/$name").use { input->outputStream().use { output->input.copyTo(output) } }
    }
    private fun workFiles():Set<String> = File(context.cacheDir,"document-work").list()?.toSet().orEmpty()
}
