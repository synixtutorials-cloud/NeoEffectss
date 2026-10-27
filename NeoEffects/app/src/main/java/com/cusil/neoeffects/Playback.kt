package com.cusil.neoeffects

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class Probe(val isVideo: Boolean, val durMs: Long, val name: String)

fun probeMedia(ctx: Context, uri: Uri): Probe? {
    val mime = ctx.contentResolver.getType(uri) ?: ""
    val video = mime.startsWith("video")
    var name = "clip"
    try {
        val c = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        if (c != null) {
            c.use {
                if (it.moveToFirst()) name = it.getString(0) ?: "clip"
            }
        }
    } catch (e: Exception) {
    }
    if (!video) return Probe(false, 10000L, name)
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, uri)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        if (d <= 0L) null else Probe(true, d, name)
    } catch (e: Exception) {
        null
    } finally {
        try { r.release() } catch (e: Exception) { }
    }
}

fun loadThumb(ctx: Context, c: Clip): Bitmap? {
    return try {
        if (c.isVideo) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, Uri.parse(c.uri))
                val f = r.getFrameAtTime(c.inMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (f == null) {
                    null
                } else {
                    val h = 200
                    val w = maxOf(1, f.width * h / f.height)
                    Bitmap.createScaledBitmap(f, w, h, true)
                }
            } finally {
                try { r.release() } catch (e: Exception) { }
            }
        } else {
            loadBitmap(ctx, Uri.parse(c.uri), 256)
        }
    } catch (e: Exception) {
        null
    }
}

class PlaybackEngine(private val ctx: Context, val project: Project, private val gl: GlEngine) {
    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    private var loadedId = -1L
    private var ready = false
    private var pendingSeek = 0L
    private var lastSrc = 0L
    private var wantPlay = false
    private val photos = HashMap<String, Bitmap>()

    var playing by mutableStateOf(false)
        private set
    var error by mutableStateOf("")

    fun onSurface(s: Surface) {
        surface = s
        val p = mp
        if (p != null) {
            try { p.setSurface(s) } catch (e: Exception) { }
            if (ready) seek(lastSrc, false)
        }
    }

    fun show(tMs: Long, fast: Boolean = false) {
        showMain(tMs, fast)
        syncAudio()
    }

    private val audioPlayers = HashMap<Long, MediaPlayer>()
    private val audioReady = HashSet<Long>()
    private var audioAcc = 0L

    fun syncAudio() {
        val t = project.playheadMs
        val ids = project.layers.map { it.id }.toSet()
        for (k in audioPlayers.keys.toList()) {
            if (!ids.contains(k)) {
                try { audioPlayers[k]?.release() } catch (e: Exception) { }
                audioPlayers.remove(k)
                audioReady.remove(k)
            }
        }
        val opP = TRANSFORM_FX.params[6]
        for (l in project.layers) {
            if (l.type != "audio") continue
            val active = t >= l.startMs && t < l.startMs + l.lenMs
            var p = audioPlayers[l.id]
            if (!active) {
                if (p != null) {
                    try { if (p.isPlaying) p.pause() } catch (e: Exception) { }
                }
                continue
            }
            if (p == null) {
                val np = MediaPlayer()
                try {
                    np.setDataSource(ctx, Uri.parse(l.uri))
                    np.setOnPreparedListener {
                        audioReady.add(l.id)
                        syncAudio()
                    }
                    np.prepareAsync()
                } catch (e: Exception) {
                }
                audioPlayers[l.id] = np
                p = np
            }
            if (!audioReady.contains(l.id)) continue
            try {
                val want = t - l.startMs
                val vol = l.tr.valueAt(opP, want).coerceIn(0f, 1f)
                p.setVolume(vol, vol)
                if (wantPlay) {
                    if (!p.isPlaying) {
                        p.seekTo(want.toInt())
                        p.start()
                    } else if (Math.abs(p.currentPosition - want) > 400L) {
                        p.seekTo(want.toInt())
                    }
                } else if (p.isPlaying) {
                    p.pause()
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun showMain(tMs: Long, fast: Boolean) {
        val total = project.totalMs
        project.playheadMs = tMs.coerceIn(0L, total)
        val loc = if (project.playheadMs >= project.clipsMs && project.clips.isNotEmpty()) null else project.clipAt(project.playheadMs)
        if (loc == null) {
            releasePlayer()
            gl.clear()
            return
        }
        val c = loc.first
        if (!c.isVideo) {
            releasePlayer()
            gl.setPhoto(photo(c))
            return
        }
        gl.setVideoMode()
        val src = c.inMs + (loc.second * c.speed).toLong()
        lastSrc = src
        ensureLoaded(c, src, fast)
    }

    private fun photo(c: Clip): Bitmap {
        val cached = photos[c.uri]
        if (cached != null) return cached
        val b = loadBitmap(ctx, Uri.parse(c.uri), 1600) ?: Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        photos[c.uri] = b
        return b
    }

    private fun ensureLoaded(c: Clip, srcMs: Long, fast: Boolean) {
        if (loadedId == c.id && mp != null) {
            if (ready) {
                seek(srcMs, fast)
                if (wantPlay) startMp(c) else pauseMp()
            } else {
                pendingSeek = srcMs
            }
            return
        }
        releasePlayer()
        val p = MediaPlayer()
        mp = p
        loadedId = c.id
        ready = false
        pendingSeek = srcMs
        try {
            p.setDataSource(ctx, Uri.parse(c.uri))
            val s = gl.currentSurface ?: surface
            if (s != null) p.setSurface(s)
            p.setOnVideoSizeChangedListener { _, w, h -> gl.setVideoSize(w, h) }
            p.setOnPreparedListener { pl ->
                ready = true
                gl.setVideoSize(pl.videoWidth, pl.videoHeight)
                seek(pendingSeek, false)
                if (wantPlay) startMp(c) else pauseMp()
            }
            p.setOnErrorListener { _, what, extra ->
                error = "Video error " + what + "/" + extra
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            error = e.message ?: "Cannot open video"
        }
    }

    private fun seek(ms: Long, fast: Boolean) {
        val p = mp ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                p.seekTo(ms, if (fast) MediaPlayer.SEEK_CLOSEST_SYNC else MediaPlayer.SEEK_CLOSEST)
            } else {
                p.seekTo(ms.toInt())
            }
        } catch (e: Exception) {
        }
    }

    private fun startMp(c: Clip) {
        val p = mp ?: return
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                p.playbackParams = PlaybackParams().setSpeed(c.speed)
            }
            if (!p.isPlaying) p.start()
        } catch (e: Exception) {
        }
    }

    private fun pauseMp() {
        val p = mp ?: return
        try {
            if (p.isPlaying) p.pause()
        } catch (e: Exception) {
        }
    }

    private fun releasePlayer() {
        val p = mp
        mp = null
        loadedId = -1L
        ready = false
        if (p != null) {
            try {
                p.setOnPreparedListener(null)
                p.release()
            } catch (e: Exception) {
            }
        }
    }

    fun play() {
        if (project.clips.isEmpty()) return
        if (project.playheadMs >= project.totalMs - 50L) project.playheadMs = 0L
        wantPlay = true
        playing = true
        show(project.playheadMs)
    }

    fun pause() {
        wantPlay = false
        playing = false
        pauseMp()
        syncAudio()
    }

    fun tick(dtMs: Long) {
        if (!playing) return
        val total = project.totalMs
        val next = project.playheadMs + dtMs
        audioAcc += dtMs
        if (audioAcc >= 100L) {
            audioAcc = 0L
            syncAudio()
        }
        if (next >= total) {
            project.playheadMs = total
            pause()
            return
        }
        val before = project.clipAt(project.playheadMs)?.first
        val after = project.clipAt(next)?.first
        project.playheadMs = next
        if (before !== after) {
            val same = before != null && after != null && before.isVideo && after.isVideo &&
                before.uri == after.uri && before.outMs == after.inMs && before.speed == after.speed &&
                mp != null && ready
            if (same && after != null) {
                loadedId = after.id
            } else {
                show(next)
            }
        }
    }

    fun releaseAll() {
        releasePlayer()
        for (ap in audioPlayers.values) {
            try { ap.release() } catch (e: Exception) { }
        }
        audioPlayers.clear()
        audioReady.clear()
        photos.clear()
    }
}
