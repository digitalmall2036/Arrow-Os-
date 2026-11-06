package com.arrowos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ClonerToolsTest {

    @Test fun searchFindsToolsByName() {
        assertEquals("canva", AutoDesigner.searchTools("canva").first().id)
        assertEquals("gmail", AutoDesigner.searchTools("Gmail").first().id)
        assertTrue(AutoDesigner.searchTools("goo").any { it.id == "gdrive" })
    }

    @Test fun searchFindsToolsByPurpose() {
        val ids = AutoDesigner.searchTools("payments").map { it.id }
        assertTrue(ids.contains("paypal"))
    }

    @Test fun searchFindsToolsForAProfession() {
        val ids = AutoDesigner.searchTools("photographer").map { it.id }
        assertTrue(ids.contains("lightroom"))
    }

    @Test fun emptySearchFindsNothing() {
        assertTrue(AutoDesigner.searchTools("   ").isEmpty())
    }

    @Test fun customWebsitesRoundTrip() {
        val id = CustomSites.idFor("Netlify", "app.netlify.com")
        assertNotNull(id)
        assertTrue(!id!!.contains(","))
        val tool = CustomSites.toolFor(id)!!
        assertEquals("Netlify", tool.label)
        assertEquals("https://app.netlify.com/", tool.url)
        assertEquals("N", tool.initial)
    }

    @Test fun customWebsiteNeedsAnAddress() {
        assertNull(CustomSites.idFor("x", "not a website"))
        assertNull(CustomSites.toolFor("site:broken"))
        assertEquals("https://example.com/path", CustomSites.normalizeUrl("https://example.com/path"))
        assertEquals("example.com", CustomSites.toolFor(CustomSites.idFor("", "www.example.com")!!)!!.label)
    }

    @Test fun homeSetupKeepsCustomWebsites() {
        val site = CustomSites.idFor("Netlify", "netlify.com")!!
        val setup = HomeSetup("Website developer", listOf("claude")).plus(listOf(site, "claude", "nope"))
        assertEquals(listOf("claude", site), setup.toolIds)
        val back = HomeSetup.decode(setup.encode())!!
        assertEquals(setup, back)
        assertEquals("Netlify", back.findTool("netlify")?.label)
        assertEquals(listOf("claude"), back.minus(site).toolIds)
    }

    @Test fun readsPlainTextAndWebPages() {
        assertEquals("hello", ClonerText.read("a.txt", "text/plain", "hello".toByteArray()))
        assertEquals("Title\nHi & bye", ClonerText.read("a.html", null, "<h1>Title</h1><script>x</script><p>Hi &amp; bye</p>".toByteArray()))
        assertEquals("", ClonerText.read("photo.jpg", "image/jpeg", byteArrayOf(1, 2, 3)))
        assertTrue(ClonerText.canRead("notes.md", null))
    }

    @Test fun readsWordFiles() {
        val xml = "<w:document><w:body><w:p><w:r><w:t>First line</w:t></w:r></w:p><w:p><w:r><w:t>Second</w:t></w:r></w:p></w:body></w:document>"
        val text = ClonerText.read("guide.docx", null, zip("word/document.xml" to xml, "word/styles.xml" to "<x>no</x>"))
        assertEquals("First line\nSecond", text)
    }

    @Test fun readsSlidesInOrder() {
        val text = ClonerText.read(
            "deck.pptx", null,
            zip("ppt/slides/slide10.xml" to "<a:p><a:t>Ten</a:t></a:p>", "ppt/slides/slide2.xml" to "<a:p><a:t>Two</a:t></a:p>"),
        )
        assertEquals("Two\n\nTen", text)
    }

    @Test fun brokenOfficeFileGivesNothing() {
        assertEquals("", ClonerText.read("bad.docx", null, "not a zip".toByteArray()))
    }

    @Test fun findsRelatedFilesOnThePhone() {
        val index = ArrowFilesIndex()
        fun add(id: String, name: String) = index.add(
            ArrowFile(id, name, "whatsapp", FileKind.of(name, null), "x", 1, 1, "", "a/$name", FileStatus.Ready),
        )
        add("1", "Rich Dad Poor Dad audiobook.mp3")
        add("2", "poor people.pdf")
        add("3", "invoice.pdf")
        assertEquals(listOf("1"), index.related("rich dad poor dad by robert kiyosaki").map { it.id })
        assertEquals(listOf("3"), index.related("invoice").map { it.id })
        assertTrue(index.related("the a").isEmpty())
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, body) ->
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
