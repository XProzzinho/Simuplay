package com.geanalves.simulplay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Caixa de som Bluetooth da TV: procura, pareia e conecta a caixa que o celular escolher.
 * Tudo que muda é mandado de volta pro celular.
 */
@SuppressLint("MissingPermission")
class BtManager(private val act: Activity, private val send: (JSONObject) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (act.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var a2dp: BluetoothA2dp? = null
    private val found = LinkedHashMap<String, BluetoothDevice>()
    private var scanning = false
    private var pendingConnect: String? = null
    private var pendingOp: JSONObject? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (canPlay(d)) {
                        found[d.address] = d
                        pushState()
                    }
                }
                BluetoothDevice.ACTION_NAME_CHANGED -> {
                    @Suppress("DEPRECATION")
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (canPlay(d)) { found[d.address] = d; pushState() }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanning = false
                    if (found.isEmpty()) msg(
                        if (canScan()) "Não achei nenhuma caixa. Coloque a caixa em modo de pareamento e procure de novo."
                        else "A TV não deixou o app procurar. Toque em \"Parear pela TV\" e escolha a caixa com o controle; depois ela aparece aqui.",
                    )
                    pushState()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> pushState()
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    @Suppress("DEPRECATION")
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (d.address == pendingConnect) {
                        when (st) {
                            BluetoothDevice.BOND_BONDED -> main.postDelayed({ connectA2dp(d) }, 800)
                            BluetoothDevice.BOND_NONE -> { pendingConnect = null; msg("Não deu pra parear com ${safeName(d) ?: "a caixa"}. Deixe ela piscando (modo de pareamento) e tente de novo.") }
                        }
                    }
                    pushState()
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    @Suppress("DEPRECATION")
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val st = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (st == BluetoothProfile.STATE_CONNECTED && d != null) {
                        if (d.address == pendingConnect) pendingConnect = null
                        msg("Som da TV saindo na ${safeName(d) ?: "caixa"} ✔")
                    }
                    pushState()
                }
            }
        }
    }

    fun start() {
        if (adapter == null || registered) return
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        try { act.registerReceiver(receiver, f); registered = true } catch (_: Exception) {}
        if (canConnect()) openProxy()
    }

    fun stop() {
        if (registered) try { act.unregisterReceiver(receiver) } catch (_: Exception) {}
        registered = false
        try { if (scanning) adapter?.cancelDiscovery() } catch (_: Exception) {}
        a2dp?.let { try { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } catch (_: Exception) {} }
    }

    private fun openProxy() {
        if (a2dp != null) return
        try {
            adapter?.getProfileProxy(act, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) { a2dp = proxy as BluetoothA2dp; pushState() }
                override fun onServiceDisconnected(p: Int) { a2dp = null }
            }, BluetoothProfile.A2DP)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ permissões

    private fun granted(p: String) = act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** ver e conectar as caixas pareadas: até o Android 11 não precisa pedir nada */
    fun canConnect() = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)

    /** procurar caixas novas: precisa de Localização (Android 6 a 11) ou "dispositivos por perto" (12+) */
    fun canScan() = when {
        Build.VERSION.SDK_INT >= 31 -> granted(Manifest.permission.BLUETOOTH_SCAN)
        Build.VERSION.SDK_INT >= 23 -> granted(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> true
    }

    /** pede sem travar nada: se a TV não mostrar o pedido, o resto continua funcionando */
    private fun ask(forScan: Boolean) {
        if (Build.VERSION.SDK_INT < 23) return
        val list = when {
            Build.VERSION.SDK_INT >= 31 -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            forScan -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> emptyList()
        }.filter { !granted(it) }
        if (list.isEmpty()) return
        try { act.requestPermissions(list.toTypedArray(), REQ_PERM) } catch (_: Exception) {}
    }

    fun onPermissionResult() {
        if (canConnect()) openProxy()
        pendingOp?.let { pendingOp = null; handle(it) }
        pushState()
    }

    // ------------------------------------------------------------------ comandos do celular

    fun handle(j: JSONObject) {
        if (adapter == null) { msg("Essa TV não tem Bluetooth."); pushState(); return }
        val op = j.optString("op")
        when (op) {
            "settings" -> { openSettings(); return }
            "appsettings" -> { openAppSettings(); return }
            "ask" -> { ask(forScan = true); pushState(); return }
        }
        // Android 12+ sem permissão: pede, e faz o pedido de novo quando aceitar
        if (!canConnect()) {
            pendingOp = j
            ask(forScan = false)
            pushState()
            return
        }
        openProxy()
        when (op) {
            "state" -> pushState()
            "scan" -> { if (!canScan()) ask(forScan = true); scan() }
            "connect" -> connect(j.optString("addr"))
            "disconnect" -> disconnect(j.optString("addr"))
        }
    }

    private fun ensureOn(): Boolean {
        val a = adapter ?: return false
        if (a.isEnabled) return true
        @Suppress("DEPRECATION")
        val ok = Build.VERSION.SDK_INT < 33 && try { a.enable() } catch (_: Exception) { false }
        if (!ok) {
            try { act.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {}
            msg("Ligue o Bluetooth na TV (aperte OK no controle).")
        } else msg("Ligando o Bluetooth da TV…")
        return false
    }

    private fun scan() {
        val a = adapter ?: return
        if (!ensureOn()) { main.postDelayed({ if (a.isEnabled) scan() }, 2500); return }
        if (Build.VERSION.SDK_INT in 29..30) {
            val lm = act.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            if (lm != null && !lm.isLocationEnabled) {
                msg("Nessa TV o Android só acha caixas com a Localização ligada. Abri as configurações: ligue a Localização e procure de novo.")
                try { act.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
            }
        }
        try {
            if (a.isDiscovering) a.cancelDiscovery()
            found.clear()
            scanning = a.startDiscovery()
            if (!scanning) msg("A TV não conseguiu procurar agora. Tente de novo.")
        } catch (e: Exception) { msg("Erro ao procurar: ${e.message}") }
        pushState()
        main.postDelayed({ if (scanning) { try { a.cancelDiscovery() } catch (_: Exception) {}; scanning = false; pushState() } }, 15_000)
    }

    private fun bondedWith(addr: String): BluetoothDevice? =
        try { adapter?.bondedDevices?.firstOrNull { it.address.equals(addr, true) } } catch (_: Exception) { null }

    private fun device(addr: String): BluetoothDevice? =
        bondedWith(addr) ?: found[addr] ?: try { adapter?.getRemoteDevice(addr) } catch (_: Exception) { null }

    private fun connect(addr: String) {
        val a = adapter ?: return
        if (!ensureOn()) return
        val d = device(addr) ?: return
        try { if (a.isDiscovering) a.cancelDiscovery() } catch (_: Exception) {}
        scanning = false
        pendingConnect = addr
        // já pareada (aparece na lista da TV): vai direto pro conectar
        val already = bondedWith(addr) != null || d.bondState == BluetoothDevice.BOND_BONDED
        if (!already) {
            msg("Pareando com ${safeName(d) ?: "a caixa"}…")
            val ok = try { d.createBond() } catch (_: Exception) { false }
            if (!ok) fallback(d) // não deixou parear pelo app: abre o Bluetooth da TV
            pushState()
            return
        }
        connectA2dp(d)
    }

    /** conecta o som (perfil de áudio); se o Android não deixar, abre o Bluetooth da TV */
    private fun connectA2dp(d: BluetoothDevice) {
        val p = a2dp
        if (p == null) { openProxy(); main.postDelayed({ if (a2dp != null) connectA2dp(d) else fallback(d) }, 1200); return }
        msg("Conectando ${safeName(d) ?: "a caixa"}…")
        // só uma caixa por vez
        for (o in p.connectedDevices) if (o.address != d.address) callHidden(p, "disconnect", o)
        if (!callHidden(p, "connect", d)) { fallback(d); return }
        main.postDelayed({
            if (a2dp?.getConnectionState(d) != BluetoothProfile.STATE_CONNECTED && pendingConnect == d.address) fallback(d)
        }, 9000)
        pushState()
    }

    private fun disconnect(addr: String) {
        val d = device(addr) ?: return
        val p = a2dp
        if (p != null && callHidden(p, "disconnect", d)) { msg("Caixa desconectada. O som volta pro alto-falante da TV."); main.postDelayed({ pushState() }, 1500); return }
        openSettings()
        msg("Na TV, escolha a caixa e toque em Desconectar.")
    }

    private fun fallback(d: BluetoothDevice) {
        pendingConnect = null
        if (a2dp?.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED) { pushState(); return }
        openSettings()
        msg("Essa TV não deixa o app conectar sozinho. Abri o Bluetooth na TV: escolha \"${safeName(d) ?: "a caixa"}\" com o controle.")
    }

    private fun openAppSettings() {
        try {
            act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${act.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            msg("Abri as configurações do Simulplay na TV: entre em Permissões e libere Bluetooth/Dispositivos por perto.")
        } catch (_: Exception) { openSettings() }
    }

    private fun openSettings() {
        try { act.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { try { act.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {} }
    }

    private fun callHidden(p: BluetoothA2dp, name: String, d: BluetoothDevice): Boolean = try {
        val m = p.javaClass.getMethod(name, BluetoothDevice::class.java)
        (m.invoke(p, d) as? Boolean) ?: true
    } catch (_: Throwable) { false }

    // ------------------------------------------------------------------ estado

    private fun safeName(d: BluetoothDevice): String? = try { d.name } catch (_: Exception) { null }

    private fun isAudio(d: BluetoothDevice): Boolean {
        val c: BluetoothClass = try { d.bluetoothClass } catch (_: Exception) { null } ?: return false
        return c.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
            c.hasService(BluetoothClass.Service.AUDIO) || c.hasService(BluetoothClass.Service.RENDER)
    }

    /** pode tocar som? Tudo com nome, menos celular, computador e controle remoto (a Echo Dot entra aqui) */
    private fun canPlay(d: BluetoothDevice): Boolean {
        if (isAudio(d)) return true
        val name = safeName(d) ?: return false
        if (name.contains("echo", true) || name.contains("alexa", true)) return true
        val major = try { d.bluetoothClass?.majorDeviceClass } catch (_: Exception) { null } ?: return true
        return major != BluetoothClass.Device.Major.PHONE && major != BluetoothClass.Device.Major.COMPUTER &&
            major != BluetoothClass.Device.Major.PERIPHERAL && major != BluetoothClass.Device.Major.IMAGING
    }

    private fun dev(d: BluetoothDevice) = JSONObject().put("addr", d.address).put("name", safeName(d) ?: d.address)

    fun pushState() {
        val a = adapter
        val st = JSONObject().put("t", "bt")
        val s = JSONObject()
        s.put("has", a != null)
        s.put("perm", canConnect())
        s.put("scanPerm", canScan())
        s.put("sdk", Build.VERSION.SDK_INT)
        if (a != null && canConnect()) {
            s.put("on", try { a.isEnabled } catch (_: Exception) { false })
            s.put("scanning", scanning)
            val connected = try { a2dp?.connectedDevices ?: emptyList() } catch (_: Exception) { emptyList() }
            s.put("connected", JSONArray().apply { connected.forEach { put(dev(it)) } })
            val bonded = try { a.bondedDevices?.filter { canPlay(it) }?.sortedByDescending { isAudio(it) } ?: emptyList() } catch (_: Exception) { emptyList() }
            s.put("paired", JSONArray().apply { bonded.forEach { put(dev(it)) } })
            val bondedAddr = bonded.map { it.address }.toSet()
            s.put("found", JSONArray().apply {
                found.values.filter { it.address !in bondedAddr }.sortedByDescending { isAudio(it) }.forEach { put(dev(it)) }
            })
            s.put("pending", pendingConnect ?: "")
        }
        st.put("state", s)
        send(st)
    }

    private fun msg(text: String) = send(JSONObject().put("t", "bt").put("msg", text))

    companion object { const val REQ_PERM = 51 }
}
