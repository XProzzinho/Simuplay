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

private val LILAC = Color.parseColor("#2563EB")
private val INK = Color.parseColor("#0F172A")
private val SOFT = Color.parseColor("#64748B")

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
    private lateinit var caixinha: CaixinhaView

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
    private lateinit var nowBar: LinearLayout
    private lateinit var nowFill: View
    private lateinit var nowTime: TextView
    private lateinit var pauseOverlay: LinearLayout
    private lateinit var pauseTitle: TextView
    private lateinit var startToast: LinearLayout
    private lateinit var startToastText: TextView
    private var lastTitle = ""
    private var tickN = 0
    private var wasPlaying = false
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
                // detalhe do erro (ex.: 403 = o servidor recusou a TV)
                var http = 0
                var c: Throwable? = error.cause
                while (c != null) {
                    if (c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) { http = c.responseCode; break }
                    c = c.cause
                }
                link.send(JSONObject().put("t", "error").put("id", currentId).put("code", error.errorCode)
                    .put("http", http).put("name", error.errorCodeName)
                    .put("msg", "não consegui tocar (${error.errorCodeName}${if (http > 0) " $http" else ""})"))
                statusText.text = "Não consegui abrir esse vídeo" +
                    (if (http > 0) " (o servidor respondeu $http)" else " (${error.errorCodeName})")
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
                intArrayOf(Color.WHITE, Color.parseColor("#F1F6FD"), Color.parseColor("#E3EDFB")),
            )
        }
        overlay.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(130), dp(130)))
        val brand = android.text.SpannableStringBuilder("SimulPlay").apply {
            setSpan(android.text.style.ForegroundColorSpan(LILAC), 5, 9, 0)
        }
        overlay.addView(TextView(this).apply {
            text = brand; textSize = 56f; setTextColor(INK)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        overlay.addView(TextView(this).apply {
            text = "Receba a transmissão em sua TV"; textSize = 22f
            setTextColor(SOFT); gravity = Gravity.CENTER
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
            textSize = 28f; setTextColor(INK); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(60), dp(24), dp(60), 0); maxLines = 2
        }
        overlay.addView(titleText)
        nowBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val track = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor("#DBEAFE")); cornerRadius = dp(3).toFloat() }
        }
        nowFill = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#06B6D4"), Color.parseColor("#2563EB"))).apply { cornerRadius = dp(3).toFloat() }
        }
        track.addView(nowFill, FrameLayout.LayoutParams(0, MATCH_PARENT))
        nowBar.addView(track, LinearLayout.LayoutParams(dp(520), dp(6)))
        nowTime = TextView(this).apply {
            textSize = 16f; setTextColor(Color.parseColor("#475569")); gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        nowBar.addView(nowTime, LinearLayout.LayoutParams(dp(520), WRAP_CONTENT))
        overlay.addView(nowBar, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18) })
        val tag = android.text.SpannableStringBuilder("Conecte • Transmita • Aproveite").apply {
            setSpan(android.text.style.ForegroundColorSpan(LILAC), 10, length, 0)
        }
        overlay.addView(TextView(this).apply {
            text = tag; textSize = 18f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(30), 0, 0); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        footer = TextView(this).apply {
            textSize = 15f; setTextColor(SOFT); setPadding(dp(24), dp(14), dp(24), dp(14))
        }
        root.addView(footer, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))

        // tela do modo caixinha (som do celular)
        caixinha = CaixinhaView(this) { if (::audio.isInitialized) audio.bands else FloatArray(16) }
        root.addView(caixinha, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // aviso de pausa por cima do vídeo
        pauseOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(170, 255, 255, 255))
            visibility = View.GONE
        }
        val pauseCircle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(2), Color.parseColor("#2563EB"))
            }
        }
        pauseCircle.addView(ImageView(this).apply {
            setImageDrawable(PauseBars(Color.parseColor("#2563EB")))
        }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        pauseOverlay.addView(pauseCircle, LinearLayout.LayoutParams(dp(120), dp(120)))
        pauseOverlay.addView(TextView(this).apply {
            text = "Pausado"; textSize = 26f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(4))
        })
        pauseTitle = TextView(this).apply {
            textSize = 18f; setTextColor(Color.parseColor("#475569")); gravity = Gravity.CENTER; maxLines = 1
            setPadding(dp(60), 0, dp(60), 0)
        }
        pauseOverlay.addView(pauseTitle)
        root.addView(pauseOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // nome do vídeo quando começa
        startToast = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 255, 255, 255)); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.parseColor("#DCE6F5"))
            }
            setPadding(dp(10), dp(8), dp(22), dp(8))
            alpha = 0f
        }
        startToast.addView(ImageView(this).apply { setImageResource(R.drawable.logo) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        startToastText = TextView(this).apply {
            textSize = 17f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD; maxLines = 1
            setPadding(dp(12), 0, 0, 0)
        }
        startToast.addView(startToastText)
        root.addView(startToast, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(24); leftMargin = dp(24)
        })

        // barrinha de atualização (canto de cima)
        updateBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(18).toFloat()
                setStroke(dp(2), Color.parseColor("#2563EB"))
            }
            setPadding(dp(20), dp(10), dp(10), dp(10))
            visibility = View.GONE
        }
        updateText = TextView(this).apply { textSize = 17f; setTextColor(INK); setPadding(0, 0, dp(16), 0) }
        updateBar.addView(updateText)
        updateBtn = android.widget.Button(this).apply {
            textSize = 17f; isAllCaps = false; setTextColor(Color.WHITE)
            stateListAnimator = null
            val normal = GradientDrawable().apply { setColor(Color.parseColor("#2563EB")); cornerRadius = dp(22).toFloat() }
            val focused = GradientDrawable().apply {
                setColor(Color.parseColor("#1D4ED8")); cornerRadius = dp(22).toFloat(); setStroke(dp(3), INK)
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

    /** cartão "tocando agora", aviso de pausa e nome do vídeo ao começar */
    private fun updateNowUi() {
        val s = state
        val showingVideo = (kind == "media" && hasVideo) || kind == "youtube"
        val playingNow = s?.playing == true && link.phoneNow() - adjMs >= (s?.at ?: 0)
        // pausa por cima do vídeo
        val paused = showingVideo && s != null && !s.playing
        pauseOverlay.visibility = if (paused) View.VISIBLE else View.GONE
        if (paused) pauseTitle.text = lastTitle
        // só áudio: barrinha de progresso
        val audioOnly = kind == "media" && !hasVideo && s != null
        nowBar.visibility = if (audioOnly) View.VISIBLE else View.GONE
        if (audioOnly) {
            val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            val pos = player.currentPosition
            val w = resources.displayMetrics.density * 520
            nowFill.layoutParams = nowFill.layoutParams.apply { width = if (dur > 0) (w * pos / dur).toInt() else 0 }
            nowTime.text = if (dur > 0) "${fmt(pos)}  /  ${fmt(dur)}" else fmt(pos)
            if (!s!!.playing) statusText.text = "Pausado"
            else if (statusText.text.startsWith("Pausado")) statusText.text = "Tocando junto com $phoneName"
        }
        // nome do vídeo quando começa a tocar
        if (playingNow && !wasPlaying && showingVideo && lastTitle.isNotBlank()) {
            startToastText.text = "▶  " + lastTitle
            startToast.animate().cancel()
            startToast.animate().alpha(1f).setDuration(300).withEndAction {
                startToast.animate().alpha(0f).setStartDelay(3500).setDuration(600).start()
            }.start()
        }
        wasPlaying = playingNow
    }

    private fun fmt(ms: Long): String {
        val t = ms / 1000
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t / 60) % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }

    /** ícone de pausa desenhado (duas barrinhas) */
    private class PauseBars(private val color: Int) : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds; val w = b.width() * 0.26f; val g = b.width() * 0.14f; val r = b.width() * 0.06f
            c.drawRoundRect(b.exactCenterX() - g - w, b.top + b.height() * 0.12f, b.exactCenterX() - g, b.bottom - b.height() * 0.12f, r, r, p)
            c.drawRoundRect(b.exactCenterX() + g, b.top + b.height() * 0.12f, b.exactCenterX() + g + w, b.bottom - b.height() * 0.12f, r, r, p)
        }
        override fun setAlpha(a: Int) { p.alpha = a }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun paintStatus() {
        val s = statusText.text?.toString() ?: ""
        val d = resources.displayMetrics.density
        val (bg, stroke, fg) = when {
            s.startsWith("Conectado") || s.startsWith("Tocando") || s.startsWith("Modo") ->
                Triple("#ECFDF3", "#A7F3D0", "#047857")
            s.startsWith("Procurando") || s.startsWith("Carregando") || s.startsWith("Pausado") -> Triple("#EFF6FF", "#BFDBFE", "#1E40AF")
            s.isEmpty() -> Triple("#00000000", "#00000000", "#FFFFFF")
            else -> Triple("#FEF2F2", "#FECACA", "#B91C1C")
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
                updateText.text = "Atualização pronta"
                updateBtn.text = if (Updater.needsPermission(this)) "Liberar e instalar" else "Instalar agora"
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
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        footer.text = "TV: $myName  ·  versão $ver"
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
        caixinha.hide()
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
                    caixinha.show(phoneName)
                    statusText.text = "Modo caixinha"
                    titleText.text = "Tocando o som do celular de $phoneName"
                } else {
                    audio.stop()
                    caixinha.hide()
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
        lastTitle = title
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
                if (++tickN % 5 == 0) updateNowUi()
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
