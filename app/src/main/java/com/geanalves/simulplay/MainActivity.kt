package com.geanalves.simulplay

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import kotlin.math.abs

private val LILAC = Color.parseColor("#B79CFF")

@SuppressLint("SetJavaScriptEnabled")
class MainActivity : Activity(), Link.Listener {
    private data class State(val id: Int, val playing: Boolean, val pos: Long, val at: Long)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var link: Link
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var web: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var footer: TextView
    private lateinit var audio: AudioReceiver

    private var host: String? = null
    private var phoneName = ""
    private var kind = ""            // "media", "youtube" ou ""
    private var currentId = -1
    private var state: State? = null
    @Volatile private var adjMs = 0
    private var reportedDur = false
    private var endedSent = false
    private var seekCooldownUntil = 0L
    private var startScheduled = false
    private var lastSpeed = 1f
    @Volatile private var autoSync = true
    private var hasVideo = false

    // estado do YouTube vindo da página
    private var ytState = -1
    private var ytPos = 0L
    private var ytPosAt = 0L
    private var ytDur = 0L
    private var ytLoaded = false
    private var ytPageReady = false
    private var ytPending: String? = null

    private val myName: String by lazy {
        (try { Settings.Global.getString(contentResolver, "device_name") } catch (_: Exception) { null })
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        hideSystemBars()

        player = ExoPlayer.Builder(this).build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage("pt")
            .setPreferredAudioLanguages("pt", "por")
            .build()
        player.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            false,
        )
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                hasVideo = videoSize.width > 0
                updateOverlay()
            }
            override fun onPlayerError(error: PlaybackException) {
                link.send(JSONObject().put("t", "error").put("msg", "não consegui tocar (${error.errorCodeName})"))
                statusText.text = "Não consegui tocar esse link"
            }
        })

        audio = AudioReceiver({ link.offset }, { adjMs })
        link = Link(this, myName, this)
        link.start()
        ui.post(tick)
        Updater.listener = { refreshUpdate() }
        ui.post(updateCheck)
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        ui.removeCallbacks(updateCheck)
        Updater.listener = null
        link.stop()
        audio.stop()
        player.release()
        web.destroy()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    // ------------------------------------------------------------------ tela

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(Color.BLACK)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    ytPageReady = true
                    ytPending?.let { evaluateJavascript("load('$it')", null) }
                    ytPending = null
                }
            }
            addJavascriptInterface(YtBridge(), "Android")
            isFocusable = false
            visibility = View.GONE
        }
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#22137A"), Color.parseColor("#07061C"), Color.parseColor("#2A0F6B")),
            )
        }
        overlay.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(130), dp(130)))
        val brand = android.text.SpannableStringBuilder("SimulPlay").apply {
            setSpan(android.text.style.ForegroundColorSpan(LILAC), 5, 9, 0)
        }
        overlay.addView(TextView(this).apply {
            text = brand; textSize = 56f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        overlay.addView(TextView(this).apply {
            text = "Receba a transmissão em sua TV"; textSize = 22f
            setTextColor(Color.parseColor("#D6D3F5")); gravity = Gravity.CENTER
        })
        statusText = TextView(this).apply {
            textSize = 20f; gravity = Gravity.CENTER
            setPadding(dp(28), dp(12), dp(28), dp(12))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, e: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, e: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { paintStatus() }
            })
        }
        overlay.addView(statusText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        titleText = TextView(this).apply {
            textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(60), dp(24), dp(60), 0); maxLines = 2
        }
        overlay.addView(titleText)
        val tag = android.text.SpannableStringBuilder("Conecte • Transmita • Aproveite").apply {
            setSpan(android.text.style.ForegroundColorSpan(LILAC), 10, length, 0)
        }
        overlay.addView(TextView(this).apply {
            text = tag; textSize = 18f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(30), 0, 0); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        footer = TextView(this).apply {
            textSize = 15f; setTextColor(Color.parseColor("#A9A5D6")); setPadding(dp(24), dp(14), dp(24), dp(14))
        }
        root.addView(footer, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))

        // barrinha de atualização (canto de cima)
        updateBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6251E5C")); cornerRadius = dp(18).toFloat()
                setStroke(dp(2), Color.parseColor("#9D5CFF"))
            }
            setPadding(dp(20), dp(10), dp(10), dp(10))
            visibility = View.GONE
        }
        updateText = TextView(this).apply { textSize = 17f; setTextColor(Color.WHITE); setPadding(0, 0, dp(16), 0) }
        updateBar.addView(updateText)
        updateBtn = android.widget.Button(this).apply {
            textSize = 17f; isAllCaps = false; setTextColor(Color.WHITE)
            stateListAnimator = null
            val normal = GradientDrawable().apply { setColor(Color.parseColor("#7C3AED")); cornerRadius = dp(22).toFloat() }
            val focused = GradientDrawable().apply {
                setColor(Color.parseColor("#9D5CFF")); cornerRadius = dp(22).toFloat(); setStroke(dp(3), Color.WHITE)
            }
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), normal)
            }
            setPadding(dp(22), dp(8), dp(22), dp(8))
            setOnClickListener { onUpdateClick() }
        }
        updateBar.addView(updateBtn)
        root.addView(updateBar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(20); rightMargin = dp(20)
        })
        return root
    }

    private fun paintStatus() {
        val s = statusText.text?.toString() ?: ""
        val d = resources.displayMetrics.density
        val (bg, stroke, fg) = when {
            s.startsWith("Conectado") || s.startsWith("Tocando") || s.startsWith("Modo") ->
                Triple("#0F3D26", "#22C55E", "#B9F8CF")
            s.startsWith("Procurando") || s.startsWith("Carregando") -> Triple("#2A2463", "#9D5CFF", "#E4DCFF")
            s.isEmpty() -> Triple("#00000000", "#00000000", "#FFFFFF")
            else -> Triple("#3D1414", "#EF4444", "#FECACA")
        }
        statusText.setTextColor(Color.parseColor(fg))
        statusText.background = GradientDrawable().apply {
            setColor(Color.parseColor(bg)); cornerRadius = 30 * d; setStroke((2 * d).toInt(), Color.parseColor(stroke))
        }
    }

    // ------------------------------------------------------------------ atualização automática

    private lateinit var updateBar: LinearLayout
    private lateinit var updateText: TextView
    private lateinit var updateBtn: android.widget.Button

    private val updateCheck = object : Runnable {
        override fun run() {
            Updater.check(this@MainActivity, force = true, autoDownload = true)
            ui.postDelayed(this, 6 * 60 * 60 * 1000L)
        }
    }

    private fun refreshUpdate() {
        if (Updater.message.isNotEmpty()) Updater.clearMessage()
        val r = Updater.release
        val wasVisible = updateBar.visibility == View.VISIBLE
        when (Updater.stage) {
            Updater.Stage.IDLE -> { updateBar.visibility = View.GONE; return }
            Updater.Stage.AVAILABLE -> { updateText.text = "Nova versão do Simulplay (${r?.version})"; updateBtn.text = "Atualizar"; updateBtn.visibility = View.VISIBLE }
            Updater.Stage.DOWNLOADING -> { updateText.text = "Baixando atualização… ${Updater.percent}%"; updateBtn.visibility = View.GONE }
            Updater.Stage.READY -> {
                updateText.text = if (Updater.needsPermission(this)) "Atualização pronta. Libere \"apps desconhecidos\" pro Simulplay" else "Atualização pronta"
                updateBtn.text = if (Updater.needsPermission(this)) "Liberar" else "Instalar agora"
                updateBtn.visibility = View.VISIBLE
            }
            Updater.Stage.INSTALLING -> { updateText.text = "Instalando…"; updateBtn.visibility = View.GONE }
            Updater.Stage.ERROR -> { updateText.text = "A atualização falhou"; updateBtn.text = "Tentar de novo"; updateBtn.visibility = View.VISIBLE }
        }
        updateBar.visibility = View.VISIBLE
        // no controle remoto: o botão já fica selecionado, é só apertar OK
        if (updateBtn.visibility == View.VISIBLE && (!wasVisible || !updateBtn.hasFocus())) updateBtn.requestFocus()
    }

    private fun onUpdateClick() {
        if (Updater.needsPermission(this)) { Updater.openPermissionScreen(this); return }
        if (Updater.stage == Updater.Stage.READY) Updater.install(this) else Updater.download(this, thenInstall = true)
    }

    private fun updateOverlay() {
        val showVideo = (kind == "media" && hasVideo) || (kind == "youtube")
        overlay.visibility = if (showVideo) View.GONE else View.VISIBLE
        footer.visibility = overlay.visibility
        playerView.visibility = if (kind == "media") View.VISIBLE else View.GONE
        web.visibility = if (kind == "youtube") View.VISIBLE else View.INVISIBLE
        footer.text = "TV: $myName"
    }

    // ------------------------------------------------------------------ conexão

    override fun onSearching() {
        statusText.text = "Procurando o Transmiplay no Wi-Fi…\nAbra o Transmiplay no celular (mesma rede)."
        titleText.text = ""
        updateOverlay()
    }

    override fun onConnected(phoneName: String, host: String) {
        this.phoneName = phoneName
        this.host = host
        statusText.text = "Conectado a $phoneName\nEsperando você mandar algo pelo celular"
        updateOverlay()
    }

    override fun onDisconnected() {
        stopMedia()
        audio.stop()
        host = null
    }

    override fun onMessage(j: JSONObject) {
        when (j.optString("t")) {
            "load" -> onLoad(j)
            "state" -> {
                val s = State(j.optInt("id"), j.optBoolean("playing"), j.optLong("pos"), j.optLong("at"))
                if (s.id == currentId) {
                    if (s.playing && (state?.playing != true || state?.at != s.at)) { endedSent = false; seekCooldownUntil = 0 }
                    state = s
                }
            }
            "stop" -> {
                stopMedia()
                statusText.text = "Conectado a $phoneName\nEsperando você mandar algo pelo celular"
            }
            "adj" -> adjMs = j.optInt("ms")
            "cfg" -> {
                autoSync = j.optBoolean("sync", true)
                val q = j.optInt("quality", 0)
                val b = player.trackSelectionParameters.buildUpon()
                    .clearVideoSizeConstraints()
                    .setForceHighestSupportedBitrate(q == 1)
                if (q == 2) b.setMaxVideoSize(854, 480)
                player.trackSelectionParameters = b.build()
            }
            "audio" -> {
                val h = host ?: return
                if (j.optBoolean("on")) {
                    stopMedia()
                    audio.start(h, j.optInt("delay", 300))
                    statusText.text = "Modo caixinha"
                    titleText.text = "🔊 Tocando o som do celular de $phoneName"
                } else {
                    audio.stop()
                    statusText.text = "Conectado a $phoneName\nEsperando você mandar algo pelo celular"
                    titleText.text = ""
                }
                updateOverlay()
            }
        }
    }

    private fun onLoad(j: JSONObject) {
        stopMedia()
        currentId = j.optInt("id")
        state = null
        reportedDur = false
        endedSent = false
        startScheduled = false
        hasVideo = false
        kind = j.optString("kind")
        val title = j.optString("title")
        var url = j.optString("url")
        if (j.optBoolean("local")) url = "http://$host:${Proto.HTTP_PORT}$url"

        statusText.text = "Carregando…"
        titleText.text = if (kind == "youtube") "" else "♪ $title"
        if (kind == "youtube") {
            ytState = -1; ytPos = 0; ytDur = 0
            if (!ytLoaded) {
                val html = assets.open("yt.html").bufferedReader().readText()
                // a "origem" identifica o app pro YouTube (sem isso alguns vídeos não abrem)
                web.loadDataWithBaseURL("https://com.geanalves.simulplay", html, "text/html", "utf-8", null)
                ytLoaded = true
            }
            val id = url.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            if (ytPageReady) web.evaluateJavascript("load('$id')", null) else ytPending = id
        } else {
            // cabeçalhos que o site exige (Referer, cookies...)
            val headers = mutableMapOf<String, String>()
            j.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
            val ua = headers.remove("User-Agent")
            val http = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setDefaultRequestProperties(headers)
            if (ua != null) http.setUserAgent(ua)
            val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))

            val b = MediaItem.Builder().setUri(url)
            when (j.optString("mime")) {
                "m3u8" -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
                "mpd" -> b.setMimeType(MimeTypes.APPLICATION_MPD)
            }
            val sub = j.optString("sub")
            if (sub.isNotEmpty()) {
                val subUrl = if (j.optBoolean("subLocal")) "http://$host:${Proto.HTTP_PORT}$sub" else sub
                b.setSubtitleConfigurations(listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                        .setMimeType(j.optString("subMime", MimeTypes.APPLICATION_SUBRIP))
                        .setLanguage("pt")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ))
            }
            player.setMediaSource(factory.createMediaSource(b.build()))
            player.playWhenReady = false
            player.prepare()
        }
        updateOverlay()
    }

    private fun stopMedia() {
        ui.removeCallbacks(startRunnable)
        startScheduled = false
        state = null
        currentId = -1
        if (kind == "media") { player.stop(); player.clearMediaItems() }
        if (kind == "youtube") web.evaluateJavascript("stopVideo()", null)
        kind = ""
        hasVideo = false
        titleText.text = ""
        setSpeed(1f)
        updateOverlay()
    }

    // ------------------------------------------------------------------ sincronia

    private val tick = object : Runnable {
        override fun run() {
            try {
                if (kind == "media") syncMedia() else if (kind == "youtube") syncYoutube()
            } catch (_: Exception) {}
            ui.postDelayed(this, 50)
        }
    }

    private val startRunnable = Runnable {
        startScheduled = false
        if (kind == "media") player.playWhenReady = true
        if (kind == "youtube") web.evaluateJavascript("play()", null)
    }

    private fun setSpeed(s: Float) {
        if (!::player.isInitialized) return
        if (abs(s - lastSpeed) < 0.004f) return
        lastSpeed = s
        player.playbackParameters = PlaybackParameters(s)
    }

    private fun syncMedia() {
        if (player.playbackState == Player.STATE_READY && !reportedDur && player.duration != C.TIME_UNSET) {
            reportedDur = true
            link.send(JSONObject().put("t", "info").put("id", currentId).put("dur", player.duration))
            if (!hasVideo) statusText.text = "Tocando junto com $phoneName"
        }
        val s = state ?: return
        if (!link.synced) return
        val phoneNow = link.phoneNow() - adjMs
        val now = SystemClock.elapsedRealtime()

        if (!s.playing) {
            ui.removeCallbacks(startRunnable); startScheduled = false
            if (player.playWhenReady) player.playWhenReady = false
            setSpeed(1f)
            if (abs(player.currentPosition - s.pos) > 250 && now > seekCooldownUntil) {
                player.seekTo(s.pos); seekCooldownUntil = now + 800
            }
            return
        }
        if (phoneNow < s.at) {
            // ainda não é hora: espera parado no ponto certo e começa no milissegundo combinado
            if (player.playWhenReady && !startScheduled) player.playWhenReady = false
            if (abs(player.currentPosition - s.pos) > 80 && now > seekCooldownUntil) {
                player.seekTo(s.pos); seekCooldownUntil = now + 300
            }
            val wait = s.at - phoneNow
            if (wait < 300 && !startScheduled && player.playbackState == Player.STATE_READY) {
                startScheduled = true
                ui.postDelayed(startRunnable, wait)
            }
            return
        }
        if (player.playbackState == Player.STATE_ENDED) {
            if (!endedSent) { endedSent = true; link.send(JSONObject().put("t", "ended").put("id", currentId)) }
            return
        }
        val target = s.pos + (phoneNow - s.at)
        if (player.duration != C.TIME_UNSET && target > player.duration) return
        if (!player.playWhenReady && !startScheduled) player.playWhenReady = true
        if (player.playbackState != Player.STATE_READY || startScheduled) return

        if (!autoSync) { setSpeed(1f); return }
        val diff = player.currentPosition - target
        if (abs(diff) > 600) {
            if (now > seekCooldownUntil) {
                setSpeed(1f)
                player.seekTo(target + 150)
                seekCooldownUntil = now + 1500
            }
        } else if (abs(diff) > 15) {
            // corrige devagarinho acelerando ou freando um pouquinho (não dá pra perceber)
            setSpeed((1f - diff / 2500f).coerceIn(0.95f, 1.05f))
        } else setSpeed(1f)
    }

    private fun syncYoutube() {
        if (ytDur > 0 && !reportedDur) {
            reportedDur = true
            link.send(JSONObject().put("t", "info").put("id", currentId).put("dur", ytDur))
        }
        val s = state ?: return
        if (!link.synced) return
        val phoneNow = link.phoneNow() - adjMs
        val now = SystemClock.elapsedRealtime()
        val est = if (ytState == 1) ytPos + (now - ytPosAt) else ytPos

        if (!s.playing) {
            ui.removeCallbacks(startRunnable); startScheduled = false
            if (ytState == 1 || ytState == 3) web.evaluateJavascript("pause()", null)
            if (abs(est - s.pos) > 1000 && now > seekCooldownUntil && ytState != -1) {
                web.evaluateJavascript("seek(${s.pos})", null); seekCooldownUntil = now + 1500
            }
            return
        }
        if (phoneNow < s.at) {
            if (ytState == 1 && !startScheduled) web.evaluateJavascript("pause()", null)
            if (abs(est - s.pos) > 400 && now > seekCooldownUntil && ytState != -1) {
                web.evaluateJavascript("seek(${s.pos})", null); seekCooldownUntil = now + 800
            }
            val wait = s.at - phoneNow
            if (wait < 300 && !startScheduled) {
                startScheduled = true
                ui.postDelayed(startRunnable, wait)
            }
            return
        }
        if (ytState == 0) {
            if (!endedSent) { endedSent = true; link.send(JSONObject().put("t", "ended").put("id", currentId)) }
            return
        }
        if (startScheduled) return
        val target = s.pos + (phoneNow - s.at)
        if (ytState != 1 && ytState != 3 && now > seekCooldownUntil) {
            web.evaluateJavascript("seek(${target + 400}); play()", null)
            seekCooldownUntil = now + 2000
            return
        }
        if (autoSync && ytState == 1 && abs(est - target) > 300 && now > seekCooldownUntil) {
            web.evaluateJavascript("seek(${target + 350})", null)
            seekCooldownUntil = now + 3000
        }
    }

    inner class YtBridge {
        @JavascriptInterface
        fun onState(st: Int, posMs: Double, durMs: Double) {
            val t = SystemClock.elapsedRealtime()
            ui.post {
                ytState = st
                ytPos = posMs.toLong()
                ytPosAt = t
                if (durMs > 0) ytDur = durMs.toLong()
            }
        }

        @JavascriptInterface
        fun onError(code: String) {
            ui.post {
                val msg = when (code) {
                    "101", "150", "152", "153" -> "o dono do vídeo não deixa tocar fora do YouTube"
                    "100" -> "vídeo não encontrado"
                    else -> "erro do YouTube $code"
                }
                link.send(JSONObject().put("t", "error").put("msg", msg))
                kind = "youtube"
                statusText.text = "Esse vídeo não pode tocar aqui: $msg"
                overlay.visibility = View.VISIBLE
            }
        }
    }
}
