package com.geanalves.simulplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Modo festa na TV (opção 5 "no canto do vídeo"): o vídeo continua em tela cheia,
 * em cima fica quem pediu a música atual e do lado a fila com quem pediu e quando toca.
 */
class PartyPanel(ctx: Context) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    /** cores de cada estilo da festa */
    private class Th(
        val panel: Int, val stroke: Int, val text: Int, val soft: Int, val accent: Int, val dim: Int,
        val firstBg: Int, val hiBg: Int, val hiStroke: Int, val hiText: Int, val hiSub: Int,
        val bannerBg: Int, val bannerStroke: Int, val bannerText: Int, val qrTitle: String, val head: String,
    )
    private val themes = mapOf(
        "luzes" to Th(Color.argb(190, 15, 23, 42), Color.argb(46, 255, 255, 255), Color.WHITE, Color.parseColor("#CBD5E1"),
            Color.parseColor("#60A5FA"), Color.parseColor("#94A3B8"), Color.argb(46, 59, 130, 246),
            Color.argb(72, 37, 99, 235), Color.parseColor("#60A5FA"), Color.WHITE, Color.parseColor("#93C5FD"),
            Color.parseColor("#4F46E5"), Color.argb(90, 255, 255, 255), Color.WHITE, "Peça sua música!", "A seguir"),
        "neon" to Th(Color.argb(190, 11, 4, 24), Color.argb(180, 103, 232, 249), Color.WHITE, Color.parseColor("#E9D5FF"),
            Color.parseColor("#67E8F9"), Color.parseColor("#E9D5FF"), Color.argb(40, 103, 232, 249),
            Color.argb(115, 134, 25, 143), Color.parseColor("#F0ABFC"), Color.WHITE, Color.parseColor("#F5D0FE"),
            Color.argb(170, 88, 28, 135), Color.parseColor("#F0ABFC"), Color.WHITE, "PEÇA SUA MÚSICA", "A SEGUIR"),
        "laser" to Th(Color.argb(210, 3, 8, 5), Color.argb(115, 74, 222, 128), Color.parseColor("#F0FDF4"), Color.parseColor("#BBF7D0"),
            Color.parseColor("#4ADE80"), Color.parseColor("#86EFAC"), Color.argb(40, 74, 222, 128),
            Color.parseColor("#4ADE80"), Color.parseColor("#4ADE80"), Color.parseColor("#03140A"), Color.parseColor("#14532D"),
            Color.argb(200, 20, 83, 45), Color.parseColor("#4ADE80"), Color.parseColor("#F0FDF4"), "Peça sua música", "Fila"),
        "claro" to Th(Color.argb(244, 255, 255, 255), Color.parseColor("#DCE6F5"), Color.parseColor("#0F172A"), Color.parseColor("#475569"),
            Color.parseColor("#2563EB"), Color.parseColor("#94A3B8"), Color.parseColor("#EFF6FF"),
            Color.parseColor("#DBEAFE"), Color.parseColor("#2563EB"), Color.parseColor("#0F172A"), Color.parseColor("#2563EB"),
            Color.parseColor("#2563EB"), Color.parseColor("#2563EB"), Color.WHITE, "Peça sua música!", "A seguir"),
    )
    private var style = "luzes"
    private var th = themes.getValue("luzes")
    private val ink get() = th.text
    private val soft get() = th.soft
    private val blue get() = th.accent
    private val bg = PartyBg(ctx)
    private var videoOn = false

    private val pill = LinearLayout(ctx)
    private val pillAvatar = TextView(ctx)
    private val pillText = TextView(ctx)
    private val side = LinearLayout(ctx)
    private val leftText = TextView(ctx)
    private val list = LinearLayout(ctx)
    private val qr = ImageView(ctx)
    private var qrUrl = ""
    private val pillNext = TextView(ctx)
    private val qrCard = LinearLayout(ctx)
    private val banner = TextView(ctx)
    private var lastSeq = -1
    private var forceUntil = 0L
    private var newIdx = -1
    private var listShown = true
    private var last: JSONObject? = null

    private val avatarColors = listOf(
        "#FCE7F3" to "#9D174D", "#E0E7FF" to "#3730A3", "#CCFBF1" to "#115E59",
        "#FEF3C7" to "#92400E", "#DCFCE7" to "#166534", "#DBEAFE" to "#1E3A8A",
    )

    init {
        visibility = View.GONE
        isFocusable = false
        build()
    }

    private fun build() {
        val ctx = context
        removeAllViews(); pill.removeAllViews(); side.removeAllViews(); list.removeAllViews(); qrCard.removeAllViews()
        for (v in listOf<View>(qr, pillAvatar, pillText, pillNext, leftText, list, banner, pill, side, qrCard))
            (v.parent as? android.view.ViewGroup)?.removeView(v)
        // fundo animado (só quando não tem vídeo passando)
        bg.style = style
        bg.visibility = if (videoOn) View.GONE else View.VISIBLE
        addView(bg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // quem pediu a música atual (em cima, à esquerda)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = round(th.panel, 999, th.stroke)
        pill.setPadding(dp(8), dp(7), dp(16), dp(7))
        pill.addView(pillAvatar, LinearLayout.LayoutParams(dp(26), dp(26)))
        pillText.textSize = 14f; pillText.setTextColor(ink); pillText.maxLines = 1
        pillText.ellipsize = TextUtils.TruncateAt.END
        pillText.setPadding(dp(9), 0, 0, 0)
        val pc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        pc.addView(pillText)
        pillNext.textSize = 12f; pillNext.setTextColor(soft); pillNext.maxLines = 1
        pillNext.ellipsize = TextUtils.TruncateAt.END; pillNext.setPadding(dp(9), 0, 0, 0)
        pc.addView(pillNext)
        pill.addView(pc, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(24); leftMargin = dp(27); rightMargin = dp(330)
        })

        // fila (do lado direito)
        side.orientation = LinearLayout.VERTICAL
        side.background = round(th.panel, 20, th.stroke)
        side.setPadding(dp(16), dp(16), dp(16), dp(14))
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(text(th.head, 17f, if (style == "neon") th.accent else ink, true), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        leftText.textSize = 12f; leftText.setTextColor(soft)
        head.addView(leftText)
        side.addView(head)
        list.orientation = LinearLayout.VERTICAL
        side.addView(list, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        addView(side, LayoutParams(dp(270), LayoutParams.MATCH_PARENT, Gravity.END).apply {
            topMargin = dp(24); bottomMargin = dp(24); rightMargin = dp(27)
        })

        // QR code no cantinho de baixo, com o passo a passo
        qrCard.orientation = LinearLayout.HORIZONTAL
        qrCard.gravity = Gravity.CENTER_VERTICAL
        qrCard.background = round(th.panel, 16, if (style == "neon") th.bannerStroke else th.stroke)
        qrCard.setPadding(dp(10), dp(10), dp(16), dp(10))
        qrCard.addView(qr, LinearLayout.LayoutParams(dp(84), dp(84)))
        val qc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        qc.addView(text(th.qrTitle, 15f, if (style == "neon") th.bannerStroke else blue, true))
        qc.addView(text("1. Aponte a câmera do celular aqui", 11f, ink, false))
        qc.addView(text("2. Abra o link (no mesmo Wi-Fi)", 11f, ink, false))
        qc.addView(text("3. Cole um link ou mande sua música", 11f, ink, false))
        qrCard.addView(qc)
        addView(qrCard, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = dp(24); leftMargin = dp(27)
        })

        // aviso de pedido novo (em cima, no meio)
        banner.textSize = 16f; banner.setTextColor(Color.WHITE); banner.typeface = Typeface.DEFAULT_BOLD
        banner.background = round(th.bannerBg, if (style == "laser") 6 else 999, th.bannerStroke)
        banner.setTextColor(th.bannerText)
        banner.setPadding(dp(22), dp(10), dp(22), dp(10))
        banner.gravity = Gravity.CENTER
        banner.maxLines = 1; banner.ellipsize = TextUtils.TruncateAt.END
        banner.visibility = View.GONE
        banner.maxWidth = resources.displayMetrics.widthPixels - dp(27 + 270 + 27 + 24)
        addView(banner, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(84); leftMargin = dp(27)
        })
    }

    private fun round(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    /** escolhido no celular: luzes, neon, laser ou claro */
    fun setStyle(s: String) {
        if (s == style || s !in themes) return
        style = s; th = themes.getValue(s)
        build()
        listShown = true
        last?.let { update(it) }
    }

    /** com vídeo passando o fundo some e fica só o vídeo por trás */
    fun setVideo(on: Boolean) {
        if (on == videoOn) return
        videoOn = on
        bg.visibility = if (on) View.GONE else View.VISIBLE
        if (!on) bg.invalidate()
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean) = TextView(context).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    private fun avatar(v: TextView, name: String) {
        val (bg, fg) = avatarColors[(name.hashCode() and 0x7fffffff) % avatarColors.size]
        v.text = name.trim().take(1).uppercase().ifBlank { "♪" }
        v.gravity = Gravity.CENTER
        v.textSize = 12f; v.typeface = Typeface.DEFAULT_BOLD
        v.setTextColor(Color.parseColor(fg))
        v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(bg)) }
    }

    private fun fmt(ms: Long): String {
        val t = ms / 1000
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t / 60) % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }

    /** chega do celular a cada 2 s */
    fun update(j: JSONObject) {
        if (!j.optBoolean("on")) { visibility = View.GONE; last = null; return }
        visibility = View.VISIBLE
        last = j
        if (j.has("style")) setStyle(j.optString("style"))
        val nw = j.optJSONObject("new")
        if (nw != null && nw.optInt("seq") != lastSeq) {
            val first = lastSeq == -1
            lastSeq = nw.optInt("seq")
            if (!first) {
                // pedido novo: mostra a lista agora, com a música da pessoa destacada
                newIdx = nw.optInt("idx", -1)
                forceUntil = android.os.SystemClock.elapsedRealtime() + 30_000
                val by = nw.optString("by").ifBlank { "alguém" }
                banner.text = if (nw.optBoolean("now")) "Pedido de $by: ${nw.optString("title")} · tocando agora"
                    else "Pedido novo de $by: ${nw.optString("title")} · toca em ${fmt(nw.optLong("in"))}"
                banner.visibility = View.VISIBLE
                banner.alpha = 0f; banner.animate().alpha(1f).setDuration(250).start()
                removeCallbacks(hideBanner); postDelayed(hideBanner, 12_000)
                applyCycle(true)
            }
        } else if (nw != null) newIdx = nw.optInt("idx", -1)
        val now = j.optJSONObject("now")
        if (now != null) {
            pill.visibility = View.VISIBLE
            val by = now.optString("by")
            avatar(pillAvatar, by)
            pillText.text = android.text.SpannableStringBuilder().apply {
                val st = length; append(now.optString("title"))
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, length, 0)
                if (by.isNotBlank()) append(" · pedido por $by")
            }
        } else pill.visibility = View.GONE
        val firstItem = j.optJSONArray("items")?.optJSONObject(0)
        pillNext.text = firstItem?.let { "a seguir: ${it.optString("title")} · em ${fmt(it.optLong("in"))}" } ?: ""
        pillNext.visibility = if (firstItem == null) View.GONE else View.VISIBLE
        if (now == null && firstItem != null) {
            pill.visibility = View.VISIBLE; avatar(pillAvatar, ""); pillText.text = "A festa vai começar"
        }

        val left = j.optLong("left")
        leftText.text = if (left > 0) "fila: ${(left + 59_999) / 60_000} min" else ""
        list.removeAllViews()
        val items = j.optJSONArray("items")
        val n = items?.length() ?: 0
        if (n == 0) list.addView(text("A fila está vazia. Peça a primeira!", 13f, soft, false).apply { maxLines = 2 })
        val highlight = if (android.os.SystemClock.elapsedRealtime() < forceUntil) newIdx else -1
        for (i in 0 until minOf(n, 5)) list.addView(row(i, items!!.getJSONObject(i), highlight == i))
        val count = j.optInt("count")
        if (highlight >= 5) {
            // a música nova está mais pro fim da fila: mostra ela embaixo, destacada
            if (highlight > 5) list.addView(text("⋯", 14f, soft, true).apply { setPadding(dp(10), 0, 0, 0) })
            val nwi = j.optJSONObject("new")
            val o = items?.optJSONObject(highlight) ?: JSONObject().put("title", nwi?.optString("title")).put("by", nwi?.optString("by")).put("in", nwi?.optLong("in") ?: 0)
            list.addView(row(highlight, o, true))
        } else if (count > 5) list.addView(text("+ ${count - 5} na fila", 12f, soft, false).apply { setPadding(dp(10), dp(6), 0, 0) })

        val url = j.optString("url")
        if (url != qrUrl && url.isNotBlank()) {
            qrUrl = url
            try { qr.setImageBitmap(makeQr(url, 256)) } catch (_: Exception) {}
        }
    }

    private fun row(i: Int, it: JSONObject, mine: Boolean): View {
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = when {
                mine -> GradientDrawable().apply { setColor(th.hiBg); cornerRadius = dp(12).toFloat(); setStroke(dp(2), th.hiStroke) }
                i == 0 -> round(th.firstBg, 12)
                else -> null
            }
        }
        r.addView(text("${i + 1}", if (i == 0 || mine) 18f else 16f, when { mine -> th.hiText; i == 0 -> blue; else -> th.dim }, true),
            LinearLayout.LayoutParams(dp(24), LayoutParams.WRAP_CONTENT))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(it.optString("title"), if (i == 0 || mine) 14f else 13f, if (mine) th.hiText else ink, true))
        val by = it.optString("by")
        col.addView(text((if (mine) "pedido novo · " else "") + (if (by.isNotBlank()) "$by · " else "") + "em ${fmt(it.optLong("in"))}",
            11f, if (mine) th.hiSub else soft, mine))
        r.addView(col, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        r.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) }
        return r
    }

    private val hideBanner = Runnable { banner.animate().alpha(0f).setDuration(400).withEndAction { banner.visibility = View.GONE }.start() }

    /** a lista aparece a cada 1 minuto e fica 30 s; um pedido novo mostra na hora */
    private val cycle = object : Runnable {
        override fun run() {
            applyCycle(false)
            postDelayed(this, 1000)
        }
    }

    private fun applyCycle(now: Boolean) {
        val t = android.os.SystemClock.elapsedRealtime()
        // mesmo relógio do dia em todas as TVs: aparecem e somem juntas
        val show = t < forceUntil || (System.currentTimeMillis() / 1000) % 60 < 30
        if (t >= forceUntil && newIdx >= 0 && !show) newIdx = -1
        if (show == listShown && !now) return
        listShown = show
        side.animate().cancel()
        if (show) {
            side.visibility = View.VISIBLE
            side.animate().alpha(1f).translationX(0f).setDuration(350).start()
            last?.let { update(it) }
        } else {
            side.animate().alpha(0f).translationX(dp(60).toFloat()).setDuration(350)
                .withEndAction { side.visibility = View.GONE }.start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(cycle) }

    override fun onDetachedFromWindow() { removeCallbacks(cycle); super.onDetachedFromWindow() }

    private fun makeQr(text: String, size: Int): Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { if (m[it % size, it / size]) Color.parseColor("#0F172A") else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
