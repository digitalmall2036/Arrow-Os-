package com.arrowos.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudFilesTest {
    @Test fun pathPartsAreEncodedButSlashesKept() {
        assertEquals(
            "ArrowFiles/u1/websites/canva.com/my%20invoice%20%282%29.pdf",
            CloudFiles.encodePath("ArrowFiles/u1/websites/canva.com/my invoice (2).pdf"),
        )
    }
}
