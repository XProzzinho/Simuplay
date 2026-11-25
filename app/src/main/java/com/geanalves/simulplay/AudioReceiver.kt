package com.geanalves.simulplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Modo caixinha na TV: recebe o som ao vivo do celular e toca no horário certo,
 * pra todas as TVs ficarem juntas.
 */
class AudioReceiver(
    private val clockOffset: () -> Long,   // relógio do celular - relógio da TV
    private val adjMs: () -> Int,          // ajuste fino dessa TV
) {
    private class Pkt(val ts: Long, val data: ByteArray)

    @Volatile private var running = false
    @Volatile private var gen = 0
    private var socket: Socket? = null
    private val queue = ArrayBlockingQueue<Pkt>(150)

    fun isRunning() = running

    /** força do som agora, em 16 faixas (0 a 1), pra animação da tela */
    @Volatile var bands = FloatArray(16); private set

    fun start(host: String, delayMs: Int) {
        stop()
        val my = ++gen
        running = true
        queue.clear()
        thread(name = "audio-net", isDaemon = true) {
            while (running && my == gen) {
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(host, Proto.AUDIO_PORT), 3000)
                    s.tcpNoDelay = true
                    s.receiveBufferSize = 64 * 1024
                    socket = s
                    val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 32 * 1024))
                    while (running && my == gen) {
                        val ts = inp.readLong()
                        val len = inp.readInt()
                        if (len <= 0 || len > 1_000_000) break
                        val data = ByteArray(len)
                        inp.readFully(data)
                        val p = Pkt(ts, data)
                        if (!queue.offer(p)) { queue.poll(); queue.offer(p) }
                    }
                } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
                if (running && my == gen) try { Thread.sleep(500) } catch (_: Exception) {}
            }
        }
        thread(name = "audio-play", isDaemon = true, priority = Thread.MAX_PRIORITY) { playLoop(delayMs, my) }
    }

    fun stop() {
        running = false
        gen++
        try { socket?.close() } catch (_: Exception) {}
    }

    /** mede o volume de cada pedacinho do áudio (16 partes) */
    private fun measure(d: ByteArray): FloatArray {
        val out = FloatArray(16)
        val frames = d.size / 4
        if (frames < 16) return out
        val per = frames / 16
        for (b in 0 until 16) {
            var sum = 0.0
            var n = 0
            var f = b * per
            val end = f + per
            while (f < end) {
                val i = f * 4
                val l = (d[i].toInt() and 0xff) or (d[i + 1].toInt() shl 8)
                val r = (d[i + 2].toInt() and 0xff) or (d[i + 3].toInt() shl 8)
                val m = (l.toShort() + r.toShort()) / 2.0
                sum += m * m
                n++
                f += 2
            }
            out[b] = (Math.sqrt(sum / n) / 9000.0).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }

    private fun playLoop(delayMs: Int, my: Int) {
        val rate = Proto.SAMPLE_RATE
        val bytesPerFrame = 4
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, rate * bytesPerFrame * 100 / 1000)
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
            bufSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        var written = 0L
        val stamp = android.media.AudioTimestamp()
        val silence = ByteArray(rate * bytesPerFrame / 10) // 100 ms
        try {
            track.play()
            while (running && my == gen) {
                val p = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                // horário na TV em que esse pedaço deve sair no alto-falante
                val target = p.ts - clockOffset() + delayMs + adjMs()
                // quando o pedaço vai sair de verdade (conta também o atraso de caixa Bluetooth)
                val nowEl = SystemClock.elapsedRealtime()
                val playAt = if (track.getTimestamp(stamp) && stamp.framePosition > 0) {
                    val stampEl = nowEl + (stamp.nanoTime - System.nanoTime()) / 1_000_000
                    stampEl + (written - stamp.framePosition).coerceAtLeast(0) * 1000 / rate
                } else {
                    val head = track.playbackHeadPosition.toLong() and 0xffffffffL
                    nowEl + (written - head).coerceAtLeast(0) * 1000 / rate
                }
                val late = playAt - target
                if (late > 60) continue                       // atrasado: pula esse pedaço
                if (late < -30) {                              // adiantado: espera com silêncio
                    val ms = (-late).coerceAtMost(100)
                    val n = (ms * rate / 1000 * bytesPerFrame).toInt()
                    track.write(silence, 0, n)
                    written += n / bytesPerFrame
                }
                track.write(p.data, 0, p.data.size)
                written += p.data.size / bytesPerFrame
                bands = measure(p.data)
            }
        } catch (_: Exception) {
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }
}
