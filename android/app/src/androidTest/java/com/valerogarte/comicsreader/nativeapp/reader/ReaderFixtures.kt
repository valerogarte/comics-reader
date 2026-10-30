package com.valerogarte.comicsreader.nativeapp.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Original, generated QA documents. No user books, credentials or former web tests are used. */
internal class ReaderFixtures(private val directory: File) {
    fun file(name: String, text: String): File = File(directory, name).apply { writeText(text) }
    fun png(color: Int): ByteArray = ByteArrayOutputStream().use { output ->
        val image = Bitmap.createBitmap(180, 270, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(color)
        canvas.drawText("Reader QA", 12f, 110f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; textSize = 24f })
        image.compress(Bitmap.CompressFormat.PNG, 100, output)
        image.recycle()
        output.toByteArray()
    }
    fun zip(name: String, entries: Map<String, ByteArray>, comment: String? = null): File = File(directory, name).apply {
        ZipOutputStream(outputStream()).use { zip ->
            if (comment != null) zip.setComment(comment)
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
    fun zipText(name: String, entries: Map<String, String>): File = zip(name, entries.mapValues { it.value.toByteArray() })
    val paragraphs: String get() = (1..100).joinToString("\n\n") { "Paragraph $it. This original test book checks native pagination, characters áéíóú ñ, and reading progress across multiple pages." }

    fun comic(extension: String, invalidCover: Boolean = false): File {
        val info = "<ComicInfo><Title>Comic QA</Title><Writer>QA Author</Writer><Publisher>QA Publisher</Publisher><PageCount>2</PageCount><Pages><Page Image=\"${if (invalidCover) 99 else 1}\" Type=\"FrontCover\"/></Pages></ComicInfo>".toByteArray()
        val entries = linkedMapOf("000.png" to png(Color.RED), "001.png" to png(Color.BLUE), "ComicInfo.xml" to info)
        return comicArchive("comic.$extension", entries)
    }

    fun comicArchive(name: String, entries: Map<String, ByteArray>): File = when (name.substringAfterLast('.')) {
            "cbt" -> tar(name, entries)
            "cbr" -> rar(name, entries)
            "cb7" -> sevenZip(name, entries)
            else -> zip(name, entries)
        }

    fun epub(): File = zip("book.epub", linkedMapOf(
        "mimetype" to "application/epub+zip".toByteArray(),
        "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
        "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">qa-original-1</dc:identifier><dc:title>EPUB QA</dc:title><dc:creator>QA Author</dc:creator><dc:language>en</dc:language><meta name="cover" content="cover"/></metadata><manifest><item id="cover" href="cover.png" media-type="image/png"/><item id="page" href="page.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="page"/></spine></package>""".toByteArray(),
        "OEBPS/cover.png" to png(Color.MAGENTA),
        "OEBPS/page.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>EPUB QA</title></head><body><h1>EPUB QA</h1><img src="cover.png" alt="Cover"/>${paragraphs.split("\n\n").joinToString("") { "<p>$it</p>" }}</body></html>""".toByteArray(),
        "padding.bin" to ByteArray(128 * 1024) { (it * 37).toByte() },
    ))

    fun fb2(): File = file("book.fb2", """<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><genre>science</genre><author><first-name>QA</first-name><last-name>Author</last-name></author><book-title>FB2 QA</book-title><lang>en</lang></title-info></description><body><section><title><p>FB2 QA</p></title>${paragraphs.split("\n\n").joinToString("") { "<p>$it</p>" }}</section></body></FictionBook>""")

    fun docx(): File = zipText("book.docx", mapOf(
        "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
        "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>""",
        "docProps/core.xml" to core("DOCX QA"),
        "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>${paragraphs.split("\n\n").joinToString("") { "<w:p><w:r><w:t>$it</w:t></w:r></w:p>" }}</w:body></w:document>""",
    ))

    fun pptx(): File = zipText("book.pptx", mapOf(
        "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/></Types>""",
        "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/></Relationships>""",
        "docProps/core.xml" to core("PPTX QA"),
        "ppt/presentation.xml" to """<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst></p:presentation>""",
        "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/></Relationships>""",
        "ppt/slides/slide1.xml" to """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>PPTX QA native slide content</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>""",
    ))

    fun xlsx(): File = zipText("book.xlsx", mapOf(
        "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
        "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
        "docProps/core.xml" to core("XLSX QA"),
        "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Reader QA" sheetId="1" r:id="rId1"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
        "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>XLSX QA cell native content</t></is></c></row></sheetData></worksheet>""",
    ))

    fun ofd(): File = zipText("book.ofd", mapOf(
        "OFD.xml" to """<ofd:OFD xmlns:ofd="http://www.ofdspec.org/2016"><ofd:DocBody><ofd:DocInfo><ofd:Title>OFD QA</ofd:Title><ofd:Author>QA Author</ofd:Author></ofd:DocInfo><ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot></ofd:DocBody></ofd:OFD>""",
        "Doc_0/Document.xml" to """<ofd:Document xmlns:ofd="http://www.ofdspec.org/2016"><ofd:CommonData><ofd:PageArea><ofd:PhysicalBox>0 0 210 297</ofd:PhysicalBox></ofd:PageArea></ofd:CommonData><ofd:Pages><ofd:Page ID="1" BaseLoc="Pages/Page_0/Content.xml"/></ofd:Pages></ofd:Document>""",
        "Doc_0/Pages/Page_0/Content.xml" to """<ofd:Page xmlns:ofd="http://www.ofdspec.org/2016"><ofd:Content><ofd:Layer><ofd:TextObject ID="2" Boundary="20 20 170 60" Size="8"><ofd:FillColor Value="20 20 20"/><ofd:TextCode X="0" Y="12">OFD QA native content</ofd:TextCode></ofd:TextObject><ofd:PathObject ID="3" Boundary="20 90 100 50" Fill="true"><ofd:FillColor Value="230 30 30"/><ofd:AbbreviatedData>M 0 0 L 100 0 L 100 50 L 0 50 C</ofd:AbbreviatedData></ofd:PathObject></ofd:Layer></ofd:Content></ofd:Page>""",
    ))

    fun xps(oxps: Boolean = false): File {
        val namespace = if (oxps) "http://schemas.openxps.org/oxps/v1.0" else "http://schemas.microsoft.com/xps/2005/06"
        val mime = if (oxps) "application/oxps" else "application/vnd.ms-package.xps"
        return zipText(if (oxps) "book.oxps" else "book.xps", mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/FixedDocSeq.fdseq" ContentType="$mime-fixeddocumentsequence+xml"/><Override PartName="/Documents/1/FixedDoc.fdoc" ContentType="$mime-fixeddocument+xml"/><Override PartName="/Documents/1/Pages/1.fpage" ContentType="$mime-fixedpage+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$namespace/fixedrepresentation" Target="FixedDocSeq.fdseq"/></Relationships>""",
            "FixedDocSeq.fdseq" to """<FixedDocumentSequence xmlns="$namespace"><DocumentReference Source="Documents/1/FixedDoc.fdoc"/></FixedDocumentSequence>""",
            "Documents/1/FixedDoc.fdoc" to """<FixedDocument xmlns="$namespace"><PageContent Source="Pages/1.fpage" Width="600" Height="900"/></FixedDocument>""",
            "Documents/1/Pages/1.fpage" to """<FixedPage xmlns="$namespace" Width="600" Height="900" xml:lang="en"><Path Fill="#FFCC2222" Data="M 50,50 L 550,50 550,400 50,400 Z"/><Path Fill="#FF2222CC" Data="M 100,500 L 500,500 500,800 100,800 Z"/></FixedPage>""",
        ))
    }

    fun legacyOffice(ppt: Boolean = false): File {
        val word = ByteArray(4096)
        val table = ByteArray(4096)
        if (!ppt) {
            val text = "Word DOC QA native piece table text. ".repeat(35).toByteArray(Charsets.UTF_16LE)
            ByteBuffer.wrap(word).order(ByteOrder.LITTLE_ENDIAN).apply {
                putShort(0,0xa5ec.toShort()); putShort(2,0x00c1); putInt(24,512); putInt(28,512+text.size)
                putShort(32,0); putShort(34,0); putShort(36,34); putInt(38+33*8,0); putInt(38+33*8+4,21)
            }
            text.copyInto(word,512)
            ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(2); putInt(16); putInt(0); putInt(text.size/2); putShort(0); putInt(512); putShort(0)
            }
        } else {
            val text = "PowerPoint PPT QA native record text. ".repeat(35).toByteArray(Charsets.UTF_16LE)
            ByteBuffer.wrap(word).order(ByteOrder.LITTLE_ENDIAN).apply { putShort(0); putShort(4000); putInt(text.size); put(text) }
        }
        val names = listOf(if (ppt) "PowerPoint Document" else "WordDocument", "0Table", "\u0005SummaryInformation")
        val summary = ByteArray(4096)
        ByteBuffer.wrap(summary).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0,0xfffe.toShort()); putInt(24,1); putInt(44,48)
            putInt(48,160); putInt(52,2); putInt(56,2); putInt(60,24); putInt(64,4); putInt(68,80)
            position(72); putInt(30); val title=(if(ppt) "PPT QA" else "DOC QA").toByteArray(); putInt(title.size+1); put(title); put(0)
            position(128); putInt(30); putInt(10); put("QA Author\u0000".toByteArray())
        }
        val data = listOf(word,table,summary)
        val header = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(0xd0.toByte(),0xcf.toByte(),0x11,0xe0.toByte(),0xa1.toByte(),0xb1.toByte(),0x1a,0xe1.toByte()))
        header.putShort(24,0x003e);header.putShort(26,3);header.putShort(28,0xfffe.toShort());header.putShort(30,9);header.putShort(32,6)
        header.putInt(44,1);header.putInt(48,0);header.putInt(56,4096);header.putInt(60,-2);header.putInt(68,-2)
        for(i in 0 until 109) header.putInt(76+i*4,if(i==0)25 else -1)
        val directory=ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        fun entry(index:Int,name:String,type:Int,first:Int,size:Int) {
            val offset=index*128;val encoded=(name+"\u0000").toByteArray(Charsets.UTF_16LE)
            encoded.copyInto(directory.array(),offset);directory.putShort(offset+64,encoded.size.toShort())
            directory.put(offset+66,type.toByte());directory.put(offset+67,1);directory.putInt(offset+68,-1)
            directory.putInt(offset+72,if(index in 1..2)index+1 else -1);directory.putInt(offset+76,if(index==0)1 else -1)
            directory.putInt(offset+116,first);directory.putLong(offset+120,size.toLong())
        }
        entry(0,"Root Entry",5,-2,0);names.forEachIndexed { i,name->entry(i+1,name,2,1+i*8,4096) }
        val fat=ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        for(i in 0 until 128) fat.putInt(i*4,when { i==0 -> -2;i==25 -> -3;i in 1..24 -> if(i%8==0)-2 else i+1;else -> -1 })
        return File(directory(),if(ppt)"book.ppt" else "book.doc").apply { outputStream().use { out->out.write(header.array());out.write(directory.array());data.forEach(out::write);out.write(fat.array()) } }
    }

    private fun directory(): File = directory

    /** A valid uncompressed CHM directory/data container generated independently from the format fields. */
    fun chm(): File {
        val html="<html><head><title>CHM QA</title></head><body><h1>CHM QA</h1><p>${paragraphs.replace("\n"," ")}</p></body></html>".toByteArray()
        val system=ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(3).array())
            fun property(code:Int,value:String) {val bytes=(value+"\u0000").toByteArray();write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(code.toShort()).putShort(bytes.size.toShort()).array());write(bytes)}
            property(2,"index.html");property(3,"CHM QA")
        }.toByteArray()
        fun cword(number:Int):ByteArray {
            var value=number;val result=ArrayList<Int>();result+=value and 127;value=value ushr 7
            while(value>0){result+=(value and 127) or 128;value=value ushr 7}
            return result.reversed().map { it.toByte() }.toByteArray()
        }
        val entries=ByteArrayOutputStream().apply {
            var offset=0
            for((name,data) in listOf("/#SYSTEM" to system,"/index.html" to html)) {
                val bytes=name.toByteArray();write(cword(bytes.size));write(bytes);write(cword(0));write(cword(offset));write(cword(data.size));offset+=data.size
            }
        }.toByteArray()
        val header=ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN)
        header.put("ITSF".toByteArray());header.putInt(3);header.putInt(96);header.putInt(1);header.putInt(0);header.putInt(0x409)
        header.putLong(72,96);header.putLong(80,84+4096L);header.putLong(88,96+84+4096L)
        val itsp=ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN)
        itsp.put("ITSP".toByteArray());itsp.putInt(1);itsp.putInt(84);itsp.putInt(10);itsp.putInt(4096)
        itsp.putInt(28,-1);itsp.putInt(32,0);itsp.putInt(36,0);itsp.putInt(44,1);itsp.putInt(48,0x409)
        val block=ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        block.put("PMGL".toByteArray());block.putInt(4096-20-entries.size);block.putInt(0);block.putInt(-1);block.putInt(-1);block.put(entries)
        return File(directory,"book.chm").apply { writeBytes(header.array()+itsp.array()+block.array()+system+html) }
    }

    private fun core(title: String) = """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title><dc:creator>QA Author</dc:creator></cp:coreProperties>"""

    fun pdf(name: String = "book.pdf", padding: Int = 1024 * 1024): File = File(directory, name).apply { writeBytes(pdfBytes(padding)) }
    fun pdfBytes(padding: Int = 0): ByteArray {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Count 2 /Kids [5 0 R 7 0 R] >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            pdfContent("Native PDF QA page one"),
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 900] /Resources << /Font << /F1 3 0 R >> >> /Contents 4 0 R >>",
            pdfContent("Native PDF QA page two"),
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 900] /Resources << /Font << /F1 3 0 R >> >> /Contents 6 0 R >>",
            "<< /Title (PDF QA) /Author (QA Author) /Subject (Original native reader fixture) >>",
        )
        val output = ByteArrayOutputStream()
        output.write("%PDF-1.4\n".toByteArray())
        if (padding > 0) { output.write('%'.code); output.write(ByteArray(padding) { 'P'.code.toByte() }); output.write('\n'.code) }
        val offsets = objects.mapIndexed { index, value -> val offset = output.size(); output.write("${index + 1} 0 obj\n$value\nendobj\n".toByteArray()); offset }
        val xref = output.size()
        output.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray())
        offsets.forEach { output.write("%010d 00000 n \n".format(it).toByteArray()) }
        output.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R /Info 8 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
        return output.toByteArray()
    }
    private fun pdfContent(text: String): String {
        val content = "BT /F1 26 Tf 50 760 Td ($text) Tj ET"
        return "<< /Length ${content.toByteArray().size} >>\nstream\n$content\nendstream"
    }

    fun caj(): File {
        val pdf = String(pdfBytes(), Charsets.ISO_8859_1)
        // A CAJ embeds PDF object fragments, without a standalone PDF catalog/trailer.
        val body = pdf.substringAfter('\n').substringBefore("xref\n")
            .replace(Regex("(?s)(?m)^1 0 obj\\b.*?endobj\\s*"), "")
            .replace(Regex("(?s)(?m)^8 0 obj\\b.*?endobj\\s*"), "")
            .toByteArray(Charsets.ISO_8859_1)
        val start = 0x114 + 24
        val result = ByteBuffer.allocate(start + body.size).order(ByteOrder.LITTLE_ENDIAN)
        result.put(byteArrayOf('C'.code.toByte(), 'A'.code.toByte(), 'J'.code.toByte(), 0))
        result.putInt(0x10, 2); result.putInt(0x14, 0x114); result.putInt(0x110, 0)
        result.position(0x114)
        result.putInt(start).putInt(body.size).putInt(5)
        result.putInt(start + body.size).putInt(0).putInt(7)
        result.put(body)
        return File(directory, "book.caj").apply { writeBytes(result.array()) }
    }

    fun djvu(): File {
        val image = Bitmap.createBitmap(180, 270, Bitmap.Config.ARGB_8888)
        Canvas(image).apply { drawColor(Color.rgb(210, 40, 40)); drawText("DjVu QA", 15f, 100f, Paint().apply { color = Color.WHITE; textSize = 24f }) }
        val jpeg = ByteArrayOutputStream().apply { image.compress(Bitmap.CompressFormat.JPEG, 90, this) }.toByteArray()
        image.recycle()
        fun chunk(name: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().apply {
            write(name.toByteArray()); write(ByteBuffer.allocate(4).putInt(bytes.size).array()); write(bytes); if (bytes.size % 2 != 0) write(0)
        }.toByteArray()
        val info = byteArrayOf(0, 180.toByte(), 1, 14, 26, 0, 44, 1, 22, 1)
        val payload = "DJVU".toByteArray() + chunk("INFO", info) + chunk("BGjp", jpeg)
        return File(directory, "book.djvu").apply { writeBytes("AT&T".toByteArray() + "FORM".toByteArray() + ByteBuffer.allocate(4).putInt(payload.size).array() + payload) }
    }

    private fun tar(name: String, entries: Map<String, ByteArray>): File = File(directory, name).apply {
        outputStream().use { out ->
            entries.forEach { (path, data) ->
                val header = ByteArray(512)
                fun field(offset: Int, size: Int, value: String) { value.toByteArray().copyInto(header, offset, 0, minOf(value.length, size)) }
                field(0,100,path); field(100,8,"0000644\u0000"); field(108,8,"0000000\u0000"); field(116,8,"0000000\u0000")
                field(124,12,data.size.toString(8).padStart(11,'0')+"\u0000"); field(136,12,"00000000000\u0000")
                for (i in 148..155) header[i] = 32
                header[156]='0'.code.toByte(); field(257,6,"ustar\u0000"); field(263,2,"00")
                val sum=header.sumOf { it.toInt() and 255 }; field(148,8,sum.toString(8).padStart(6,'0')+"\u0000 ")
                out.write(header); out.write(data); out.write(ByteArray((512-data.size%512)%512))
            }
            out.write(ByteArray(1024))
        }
    }

    /** Stored RAR4 files generated from the documented RAR block layout. */
    private fun rar(name: String, entries: Map<String, ByteArray>): File = File(directory,name).apply {
        outputStream().use { out ->
            out.write(byteArrayOf(0x52,0x61,0x72,0x21,0x1a,0x07,0x00))
            fun header(type:Int,flags:Int,body:ByteArray) {
                val bytes=ByteBuffer.allocate(7+body.size).order(ByteOrder.LITTLE_ENDIAN)
                bytes.putShort(0).put(type.toByte()).putShort(flags.toShort()).putShort(bytes.capacity().toShort()).put(body)
                val data=bytes.array(); val crc=CRC32().apply { update(data,2,data.size-2) }.value
                ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putShort(crc.toShort()); out.write(data)
            }
            header(0x73,0,ByteArray(6))
            entries.forEach { (path,data) ->
                val nameBytes=path.toByteArray()
                val body=ByteBuffer.allocate(25+nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                body.putInt(data.size).putInt(data.size).put(3).putInt(CRC32().apply { update(data) }.value.toInt()).putInt(0)
                    .put(20).put(0x30).putShort(nameBytes.size.toShort()).putInt(0x20).put(nameBytes)
                header(0x74,0x8000,body.array()); out.write(data)
            }
            header(0x7b,0x4000,ByteArray(0))
        }
    }

    private fun sevenZip(name: String, entries: Map<String, ByteArray>): File = File(directory,name).apply {
        val archive=Archive.writeNew()
        try {
            Archive.writeSetFormat7zip(archive)
            Archive.writeOpenFileName(archive,absolutePath.toByteArray())
            entries.forEach { (path,data) ->
                val entry=ArchiveEntry.new1()
                try {
                    ArchiveEntry.setPathnameUtf8(entry,path); ArchiveEntry.setSize(entry,data.size.toLong())
                    ArchiveEntry.setFiletype(entry,ArchiveEntry.AE_IFREG); ArchiveEntry.setPerm(entry,420)
                    Archive.writeHeader(archive,entry); Archive.writeData(archive,ByteBuffer.wrap(data)); Archive.writeFinishEntry(archive)
                } finally { ArchiveEntry.free(entry) }
            }
            Archive.writeClose(archive)
        } finally { Archive.free(archive) }
    }
}
