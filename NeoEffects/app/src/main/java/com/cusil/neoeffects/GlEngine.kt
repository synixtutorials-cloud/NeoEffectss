package com.cusil.neoeffects

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class Pass(val effect: NeoEffect, val values: Map<String, Float>, val ctime: Float = -1f, val cdur: Float = 0f)

class FboPair(val w: Int, val h: Int) {
    val fbo = IntArray(2)
    val tex = IntArray(2)
}

class LTex(var tex: Int, var bmp: Bitmap?)
class Geo(val buf: FloatBuffer, val count: Int, val mode: Int)

class GlEngine : GLSurfaceView.Renderer {
    @Volatile var chain: List<Pass> = emptyList()
    @Volatile var timeSec: Float = -1f
    @Volatile var onError: (String) -> Unit = {}
    @Volatile var onSurface: (Surface) -> Unit = {}
    @Volatile var currentSurface: Surface? = null

    @Volatile var canvasAspect: Float = 0f
    @Volatile var bgMode: Int = 0
    @Volatile var maxSide: Int = 1280
    @Volatile var hidden: Boolean = false
    @Volatile var chainTarget: Int = 0

    @Volatile var mainTr: TransformVals = TransformVals()
    @Volatile var mainReps: List<TransformVals> = emptyList()
    @Volatile var mainBlend: Int = 0
    @Volatile var mainMesh: Int = 0
    @Volatile var mainSpin: Float = 0f
    @Volatile var layers: List<LayerRender> = emptyList()

    @Volatile private var photo: Bitmap? = null
    @Volatile private var photoDirty = false
    @Volatile private var bgBmp: Bitmap? = null
    @Volatile private var bgDirty = false
    @Volatile private var mode = 0
    @Volatile private var vidW = 0
    @Volatile private var vidH = 0

    private val main = Handler(Looper.getMainLooper())
    private val frameFlag = AtomicBoolean(false)
    private val startNs = System.nanoTime()

    private var oesTex = 0
    private var st: SurfaceTexture? = null
    private var photoTex = 0
    private var photoW = 1
    private var photoH = 1
    private var bgTex = 0
    private var bgW = 1
    private var bgH = 1
    private var vw = 1
    private var vh = 1
    private var copyProg = 0
    private var gradProg = 0
    private var coverProg = 0
    private var layerProg = 0
    private val pairs = HashMap<Long, FboPair>()
    private val layerTex = HashMap<Long, LTex>()
    private val geos = HashMap<Int, Geo>()
    private val programs = HashMap<String, Int>()
    private val failed = ConcurrentHashMap<String, String>()
    private val texMat = FloatArray(16)
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val tmpM = FloatArray(16)
    private val mvp = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    init {
        quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        val b: Buffer = quad
        b.position(0)
        Matrix.setIdentityM(texMat, 0)
    }

    fun setPhoto(b: Bitmap) {
        photo = b
        photoDirty = true
        mode = 1
    }

    fun setBgBitmap(b: Bitmap?) {
        bgBmp = b
        bgDirty = true
    }

    fun setVideoMode() {
        mode = 2
    }

    fun clear() {
        mode = 0
    }

    fun setVideoSize(w: Int, h: Int) {
        vidW = w
        vidH = h
    }

    fun retry() {
        failed.clear()
    }

    fun releaseExtras() {
        try { st?.release() } catch (e: Exception) { }
        try { currentSurface?.release() } catch (e: Exception) { }
    }

    fun setState(s: RenderState) {
        val m = s.main
        chain = m?.chain ?: emptyList()
        mainTr = m?.tr ?: TransformVals()
        mainReps = m?.reps ?: emptyList()
        mainBlend = m?.blend ?: 0
        mainMesh = m?.mesh ?: 0
        mainSpin = m?.spin ?: 0f
        layers = s.layers
    }

    private fun report(m: String) {
        main.post { onError(m) }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        programs.clear()
        pairs.clear()
        layerTex.clear()
        geos.clear()
        copyProg = 0
        gradProg = 0
        coverProg = 0
        layerProg = 0
        photoTex = 0
        bgTex = 0
        photoDirty = photo != null
        bgDirty = bgBmp != null
        GLES20.glClearColor(0.04f, 0.04f, 0.07f, 1f)

        try { st?.release() } catch (e: Exception) { }
        try { currentSurface?.release() } catch (e: Exception) { }

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        oesTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val s = SurfaceTexture(oesTex)
        s.setOnFrameAvailableListener { frameFlag.set(true) }
        st = s
        val sf = Surface(s)
        currentSurface = sf
        main.post { onSurface(sf) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        vw = width
        vh = height
    }

    private fun fit(aspect: Float): IntArray {
        val av = vw.toFloat() / vh.toFloat()
        var rw = vw
        var rh = vh
        if (aspect > av) {
            rh = (vw / aspect).toInt()
        } else {
            rw = (vh * aspect).toInt()
        }
        return intArrayOf((vw - rw) / 2, (vh - rh) / 2, maxOf(1, rw), maxOf(1, rh))
    }

    private fun getPair(w: Int, h: Int): FboPair {
        val key = w.toLong() * 100000L + h.toLong()
        val have = pairs[key]
        if (have != null) return have
        if (pairs.size >= 8) {
            for (p in pairs.values) {
                GLES20.glDeleteTextures(2, p.tex, 0)
                GLES20.glDeleteFramebuffers(2, p.fbo, 0)
            }
            pairs.clear()
        }
        val p = FboPair(w, h)
        GLES20.glGenTextures(2, p.tex, 0)
        GLES20.glGenFramebuffers(2, p.fbo, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, p.tex[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, p.fbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, p.tex[i], 0)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        pairs[key] = p
        return p
    }

    private fun bindFbo(p: FboPair, idx: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, p.fbo[idx])
        GLES20.glViewport(0, 0, p.w, p.h)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun runChain(src: Int, srcIdx: Int, p: FboPair, passes: List<Pass>, tsec: Float): Int {
        var cur = src
        var idx = srcIdx
        for (ps in passes) {
            val prog = programFor(ps.effect)
            if (prog == 0) continue
            val out = if (idx == 0) 1 else 0
            bindFbo(p, out)
            drawProgram(prog, cur, p.w, p.h, tsec, ps, -1f)
            cur = p.tex[out]
            idx = out
        }
        return cur
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, vw, vh)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (vw < 2 || vh < 2) return
        val ca = canvasAspect
        val m = mode
        if (bgDirty) {
            bgDirty = false
            val bb = bgBmp
            if (bb != null && !bb.isRecycled) uploadBg(bb)
        }
        val lays = layers

        var sw = 1
        var sh = 1
        var cur = 0
        var curIdx = -1
        var haveMain = false
        if (m == 1) {
            if (photoDirty) {
                photoDirty = false
                val b = photo
                if (b != null && !b.isRecycled) uploadPhoto(b)
            }
            if (photoTex != 0) {
                sw = photoW
                sh = photoH
                cur = photoTex
                haveMain = true
            }
        } else if (m == 2 && vidW > 0 && vidH > 0) {
            sw = vidW
            sh = vidH
            haveMain = true
        }
        var w = 2
        var h = 2
        var pair: FboPair? = null
        if (haveMain) {
            val ct = chainTarget
            val scale = if (ct > 0) ct.toFloat() / maxOf(sw, sh).toFloat() else minOf(1f, maxSide.toFloat() / maxOf(sw, sh).toFloat())
            w = maxOf(2, (sw * scale).toInt())
            h = maxOf(2, (sh * scale).toInt())
            pair = getPair(w, h)
            if (m == 2) {
                val s = st
                if (s == null) {
                    haveMain = false
                } else {
                    if (frameFlag.getAndSet(false)) {
                        try { s.updateTexImage() } catch (e: Exception) { }
                    }
                    try { s.getTransformMatrix(texMat) } catch (e: Exception) { }
                    drawExternal(pair)
                    cur = pair.tex[0]
                    curIdx = 0
                }
            }
        }
        val sa = if (haveMain) w.toFloat() / h.toFloat() else 1f
        val cr = fit(if (ca > 0f) ca else sa)
        if (ca > 0f) drawBackground(cr, if (haveMain) cur else 0, sa)
        if (!haveMain && lays.isEmpty()) return

        val tsec = if (timeSec >= 0f) timeSec else (System.nanoTime() - startNs) / 1_000_000_000f
        val asp = cr[2].toFloat() / cr[3].toFloat()
        if (haveMain && !hidden && pair != null) {
            val res = runChain(cur, curIdx, pair, chain, tsec)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(cr[0], cr[1], cr[2], cr[3])
            GLES20.glEnable(GLES20.GL_BLEND)
            val hw: Float
            val hh: Float
            if (sa >= asp) {
                hw = asp
                hh = asp / sa
            } else {
                hh = 1f
                hw = sa
            }
            drawLayerTex(res, hw, hh, mainTr, mainReps, mainBlend, mainMesh, mainSpin, asp, tsec)
        }
        for (lr in lays) {
            val b = lr.bmp ?: continue
            if (b.isRecycled) continue
            val lt = layerTex.getOrPut(lr.id) { LTex(0, null) }
            if (lt.bmp !== b || lt.tex == 0) {
                if (lt.tex == 0) {
                    val t = IntArray(1)
                    GLES20.glGenTextures(1, t, 0)
                    lt.tex = t[0]
                }
                bindTexParams(lt.tex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
                lt.bmp = b
            }
            val lp = getPair(b.width, b.height)
            val res = runChain(lt.tex, -1, lp, lr.chain, tsec)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(cr[0], cr[1], cr[2], cr[3])
            GLES20.glEnable(GLES20.GL_BLEND)
            drawLayerTex(res, asp, 1f, lr.tr, lr.reps, lr.blend, lr.mesh, lr.spin, asp, tsec)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private fun geoFor(kind: Int): Geo {
        val have = geos[kind]
        if (have != null) return have
        val g: Geo
        if (kind == 0) {
            val data = floatArrayOf(
                -1f, -1f, 0f, 0f, 1f,
                1f, -1f, 0f, 1f, 1f,
                -1f, 1f, 0f, 0f, 0f,
                1f, 1f, 0f, 1f, 0f
            )
            val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.put(data)
            val bb: Buffer = fb
            bb.position(0)
            g = Geo(fb, 4, GLES20.GL_TRIANGLE_STRIP)
        } else {
            val data = MeshGen.build(kind)
            val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.put(data)
            val bb: Buffer = fb
            bb.position(0)
            g = Geo(fb, data.size / 5, GLES20.GL_TRIANGLES)
        }
        geos[kind] = g
        return g
    }

    private fun buildMvp(t: TransformVals, spin: Float, tsec: Float, asp: Float) {
        Matrix.perspectiveM(proj, 0, 45f, asp, 0.5f, 30f)
        Matrix.setIdentityM(view, 0)
        Matrix.translateM(view, 0, 0f, 0f, -2.41421f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, t.px * asp, t.py, 0f)
        Matrix.rotateM(model, 0, -t.rz, 0f, 0f, 1f)
        Matrix.rotateM(model, 0, t.ry + spin * tsec, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, t.rx, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, maxOf(0.001f, t.sc * t.sx), maxOf(0.001f, t.sc * t.sy), maxOf(0.001f, t.sc))
        Matrix.multiplyMM(tmpM, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, tmpM, 0)
    }

    private fun drawLayerTex(
        tex: Int, hw: Float, hh: Float, tr: TransformVals, reps: List<TransformVals>,
        blend: Int, mesh: Int, spin: Float, asp: Float, tsec: Float
    ) {
        if (layerProg == 0) {
            val vs = "attribute vec3 aPos; attribute vec2 aUv; uniform mat4 uMvp; uniform vec2 uHalf; uniform float uZ; varying vec2 vUv; " +
                "void main() { vUv = aUv; gl_Position = uMvp * vec4(aPos.x * uHalf.x, aPos.y * uHalf.y, aPos.z * uZ, 1.0); }"
            val fs = "precision mediump float;\nuniform sampler2D image;\nuniform float uOpacity;\nvarying vec2 vUv;\n" +
                "void main() { vec4 c = texture2D(image, vUv); gl_FragColor = vec4(min(c.rgb, vec3(c.a)), c.a) * uOpacity; }\n"
            try {
                layerProg = link(vs, fs)
            } catch (e: Exception) {
                report("Layer shader: " + (e.message ?: "error"))
                return
            }
        }
        GLES20.glUseProgram(layerProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(loc(layerProg, "image"), 0)
        when (blend) {
            1 -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            2 -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR)
            3 -> GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            else -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }
        val md = if (mesh == 12) 0 else mesh
        val geo = geoFor(md)
        val s = minOf(hw, hh)
        for (i in -1 until reps.size) {
            val t = if (i < 0) tr else reps[i]
            if (t.op <= 0.002f) continue
            buildMvp(t, spin, tsec, asp)
            GLES20.glUniformMatrix4fv(loc(layerProg, "uMvp"), 1, false, mvp, 0)
            if (md == 0) {
                GLES20.glUniform2f(loc(layerProg, "uHalf"), hw, hh)
                GLES20.glUniform1f(loc(layerProg, "uZ"), 1f)
            } else {
                GLES20.glUniform2f(loc(layerProg, "uHalf"), s, s)
                GLES20.glUniform1f(loc(layerProg, "uZ"), s)
                GLES20.glEnable(GLES20.GL_DEPTH_TEST)
                GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
            }
            GLES20.glUniform1f(loc(layerProg, "uOpacity"), t.op.coerceIn(0f, 1f))
            val bb: Buffer = geo.buf
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glEnableVertexAttribArray(1)
            bb.position(0)
            GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 20, geo.buf)
            bb.position(3)
            GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 20, geo.buf)
            GLES20.glDrawArrays(geo.mode, 0, geo.count)
            GLES20.glDisableVertexAttribArray(1)
            if (md != 0) GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        }
    }

    private fun clearRect(cr: IntArray, v: Float) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(cr[0], cr[1], cr[2], cr[3])
        GLES20.glClearColor(v, v, v, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0.04f, 0.04f, 0.07f, 1f)
    }

    private fun drawBackground(cr: IntArray, tex: Int, sa: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(cr[0], cr[1], cr[2], cr[3])
        GLES20.glDisable(GLES20.GL_BLEND)
        val ka = cr[2].toFloat() / cr[3].toFloat()
        val bm = bgMode
        if (bm == 1) {
            clearRect(cr, 1f)
        } else if (bm == 2) {
            drawGradient()
        } else if (bm == 3 && tex != 0) {
            drawCover(tex, sa, ka, 1f)
        } else if (bm == 4 && bgTex != 0) {
            drawCover(bgTex, bgW.toFloat() / bgH.toFloat(), ka, 0f)
        } else {
            clearRect(cr, 0f)
        }
    }

    private fun drawGradient() {
        if (gradProg == 0) {
            val fs = "precision mediump float;\nvarying vec2 vUv;\n" +
                "void main() { float t = clamp((vUv.x + vUv.y) * 0.5, 0.0, 1.0); " +
                "vec3 a = vec3(0.05, 0.03, 0.30); vec3 b = vec3(0.65, 0.12, 0.85); vec3 c = vec3(0.95, 0.20, 0.45); " +
                "vec3 col = t < 0.5 ? mix(a, b, t * 2.0) : mix(b, c, (t - 0.5) * 2.0); gl_FragColor = vec4(col, 1.0); }\n"
            try {
                gradProg = link(NeoFx.VERTEX, fs)
            } catch (e: Exception) {
                return
            }
        }
        GLES20.glUseProgram(gradProg)
        GLES20.glUniform1f(loc(gradProg, "uFlip"), 1f)
        drawQuad()
    }

    private fun drawCover(tex: Int, contentAspect: Float, canvasAsp: Float, blur: Float) {
        if (coverProg == 0) {
            val vs = "attribute vec2 aPos; uniform float uFlip; uniform vec2 uScale; varying vec2 vUv; " +
                "void main() { vec2 u = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5); " +
                "vUv = (u - vec2(0.5, 0.5)) * uScale + vec2(0.5, 0.5); gl_Position = vec4(aPos.x, aPos.y * uFlip, 0.0, 1.0); }"
            val fs = "precision mediump float;\nuniform sampler2D image;\nuniform float uBlur;\nvarying vec2 vUv;\n" +
                "void main() { if (uBlur > 0.5) { vec3 c = texture2D(image, vUv).rgb; " +
                "for (int i = 0; i < 8; i++) { float a = float(i) * 0.785398; vec2 d = vec2(cos(a), sin(a)); " +
                "c += texture2D(image, vUv + d * 0.03).rgb; c += texture2D(image, vUv + d * 0.07).rgb; } " +
                "gl_FragColor = vec4(c / 17.0 * 0.55, 1.0); } else { gl_FragColor = vec4(texture2D(image, vUv).rgb, 1.0); } }\n"
            try {
                coverProg = link(vs, fs)
            } catch (e: Exception) {
                return
            }
        }
        var sx = 1f
        var sy = 1f
        if (contentAspect > canvasAsp) {
            sx = canvasAsp / contentAspect
        } else {
            sy = contentAspect / canvasAsp
        }
        GLES20.glUseProgram(coverProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(loc(coverProg, "image"), 0)
        GLES20.glUniform1f(loc(coverProg, "uFlip"), 1f)
        GLES20.glUniform1f(loc(coverProg, "uBlur"), blur)
        GLES20.glUniform2f(loc(coverProg, "uScale"), sx, sy)
        drawQuad()
    }

    private fun loc(prog: Int, name: String): Int = GLES20.glGetUniformLocation(prog, name)

    private fun drawQuad() {
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawProgram(prog: Int, tex: Int, w: Int, h: Int, tsec: Float, pass: Pass?, flip: Float) {
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(loc(prog, "image"), 0)
        GLES20.glUniform2f(loc(prog, "resolution"), w.toFloat(), h.toFloat())
        GLES20.glUniform1f(loc(prog, "time"), tsec)
        GLES20.glUniform1f(loc(prog, "ctime"), if (pass != null && pass.ctime >= 0f) pass.ctime else tsec)
        GLES20.glUniform1f(loc(prog, "cdur"), if (pass != null && pass.cdur > 0f) pass.cdur else 5f)
        GLES20.glUniform1f(loc(prog, "uFlip"), flip)
        if (pass != null) {
            for (p in pass.effect.params) {
                val l = loc(prog, p.id)
                if (l >= 0) GLES20.glUniform1f(l, pass.values[p.id] ?: p.def)
            }
        }
        drawQuad()
    }

    private fun drawExternal(p: FboPair) {
        if (copyProg == 0) {
            val fs = "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vUv;\n" +
                "uniform samplerExternalOES tex;\n" +
                "uniform mat4 uTex;\n" +
                "void main() { vec2 st = vec2(vUv.x, 1.0 - vUv.y); gl_FragColor = texture2D(tex, (uTex * vec4(st, 0.0, 1.0)).xy); }\n"
            try {
                copyProg = link(NeoFx.VERTEX, fs)
            } catch (e: Exception) {
                report("Video shader: " + (e.message ?: "error"))
                return
            }
        }
        bindFbo(p, 0)
        GLES20.glUseProgram(copyProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(loc(copyProg, "tex"), 0)
        GLES20.glUniformMatrix4fv(loc(copyProg, "uTex"), 1, false, texMat, 0)
        GLES20.glUniform1f(loc(copyProg, "uFlip"), -1f)
        drawQuad()
    }

    private fun uploadPhoto(b: Bitmap) {
        if (photoTex == 0) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            photoTex = t[0]
        }
        bindTexParams(photoTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
        photoW = b.width
        photoH = b.height
    }

    private fun uploadBg(b: Bitmap) {
        if (bgTex == 0) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            bgTex = t[0]
        }
        bindTexParams(bgTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
        bgW = b.width
        bgH = b.height
    }

    private fun bindTexParams(tex: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun programFor(e: NeoEffect?): Int {
        if (e != null && e.shader.isBlank()) return 0
        val key = if (e == null) "__pass" else e.id + ":" + e.shader.hashCode() + ":" + e.params.size
        val have = programs[key]
        if (have != null) return have
        if (failed.containsKey(key)) return 0
        return try {
            val code = e?.shader ?: NeoFx.PASSTHROUGH
            val ps = e?.params ?: emptyList()
            val p = link(NeoFx.VERTEX, NeoFx.fragment(code, ps))
            programs[key] = p
            p
        } catch (ex: Exception) {
            val msg = (e?.name ?: "pass") + ": " + (ex.message ?: "shader error")
            failed[key] = msg
            report(msg)
            0
        }
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException(log)
        }
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f: Int
        try {
            f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        } catch (e: Exception) {
            GLES20.glDeleteShader(v)
            throw e
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glBindAttribLocation(p, 0, "aPos")
        GLES20.glBindAttribLocation(p, 1, "aUv")
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        val ls = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ls, 0)
        if (ls[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException(log)
        }
        return p
    }
}

@Composable
fun GlPreview(engine: GlEngine, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { c ->
            GLSurfaceView(c).apply {
                setEGLContextClientVersion(2)
                setRenderer(engine)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }
    )
}
