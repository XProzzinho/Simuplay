package com.geanalves.simulplay

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice
import java.time.Duration

/** TV simulada SEM nenhuma permissão dada: as caixas pareadas têm que aparecer mesmo assim. */
@RunWith(RobolectricTestRunner::class)
class BtSimTest {
    private fun tvWithSpeakers(): Triple<Activity, BtManager, MutableList<JSONObject>> {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val adapter = (act.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val jbl = ShadowBluetoothDevice.newInstance("11:22:33:44:55:66").also { shadowOf(it).setName("JBL Flip 5") }
        val echo = ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:FF").also { shadowOf(it).setName("Echo Dot-4KQ") }
        shadowOf(adapter).setBondedDevices(setOf(jbl, echo))
        val sent = mutableListOf<JSONObject>()
        val bt = BtManager(act) { sent += it }
        bt.start()
        return Triple(act, bt, sent)
    }

    private fun lastState(sent: List<JSONObject>) = sent.last { it.has("state") }.getJSONObject("state")
    private fun names(st: JSONObject, key: String) =
        st.getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }

    @Test @Config(sdk = [30])
    fun android11_semPermissao_mostraAsCaixas() {
        val (_, bt, sent) = tvWithSpeakers()
        bt.handle(JSONObject().put("op", "state"))
        val st = lastState(sent)
        println("Android 11 sem permissão -> " + st)
        assertTrue("não pode travar", st.getBoolean("perm"))
        assertFalse(st.getBoolean("scanPerm"))
        val paired = names(st, "paired")
        assertTrue(paired.contains("Echo Dot-4KQ"))
        assertTrue(paired.contains("JBL Flip 5"))
    }

    @Test @Config(sdk = [30])
    fun android11_conectarEcho_semPermissao() {
        val (_, bt, sent) = tvWithSpeakers()
        bt.handle(JSONObject().put("op", "connect").put("addr", "AA:BB:CC:DD:EE:FF"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
        val msgs = sent.filter { it.has("msg") }.map { it.getString("msg") }
        println("Mensagens ao conectar a Echo: $msgs")
        assertTrue("tem que tentar conectar ou abrir o Bluetooth da TV",
            msgs.any { it.contains("Conectando") || it.contains("Abri o Bluetooth") })
        assertFalse(msgs.any { it.contains("permissão", ignoreCase = true) && it.contains("Aceite") })
    }

    @Test @Config(sdk = [30])
    fun android11_procurar_semPermissao_naoTrava() {
        val (_, bt, sent) = tvWithSpeakers()
        bt.handle(JSONObject().put("op", "scan"))
        val st = lastState(sent)
        println("Procurar sem permissão -> scanning=${st.optBoolean("scanning")} pareadas=${names(st, "paired")}")
        assertEquals(2, names(st, "paired").size)
    }

    @Test @Config(sdk = [31])
    fun android12_semPermissao_mostraCaminho() {
        val (_, bt, sent) = tvWithSpeakers()
        bt.handle(JSONObject().put("op", "state"))
        val st = lastState(sent)
        println("Android 12 sem permissão -> " + st)
        assertFalse(st.getBoolean("perm")) // celular mostra o botão "Abrir permissões do Simulplay na TV"
    }

    @Test @Config(sdk = [31])
    fun android12_comPermissao_mostraAsCaixas() {
        val (act, bt, sent) = tvWithSpeakers()
        shadowOf(act.application).grantPermissions("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN")
        bt.handle(JSONObject().put("op", "state"))
        val st = lastState(sent)
        println("Android 12 com permissão -> pareadas=${names(st, "paired")}")
        assertTrue(names(st, "paired").contains("Echo Dot-4KQ"))
    }
}
