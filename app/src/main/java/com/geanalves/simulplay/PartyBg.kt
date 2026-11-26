package com.geanalves.simulplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Fundo animado da festa (aparece quando não tem vídeo passando): luzes de show, neon ou laser. */
class PartyBg(ctx: Context) : View(ctx) {
    var style = "luzes"
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = System.nanoTime()
    private val rnd = Random(7)
    // confete: x, y, velocidade, cor, giro
    private val confetti = List(140) {
        floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.02f + rnd.nextFloat() * 0.05f, rnd.nextInt(6).toFloat(), rnd.nextFloat() * 6f)
    }
    private val confettiColors = intArrayOf(
        Color.parseColor("#F472B6"), Color.parseColor("#FACC15"), Color.parseColor("#22D3EE"),
        Color.parseColor("#A3E635"), Color.parseColor("#A78BFA"), Color.parseColor("#FB923C"),
    )
    private var lastFrame = 0L

    private fun beam(c: Canvas, x: Float, y: Float, angleDeg: Double, len: Float, width: Float, color: Int, alpha: Int) {
        val a = Math.toRadians(angleDeg)
        val ex = x + (cos(a) * len).toFloat(); val ey = y + (sin(a) * len).toFloat()
        val nx = (-sin(a)).toFloat() * width / 2; val ny = cos(a).toFloat() * width / 2
        val path = Path().apply { moveTo(x, y); lineTo(ex + nx, ey + ny); lineTo(ex - nx, ey - ny); close() }
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(x, y, ex, ey,
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)),
            Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        // miolo mais forte
        p.shader = LinearGradient(x, y, ex, ey, Color.argb(minOf(255, alpha + 60), 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        p.strokeWidth = width * 0.04f + 1f; p.style = Paint.Style.STROKE
        c.drawLine(x, y, x + (ex - x) * 0.6f, y + (ey - y) * 0.6f, p)
        p.shader = null
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int) {
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(x, y, r, Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)),
            Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
        p.shader = null
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f) return
        val t = (System.nanoTime() - start) / 1e9
        when (style) {
            "neon" -> {
                c.drawColor(Color.parseColor("#0B0418"))
                glow(c, w * 0.5f, h * 1.1f, h * 0.9f, Color.parseColor("#A855F7"), 190)
                glow(c, w * 0.12f, 0f, h * 0.6f, Color.parseColor("#22D3EE"), 90)
                val cols = intArrayOf(Color.parseColor("#D946EF"), Color.parseColor("#22D3EE"), Color.parseColor("#A855F7"), Color.parseColor("#EC4899"))
                for (i in 0 until 8) {
                    val fromLeft = i % 2 == 0
                    val x = if (fromLeft) -w * 0.05f else w * 1.05f
                    val y = h * (0.15f + 0.1f * i)
                    val base = if (fromLeft) -12.0 else 192.0
                    beam(c, x, y, base + 18 * sin(t * 0.35 + i), w * 1.3f, h * 0.05f, cols[i % cols.size], 150)
                }
            }
            "laser" -> {
                c.drawColor(Color.parseColor("#030805"))
                glow(c, w * 0.5f, h * 1.05f, h * 0.8f, Color.parseColor("#16A34A"), 150)
                p.style = Paint.Style.STROKE
                for (i in 0 until 26) {
                    val a = Math.toRadians(-160.0 + i * 140.0 / 25 + 6 * sin(t * 0.5 + i * 0.3))
                    val len = h * 1.6f
                    val x = w * 0.5f; val y = h * 1.02f
                    p.shader = LinearGradient(x, y, x + (cos(a) * len).toFloat(), y + (sin(a) * len).toFloat(),
                        Color.argb(200, 74, 222, 128), Color.argb(0, 74, 222, 128), Shader.TileMode.CLAMP)
                    p.strokeWidth = if (i % 5 == 0) 3f else 1.4f
                    c.drawLine(x, y, x + (cos(a) * len).toFloat(), y + (sin(a) * len).toFloat(), p)
                }
                p.shader = null
                for (i in 0 until 3) beam(c, w * (0.2f + 0.3f * i), -h * 0.05f, 90.0 + 25 * sin(t * 0.4 + i * 2), h * 1.2f, h * 0.06f, Color.parseColor("#2DD4BF"), 90)
            }
            else -> {
                // show de luzes: palco escuro, feixes coloridos, confete e o público
                p.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#050816"), Color.parseColor("#120A2A"), Shader.TileMode.CLAMP)
                p.style = Paint.Style.FILL; c.drawRect(0f, 0f, w, h, p); p.shader = null
                glow(c, w * 0.3f, h * 0.72f, h * 0.5f, Color.parseColor("#EC4899"), 120)
                glow(c, w * 0.7f, h * 0.7f, h * 0.5f, Color.parseColor("#3B82F6"), 120)
                glow(c, w * 0.5f, h * 0.65f, h * 0.35f, Color.parseColor("#22D3EE"), 90)
                val cols = intArrayOf(Color.parseColor("#EF4444"), Color.parseColor("#FACC15"), Color.parseColor("#22C55E"),
                    Color.parseColor("#22D3EE"), Color.parseColor("#6366F1"), Color.parseColor("#D946EF"))
                for (i in 0 until 12) {
                    val x = w * (0.04f + 0.92f * i / 11)
                    val base = if (i < 6) 60.0 else 120.0
                    beam(c, x, -h * 0.02f, base + 22 * sin(t * 0.45 + i * 0.9), h * 1.15f, h * 0.07f, cols[i % cols.size], 125)
                }
                // confete caindo
                p.style = Paint.Style.FILL
                for (f in confetti) {
                    val y = ((f[1] + t * f[2]) % 1.0).toFloat() * h
                    val x = f[0] * w + 10f * sin(t + f[4]).toFloat()
                    p.color = confettiColors[f[3].toInt()]
                    c.save(); c.rotate((t * 90 + f[4] * 60).toFloat(), x, y)
                    c.drawRect(x - 4f, y - 2f, x + 4f, y + 2f, p)
                    c.restore()
                }
                // público (silhueta)
                p.color = Color.parseColor("#02030A")
                val crowd = Path().apply {
                    moveTo(0f, h)
                    var x = 0f; var k = 0
                    while (x <= w + 40) {
                        val head = h * (0.86f + 0.04f * sin(k * 1.7f).toFloat())
                        lineTo(x, head + 14f); quadTo(x + 14f, head - 10f, x + 28f, head + 14f)
                        if (k % 4 == 1) { lineTo(x + 30f, head - 50f - 10f * sin(t * 2 + k).toFloat()); lineTo(x + 36f, head - 50f); lineTo(x + 34f, head + 10f) }
                        x += 34f; k++
                    }
                    lineTo(w, h); close()
                }
                c.drawPath(crowd, p)
            }
        }
        // anima a uns 25 quadros por segundo só enquanto aparece
        if (visibility == VISIBLE && isShown) {
            val now = System.nanoTime()
            val wait = 40L - (now - lastFrame) / 1_000_000
            lastFrame = now
            postInvalidateDelayed(wait.coerceIn(0, 40))
        }
    }
}
