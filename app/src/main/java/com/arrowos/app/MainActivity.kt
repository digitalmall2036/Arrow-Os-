package com.arrowos.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.arrowos.app.databinding.ActivityMainBinding
import com.arrowos.app.databinding.ItemAppTileBinding
import java.util.Locale

class MainActivity : AppCompatActivity(), VoiceController.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var voice: VoiceController
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** The app currently open in the browser, or null on the home screen. */
    private var currentApp: ArrowApp? = null

    /** The user turned the mic off with the button; don't auto-restart it on resume. */
    private var userMuted = false

    private lateinit var web: WebActions
    private val handler = Handler(Looper.getMainLooper())

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else {
            binding.voiceStatus.text = getString(R.string.voice_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voice = VoiceController(this, this)
        web = WebActions(binding.webView)
        setupHomeScreen()
        setupBrowser()
        setupVoiceBar()
        setupBackHandling()
        setupTts()

        showHome()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        if (!userMuted) ensureMicPermissionThenListen()
    }

    override fun onPause() {
        // Always-listening only while Arrow Os is on screen.
        voice.stop()
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        voice.destroy()
        tts?.shutdown()
        binding.webView.destroy()
        super.onDestroy()
    }

    // ---------- Home screen ----------

    private fun setupHomeScreen() {
        val inflater = LayoutInflater.from(this)
        ArrowApp.entries.forEach { app ->
            val tile = ItemAppTileBinding.inflate(inflater, binding.appGrid, false)
            tile.appIcon.text = app.initial
            tile.appIcon.backgroundTintList = ColorStateList.valueOf(Color.parseColor(app.colorHex))
            tile.appLabel.text = app.label
            tile.root.contentDescription = getString(R.string.open_app, app.label)
            tile.root.setOnClickListener { openApp(app) }
            binding.appGrid.addView(tile.root)
        }
    }

    private fun showHome() {
        currentApp = null
        binding.homeScreen.isVisible = true
        binding.browserScreen.isVisible = false
        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank")
        binding.webView.clearHistory()
    }

    // ---------- In-app browser ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupBrowser() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(view, request.url.toString())

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                binding.pageProgress.isVisible = true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                binding.pageProgress.isVisible = false
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.pageProgress.progress = newProgress
                binding.pageProgress.isVisible = newProgress < 100
            }

            // Full-screen video (YouTube, TikTok) stays inside Arrow Os too.
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                exitFullscreen()
                fullscreenView = view
                fullscreenCallback = callback
                binding.fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER),
                )
                binding.fullscreenContainer.isVisible = true
            }

            override fun onHideCustomView() = exitFullscreen()
        }

        binding.browserBack.setOnClickListener { goBack() }
        binding.browserHome.setOnClickListener { showHome() }
        binding.browserReload.setOnClickListener { binding.webView.reload() }
    }

    /** Keeps every link inside Arrow Os. Returns true when the WebView should NOT load [url] itself. */
    private fun handleUrl(view: WebView, url: String): Boolean {
        if (url.startsWith("http://") || url.startsWith("https://")) return false
        if (url.startsWith("intent://")) {
            // Sites try to bounce to the real app; use their web fallback instead.
            val fallback = try {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
            } catch (e: Exception) {
                null
            }
            if (fallback != null && fallback.startsWith("http")) {
                view.loadUrl(fallback)
                return true
            }
        }
        // fb://, instagram://, snssdk://, market:// ... would leave Arrow Os, so block them.
        return true
    }

    private fun exitFullscreen() {
        fullscreenView?.let { binding.fullscreenContainer.removeView(it) }
        binding.fullscreenContainer.isVisible = false
        fullscreenCallback?.onCustomViewHidden()
        fullscreenView = null
        fullscreenCallback = null
    }

    private fun openApp(app: ArrowApp, url: String = app.homeUrl) {
        val switching = currentApp != app
        currentApp = app
        binding.homeScreen.isVisible = false
        binding.browserScreen.isVisible = true
        binding.browserTitle.text = app.label
        binding.webView.loadUrl(url)
        if (switching) binding.webView.clearHistory()
    }

    private fun goBack() {
        when {
            fullscreenView != null -> exitFullscreen()
            binding.browserScreen.isVisible && binding.webView.canGoBack() -> binding.webView.goBack()
            binding.browserScreen.isVisible -> showHome()
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.homeScreen.isVisible && fullscreenView == null) {
                    // On the Arrow Os home screen, back leaves the app as usual.
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                } else {
                    goBack()
                }
            }
        })
    }

    // ---------- Voice ----------

    private fun setupVoiceBar() {
        binding.micButton.setOnClickListener {
            if (voice.continuous) {
                userMuted = true
                voice.stop()
                binding.voiceStatus.text = getString(R.string.voice_off)
            } else {
                userMuted = false
                ensureMicPermissionThenListen()
            }
        }
        binding.voiceStatus.text = getString(R.string.voice_hint)

        // Typing is the second way to give commands; it goes through the same parser as speech.
        binding.commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitTypedCommand()
                true
            } else {
                false
            }
        }
        binding.sendButton.setOnClickListener { submitTypedCommand() }
        // Don't let the mic pick up stray words while the user is typing.
        binding.commandInput.setOnFocusChangeListener { _, hasFocus -> voice.paused = hasFocus }
    }

    private fun submitTypedCommand() {
        val text = binding.commandInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.commandInput.text.clear()
        binding.commandInput.clearFocus()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.commandInput.windowToken, 0)
        handleInput(text, typed = true)
    }

    /**
     * Runs what the user said or typed. Only input that starts with "tap" or "search"
     * does anything. One input can hold several steps
     * ("tap scroll to the second video and play it"); they run one after another.
     */
    private fun handleInput(text: String, typed: Boolean) {
        val commands = CommandParser.parseAll(text)
        if (commands.singleOrNull() is Command.Ignored) {
            // Not a command: do nothing. Only a typed line gets a hint, since the
            // always-on mic hears plenty of talk that isn't meant for Arrow Os.
            binding.voiceStatus.text = getString(R.string.say_ignored, text.trim())
            if (typed) toast(getString(R.string.say_ignored, text.trim()))
            return
        }
        binding.voiceStatus.text = "“$text”"
        handler.removeCallbacksAndMessages(null) // a new instruction replaces an unfinished sequence
        runSequence(commands, 0)
    }

    private fun runSequence(commands: List<Command>, index: Int) {
        val command = commands.getOrNull(index) ?: return
        perform(command)
        if (index + 1 < commands.size) {
            // Give a new page time to load before acting on it.
            val delay = if (command.loadsPage()) 3000L else 800L
            handler.postDelayed({ runSequence(commands, index + 1) }, delay)
        }
    }

    private fun Command.loadsPage() = this is Command.Open || this is Command.Search || this is Command.Home ||
        this is Command.Back || this is Command.Tap || this is Command.TapNth || this is Command.Reload

    private fun ensureMicPermissionThenListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (!voice.continuous) voice.start()
    }

    override fun onListeningChanged(listening: Boolean) {
        binding.micButton.isActivated = listening || voice.continuous
        binding.micButton.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (voice.continuous) R.color.mic_on else R.color.mic_off),
        )
        if (listening) binding.voiceStatus.text = getString(R.string.voice_listening)
    }

    override fun onPartialText(text: String) {
        binding.voiceStatus.text = "“$text”"
    }

    override fun onPhrase(text: String) = handleInput(text, typed = false)

    override fun onVoiceUnavailable() {
        binding.voiceStatus.text = getString(R.string.voice_unavailable)
    }

    private val inApp: Boolean get() = binding.browserScreen.isVisible

    private fun perform(command: Command) {
        when (command) {
            is Command.Open -> {
                confirm(getString(R.string.say_opening, command.app.label))
                openApp(command.app)
            }
            is Command.Search -> {
                val app = command.app ?: currentApp ?: ArrowApp.YOUTUBE
                confirm(getString(R.string.say_searching, command.query, app.label))
                openApp(app, app.searchUrl(command.query))
            }
            Command.Back -> {
                confirm(getString(R.string.say_back))
                goBack()
            }
            Command.Forward -> inPage {
                if (binding.webView.canGoForward()) binding.webView.goForward() else toast("Nothing to go forward to")
            }
            Command.Home -> {
                confirm(getString(R.string.say_home))
                exitFullscreen()
                showHome()
            }
            Command.Reload -> inPage {
                confirm(getString(R.string.say_reload))
                binding.webView.reload()
            }

            Command.ScrollDown -> inPage { toast("Scrolling down"); web.scroll(0.8) }
            Command.ScrollUp -> inPage { toast("Scrolling up"); web.scroll(-0.8) }
            Command.ScrollTop -> inPage { toast("Back to the top"); web.scrollToTop() }
            Command.ScrollBottom -> inPage { toast("To the bottom"); web.scrollToBottom() }
            // The eyes look for the page's own Next/Previous button first; feeds without
            // one (TikTok, Shorts, Reels) just move on to the next video.
            Command.NextItem -> inPage {
                web.tap(TapVocabulary.next) { name ->
                    if (name != null) confirm("Tapped $name") else { toast("Next"); web.nextItem(forward = true) }
                }
            }
            Command.PreviousItem -> inPage {
                web.tap(TapVocabulary.previous) { name ->
                    if (name != null) confirm("Tapped $name") else { toast("Previous"); web.nextItem(forward = false) }
                }
            }
            Command.ZoomIn -> inPage { binding.webView.zoomIn() }
            Command.ZoomOut -> inPage { binding.webView.zoomOut() }

            Command.Play -> inPage { web.play { ok -> toast(if (ok) "Playing" else "No video on this page") } }
            Command.Pause -> inPage { web.pause { ok -> toast(if (ok) "Paused" else "No video on this page") } }
            Command.Mute -> inPage { web.mute { ok -> toast(if (ok) "Muted" else "No video on this page") } }
            Command.Unmute -> inPage { web.unmute { ok -> toast(if (ok) "Sound on" else "No video on this page") } }

            is Command.Tap -> {
                if (!inApp) {
                    toast("Open an app first, for example \"tap open TikTok\"")
                    return
                }
                binding.voiceStatus.text = getString(R.string.scanning, command.spoken)
                web.tap(command.labels) { name ->
                    if (name != null) {
                        confirm("Tapped ${command.spoken}")
                        binding.voiceStatus.text = getString(R.string.tapped, name)
                    } else {
                        toast("Couldn't find \"${command.spoken}\" on this page")
                        binding.voiceStatus.text = getString(R.string.not_found, command.spoken)
                    }
                }
            }
            is Command.TapNth -> inPage {
                web.tapNth(command.n) { ok ->
                    if (ok) confirm("Opening number ${command.n}") else toast("Couldn't find item ${command.n} on this page")
                }
            }
            is Command.TypeText -> {
                if (!inApp) {
                    perform(Command.Search(command.text, null))
                    return
                }
                web.type(command.text, command.submit) { ok ->
                    if (ok) toast("Typed \"${command.text}\"") else perform(Command.Search(command.text, null))
                }
            }
            Command.PressEnter -> inPage { web.pressEnter() }

            Command.Help -> showHelp()
            Command.StopListening -> {
                confirm(getString(R.string.say_stop))
                userMuted = true
                voice.stop()
                binding.voiceStatus.text = getString(R.string.voice_off)
            }
            is Command.ScrollToNth -> inPage {
                web.scrollToNth(command.n) { ok -> toast(if (ok) "Number ${command.n}" else "Couldn't find item ${command.n}") }
            }
            is Command.Ignored -> Unit
        }
    }

    /** Runs [action] only when an app is open; on the home screen, explains what to do. */
    private inline fun inPage(action: () -> Unit) {
        if (inApp) action() else toast("Open an app first, for example \"tap open TikTok\"")
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Start every command with \"tap\" or \"search\"")
            .setMessage(
                """
                SEARCH finds things:
                search Tom and Jerry · search Jeff Bezos · search cats on TikTok

                TAP does anything on screen:
                tap open YouTube · tap home · tap back
                tap like this video · tap comment · tap share · tap follow · tap subscribe
                tap play the second video on this page
                tap scroll to the second video and play it
                tap next video · tap previous video · tap scroll down · tap scroll to the top
                tap pause · tap play · tap mute · tap unmute · tap skip ad
                tap Shorts · tap Following (any button or link by its name)
                tap type happy birthday and send
                tap stop listening

                Chain them: tap open TikTok then search cats

                Anything that doesn't start with tap or search is ignored.
                """.trimIndent(),
            )
            .setPositiveButton("OK", null)
            .show()
    }

    /** Shows what Arrow Os understood, and says it out loud. */
    private fun confirm(message: String) {
        toast(message)
        val engine = tts
        if (ttsReady && engine != null) {
            voice.paused = true // don't let the mic hear Arrow Os talking
            val result = engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "arrow-confirm")
            if (result == TextToSpeech.ERROR) voice.paused = false
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = resumeMic()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = resumeMic()
            override fun onError(utteranceId: String?, errorCode: Int) = resumeMic()
        })
    }

    private fun resumeMic() = runOnUiThread { voice.paused = false }
}
