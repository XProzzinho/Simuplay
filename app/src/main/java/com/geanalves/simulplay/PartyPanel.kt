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
    private val ink = Color.parseColor("#0F172A")
    private val soft = Color.parseColor("#475569")
    private val blue = Color.parseColor("#2563EB")

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
        // quem pediu a música atual (em cima, à esquerda)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = round(Color.argb(240, 255, 255, 255), 999)
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
        side.background = round(Color.argb(244, 255, 255, 255), 20)
        side.setPadding(dp(16), dp(16), dp(16), dp(14))
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(text("A seguir", 17f, ink, true), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
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
        qrCard.background = round(Color.argb(244, 255, 255, 255), 16)
        qrCard.setPadding(dp(10), dp(10), dp(16), dp(10))
        qrCard.addView(qr, LinearLayout.LayoutParams(dp(84), dp(84)))
        val qc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        qc.addView(text("Peça sua música!", 15f, blue, true))
        qc.addView(text("1. Aponte a câmera do celular aqui", 11f, ink, false))
        qc.addView(text("2. Abra o link (no mesmo Wi-Fi)", 11f, ink, false))
        qc.addView(text("3. Cole um link ou mande sua música", 11f, ink, false))
        qrCard.addView(qc)
        addView(qrCard, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = dp(24); leftMargin = dp(27)
        })

        // aviso de pedido novo (em cima, no meio)
        banner.textSize = 16f; banner.setTextColor(Color.WHITE); banner.typeface = Typeface.DEFAULT_BOLD
        banner.background = round(blue, 999)
        banner.setPadding(dp(22), dp(10), dp(22), dp(10))
        banner.gravity = Gravity.CENTER
        banner.maxLines = 1; banner.ellipsize = TextUtils.TruncateAt.END
        banner.visibility = View.GONE
        banner.maxWidth = resources.displayMetrics.widthPixels - dp(27 + 270 + 27 + 24)
        addView(banner, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(84); leftMargin = dp(27)
        })
    }

    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

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
                mine -> GradientDrawable().apply { setColor(Color.parseColor("#DBEAFE")); cornerRadius = dp(12).toFloat(); setStroke(dp(2), blue) }
                i == 0 -> round(Color.parseColor("#EFF6FF"), 12)
                else -> null
            }
        }
        r.addView(text("${i + 1}", if (i == 0 || mine) 18f else 16f, if (i == 0 || mine) blue else Color.parseColor("#94A3B8"), true),
            LinearLayout.LayoutParams(dp(24), LayoutParams.WRAP_CONTENT))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(it.optString("title"), if (i == 0 || mine) 14f else 13f, ink, true))
        val by = it.optString("by")
        col.addView(text((if (mine) "pedido novo · " else "") + (if (by.isNotBlank()) "$by · " else "") + "em ${fmt(it.optLong("in"))}",
            11f, if (mine) blue else soft, mine))
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
        val px = IntArray(size * size) { if (m[it % size, it / size]) ink else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
