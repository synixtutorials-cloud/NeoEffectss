package com.cusil.neoeffects

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class Key(val t: Long, val v: Float, val ease: Int = 0, val bz: FloatArray? = null)

val EASE_NAMES = listOf("Linear", "Ease in", "Ease out", "Ease in-out", "Hold", "Overshoot", "Bounce", "Elastic", "Custom")

private fun bounceOut(x: Float): Float {
    val n1 = 7.5625f
    val d1 = 2.75f
    return if (x < 1f / d1) {
        n1 * x * x
    } else if (x < 2f / d1) {
        val t = x - 1.5f / d1
        n1 * t * t + 0.75f
    } else if (x < 2.5f / d1) {
        val t = x - 2.25f / d1
        n1 * t * t + 0.9375f
    } else {
        val t = x - 2.625f / d1
        n1 * t * t + 0.984375f
    }
}

private fun bezierEase(bz: FloatArray?, x: Float): Float {
    if (bz == null || bz.size < 4) return x
    var lo = 0f
    var hi = 1f
    var t = x
    for (i in 0 until 14) {
        t = (lo + hi) / 2f
        val bx = 3f * (1f - t) * (1f - t) * t * bz[0] + 3f * (1f - t) * t * t * bz[2] + t * t * t
        if (bx < x) lo = t else hi = t
    }
    return 3f * (1f - t) * (1f - t) * t * bz[1] + 3f * (1f - t) * t * t * bz[3] + t * t * t
}

fun easeApply(type: Int, tt: Float, bz: FloatArray?): Float {
    val t = tt.coerceIn(0f, 1f)
    return when (type) {
        1 -> t * t
        2 -> 1f - (1f - t) * (1f - t)
        3 -> t * t * (3f - 2f * t)
        4 -> 0f
        5 -> {
            val c1 = 1.70158f
            val c3 = c1 + 1f
            val u = t - 1f
            1f + c3 * u * u * u + c1 * u * u
        }
        6 -> bounceOut(t)
        7 -> if (t <= 0f || t >= 1f) t else (Math.pow(2.0, (-10f * t).toDouble()) * Math.sin((t * 10f - 0.75f) * (2.0 * Math.PI / 3.0)) + 1.0).toFloat()
        8 -> bezierEase(bz, t)
        else -> t
    }
}

class FxInst(val effect: NeoEffect) {
    val values = mutableStateMapOf<String, Float>()
    val keys = mutableStateMapOf<String, List<Key>>()

    init {
        for (p in effect.params) values[p.id] = p.def
    }

    fun valueAt(p: NeoParam, tMs: Long): Float {
        val ks = keys[p.id]
        if (ks == null || ks.isEmpty()) return values[p.id] ?: p.def
        if (tMs <= ks.first().t) return ks.first().v
        if (tMs >= ks.last().t) return ks.last().v
        for (i in 0 until ks.size - 1) {
            val a = ks[i]
            val b = ks[i + 1]
            if (tMs <= b.t) {
                val f = (tMs - a.t).toFloat() / maxOf(1L, b.t - a.t).toFloat()
                return a.v + (b.v - a.v) * easeApply(a.ease, f, a.bz)
            }
        }
        return ks.last().v
    }

    fun setKey(pid: String, tMs: Long, v: Float) {
        val old = keys[pid] ?: emptyList()
        val list = ArrayList<Key>()
        var ex: Key? = null
        for (k in old) {
            if (abs(k.t - tMs) >= 50L) list.add(k) else ex = k
        }
        list.add(Key(tMs, v, ex?.ease ?: 0, ex?.bz))
        keys[pid] = list.sortedBy { it.t }
    }

    fun removeKey(pid: String, tMs: Long) {
        val old = keys[pid] ?: return
        val rest = old.filter { abs(it.t - tMs) >= 50L }
        if (rest.isEmpty()) keys.remove(pid) else keys[pid] = rest
    }

    fun setEaseAt(tMs: Long, ease: Int, bz: FloatArray?) {
        for ((pid, list) in keys.entries.toList()) {
            keys[pid] = list.map { if (abs(it.t - tMs) < 50L) Key(it.t, it.v, ease, bz) else it }
        }
    }

    fun hasKeyAt(tMs: Long): Boolean {
        for ((_, list) in keys) for (k in list) if (abs(k.t - tMs) < 50L) return true
        return false
    }

    fun copyInst(shift: Long): FxInst {
        val g = FxInst(effect)
        for ((k, v) in values) g.values[k] = v
        for ((k, list) in keys) g.keys[k] = list.map { Key(it.t - shift, it.v, it.ease, it.bz) }
        return g
    }
}

abstract class Item(val id: Long) {
    val fx = mutableStateListOf<FxInst>()
    val tr = FxInst(TRANSFORM_FX)
    var blend by mutableIntStateOf(0)
    var mesh by mutableIntStateOf(0)
    var meshSpin by mutableFloatStateOf(0f)
    abstract val durMs: Long

    fun copyCommon(n: Item, shift: Long) {
        n.blend = blend
        n.mesh = mesh
        n.meshSpin = meshSpin
        for ((k, v) in tr.values) n.tr.values[k] = v
        for ((k, list) in tr.keys) n.tr.keys[k] = list.map { Key(it.t - shift, it.v, it.ease, it.bz) }
        for (f in fx) n.fx.add(f.copyInst(shift))
    }
}

class Clip(id: Long, val uri: String, val isVideo: Boolean, val srcDurMs: Long, val name: String) : Item(id) {
    var inMs by mutableLongStateOf(0L)
    var outMs by mutableLongStateOf(srcDurMs)
    var speed by mutableFloatStateOf(1f)
    var thumb by mutableStateOf<Bitmap?>(null)

    override val durMs: Long
        get() = maxOf(100L, ((outMs - inMs) / speed).toLong())

    fun duplicate(newId: Long, shiftKeysMs: Long): Clip {
        val n = Clip(newId, uri, isVideo, srcDurMs, name)
        n.inMs = inMs
        n.outMs = outMs
        n.speed = speed
        n.thumb = thumb
        copyCommon(n, shiftKeysMs)
        return n
    }
}

class Layer(id: Long, val type: String) : Item(id) {
    var name by mutableStateOf("")
    var track by mutableIntStateOf(2)
    var startMs by mutableLongStateOf(0L)
    var lenMs by mutableLongStateOf(3000L)
    var color by mutableIntStateOf(-1)
    var stroke by mutableIntStateOf(-16777216)
    var strokeW by mutableFloatStateOf(0f)
    var text by mutableStateOf("Your text")
    var size by mutableFloatStateOf(0.1f)
    var bold by mutableStateOf(true)
    var italic by mutableStateOf(false)
    var textMode by mutableIntStateOf(0)
    var from by mutableFloatStateOf(0f)
    var to by mutableFloatStateOf(10f)
    var shape by mutableIntStateOf(0)
    var uri by mutableStateOf("")
    var parentId by mutableLongStateOf(0L)
    var bmp: Bitmap? = null
    var sig: String = ""

    override val durMs: Long
        get() = lenMs

    fun duplicate(newId: Long, shiftStart: Long): Layer {
        val n = Layer(newId, type)
        n.name = name
        n.track = track
        n.startMs = startMs + shiftStart
        n.lenMs = lenMs
        n.color = color
        n.stroke = stroke
        n.strokeW = strokeW
        n.text = text
        n.size = size
        n.bold = bold
        n.italic = italic
        n.textMode = textMode
        n.from = from
        n.to = to
        n.shape = shape
        n.uri = uri
        n.parentId = parentId
        copyCommon(n, 0L)
        return n
    }
}

fun newClipId(): Long = System.nanoTime()

class Project(val id: String, var name: String) {
    val clips = mutableStateListOf<Clip>()
    val layers = mutableStateListOf<Layer>()
    val beats = mutableStateListOf<Long>()
    var playheadMs by mutableLongStateOf(0L)
    var width by mutableIntStateOf(1080)
    var height by mutableIntStateOf(1920)
    var fps by mutableIntStateOf(30)
    var resLabel by mutableStateOf("1080p")
    var aspectLabel by mutableStateOf("9:16")
    var bgMode by mutableIntStateOf(0)
    var bgUri by mutableStateOf("")
    var autosave by mutableStateOf(true)
    var previewQ by mutableIntStateOf(1)
    var templateId by mutableStateOf("")
    var fav by mutableStateOf(false)

    val totalMs: Long
        get() {
            var t = 0L
            for (c in clips) t += c.durMs
            for (l in layers) if (l.startMs + l.lenMs > t) t = l.startMs + l.lenMs
            return t
        }

    val clipsMs: Long
        get() {
            var t = 0L
            for (c in clips) t += c.durMs
            return t
        }

    fun startOf(c: Item): Long {
        if (c is Layer) return c.startMs
        var t = 0L
        for (x in clips) {
            if (x === c) return t
            t += x.durMs
        }
        return t
    }

    fun clipAt(t: Long): Pair<Clip, Long>? {
        if (clips.isEmpty()) return null
        var s = 0L
        for (c in clips) {
            val d = c.durMs
            if (t < s + d) return Pair(c, maxOf(0L, t - s))
            s += d
        }
        val last = clips.last()
        return Pair(last, last.durMs)
    }

    fun splitClipAt(t: Long): Boolean {
        val loc = clipAt(t) ?: return false
        val c = loc.first
        val off = loc.second
        if (off < 200L || c.durMs - off < 200L) return false
        val cut = c.inMs + (off * c.speed).toLong()
        val b = c.duplicate(newClipId(), off)
        b.inMs = cut
        c.outMs = cut
        clips.add(clips.indexOf(c) + 1, b)
        return true
    }

    fun splitAtPlayhead(): Boolean = splitClipAt(playheadMs)
}

class ProjInfo(
    val id: String,
    val name: String,
    val modified: Long,
    val durMs: Long,
    val clips: Int,
    val res: String,
    val fps: Int,
    val fav: Boolean
)

object ProjectStore {
    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "projects")
        d.mkdirs()
        return d
    }

    fun thumbFile(ctx: Context, id: String): File = File(dir(ctx), id + ".jpg")

    fun projectsDir(ctx: Context): File = dir(ctx)

    private fun instToJson(f: FxInst): JSONObject {
        val fo = JSONObject()
        fo.put("id", f.effect.id)
        val vo = JSONObject()
        for ((k, v) in f.values) vo.put(k, v.toDouble())
        fo.put("values", vo)
        val ko = JSONObject()
        for ((k, list) in f.keys) {
            val ka = JSONArray()
            for (key in list) {
                val pair = JSONArray()
                pair.put(key.t)
                pair.put(key.v.toDouble())
                pair.put(key.ease)
                val bz = key.bz
                if (bz != null && bz.size >= 4) {
                    for (x in bz) pair.put(x.toDouble())
                }
                ka.put(pair)
            }
            ko.put(k, ka)
        }
        fo.put("keys", ko)
        return fo
    }

    private fun fillInst(inst: FxInst, fo: JSONObject) {
        val vo = fo.optJSONObject("values")
        if (vo != null) {
            val it2 = vo.keys()
            while (it2.hasNext()) {
                val k = it2.next()
                inst.values[k] = vo.getDouble(k).toFloat()
            }
        }
        val ko = fo.optJSONObject("keys")
        if (ko != null) {
            val it3 = ko.keys()
            while (it3.hasNext()) {
                val k = it3.next()
                val ka = ko.getJSONArray(k)
                val list = ArrayList<Key>()
                for (m in 0 until ka.length()) {
                    val pr = ka.getJSONArray(m)
                    val ease = if (pr.length() > 2) pr.getInt(2) else 0
                    val bz = if (pr.length() >= 7) floatArrayOf(pr.getDouble(3).toFloat(), pr.getDouble(4).toFloat(), pr.getDouble(5).toFloat(), pr.getDouble(6).toFloat()) else null
                    list.add(Key(pr.getLong(0), pr.getDouble(1).toFloat(), ease, bz))
                }
                inst.keys[k] = list
            }
        }
    }

    private fun itemToJson(c: Item, co: JSONObject) {
        co.put("blend", c.blend)
        co.put("mesh", c.mesh)
        co.put("spin", c.meshSpin.toDouble())
        co.put("tr", instToJson(c.tr))
        val fa = JSONArray()
        for (f in c.fx) fa.put(instToJson(f))
        co.put("fx", fa)
    }

    private fun itemFromJson(c: Item, co: JSONObject, lib: List<NeoEffect>) {
        c.blend = co.optInt("blend", 0)
        c.mesh = co.optInt("mesh", 0)
        c.meshSpin = co.optDouble("spin", 0.0).toFloat()
        val tj = co.optJSONObject("tr")
        if (tj != null) fillInst(c.tr, tj)
        val fa = co.optJSONArray("fx")
        if (fa != null) {
            for (j in 0 until fa.length()) {
                val fo = fa.getJSONObject(j)
                val fid = fo.getString("id")
                val e = lib.firstOrNull { it.id == fid } ?: continue
                val inst = FxInst(e)
                fillInst(inst, fo)
                c.fx.add(inst)
            }
        }
    }

    fun toJson(p: Project): JSONObject {
        val o = JSONObject()
        o.put("id", p.id)
        o.put("name", p.name)
        o.put("playhead", p.playheadMs)
        o.put("width", p.width)
        o.put("height", p.height)
        o.put("fps", p.fps)
        o.put("res", p.resLabel)
        o.put("aspect", p.aspectLabel)
        o.put("bgMode", p.bgMode)
        o.put("bgUri", p.bgUri)
        o.put("autosave", p.autosave)
        o.put("previewQ", p.previewQ)
        o.put("template", p.templateId)
        o.put("fav", p.fav)
        val cs = JSONArray()
        for (c in p.clips) {
            val co = JSONObject()
            co.put("id", c.id)
            co.put("uri", c.uri)
            co.put("video", c.isVideo)
            co.put("name", c.name)
            co.put("srcDur", c.srcDurMs)
            co.put("in", c.inMs)
            co.put("out", c.outMs)
            co.put("speed", c.speed.toDouble())
            itemToJson(c, co)
            cs.put(co)
        }
        o.put("clips", cs)
        val ls = JSONArray()
        for (l in p.layers) {
            val lo = JSONObject()
            lo.put("id", l.id)
            lo.put("type", l.type)
            lo.put("name", l.name)
            lo.put("track", l.track)
            lo.put("start", l.startMs)
            lo.put("len", l.lenMs)
            lo.put("color", l.color)
            lo.put("stroke", l.stroke)
            lo.put("strokeW", l.strokeW.toDouble())
            lo.put("text", l.text)
            lo.put("size", l.size.toDouble())
            lo.put("bold", l.bold)
            lo.put("italic", l.italic)
            lo.put("tmode", l.textMode)
            lo.put("from", l.from.toDouble())
            lo.put("to", l.to.toDouble())
            lo.put("shape", l.shape)
            lo.put("uri", l.uri)
            lo.put("parent", l.parentId)
            itemToJson(l, lo)
            ls.put(lo)
        }
        o.put("layers", ls)
        val bs = JSONArray()
        for (b in p.beats) bs.put(b)
        o.put("beats", bs)
        return o
    }

    fun fromJson(o: JSONObject, lib: List<NeoEffect>): Project {
        val p = Project(o.getString("id"), o.optString("name", "Project"))
        p.width = o.optInt("width", 1080)
        p.height = o.optInt("height", 1920)
        p.fps = o.optInt("fps", 30)
        p.resLabel = o.optString("res", "1080p")
        p.aspectLabel = o.optString("aspect", "9:16")
        p.bgMode = o.optInt("bgMode", 0)
        p.bgUri = o.optString("bgUri", "")
        p.autosave = o.optBoolean("autosave", true)
        p.previewQ = o.optInt("previewQ", 1)
        p.templateId = o.optString("template", "")
        p.fav = o.optBoolean("fav", false)
        val cs = o.optJSONArray("clips")
        if (cs != null) {
            for (i in 0 until cs.length()) {
                val co = cs.getJSONObject(i)
                val c = Clip(
                    co.getLong("id"), co.getString("uri"), co.getBoolean("video"),
                    co.getLong("srcDur"), co.optString("name", "clip")
                )
                c.inMs = co.getLong("in")
                c.outMs = co.getLong("out")
                c.speed = co.optDouble("speed", 1.0).toFloat()
                itemFromJson(c, co, lib)
                p.clips.add(c)
            }
        }
        val ls = o.optJSONArray("layers")
        if (ls != null) {
            for (i in 0 until ls.length()) {
                val lo = ls.getJSONObject(i)
                val l = Layer(lo.getLong("id"), lo.getString("type"))
                l.name = lo.optString("name", "")
                l.track = lo.optInt("track", 2)
                l.startMs = lo.optLong("start", 0L)
                l.lenMs = lo.optLong("len", 3000L)
                l.color = lo.optInt("color", -1)
                l.stroke = lo.optInt("stroke", -16777216)
                l.strokeW = lo.optDouble("strokeW", 0.0).toFloat()
                l.text = lo.optString("text", "")
                l.size = lo.optDouble("size", 0.1).toFloat()
                l.bold = lo.optBoolean("bold", true)
                l.italic = lo.optBoolean("italic", false)
                l.textMode = lo.optInt("tmode", 0)
                l.from = lo.optDouble("from", 0.0).toFloat()
                l.to = lo.optDouble("to", 10.0).toFloat()
                l.shape = lo.optInt("shape", 0)
                l.uri = lo.optString("uri", "")
                l.parentId = lo.optLong("parent", 0L)
                itemFromJson(l, lo, lib)
                p.layers.add(l)
            }
        }
        val bs = o.optJSONArray("beats")
        if (bs != null) for (i in 0 until bs.length()) p.beats.add(bs.getLong(i))
        p.playheadMs = o.optLong("playhead", 0L)
        return p
    }

    fun save(ctx: Context, p: Project) {
        File(dir(ctx), p.id + ".json").writeText(toJson(p).toString())
        val t = p.clips.firstOrNull { it.thumb != null }?.thumb
        if (t != null) {
            try {
                FileOutputStream(thumbFile(ctx, p.id)).use { t.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            } catch (e: Exception) {
            }
        }
    }

    fun list(ctx: Context): List<ProjInfo> {
        val fs = dir(ctx).listFiles() ?: return emptyList()
        val out = ArrayList<ProjInfo>()
        for (f in fs.sortedByDescending { it.lastModified() }) {
            if (!f.name.endsWith(".json")) continue
            try {
                val o = JSONObject(f.readText())
                val cs = o.optJSONArray("clips")
                var dur = 0L
                var n = 0
                if (cs != null) {
                    n = cs.length()
                    for (i in 0 until cs.length()) {
                        val co = cs.getJSONObject(i)
                        val sp = co.optDouble("speed", 1.0).toFloat()
                        dur += maxOf(100L, ((co.getLong("out") - co.getLong("in")) / sp).toLong())
                    }
                }
                out.add(
                    ProjInfo(
                        o.getString("id"), o.optString("name", "Project"), f.lastModified(),
                        dur, n, o.optString("res", "1080p"), o.optInt("fps", 30), o.optBoolean("fav", false)
                    )
                )
            } catch (e: Exception) {
            }
        }
        return out
    }

    fun load(ctx: Context, id: String, lib: List<NeoEffect>): Project? {
        return try {
            fromJson(JSONObject(File(dir(ctx), id + ".json").readText()), lib)
        } catch (e: Exception) {
            null
        }
    }

    private fun editJson(ctx: Context, id: String, block: (JSONObject) -> Unit) {
        try {
            val f = File(dir(ctx), id + ".json")
            val o = JSONObject(f.readText())
            block(o)
            f.writeText(o.toString())
        } catch (e: Exception) {
        }
    }

    fun rename(ctx: Context, id: String, name: String) = editJson(ctx, id) { it.put("name", name) }

    fun setFav(ctx: Context, id: String, fav: Boolean) = editJson(ctx, id) { it.put("fav", fav) }

    fun duplicate(ctx: Context, id: String) {
        try {
            val o = JSONObject(File(dir(ctx), id + ".json").readText())
            val nid = System.currentTimeMillis().toString()
            o.put("id", nid)
            o.put("name", o.optString("name", "Project") + " copy")
            o.put("fav", false)
            File(dir(ctx), nid + ".json").writeText(o.toString())
            val t = thumbFile(ctx, id)
            if (t.exists()) t.copyTo(thumbFile(ctx, nid), true)
        } catch (e: Exception) {
        }
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), id + ".json").delete()
        thumbFile(ctx, id).delete()
    }
}
