package com.mrcerise.blockgame

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.sin

/** Tiny synthesized sound effects — no audio assets required. */
class SoundManager {

    var enabled = true

    private val sampleRate = 44100

    /**
     * Every clip is deterministic, so it is synthesized at most once and then reused.
     * Both the synthesis and the playback happen off the UI thread: generating a clip
     * costs tens of thousands of sin()/exp() calls, and doing that inline used to stall
     * the frame on exactly the taps that trigger the most animation (line clear, game
     * over).
     */
    private val clips = ConcurrentHashMap<String, ShortArray>()

    private val threadFactory = ThreadFactory { r ->
        Thread(r, "sfx").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /**
     * Up to 3 clips may overlap; anything beyond that is dropped rather than queued,
     * so a burst of sounds can never build up a backlog of sleeping threads.
     */
    private val executor = ThreadPoolExecutor(
        0, 3, 5L, TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(),
        threadFactory,
        ThreadPoolExecutor.DiscardPolicy()
    )

    private fun tone(freq: Double, durationMs: Int, volume: Float, sweepTo: Double = freq, delayMs: Int = 0): ShortArray {
        val n = sampleRate * (durationMs + delayMs) / 1000
        val out = ShortArray(n)
        val delaySamples = sampleRate * delayMs / 1000
        val dur = durationMs / 1000.0
        for (i in 0 until n) {
            val t = (i - delaySamples).coerceAtLeast(0) / sampleRate.toDouble()
            if (i < delaySamples) continue
            val p = (t / dur).coerceIn(0.0, 1.0)
            val f = freq + (sweepTo - freq) * p
            val env = exp(-4.5 * p) * (1.0 - 0.35 * p)
            val v = sin(2.0 * Math.PI * f * t) * env * volume
            out[i] = (v * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun mix(vararg parts: ShortArray): ShortArray {
        val n = parts.maxOf { it.size }
        val out = ShortArray(n)
        for (p in parts) {
            for (i in p.indices) {
                out[i] = (out[i].toInt() + p[i].toInt()).coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }

    /** Synthesize (once) and play [key]; all work happens on a background thread. */
    private fun play(key: String, build: () -> ShortArray) {
        if (!enabled) return
        try {
            executor.execute {
                try {
                    val data = clips.getOrPut(key, build)
                    val track = AudioTrack(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                        data.size * 2,
                        AudioTrack.MODE_STATIC,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                    )
                    track.write(data, 0, data.size)
                    track.play()
                    val durationMs = data.size * 1000L / sampleRate
                    Thread.sleep(durationMs + 120)
                    track.release()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun pickup() = play("pickup") { tone(520.0, 60, 0.12f, sweepTo = 720.0) }

    fun place() = play("place") {
        mix(
            tone(240.0, 90, 0.25f, sweepTo = 170.0),
            tone(480.0, 70, 0.10f, sweepTo = 380.0, delayMs = 12)
        )
    }

    fun invalid() = play("invalid") { tone(150.0, 120, 0.18f, sweepTo = 110.0) }

    fun click() = play("click") { tone(660.0, 50, 0.14f, sweepTo = 880.0) }

    fun clear(lines: Int) {
        val tier = if (lines >= 4) 4 else if (lines >= 2) 2 else 1
        play("clear$tier") {
            val base = when (tier) {
                4 -> doubleArrayOf(523.0, 659.0, 784.0, 1047.0, 1319.0)
                2 -> doubleArrayOf(523.0, 659.0, 784.0, 1047.0)
                else -> doubleArrayOf(523.0, 784.0, 1047.0)
            }
            mix(*Array(base.size) { i ->
                tone(base[i], 140, 0.22f, sweepTo = base[i] * 1.02, delayMs = i * 55)
            })
        }
    }

    fun gameOver() = play("gameOver") {
        mix(
            tone(392.0, 220, 0.22f, sweepTo = 330.0),
            tone(330.0, 260, 0.22f, sweepTo = 262.0, delayMs = 200),
            tone(262.0, 420, 0.22f, sweepTo = 196.0, delayMs = 430)
        )
    }
}
