package com.arrowos.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wraps Android's built-in [SpeechRecognizer] and keeps it listening in a loop:
 * every time a phrase ends (or the recognizer times out), it starts again.
 * Call [start] when Arrow Os comes to the foreground and [stop] when it leaves.
 */
class VoiceController(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onListeningChanged(listening: Boolean)
        fun onPartialText(text: String)
        fun onPhrase(text: String)
        fun onVoiceUnavailable()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** True while the always-listening loop should keep running. */
    var continuous: Boolean = false
        private set

    /** Set while Arrow Os is speaking, so it doesn't hear itself. */
    var paused: Boolean = false
        set(value) {
            field = value
            if (value) cancelCurrent() else if (continuous) scheduleRestart(250)
        }

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!isAvailable) {
            listener.onVoiceUnavailable()
            return
        }
        continuous = true
        listenNow()
    }

    fun stop() {
        continuous = false
        handler.removeCallbacksAndMessages(null)
        cancelCurrent()
        listener.onListeningChanged(false)
    }

    fun destroy() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    private fun cancelCurrent() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
    }

    private fun scheduleRestart(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (continuous && !paused) listenNow() }, delayMs)
    }

    private fun listenNow() {
        if (paused) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(recognitionListener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            r.cancel()
            r.startListening(intent)
        } catch (e: Exception) {
            // Recognizer got into a bad state; rebuild it on the next attempt.
            recognizer?.destroy()
            recognizer = null
            if (continuous) scheduleRestart(1000)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onListeningChanged(true)
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?.let(listener::onPartialText)
        }

        override fun onResults(results: Bundle?) {
            listener.onListeningChanged(false)
            val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            // Prefer the first candidate the parser understands; otherwise pass the top one.
            val best = candidates.firstOrNull { CommandParser.parse(it) !is Command.Unknown }
                ?: candidates.firstOrNull()
            if (!best.isNullOrBlank()) listener.onPhrase(best)
            if (continuous) scheduleRestart(300)
        }

        override fun onError(error: Int) {
            listener.onListeningChanged(false)
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    continuous = false
                    listener.onVoiceUnavailable()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> if (continuous) scheduleRestart(800)
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> if (continuous) scheduleRestart(2000)
                // No match / speech timeout are normal in an always-listening loop.
                else -> if (continuous) scheduleRestart(300)
            }
        }
    }
}
