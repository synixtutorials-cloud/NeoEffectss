package com.cusil.neoeffects

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun audioDurationMs(ctx: Context, uri: Uri): Long {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        try { r.release() } catch (e: Exception) { }
    }
}

fun extractAudio(ctx: Context, uri: Uri, out: File): Boolean {
    val ex = MediaExtractor()
    var mux: MediaMuxer? = null
    var started = false
    return try {
        ex.setDataSource(ctx, uri, null)
        var ai = -1
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                ai = i
                break
            }
        }
        if (ai < 0) {
            false
        } else {
            ex.selectTrack(ai)
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            val ti = m.addTrack(ex.getTrackFormat(ai))
            m.start()
            started = true
            val buf = ByteBuffer.allocate(1 shl 18)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, ex.sampleTime, ex.sampleFlags)
                m.writeSampleData(ti, buf, info)
                ex.advance()
            }
            true
        }
    } catch (e: Exception) {
        false
    } finally {
        try { if (started) mux?.stop() } catch (e: Exception) { }
        try { mux?.release() } catch (e: Exception) { }
        try { ex.release() } catch (e: Exception) { }
    }
}

// Simple energy-onset beat detector (decodes the audio track and looks for sudden loudness jumps)
fun detectBeats(ctx: Context, uri: Uri): List<Long> {
    val ex = MediaExtractor()
    var codec: MediaCodec? = null
    val beats = ArrayList<Long>()
    try {
        ex.setDataSource(ctx, uri, null)
        var ai = -1
        for (i in 0 until ex.trackCount) {
            if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                ai = i
                break
            }
        }
        if (ai < 0) return beats
        ex.selectTrack(ai)
        val fmt = ex.getTrackFormat(ai)
        val sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val ch = maxOf(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec = c
        c.configure(fmt, null, null, 0)
        c.start()
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        val hop = maxOf(1, sr / 50)
        val energy = ArrayList<Float>()
        var acc = 0.0
        var cnt = 0
        var frames = 0L
        val maxFrames = sr.toLong() * 900L
        while (!outDone && frames < maxFrames) {
            if (!inDone) {
                val ib = c.dequeueInputBuffer(10000)
                if (ib >= 0) {
                    val b = c.getInputBuffer(ib)!!
                    val n = ex.readSampleData(b, 0)
                    if (n < 0) {
                        c.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        c.queueInputBuffer(ib, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val ob = c.dequeueOutputBuffer(info, 10000)
            if (ob >= 0) {
                val b = c.getOutputBuffer(ob)
                if (b != null && info.size > 0) {
                    val sb = b.order(ByteOrder.nativeOrder()).asShortBuffer()
                    var k = 0
                    var mono = 0f
                    while (sb.hasRemaining()) {
                        mono += sb.get().toFloat() / 32768f
                        k++
                        if (k == ch) {
                            val v = mono / ch
                            acc += (v * v).toDouble()
                            cnt++
                            frames++
                            if (cnt >= hop) {
                                energy.add(Math.sqrt(acc / cnt).toFloat())
                                acc = 0.0
                                cnt = 0
                            }
                            k = 0
                            mono = 0f
                        }
                    }
                }
                c.releaseOutputBuffer(ob, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
            }
        }
        val flux = FloatArray(energy.size)
        for (i in 1 until energy.size) flux[i] = maxOf(0f, energy[i] - energy[i - 1])
        var last = -100
        for (i in 2 until flux.size) {
            val a = maxOf(0, i - 25)
            val b = minOf(flux.size - 1, i + 25)
            var mean = 0f
            for (j in a..b) mean += flux[j]
            mean /= (b - a + 1)
            if (flux[i] > mean * 1.7f + 0.004f && flux[i] >= flux[i - 1] && flux[i] >= flux[minOf(flux.size - 1, i + 1)] && i - last >= 12) {
                beats.add(i * 20L)
                last = i
            }
        }
    } catch (e: Exception) {
    } finally {
        try { codec?.stop() } catch (e: Exception) { }
        try { codec?.release() } catch (e: Exception) { }
        try { ex.release() } catch (e: Exception) { }
    }
    return beats
}
