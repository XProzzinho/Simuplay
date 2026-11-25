package com.geanalves.simulplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Gera prints da tela da TV (só pra conferir o visual). Rode com -Dshots=1 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ScreensTest {
    private fun shot(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun screens() {
        if (System.getProperty("shots").isNullOrEmpty()) return
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = a.window.decorView.rootView
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        a.onSearching(); shadowOf(Looper.getMainLooper()).idle()
        shot(root, "tv_1_procurando")
        a.onConnected("Galaxy do Gean", "127.0.0.1"); shadowOf(Looper.getMainLooper()).idle()
        shot(root, "tv_2_conectado")
        a.onMessage(JSONObject().put("t", "audio").put("on", true).put("delay", 300))
        shadowOf(Looper.getMainLooper()).idle()
        shot(root, "tv_3_caixinha")
    }
}
