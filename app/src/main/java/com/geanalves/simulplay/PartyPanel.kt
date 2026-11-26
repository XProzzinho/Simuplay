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
        pill.addView(pillText, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
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
        val foot = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        foot.addView(qr, LinearLayout.LayoutParams(dp(72), dp(72)))
        val fc = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        fc.addView(text("Peça a sua", 14f, ink, true))
        fc.addView(text("aponte a câmera do celular", 11f, soft, false))
        foot.addView(fc)
        side.addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#DCE6F5")) },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply { bottomMargin = dp(10) })
        side.addView(foot)
        addView(side, LayoutParams(dp(270), LayoutParams.MATCH_PARENT, Gravity.END).apply {
            topMargin = dp(24); bottomMargin = dp(24); rightMargin = dp(27)
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
        if (!j.optBoolean("on")) { visibility = View.GONE; return }
        visibility = View.VISIBLE
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

        val left = j.optLong("left")
        leftText.text = if (left > 0) "fila: ${(left + 59_999) / 60_000} min" else ""
        list.removeAllViews()
        val items = j.optJSONArray("items")
        val n = items?.length() ?: 0
        if (n == 0) list.addView(text("A fila está vazia. Peça a primeira!", 13f, soft, false).apply { maxLines = 2 })
        for (i in 0 until minOf(n, 5)) {
            val it = items!!.getJSONObject(i)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                if (i == 0) background = round(Color.parseColor("#EFF6FF"), 12)
            }
            row.addView(text("${i + 1}", if (i == 0) 18f else 16f, if (i == 0) blue else Color.parseColor("#94A3B8"), true),
                LinearLayout.LayoutParams(dp(22), LayoutParams.WRAP_CONTENT))
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(it.optString("title"), if (i == 0) 14f else 13f, ink, true))
            val by = it.optString("by")
            col.addView(text((if (by.isNotBlank()) "$by · " else "") + "em ${fmt(it.optLong("in"))}", 11f, soft, false))
            row.addView(col, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row)
        }
        val count = j.optInt("count")
        if (count > 5) list.addView(text("+ ${count - 5} na fila", 12f, soft, false).apply { setPadding(dp(10), dp(6), 0, 0) })

        val url = j.optString("url")
        if (url != qrUrl && url.isNotBlank()) {
            qrUrl = url
            try { qr.setImageBitmap(makeQr(url, 256)) } catch (_: Exception) {}
        }
    }

    private fun makeQr(text: String, size: Int): Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { if (m[it % size, it / size]) ink else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
