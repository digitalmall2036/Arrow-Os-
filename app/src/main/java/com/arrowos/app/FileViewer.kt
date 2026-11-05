package com.arrowos.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * Opens Arrow Files inside Arrow Os: photos, videos, music, PDFs and text. Files are never
 * handed to another app on the phone. Everything is sized for the screen so big photos and
 * long PDFs stay light on 2 GB phones.
 */
class FileViewer(private val context: Context, private val body: FrameLayout) {

    private var video: VideoView? = null
    private var pdf: PdfRenderer? = null
    private var pdfFd: ParcelFileDescriptor? = null

    /** Shows [file]; returns false when Arrow Os can't show this kind of file yet. */
    fun show(file: File, kind: FileKind, mimeType: String, name: String): Boolean {
        close()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            kind == FileKind.Image && ext != "svg" -> showImage(file)
            kind == FileKind.Video || kind == FileKind.Audio -> showMedia(file, audio = kind == FileKind.Audio)
            mimeType == "application/pdf" || ext == "pdf" -> showPdf(file)
            mimeType.startsWith("text/") || ext in textExt -> showText(file)
            else -> false
        }
    }

    /** Stops playback and frees memory. */
    fun close() {
        video?.stopPlayback()
        video = null
        try {
            pdf?.close()
            pdfFd?.close()
        } catch (e: Exception) {
            // already closed
        }
        pdf = null
        pdfFd = null
        body.removeAllViews()
    }

    private fun showImage(file: File): Boolean {
        val bitmap = decodeScaled(file, maxSide()) ?: return false
        val view = ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        body.addView(view, match())
        return true
    }

    private fun showMedia(file: File, audio: Boolean): Boolean {
        val v = VideoView(context)
        val holder = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        holder.addView(v, FrameLayout.LayoutParams(match().width, if (audio) 1 else match().height, Gravity.CENTER))
        val controls = MediaController(context)
        controls.setAnchorView(holder)
        v.setMediaController(controls)
        v.setVideoURI(Uri.fromFile(file))
        v.setOnPreparedListener {
            v.start()
            controls.show(0)
        }
        v.setOnErrorListener { _, _, _ -> false }
        if (audio) {
            holder.addView(
                TextView(context).apply {
                    text = file.name
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(match().width, match().height, Gravity.CENTER),
            )
            holder.setOnClickListener { controls.show(0) }
        }
        body.addView(holder, match())
        video = v
        return true
    }

    private fun showPdf(file: File): Boolean {
        val fd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            return false
        }
        val renderer = try {
            PdfRenderer(fd)
        } catch (e: Exception) {
            fd.close()
            return false
        }
        pdfFd = fd
        pdf = renderer
        val width = body.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setBackgroundColor(Color.parseColor("#2A2F3D"))
            adapter = PdfPages(renderer, width)
        }
        body.addView(list, match())
        return true
    }

    private fun showText(file: File): Boolean {
        val text = try {
            file.inputStream().use { input ->
                val bytes = ByteArray(minOf(file.length(), MAX_TEXT_BYTES).toInt())
                var read = 0
                while (read < bytes.size) {
                    val n = input.read(bytes, read, bytes.size - read)
                    if (n < 0) break
                    read += n
                }
                String(bytes, 0, read)
            }
        } catch (e: Exception) {
            return false
        }
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val scroll = ScrollView(context)
        scroll.addView(
            TextView(context).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 14f
                setTextIsSelectable(true)
                setPadding(pad, pad, pad, pad)
            },
        )
        body.addView(scroll, match())
        return true
    }

    /** One PDF page at a time, drawn at screen width; pages off screen are let go. */
    private class PdfPages(private val renderer: PdfRenderer, private val width: Int) :
        RecyclerView.Adapter<PdfPages.Holder>() {

        class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)

        override fun getItemCount() = renderer.pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val image = ImageView(parent.context).apply {
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = 12 }
            }
            return Holder(image)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val bitmap = try {
                renderer.openPage(position).usePage { page ->
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            } catch (e: Exception) {
                null
            }
            holder.image.setImageBitmap(bitmap)
        }

        override fun onViewRecycled(holder: Holder) {
            holder.image.setImageDrawable(null)
        }
    }

    private fun maxSide(): Int {
        val m = context.resources.displayMetrics
        return maxOf(m.widthPixels, m.heightPixels)
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    companion object {
        private const val MAX_TEXT_BYTES = 512L * 1024
        private val textExt = setOf("txt", "csv", "md", "json", "xml", "html", "htm", "log", "srt", "vtt")

        /** Decodes a photo no bigger than [maxSide] pixels, so a 12 MP photo doesn't fill memory. */
        fun decodeScaled(file: File, maxSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}

private inline fun <T> PdfRenderer.Page.usePage(block: (PdfRenderer.Page) -> T): T = try {
    block(this)
} finally {
    close()
}
