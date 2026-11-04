package com.arrowos.app

import java.net.URI
import java.util.Locale

/** What kind of file it is; drives the type filter in Arrow Files. */
enum class FileKind(val label: String, val letter: String, val colorHex: String) {
    Video("Videos", "V", "#E53935"),
    Image("Images", "I", "#8E24AA"),
    Document("Documents", "D", "#1E88E5"),
    Audio("Audio", "A", "#43A047"),
    Other("Other", "?", "#6B7590");

    companion object {
        private val videoExt = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "avi", "flv", "ts")
        private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")
        private val audioExt = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr")
        private val docExt = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "csv", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp", "epub", "md",
        )

        /** The kind from the file's mime type when it is clear, otherwise from its extension. */
        fun of(name: String, mimeType: String?): FileKind {
            val mime = mimeType?.lowercase(Locale.ROOT).orEmpty()
            when {
                mime.startsWith("video/") -> return Video
                mime.startsWith("image/") -> return Image
                mime.startsWith("audio/") -> return Audio
                mime == "application/pdf" || mime.startsWith("text/") -> return Document
            }
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (ext) {
                in videoExt -> Video
                in imageExt -> Image
                in audioExt -> Audio
                in docExt -> Document
                else -> Other
            }
        }
    }
}

enum class FileStatus { Downloading, Ready, Failed }

/**
 * One file in Arrow Files. [relPath] is where it sits under the app's own files folder,
 * e.g. "ArrowFiles/guest/websites/canva.com/invoice.pdf". The cloud copy will use the same path.
 */
data class ArrowFile(
    val id: String,
    val name: String,
    /** The website (or, later, cloud app) it came from: "canva.com". */
    val source: String,
    val kind: FileKind,
    val mimeType: String,
    val size: Long,
    /** Milliseconds since 1970. */
    val savedAt: Long,
    /** The page or link it was downloaded from. */
    val originUrl: String,
    val relPath: String,
    val status: FileStatus,
    /** The phone's download job while [status] is Downloading, else -1. */
    val downloadId: Long = -1,
    /** When it was moved to Trash, or null when it isn't in Trash. */
    val trashedAt: Long? = null,
    /** A copy is in the user's cloud folder (Supabase Storage), under the same [relPath]. */
    val inCloud: Boolean = false,
) {
    val inTrash: Boolean get() = trashedAt != null
}

/**
 * The Arrow Files list: every file downloaded inside Arrow Os, newest first, with the
 * rules for where each file goes. Pure Kotlin, no Android types, so plain JVM tests cover it.
 *
 * Layout, the same on the phone and (later) in the cloud:
 * ArrowFiles/<user id>/websites/<site>/<file name>
 * ArrowFiles/<user id>/apps/<app>/<file name>      (for cloud apps, later)
 */
class ArrowFilesIndex {

    private val files = mutableListOf<ArrowFile>()

    /** Everything, Trash included, newest first. */
    val all: List<ArrowFile> get() = files.sortedByDescending { it.savedAt }

    /** What the main list shows: not in Trash, newest first. */
    val active: List<ArrowFile> get() = all.filter { !it.inTrash }

    val trash: List<ArrowFile> get() = all.filter { it.inTrash }

    /** The sites/apps that have at least one file outside Trash, most files first. */
    val sources: List<String>
        get() = active.groupingBy { it.source }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    fun get(id: String): ArrowFile? = files.firstOrNull { it.id == id }

    fun byDownloadId(downloadId: Long): ArrowFile? =
        files.firstOrNull { it.downloadId == downloadId && it.status == FileStatus.Downloading }

    /** Files outside Trash, optionally only of one [kind] and/or from one [source]. */
    fun filter(kind: FileKind? = null, source: String? = null): List<ArrowFile> =
        active.filter { (kind == null || it.kind == kind) && (source == null || it.source == source) }

    /** Files whose name or source contains every word of [spoken], e.g. "canva invoice". */
    fun find(spoken: String): List<ArrowFile> {
        val words = simplify(spoken).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return active.filter { f ->
            val hay = simplify("${f.name} ${f.source}")
            words.all { it in hay }
        }
    }

    fun add(file: ArrowFile) {
        files.removeAll { it.id == file.id }
        files.add(file)
    }

    fun update(id: String, change: (ArrowFile) -> ArrowFile): ArrowFile? {
        val i = files.indexOfFirst { it.id == id }
        if (i < 0) return null
        files[i] = change(files[i])
        return files[i]
    }

    fun moveToTrash(id: String, now: Long) = update(id) { it.copy(trashedAt = now) }

    fun restore(id: String) = update(id) { it.copy(trashedAt = null) }

    fun remove(id: String): ArrowFile? {
        val f = get(id) ?: return null
        files.remove(f)
        return f
    }

    /** Files that have sat in Trash for [TRASH_DAYS] days; the caller deletes them from disk. */
    fun expiredTrash(now: Long): List<ArrowFile> =
        files.filter { it.trashedAt != null && now - it.trashedAt >= TRASH_DAYS * DAY_MS }

    /**
     * A name for [wanted] that no other file in the same folder uses:
     * "invoice.pdf", then "invoice (2).pdf", "invoice (3).pdf"...
     * [alsoTaken] lets the caller add names that exist on disk but not in the list.
     */
    fun uniqueName(userId: String, source: String, wanted: String, alsoTaken: (String) -> Boolean = { false }): String {
        val name = safeFileName(wanted)
        val folder = folderFor(userId, source)
        val taken = files.map { it.relPath }.toSet()
        fun free(n: String) = "$folder/$n" !in taken && !alsoTaken(n)
        if (free(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (!free("$base ($i)$ext")) i++
        return "$base ($i)$ext"
    }

    /** Ready files outside Trash with no cloud copy yet, small enough for the cloud. Oldest first. */
    fun waitingForUpload(): List<ArrowFile> =
        files.filter { it.status == FileStatus.Ready && !it.inCloud && !it.inTrash && it.size in 1..CLOUD_MAX_BYTES }
            .sortedBy { it.savedAt }

    /** Too big for the free cloud plan: these stay on the phone only. */
    fun tooBigForCloud(f: ArrowFile) = f.size > CLOUD_MAX_BYTES

    /**
     * Adds the files the account has in the cloud that this phone doesn't know yet
     * (e.g. on a new phone). Returns how many were added.
     */
    fun mergeFromCloud(cloudFiles: List<ArrowFile>): Int {
        var added = 0
        cloudFiles.filter { it.inCloud && it.status == FileStatus.Ready }.forEach { f ->
            if (get(f.id) == null) {
                files.add(f.copy(downloadId = -1))
                added++
            }
        }
        return added
    }

    /**
     * Hands every file from [fromUser]'s folder to [toUser], e.g. the files saved before the
     * first sign-in. The caller moves the folder on disk the same way.
     */
    fun moveToUser(fromUser: String, toUser: String) {
        val from = "$ROOT/${safeFolder(fromUser)}/"
        val to = "$ROOT/${safeFolder(toUser)}/"
        files.replaceAll { f -> if (f.relPath.startsWith(from)) f.copy(relPath = to + f.relPath.removePrefix(from)) else f }
    }

    /** One file per line, fields separated by tabs, with \t \n \\ escaped. */
    fun encode(): String = files.joinToString("\n") { f ->
        listOf(
            VERSION, f.id, f.name, f.source, f.kind.name, f.mimeType, f.size.toString(), f.savedAt.toString(),
            f.originUrl, f.relPath, f.status.name, f.downloadId.toString(), f.trashedAt?.toString().orEmpty(),
            if (f.inCloud) "1" else "0",
        ).joinToString("\t") { escape(it) }
    }

    companion object {
        const val ROOT = "ArrowFiles"
        /** Until email sign-in arrives, everything belongs to the one person using this phone. */
        const val LOCAL_USER = "guest"
        const val TRASH_DAYS = 30L
        /** The free Supabase plan takes files up to 50 MB. */
        const val CLOUD_MAX_BYTES = 50L * 1024 * 1024
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val VERSION = "1"

        fun decode(text: String?): ArrowFilesIndex {
            val index = ArrowFilesIndex()
            text.orEmpty().lineSequence().forEach { line ->
                val p = line.split('\t').map { unescape(it) }
                if (p.size < 13 || p[0] != VERSION) return@forEach
                val file = try {
                    ArrowFile(
                        id = p[1], name = p[2], source = p[3], kind = FileKind.valueOf(p[4]), mimeType = p[5],
                        size = p[6].toLong(), savedAt = p[7].toLong(), originUrl = p[8], relPath = p[9],
                        status = FileStatus.valueOf(p[10]), downloadId = p[11].toLong(), trashedAt = p[12].toLongOrNull(),
                        inCloud = p.getOrNull(13) == "1",
                    )
                } catch (e: IllegalArgumentException) {
                    return@forEach
                }
                index.files.add(file)
            }
            return index
        }

        /** "https://www.canva.com/design/1" → "canva.com"; "m.youtube.com" → "youtube.com". */
        fun sourceOf(url: String?): String {
            val host = try {
                URI(url.orEmpty().trim()).host
            } catch (e: Exception) {
                null
            }?.lowercase(Locale.ROOT)
            if (host.isNullOrBlank()) return "other"
            return host.removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
        }

        fun folderFor(userId: String, source: String) =
            "$ROOT/${safeFolder(userId)}/websites/${safeFolder(source)}"

        fun pathFor(userId: String, source: String, name: String) = "${folderFor(userId, source)}/$name"

        fun userFolder(userId: String) = "$ROOT/${safeFolder(userId)}"

        private fun safeFolder(s: String) =
            s.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').ifBlank { "other" }

        /** Strips folder parts and characters Android or the cloud can't store. */
        fun safeFileName(name: String): String {
            val leaf = name.substringAfterLast('/').substringAfterLast('\\')
            val clean = leaf.replace(Regex("[\\u0000-\\u001f\"*:<>?|]"), "_").trim().trim('.')
            return clean.take(120).ifBlank { "download" }
        }

        private fun simplify(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

        private fun unescape(s: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> out.append('\t')
                        'n' -> out.append('\n')
                        else -> out.append(s[i + 1])
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}
