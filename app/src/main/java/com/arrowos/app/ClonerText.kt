package com.arrowos.app

import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Reads the words inside a file the teacher uploads to Arrow Cloner, so the worker can
 * learn from it: plain text, web pages, subtitles, and Word, PowerPoint and Excel files.
 * Pictures, videos and PDFs are kept in Arrow Files but their words can't be read yet.
 *
 * Pure Kotlin (no Android), covered by unit tests.
 */
object ClonerText {

    /**
     * Splits [text] into pieces the phone's voice can say, at most [max] characters each,
     * breaking at sentence ends where it can. Web links are said as "a link".
     */
    fun speechChunks(text: String, max: Int = 3000): List<String> {
        val clean = text.trim()
            .replace(Regex("https?://\\S+"), "a link")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\s*\\n\\s*"), ".\n")
            .replace(Regex("([.!?:;])\\.\\n"), "\$1\n")
            .trim()
        if (clean.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var rest = clean
        while (rest.length > max) {
            val window = rest.substring(0, max)
            var cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf('\n'), window.lastIndexOf("? "), window.lastIndexOf("! "))
            if (cut < max / 3) cut = window.lastIndexOf(' ')
            if (cut <= 0) cut = max - 1
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out.filter { it.isNotBlank() }
    }


    /** At most this many characters are kept from one file. */
    const val MAX_CHARS = 200_000

    private val plainText = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "srt", "vtt", "log", "rtf",
        "js", "ts", "css", "kt", "java", "py", "php", "rb", "go", "c", "cpp", "h", "sh", "yml", "yaml", "ini", "sql",
    )

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** Whether the words of a file called [name] can be read. */
    fun canRead(name: String, mime: String?): Boolean {
        val ext = extension(name)
        return ext in plainText || ext in setOf("docx", "pptx", "xlsx") || (mime?.startsWith("text/") == true)
    }

    /** The words in [bytes] of a file called [name], or "" when they can't be read. */
    fun read(name: String, mime: String?, bytes: ByteArray): String {
        val text = try {
            when (extension(name)) {
                "docx" -> fromOffice(bytes) { it == "word/document.xml" }
                "pptx" -> fromOffice(bytes) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                "xlsx" -> fromOffice(bytes) { it == "xl/sharedStrings.xml" }
                "html", "htm" -> stripTags(String(bytes, Charsets.UTF_8))
                else -> if (canRead(name, mime)) String(bytes, Charsets.UTF_8) else ""
            }
        } catch (e: Exception) {
            ""
        }
        return text.replace("\u0000", "").trim().take(MAX_CHARS)
    }

    /** Office files are zip files of XML; the words are in a few of those XML files. */
    private fun fromOffice(bytes: ByteArray, wanted: (String) -> Boolean): String {
        val parts = sortedMapOf<String, String>(compareBy({ it.length }, { it }))
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && wanted(entry.name)) parts[entry.name] = String(zip.readBytes(), Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        return parts.values.joinToString("\n\n") { xml ->
            stripTags(
                xml.replace(Regex("</w:p>|</a:p>|</si>|<w:br/>|<w:tab/>"), "\n"),
            )
        }
    }

    fun stripTags(html: String): String =
        html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")
            .lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter { it.isNotEmpty() }
            .joinToString("\n")
}
