package com.geanalves.simulplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Tela do modo caixinha na TV: barras de som em volta do logo que dançam com a música,
 * de qual celular vem o som e um relógio.
 */
class CaixinhaView(ctx: Context, private val levels: () -> FloatArray) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val ink = Color.parseColor("#0F172A")
    private val soft = Color.parseColor("#64748B")
    private val blue = Color.parseColor("#2563EB")

    private val clock: TextView
    private val date: TextView
    private val from: TextView
    private val ring: Ring
    private val logo: ImageView

    init {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.WHITE, Color.parseColor("#F1F6FD"), Color.parseColor("#E3EDFB")))
        visibility = GONE

        // barras em volta do logo
        ring = Ring()
        addView(ring, LayoutParams(dp(440), dp(440), Gravity.CENTER).apply { topMargin = -dp(40) })
        logo = ImageView(ctx).apply { setImageResource(R.drawable.logo) }
        addView(logo, LayoutParams(dp(130), dp(130), Gravity.CENTER).apply { topMargin = -dp(40) })

        // canto de cima, à esquerda: marca
        val brand = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(32), 0, 0)
        }
        brand.addView(TextView(ctx).apply {
            text = android.text.SpannableStringBuilder("SimulPlay").apply {
                setSpan(android.text.style.ForegroundColorSpan(blue), 5, 9, 0)
            }
            textSize = 26f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
        })
        brand.addView(TextView(ctx).apply {
            text = "Modo caixinha"; textSize = 15f; setTextColor(soft)
        })
        addView(brand, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))

        // canto de cima, à direita: relógio
        val time = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(0, dp(26), dp(40), 0)
        }
        clock = TextView(ctx).apply { textSize = 44f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD }
        date = TextView(ctx).apply { textSize = 15f; setTextColor(soft) }
        time.addView(clock); time.addView(date)
        addView(time, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))

        // embaixo: o que está tocando
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(46))
        }
        bottom.addView(TextView(ctx).apply {
            text = "●  AO VIVO"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0E7490"))
            background = GradientDrawable().apply { setColor(Color.parseColor("#ECFEFF")); cornerRadius = dp(14).toFloat() }
            setPadding(dp(14), dp(5), dp(14), dp(5))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        bottom.addView(TextView(ctx).apply {
            text = "Tocando o som do celular"; textSize = 30f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(2))
        })
        from = TextView(ctx).apply { textSize = 18f; setTextColor(soft); gravity = Gravity.CENTER }
        bottom.addView(from)
        addView(bottom, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
    }

    fun show(phoneName: String) {
        from.text = "de $phoneName"
        tickClock()
        visibility = VISIBLE
        ring.postInvalidateOnAnimation()
    }

    fun hide() { visibility = GONE }

    private val clockTick = object : Runnable {
        override fun run() { tickClock(); if (visibility == VISIBLE) postDelayed(this, 10_000) }
    }

    private fun tickClock() {
        val now = Date()
        clock.text = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(now)
        date.text = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR")).format(now)
        removeCallbacks(clockTick)
        postDelayed(clockTick, 10_000)
    }

    /** barras em círculo, com altura conforme o som */
    private inner class Ring : View(context) {
        private val n = 64
        private val smooth = FloatArray(n)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val start = System.nanoTime()

        override fun onDraw(c: Canvas) {
            val t = (System.nanoTime() - start) / 1e9f
            val cx = width / 2f; val cy = height / 2f
            val s = min(width, height).toFloat()
            val inner = s * 0.19f
            val maxLen = s * 0.27f
            val lv = levels()
            var avg = 0f
            for (v in lv) avg += v
            avg /= lv.size.coerceAtLeast(1)
            val sc = 1f + 0.08f * avg
            logo.scaleX = sc; logo.scaleY = sc

            // halo que respira com o volume
            glow.style = Paint.Style.FILL
            glow.color = Color.argb((40 + 120 * avg).toInt().coerceAtMost(160), 37, 99, 235)
            c.drawCircle(cx, cy, inner * (1.05f + 0.25f * avg), glow)
            glow.color = Color.WHITE
            c.drawCircle(cx, cy, inner * 0.98f, glow)

            p.strokeWidth = s * 0.016f
            for (i in 0 until n) {
                val band = lv[(i * 7) % lv.size]
                val wave = 0.55f + 0.45f * sin(t * 3.1f + i * 0.45f)
                val target = (band * 1.4f * wave).coerceIn(0f, 1f)
                // sobe rápido, desce devagar
                smooth[i] = if (target > smooth[i]) smooth[i] + (target - smooth[i]) * 0.5f else smooth[i] * 0.9f
                val idle = 0.06f + 0.03f * sin(t * 1.4f + i * 0.3f)
                val len = maxLen * (idle + smooth[i] * 0.94f)
                val a = Math.toRadians(-90.0 + 360.0 * i / n)
                val x1 = cx + (inner + 10 * d) * cos(a).toFloat()
                val y1 = cy + (inner + 10 * d) * sin(a).toFloat()
                val x2 = cx + (inner + 10 * d + len) * cos(a).toFloat()
                val y2 = cy + (inner + 10 * d + len) * sin(a).toFloat()
                p.shader = LinearGradient(x1, y1, x2, y2, Color.parseColor("#06B6D4"), blue, Shader.TileMode.CLAMP)
                c.drawLine(x1, y1, x2, y2, p)
            }
            p.shader = null
            // anel fino
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = Color.parseColor("#BFDBFE")
            c.drawOval(RectF(cx - inner, cy - inner, cx + inner, cy + inner), p)
            p.style = Paint.Style.FILL
            if (this@CaixinhaView.visibility == VISIBLE) postInvalidateOnAnimation()
        }
    }
}
