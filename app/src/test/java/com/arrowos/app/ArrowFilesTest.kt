package com.arrowos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrowFilesTest {

    private fun file(
        id: String,
        name: String,
        source: String = "canva.com",
        savedAt: Long = 1,
        kind: FileKind = FileKind.of(name, null),
    ) = ArrowFile(
        id = id, name = name, source = source, kind = kind, mimeType = "", size = 10, savedAt = savedAt,
        originUrl = "https://$source/", relPath = ArrowFilesIndex.pathFor("guest", source, name),
        status = FileStatus.Ready,
    )

    @Test fun sourceIsTheSiteWithoutWwwOrMobilePrefix() {
        assertEquals("canva.com", ArrowFilesIndex.sourceOf("https://www.canva.com/design/1"))
        assertEquals("youtube.com", ArrowFilesIndex.sourceOf("https://m.youtube.com/watch?v=1"))
        assertEquals("other", ArrowFilesIndex.sourceOf("blob:nonsense"))
        assertEquals("other", ArrowFilesIndex.sourceOf(null))
    }

    @Test fun filesLiveInTheUsersFolderUnderTheirSite() {
        assertEquals("ArrowFiles/guest/websites/canva.com/a.pdf", ArrowFilesIndex.pathFor("guest", "canva.com", "a.pdf"))
        // Odd characters can't escape the folder.
        assertEquals("ArrowFiles/u_1/websites/bad_site/x.txt", ArrowFilesIndex.pathFor("u/1", "bad site", "x.txt"))
    }

    @Test fun kindComesFromMimeTypeThenExtension() {
        assertEquals(FileKind.Video, FileKind.of("clip.bin", "video/mp4"))
        assertEquals(FileKind.Image, FileKind.of("photo.JPG", null))
        assertEquals(FileKind.Document, FileKind.of("invoice.pdf", "application/octet-stream"))
        assertEquals(FileKind.Audio, FileKind.of("song.mp3", ""))
        assertEquals(FileKind.Other, FileKind.of("setup.apk", null))
    }

    @Test fun secondFileWithTheSameNameGetsANumber() {
        val index = ArrowFilesIndex()
        assertEquals("invoice.pdf", index.uniqueName("guest", "canva.com", "invoice.pdf"))
        index.add(file("1", "invoice.pdf"))
        assertEquals("invoice (2).pdf", index.uniqueName("guest", "canva.com", "invoice.pdf"))
        index.add(file("2", "invoice (2).pdf"))
        assertEquals("invoice (3).pdf", index.uniqueName("guest", "canva.com", "invoice.pdf"))
        // Same name from another site is fine: different folder.
        assertEquals("invoice.pdf", index.uniqueName("guest", "docs.google.com", "invoice.pdf"))
        // A file already on disk counts too.
        assertEquals("notes (2)", index.uniqueName("guest", "a.com", "notes") { it == "notes" })
    }

    @Test fun fileNamesAreMadeSafe() {
        assertEquals("evil.txt", ArrowFilesIndex.safeFileName("../../evil.txt"))
        assertEquals("a_b.pdf", ArrowFilesIndex.safeFileName("a:b.pdf"))
        assertEquals("download", ArrowFilesIndex.safeFileName("  "))
    }

    @Test fun newestFirstAndFiltersByKindAndSource() {
        val index = ArrowFilesIndex()
        index.add(file("1", "a.pdf", savedAt = 1))
        index.add(file("2", "b.mp4", source = "youtube.com", savedAt = 3))
        index.add(file("3", "c.png", savedAt = 2))
        assertEquals(listOf("2", "3", "1"), index.active.map { it.id })
        assertEquals(listOf("2"), index.filter(kind = FileKind.Video).map { it.id })
        assertEquals(listOf("3", "1"), index.filter(source = "canva.com").map { it.id })
        assertEquals(listOf("canva.com", "youtube.com"), index.sources)
    }

    @Test fun trashHidesFilesAndExpiresAfterThirtyDays() {
        val day = 24L * 60 * 60 * 1000
        val index = ArrowFilesIndex()
        index.add(file("1", "a.pdf"))
        index.moveToTrash("1", now = 0)
        assertTrue(index.active.isEmpty())
        assertEquals(1, index.trash.size)
        assertTrue(index.expiredTrash(now = 29 * day).isEmpty())
        assertEquals(listOf("1"), index.expiredTrash(now = 30 * day).map { it.id })
        index.restore("1")
        assertEquals(1, index.active.size)
        assertTrue(index.expiredTrash(now = 300 * day).isEmpty())
    }

    @Test fun findsByWordsInNameOrSite() {
        val index = ArrowFilesIndex()
        index.add(file("1", "Invoice-March.pdf"))
        index.add(file("2", "holiday.mp4", source = "youtube.com"))
        assertEquals(listOf("1"), index.find("canva invoice").map { it.id })
        assertEquals(listOf("2"), index.find("youtube").map { it.id })
        assertTrue(index.find("nothing here").isEmpty())
    }

    @Test fun encodeAndDecodeKeepEverything() {
        val index = ArrowFilesIndex()
        index.add(file("1", "tab\there.pdf"))
        index.add(file("2", "b.mp4").copy(status = FileStatus.Downloading, downloadId = 42))
        index.moveToTrash("1", now = 99)
        val back = ArrowFilesIndex.decode(index.encode())
        assertEquals(index.all, back.all)
        assertEquals("2", back.byDownloadId(42)?.id)
        assertNull(back.byDownloadId(7))
        assertTrue(ArrowFilesIndex.decode("garbage\nmore\tgarbage").all.isEmpty())
    }

    @Test fun filesSavedBeforeSignInMoveToTheAccount() {
        val index = ArrowFilesIndex()
        index.add(file("1", "a.pdf"))
        index.moveToUser("guest", "uid42")
        assertEquals("ArrowFiles/uid42/websites/canva.com/a.pdf", index.get("1")?.relPath)
        assertEquals("ArrowFiles/uid42", ArrowFilesIndex.userFolder("uid42"))
    }

    @Test fun onlyReadySmallFilesWaitForUpload() {
        val index = ArrowFilesIndex()
        index.add(file("1", "a.pdf", savedAt = 2))
        index.add(file("2", "b.pdf", savedAt = 1))
        index.add(file("3", "big.mp4").copy(size = ArrowFilesIndex.CLOUD_MAX_BYTES + 1))
        index.add(file("4", "c.pdf").copy(inCloud = true))
        index.add(file("5", "d.pdf").copy(status = FileStatus.Downloading))
        index.add(file("6", "e.pdf"))
        index.moveToTrash("6", now = 5)
        assertEquals(listOf("2", "1"), index.waitingForUpload().map { it.id })
        assertTrue(index.tooBigForCloud(index.get("3")!!))
    }

    @Test fun cloudFilesShowUpOnANewPhone() {
        val index = ArrowFilesIndex()
        index.add(file("1", "a.pdf").copy(inCloud = true))
        val fromCloud = listOf(
            file("1", "a.pdf").copy(inCloud = true),
            file("2", "b.pdf").copy(inCloud = true),
            file("3", "phone-only.pdf"),
        )
        assertEquals(1, index.mergeFromCloud(fromCloud))
        assertEquals(setOf("1", "2"), index.all.map { it.id }.toSet())
    }

    @Test fun cloudFlagSurvivesSaving() {
        val index = ArrowFilesIndex()
        index.add(file("1", "a.pdf").copy(inCloud = true))
        assertTrue(ArrowFilesIndex.decode(index.encode()).get("1")!!.inCloud)
    }
}
