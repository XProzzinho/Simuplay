package com.geanalves.simulplay

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

object Proto {
    const val DISCOVERY_PORT = 47800
    const val CONTROL_PORT = 47801
    const val HTTP_PORT = 47802
    const val AUDIO_PORT = 47803
    const val VIDEO_PORT = 47804
    const val SAMPLE_RATE = 48000
}

/**
 * Acha o Transmiplay no Wi-Fi, conecta e acerta o relógio da TV com o do celular.
 */
class Link(private val ctx: Context, @Volatile var myName: String, private val listener: Listener) {
    interface Listener {
        fun onSearching()
        fun onConnected(phoneName: String, host: String)
        fun onDisconnected()
        fun onMessage(j: JSONObject)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var out: OutputStream? = null
    private val sender = Executors.newSingleThreadExecutor()

    /** relógio do celular = relógio da TV + offset */
    @Volatile var offset = 0L; private set
    @Volatile var synced = false; private set
    private val samples = ArrayDeque<Pair<Long, Long>>() // (rtt, offset)

    fun phoneNow(): Long = SystemClock.elapsedRealtime() + offset

    fun start() {
        if (running) return
        running = true
        thread(name = "link", isDaemon = true) { loop() }
    }

    /** troca o nome e avisa o celular */
    fun rename(n: String) {
        myName = n
        send(JSONObject().put("t", "hello").put("name", n))
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun loop() {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wm.createMulticastLock("simulplay").apply { setReferenceCounted(false) }
        while (running) {
            main.post { listener.onSearching() }
            lock.acquire()
            val found = discover()
            lock.release()
            if (found == null) continue
            val (host, port, name) = found
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 3000)
                s.tcpNoDelay = true
                socket = s
                out = s.getOutputStream()
                synchronized(samples) { samples.clear() }
                synced = false
                send(JSONObject().put("t", "hello").put("name", myName))
                main.post { listener.onConnected(name, host) }
                val pinger = thread(name = "ping", isDaemon = true) { pingLoop(s) }
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                while (running) {
                    val line = r.readLine() ?: break
                    val j = try { JSONObject(line) } catch (_: Exception) { continue }
                    if (j.optString("t") == "pong") onPong(j) else main.post { listener.onMessage(j) }
                }
                pinger.interrupt()
            } catch (_: Exception) {
            }
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            main.post { listener.onDisconnected() }
            if (running) try { Thread.sleep(1000) } catch (_: Exception) {}
        }
    }

    private fun discover(): Triple<String, Int, String>? {
        var ds: DatagramSocket? = null
        try {
            ds = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 2000
                bind(InetSocketAddress(Proto.DISCOVERY_PORT))
            }
            val buf = ByteArray(512)
            val end = SystemClock.elapsedRealtime() + 4000
            while (running && SystemClock.elapsedRealtime() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { ds.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                val msg = String(p.data, 0, p.length)
                if (!msg.startsWith("TRANSMIPLAY|")) continue
                val parts = msg.split("|")
                val port = parts.getOrNull(2)?.toIntOrNull() ?: Proto.CONTROL_PORT
                return Triple(p.address.hostAddress ?: continue, port, parts.getOrNull(1) ?: "Celular")
            }
        } catch (_: Exception) {
            try { Thread.sleep(1000) } catch (_: Exception) {}
        } finally {
            ds?.close()
        }
        return null
    }

    private fun pingLoop(s: Socket) {
        try {
            var n = 0
            while (running && !s.isClosed) {
                send(JSONObject().put("t", "ping").put("c", SystemClock.elapsedRealtime()))
                n++
                Thread.sleep(if (n < 12) 120 else 2000)
            }
        } catch (_: Exception) {}
    }

    private fun onPong(j: JSONObject) {
        val now = SystemClock.elapsedRealtime()
        val c = j.optLong("c")
        val sv = j.optLong("s")
        val rtt = now - c
        if (rtt < 0 || rtt > 2000) return
        val off = sv - (c + rtt / 2)
        synchronized(samples) {
            samples.addLast(rtt to off)
            while (samples.size > 20) samples.removeFirst()
            // a medida com menor tempo de ida e volta é a mais precisa
            offset = samples.minByOrNull { it.first }!!.second
        }
        synced = true
    }

    fun send(j: JSONObject) {
        val o = out ?: return
        val bytes = (j.toString() + "\n").toByteArray()
        sender.execute {
            try { o.write(bytes); o.flush() } catch (_: Exception) {}
        }
    }
}
