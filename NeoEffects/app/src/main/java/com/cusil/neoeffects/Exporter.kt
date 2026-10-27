package com.cusil.neoeffects

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.os.Build
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class ExportCancelled : Exception("Cancelled")

class AudioResult(val format: MediaFormat, val samples: ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>)

object AudioMix {
    const val SR = 44100

    private class Src(val file: File, val startMs: Long, val lenMs: Long, val speed: Float, val tr: FxInst) {
        val raf = RandomAccessFile(file, "r")
        val frames = (file.length() / 4L).toInt()
        var cs = -1
        var cbuf = ShortArray(0)

        fun get(i: Int, ch: Int): Int {
            if (i < 0 || i >= frames) return 0
            if (cs < 0 || i < cs || i >= cs + cbuf.size / 2) {
                val start = maxOf(0, i - 16)
                val n = minOf(16384, frames - start)
                val bytes = ByteArray(n * 4)
                raf.seek(start.toLong() * 4L)
                raf.readFully(bytes)
                val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                cbuf = ShortArray(n * 2)
                sb.get(cbuf)
                cs = start
            }
            return cbuf[(i - cs) * 2 + ch].toInt()
        }

        fun sample(pos: Double, ch: Int): Float {
            val i0 = Math.floor(pos).toInt()
            val f = (pos - i0).toFloat()
            return get(i0, ch) * (1f - f) + get(i0 + 1, ch) * f
        }

        fun close() {
            try { raf.close() } catch (e: Exception) { }
        }
    }

    fun decodeToPcm(ctx: Context, uri: Uri, fromMs: Long, toMs: Long, out: File): Boolean {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var os: BufferedOutputStream? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var ai = -1
            for (i in 0 until ex.trackCount) {
                if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    ai = i
                    break
                }
            }
            if (ai < 0) return false
            ex.selectTrack(ai)
            val fmt = ex.getTrackFormat(ai)
            ex.seekTo(fromMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            os = BufferedOutputStream(FileOutputStream(out), 1 shl 16)
            val info = MediaCodec.BufferInfo()
            var sr0 = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = maxOf(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            var inDone = false
            var outDone = false
            var nextOut = 0.0
            var srcIdx = 0L
            var pl = 0f
            var pr = 0f
            val outBuf = ByteBuffer.allocate(1 shl 15).order(ByteOrder.LITTLE_ENDIAN)
            val fromUs = fromMs * 1000L
            val toUs = toMs * 1000L
            var guard = 0
            while (!outDone && guard < 2_000_000) {
                guard++
                if (!inDone) {
                    val ib = c.dequeueInputBuffer(10000)
                    if (ib >= 0) {
                        val b = c.getInputBuffer(ib)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0 || ex.sampleTime > toUs + 500000L) {
                            c.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ib, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val ob = c.dequeueOutputBuffer(info, 10000)
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    sr0 = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = maxOf(1, of.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                } else if (ob >= 0) {
                    val b = c.getOutputBuffer(ob)
                    if (b != null && info.size > 0) {
                        val sb = b.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val step = sr0.toDouble() / SR
                        var frameInBuf = 0
                        while (sb.remaining() >= ch) {
                            val l = sb.get().toFloat()
                            var r = l
                            if (ch >= 2) r = sb.get().toFloat()
                            for (k in 2 until ch) sb.get()
                            val tUs = info.presentationTimeUs + frameInBuf * 1000000L / sr0
                            frameInBuf++
                            if (tUs < fromUs) {
                                pl = l; pr = r
                                continue
                            }
                            if (tUs > toUs) {
                                outDone = true
                                break
                            }
                            while (nextOut <= srcIdx) {
                                val fr = (nextOut - (srcIdx - 1)).toFloat().coerceIn(0f, 1f)
                                val ol = pl + (l - pl) * fr
                                val orr = pr + (r - pr) * fr
                                if (outBuf.remaining() < 4) {
                                    os.write(outBuf.array(), 0, outBuf.position())
                                    (outBuf as Buffer).clear()
                                }
                                outBuf.putShort(ol.toInt().coerceIn(-32768, 32767).toShort())
                                outBuf.putShort(orr.toInt().coerceIn(-32768, 32767).toShort())
                                nextOut += step
                            }
                            pl = l; pr = r
                            srcIdx++
                        }
                    }
                    c.releaseOutputBuffer(ob, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                }
            }
            if (outBuf.position() > 0) os.write(outBuf.array(), 0, outBuf.position())
            os.flush()
            return out.length() > 4096L
        } catch (e: Exception) {
            return false
        } finally {
            try { os?.close() } catch (e: Exception) { }
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            try { ex.release() } catch (e: Exception) { }
        }
    }

    fun encode(ctx: Context, project: Project, totalMs: Long, cacheDir: File, cancel: AtomicBoolean, onProgress: (Float) -> Unit): AudioResult? {
        val srcs = ArrayList<Src>()
        val tmpFiles = ArrayList<File>()
        var enc: MediaCodec? = null
        try {
            var t = 0L
            var n = 0
            for (c in project.clips) {
                if (cancel.get()) throw ExportCancelled()
                if (c.isVideo) {
                    val f = File(cacheDir, "aud_" + (n++) + ".pcm")
                    tmpFiles.add(f)
                    if (decodeToPcm(ctx, Uri.parse(c.uri), c.inMs, c.outMs, f)) srcs.add(Src(f, t, c.durMs, c.speed, c.tr))
                }
                t += c.durMs
            }
            for (l in project.layers) {
                if (cancel.get()) throw ExportCancelled()
                if (l.type == "audio") {
                    val f = File(cacheDir, "aud_" + (n++) + ".pcm")
                    tmpFiles.add(f)
                    if (decodeToPcm(ctx, Uri.parse(l.uri), 0L, l.lenMs, f)) srcs.add(Src(f, l.startMs, l.lenMs, 1f, l.tr))
                }
            }
            if (srcs.isEmpty()) return null

            val fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", SR, 2)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            val e = MediaCodec.createEncoderByType("audio/mp4a-latm")
            enc = e
            e.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            e.start()

            val outFmtHolder = arrayOfNulls<MediaFormat>(1)
            val samples = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
            val binfo = MediaCodec.BufferInfo()
            fun drain(wait: Boolean): Boolean {
                var eos = false
                while (true) {
                    val idx = e.dequeueOutputBuffer(binfo, if (wait) 5000L else 0L)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outFmtHolder[0] = e.outputFormat
                    } else if (idx >= 0) {
                        val buf = e.getOutputBuffer(idx)
                        if (buf != null && binfo.size > 0 && (binfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val bytes = ByteArray(binfo.size)
                            (buf as Buffer).position(binfo.offset)
                            buf.get(bytes, 0, binfo.size)
                            val bi = MediaCodec.BufferInfo()
                            bi.set(0, bytes.size, binfo.presentationTimeUs, binfo.flags)
                            samples.add(Pair(bytes, bi))
                        }
                        e.releaseOutputBuffer(idx, false)
                        if ((binfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            eos = true
                            break
                        }
                    }
                }
                return eos
            }

            val totalFrames = totalMs * SR / 1000L
            val block = 1024
            var g = 0L
            val mix = IntArray(block * 2)
            val opP = TRANSFORM_FX.params[6]
            var lastProg = 0
            while (g < totalFrames) {
                if (cancel.get()) throw ExportCancelled()
                java.util.Arrays.fill(mix, 0)
                val midMs = (g + block / 2) * 1000L / SR
                for (s in srcs) {
                    if (midMs < s.startMs - 50L || midMs > s.startMs + s.lenMs + 50L) continue
                    val vol = s.tr.valueAt(opP, midMs - s.startMs).coerceIn(0f, 1f)
                    for (j in 0 until block) {
                        val tOut = (g + j).toDouble() / SR
                        val local = tOut - s.startMs / 1000.0
                        if (local < 0.0 || local >= s.lenMs / 1000.0) continue
                        val pos = local * s.speed * SR
                        mix[j * 2] += (s.sample(pos, 0) * vol).toInt()
                        mix[j * 2 + 1] += (s.sample(pos, 1) * vol).toInt()
                    }
                }
                val ib = e.dequeueInputBuffer(10000)
                if (ib >= 0) {
                    val ibuf = e.getInputBuffer(ib)!!
                    (ibuf as Buffer).clear()
                    ibuf.order(ByteOrder.nativeOrder())
                    for (k in mix.indices) ibuf.putShort(mix[k].coerceIn(-32768, 32767).toShort())
                    e.queueInputBuffer(ib, 0, mix.size * 2, g * 1_000_000L / SR, 0)
                    g += block
                }
                drain(false)
                val pg = (g * 100L / totalFrames).toInt()
                if (pg != lastProg) {
                    lastProg = pg
                    onProgress(pg / 100f)
                }
            }
            var queued = false
            var guard = 0
            while (!queued && guard < 1000) {
                guard++
                val ib = e.dequeueInputBuffer(10000)
                if (ib >= 0) {
                    e.queueInputBuffer(ib, 0, 0, g * 1_000_000L / SR, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                }
            }
            var done = false
            var tries = 0
            while (!done && tries < 2000) {
                tries++
                done = drain(true)
            }
            val of = outFmtHolder[0] ?: return null
            return AudioResult(of, samples)
        } finally {
            for (s in srcs) s.close()
            for (f in tmpFiles) f.delete()
            try { enc?.stop() } catch (e: Exception) { }
            try { enc?.release() } catch (e: Exception) { }
        }
    }
}

object Exporter {
    private fun even(v: Int) = maxOf(2, v - v % 2)

    fun run(
        ctx: Context, project: Project, out: File,
        chainTarget: Int, onProgress: (Float) -> Unit, cancel: AtomicBoolean
    ): String {
        val total = project.totalMs
        if (total <= 0L) return "Nothing to export. Add a clip first."
        var w = even(project.width)
        var h = even(project.height)
        var fps = project.fps.coerceIn(10, 120)
        val mime = "video/avc"
        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        var inSurface: android.view.Surface? = null
        var dpy = EGL14.EGL_NO_DISPLAY
        var ectx = EGL14.EGL_NO_CONTEXT
        var esurf = EGL14.EGL_NO_SURFACE
        var gl: GlEngine? = null
        val mmrs = HashMap<String, MediaMetadataRetriever>()
        val dims = HashMap<String, IntArray>()
        val photos = HashMap<String, Bitmap>()
        var muxStarted = false
        var audioNote = ""
        try {
            val e = MediaCodec.createEncoderByType(mime)
            enc = e
            val vc = e.codecInfo.getCapabilitiesForType(mime).videoCapabilities
            var tries = 0
            while (!vc.isSizeSupported(w, h) && tries < 10) {
                w = even((w * 0.85f).toInt())
                h = even((h * 0.85f).toInt())
                tries++
            }
            try {
                val fr = vc.getSupportedFrameRatesFor(w, h)
                fps = minOf(fps, fr.upper.toInt())
            } catch (ex: Exception) {
            }
            val bitrate = (w.toLong() * h.toLong() * fps.toLong() / 8L).toInt().coerceIn(2_000_000, 60_000_000)
            val fmt = MediaFormat.createVideoFormat(mime, w, h)
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            e.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surf = e.createInputSurface()
            inSurface = surf
            e.start()

            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            EGL14.eglInitialize(dpy, ver, 0, ver, 1)
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(dpy, attribs, 0, cfgs, 0, 1, num, 0)
            if (num[0] < 1) return "This phone cannot create an export surface."
            ectx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            esurf = EGL14.eglCreateWindowSurface(dpy, cfgs[0], surf, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(dpy, esurf, esurf, ectx)

            val eng = GlEngine()
            gl = eng
            eng.onSurfaceCreated(null, null)
            eng.onSurfaceChanged(null, w, h)
            eng.canvasAspect = w.toFloat() / h.toFloat()
            eng.bgMode = project.bgMode
            eng.maxSide = 4096
            eng.chainTarget = chainTarget
            if (project.bgMode == 4 && project.bgUri.isNotEmpty()) {
                try { eng.setBgBitmap(loadBitmap(ctx, Uri.parse(project.bgUri), 1600)) } catch (ex: Exception) { }
            }

            // audio first (small), then video
            var audio: AudioResult? = null
            try {
                audio = AudioMix.encode(ctx, project, total, ctx.cacheDir, cancel) { p -> onProgress(p * 0.08f) }
            } catch (ex: ExportCancelled) {
                throw ex
            } catch (ex: Exception) {
                audioNote = " (audio skipped)"
            }

            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            var vTrack = -1
            val info = MediaCodec.BufferInfo()

            fun startMux(vf: MediaFormat) {
                vTrack = m.addTrack(vf)
                var aTrack = -1
                val au = audio
                if (au != null) aTrack = m.addTrack(au.format)
                m.start()
                muxStarted = true
                if (au != null && aTrack >= 0) {
                    for (s in au.samples) m.writeSampleData(aTrack, ByteBuffer.wrap(s.first), s.second)
                }
            }

            fun drain(eos: Boolean) {
                if (eos) e.signalEndOfInputStream()
                var guard = 0
                while (true) {
                    val idx = e.dequeueOutputBuffer(info, if (eos) 10000L else 0L)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!eos) break
                        guard++
                        if (guard > 600) break
                        continue
                    }
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxStarted) startMux(e.outputFormat)
                    } else if (idx >= 0) {
                        val buf = e.getOutputBuffer(idx)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (buf != null && info.size > 0 && muxStarted) {
                            val bb: Buffer = buf
                            bb.position(info.offset)
                            bb.limit(info.offset + info.size)
                            m.writeSampleData(vTrack, buf, info)
                        }
                        e.releaseOutputBuffer(idx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }

            val frames = maxOf(1L, total * fps / 1000L).toInt()
            var lastBmp: Bitmap? = null
            var lastUri = ""
            var lastSrc = -1000000L
            val layerMax = minOf(2048, maxOf(w, h))
            for (n in 0 until frames) {
                if (cancel.get()) throw ExportCancelled()
                val tMs = n * 1000L / fps
                val st = computeRenderState(ctx, project, tMs, false, layerMax)
                val c = st.clip
                if (c != null && tMs < project.clipsMs) {
                    var bmp: Bitmap? = null
                    if (!c.isVideo) {
                        bmp = photos.getOrPut(c.uri) {
                            loadBitmap(ctx, Uri.parse(c.uri), 3072) ?: Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                        }
                    } else {
                        val srcMs = c.inMs + (st.clipOffMs * c.speed).toLong()
                        if (lastBmp != null && lastUri == c.uri && Math.abs(srcMs - lastSrc) < 12L) {
                            bmp = lastBmp
                        } else {
                            val r = mmrs.getOrPut(c.uri) {
                                val nr = MediaMetadataRetriever()
                                nr.setDataSource(ctx, Uri.parse(c.uri))
                                nr
                            }
                            val d = dims.getOrPut(c.uri) {
                                val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                                val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                                if (rot == 90 || rot == 270) intArrayOf(vh, vw) else intArrayOf(vw, vh)
                            }
                            var fr: Bitmap? = null
                            if (Build.VERSION.SDK_INT >= 27 && d[0] > 0 && d[1] > 0) {
                                val sc = minOf(1f, maxOf(w, h).toFloat() * (if (chainTarget > 0) 1f else 1f) / maxOf(d[0], d[1]).toFloat())
                                fr = r.getScaledFrameAtTime(srcMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, maxOf(2, (d[0] * sc).toInt()), maxOf(2, (d[1] * sc).toInt()))
                            }
                            if (fr == null) fr = r.getFrameAtTime(srcMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                            bmp = fr
                            if (fr != null) {
                                lastBmp = fr
                                lastUri = c.uri
                                lastSrc = srcMs
                            }
                        }
                    }
                    if (bmp != null) eng.setPhoto(bmp) else eng.clear()
                } else {
                    eng.clear()
                }
                eng.setState(st)
                eng.timeSec = tMs / 1000f
                eng.onDrawFrame(null)
                EGLExt.eglPresentationTimeANDROID(dpy, esurf, tMs * 1_000_000L)
                EGL14.eglSwapBuffers(dpy, esurf)
                drain(false)
                onProgress(0.08f + 0.92f * n / frames)
            }
            drain(true)
            if (!muxStarted) return "Encoder produced no video. Try a lower resolution."
            onProgress(1f)
            return ""
        } catch (ex: ExportCancelled) {
            return "Cancelled"
        } catch (ex: Throwable) {
            return "Export failed: " + (ex.message ?: ex.javaClass.simpleName)
        } finally {
            try { gl?.releaseExtras() } catch (ex: Exception) { }
            for (r in mmrs.values) {
                try { r.release() } catch (ex: Exception) { }
            }
            try {
                if (dpy != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (esurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, esurf)
                    if (ectx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ectx)
                    EGL14.eglTerminate(dpy)
                }
            } catch (ex: Exception) { }
            try { enc?.stop() } catch (ex: Exception) { }
            try { enc?.release() } catch (ex: Exception) { }
            try { inSurface?.release() } catch (ex: Exception) { }
            try { if (muxStarted) mux?.stop() } catch (ex: Exception) { }
            try { mux?.release() } catch (ex: Exception) { }
        }
    }
}
