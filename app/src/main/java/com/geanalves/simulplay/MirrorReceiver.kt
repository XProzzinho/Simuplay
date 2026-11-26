package com.geanalves.simulplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Espelhar a tela: recebe a imagem do celular (H.264) e mostra na hora certa,
 * no mesmo relógio do som, pra todas as TVs ficarem juntas.
 */
class MirrorReceiver(
    private val clockOffset: () -> Long,
    private val adjMs: () -> Int,
) {
    @Volatile private var running = false
    @Volatile private var gen = 0
    @Volatile var delayMs = 300
    @Volatile var width = 1280
    @Volatile var height = 720
    private var socket: Socket? = null
    @Volatile private var codec: MediaCodec? = null

    fun start(host: String, surface: Surface) {
        stop()
        val my = ++gen
        running = true
        thread(name = "mirror-net", isDaemon = true, priority = Thread.MAX_PRIORITY) {
            while (running && my == gen) {
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(host, Proto.VIDEO_PORT), 3000)
                    s.tcpNoDelay = true
                    s.receiveBufferSize = 512 * 1024
                    socket = s
                    val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 128 * 1024))
                    var lastConfig: ByteArray? = null
                    var ready = false
                    while (running && my == gen) {
                        val ts = inp.readLong()
                        val flags = inp.readInt()
                        val len = inp.readInt()
                        if (len <= 0 || len > 8_000_000) break
                        val data = ByteArray(len)
                        inp.readFully(data)
                        if (flags and 1 != 0) {
                            // configuração nova (começo ou girou o celular): recria o decodificador
                            if (lastConfig == null || !lastConfig.contentEquals(data) || codec == null) {
                                lastConfig = data
                                newCodec(surface, data, my)
                                ready = false
                            }
                            continue
                        }
                        val c = codec ?: continue
                        if (!ready) { if (flags and 2 == 0) continue; ready = true }
                        try {
                            val idx = c.dequeueInputBuffer(30_000)
                            if (idx >= 0) {
                                val b = c.getInputBuffer(idx)!!
                                b.clear(); b.put(data)
                                c.queueInputBuffer(idx, 0, len, ts * 1000, 0)
                            }
                        } catch (_: IllegalStateException) { ready = false }
                    }
                } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
                if (running && my == gen) try { Thread.sleep(400) } catch (_: Exception) {}
            }
            releaseCodec()
        }
    }

    private fun newCodec(surface: Surface, config: ByteArray, my: Int) {
        releaseCodec()
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        c.configure(fmt, surface, null, 0)
        c.start()
        val idx = c.dequeueInputBuffer(500_000)
        if (idx >= 0) {
            c.getInputBuffer(idx)!!.apply { clear(); put(config) }
            c.queueInputBuffer(idx, 0, config.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        }
        codec = c
        thread(name = "mirror-out", isDaemon = true, priority = Thread.MAX_PRIORITY) { outputLoop(c, my) }
    }

    private fun outputLoop(c: MediaCodec, my: Int) {
        val info = MediaCodec.BufferInfo()
        var lateRun = 0
        try {
            while (running && my == gen && codec === c) {
                val idx = c.dequeueOutputBuffer(info, 30_000)
                if (idx < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || info.presentationTimeUs == 0L) {
                    c.releaseOutputBuffer(idx, false); continue
                }
                // horário na TV em que esse quadro deve aparecer
                val target = info.presentationTimeUs / 1000 - clockOffset() + delayMs + adjMs()
                val now = SystemClock.elapsedRealtime()
                val late = now - target
                when {
                    late > 150 && lateRun < 20 -> { lateRun++; c.releaseOutputBuffer(idx, false) } // atrasado: pula
                    late > 0 -> { lateRun = 0; c.releaseOutputBuffer(idx, true) }
                    else -> {
                        lateRun = 0
                        val wait = (-late).coerceAtMost(900)
                        c.releaseOutputBuffer(idx, System.nanoTime() + wait * 1_000_000)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun releaseCodec() {
        val c = codec ?: return
        codec = null
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
    }

    fun stop() {
        running = false
        gen++
        try { socket?.close() } catch (_: Exception) {}
        thread(isDaemon = true) { Thread.sleep(150); if (!running) releaseCodec() }
    }
}
