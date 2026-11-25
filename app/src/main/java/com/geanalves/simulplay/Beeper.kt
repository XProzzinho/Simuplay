package com.geanalves.simulplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Apito de calibração: toca um tom curto exatamente no horário pedido pelo celular.
 * Usa a mesma conta de atraso do som (inclusive caixa Bluetooth), então o celular,
 * escutando pelo microfone, descobre quanto essa TV está adiantada ou atrasada.
 */
object Beeper {
    private const val RATE = 48000

    /** @param targetLocal horário (relógio da TV, elapsedRealtime) em que o apito deve SAIR do alto-falante */
    fun beepAt(targetLocal: Long, freq: Double, durMs: Int = 120) {
        thread(name = "beep", isDaemon = true, priority = Thread.MAX_PRIORITY) {
            val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                maxOf(minBuf, RATE * 4 / 10), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            try {
                val stamp = AudioTimestamp()
                var written = 0L
                val chunk = ShortArray(RATE / 100 * 2) // 10 ms de silêncio (estéreo)
                track.play()
                fun endOfQueued(): Long {
                    val now = SystemClock.elapsedRealtime()
                    return if (track.getTimestamp(stamp) && stamp.framePosition > 0) {
                        val el = now + (stamp.nanoTime - System.nanoTime()) / 1_000_000
                        el + (written - stamp.framePosition).coerceAtLeast(0) * 1000 / RATE
                    } else {
                        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
                        now + (written - head).coerceAtLeast(0) * 1000 / RATE
                    }
                }
                // enche com silêncio até faltar pouco pro horário
                var guard = 0
                while (endOfQueued() < targetLocal - 15 && guard++ < 600) {
                    track.write(chunk, 0, chunk.size)
                    written += chunk.size / 2
                }
                // completa o silêncio que falta, no frame exato
                val gap = ((targetLocal - endOfQueued()) * RATE / 1000).toInt().coerceIn(0, RATE)
                if (gap > 0) { track.write(ShortArray(gap * 2), 0, gap * 2); written += gap }
                // o apito
                val n = RATE * durMs / 1000
                val ramp = RATE * 4 / 1000
                val tone = ShortArray(n * 2)
                for (i in 0 until n) {
                    val env = when {
                        i < ramp -> 0.5 - 0.5 * cos(PI * i / ramp)
                        i > n - ramp -> 0.5 - 0.5 * cos(PI * (n - i) / ramp)
                        else -> 1.0
                    }
                    val v = (sin(2 * PI * freq * i / RATE) * env * 0.7 * Short.MAX_VALUE).toInt().toShort()
                    tone[2 * i] = v; tone[2 * i + 1] = v
                }
                track.write(tone, 0, tone.size)
                written += n
                track.write(ShortArray(RATE / 5 * 2), 0, RATE / 5 * 2) // um restinho de silêncio
                Thread.sleep(((targetLocal - SystemClock.elapsedRealtime()).coerceAtLeast(0)) + durMs + 400L)
            } catch (_: Exception) {
            } finally {
                try { track.stop() } catch (_: Exception) {}
                track.release()
            }
        }
    }
}
