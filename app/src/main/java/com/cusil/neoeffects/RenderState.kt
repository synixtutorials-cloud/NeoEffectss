package com.cusil.neoeffects

import android.content.Context
import android.graphics.Bitmap

class TransformVals(
    var px: Float = 0f, var py: Float = 0f, var sc: Float = 1f, var rz: Float = 0f,
    var rx: Float = 0f, var ry: Float = 0f, var op: Float = 1f, var sx: Float = 1f, var sy: Float = 1f
) {
    fun copyOf() = TransformVals(px, py, sc, rz, rx, ry, op, sx, sy)
}

class LayerRender(
    val id: Long, val bmp: Bitmap?, val chain: List<Pass>, val tr: TransformVals,
    val reps: List<TransformVals>, val blend: Int, val mesh: Int, val spin: Float
)

class MainRender(
    val chain: List<Pass>, val tr: TransformVals, val reps: List<TransformVals>,
    val blend: Int, val mesh: Int, val spin: Float
)

class RenderState(val main: MainRender?, val layers: List<LayerRender>, val clip: Clip?, val clipOffMs: Long)

private fun hash1(x: Float): Float {
    val s = Math.sin((x * 127.1f).toDouble()) * 43758.5453
    return (s - Math.floor(s)).toFloat()
}

private fun fv(inst: FxInst, id: String, off: Long): Float {
    val p = inst.effect.params.firstOrNull { it.id == id } ?: return 0f
    return inst.valueAt(p, off)
}

fun computeTransform(item: Item, offMs: Long): TransformVals {
    val t = TransformVals()
    val tp = TRANSFORM_FX.params
    t.px = item.tr.valueAt(tp[0], offMs)
    t.py = item.tr.valueAt(tp[1], offMs)
    t.sc = item.tr.valueAt(tp[2], offMs)
    t.rz = item.tr.valueAt(tp[3], offMs)
    t.rx = item.tr.valueAt(tp[4], offMs)
    t.ry = item.tr.valueAt(tp[5], offMs)
    t.op = item.tr.valueAt(tp[6], offMs)
    val ts = offMs / 1000f
    for (inst in item.fx) {
        if (inst.effect.category != "Animate") continue
        when (inst.effect.id) {
            "auto_shake" -> {
                val a = fv(inst, "amount", offMs)
                val sp = fv(inst, "speed", offMs)
                t.px += a * (Math.sin((ts * sp * 13f).toDouble()) + 0.5 * Math.sin((ts * sp * 7.3f + 1f).toDouble())).toFloat()
                t.py += a * (Math.cos((ts * sp * 11f).toDouble()) + 0.5 * Math.sin((ts * sp * 5.1f + 2f).toDouble())).toFloat()
            }
            "oscillate" -> {
                val f = fv(inst, "freq", offMs)
                t.px += fv(inst, "ampx", offMs) * Math.sin(2.0 * Math.PI * f * ts).toFloat()
                t.py += fv(inst, "ampy", offMs) * Math.sin(2.0 * Math.PI * f * ts + Math.PI / 2.0).toFloat()
            }
            "pulse_size" -> t.sc *= 1f + fv(inst, "amount", offMs) * Math.sin(2.0 * Math.PI * fv(inst, "freq", offMs) * ts).toFloat()
            "random_jitter" -> {
                val step = Math.floor((ts * fv(inst, "rate", offMs)).toDouble()).toFloat()
                val a = fv(inst, "amount", offMs)
                t.px += (hash1(step) - 0.5f) * 2f * a
                t.py += (hash1(step + 17.3f) - 0.5f) * 2f * a
            }
            "random_displacement" -> {
                val r = fv(inst, "rate", offMs)
                val a = fv(inst, "amount", offMs)
                t.px += a * (Math.sin((ts * r * 2.1f).toDouble()) * 0.6 + Math.sin((ts * r * 4.7f + 1.3f).toDouble()) * 0.4).toFloat()
                t.py += a * (Math.cos((ts * r * 1.7f).toDouble()) * 0.6 + Math.sin((ts * r * 3.9f + 2.1f).toDouble()) * 0.4).toFloat()
            }
            "offset" -> {
                t.px += fv(inst, "offx", offMs)
                t.py += fv(inst, "offy", offMs)
            }
            "stretch_axis" -> {
                t.sx *= fv(inst, "stx", offMs)
                t.sy *= fv(inst, "sty", offMs)
            }
            "spin" -> t.rz += fv(inst, "speed", offMs) * ts
            "swing" -> t.rz += fv(inst, "angle", offMs) * Math.sin(2.0 * Math.PI * fv(inst, "freq", offMs) * ts).toFloat()
            "flip_layer" -> t.ry += fv(inst, "speed", offMs) * ts
        }
    }
    return t
}

fun computeRepeats(item: Item, offMs: Long, base: TransformVals): List<TransformVals> {
    val inst = item.fx.firstOrNull { it.effect.category == "Repeat" } ?: return emptyList()
    val out = ArrayList<TransformVals>()
    when (inst.effect.id) {
        "linear_repeat" -> {
            val n = fv(inst, "count", offMs).toInt().coerceIn(1, 12)
            for (i in 1 until n) {
                val c = base.copyOf()
                c.px += fv(inst, "dx", offMs) * i
                c.py += fv(inst, "dy", offMs) * i
                c.rz += fv(inst, "rot", offMs) * i
                c.sc *= Math.pow(fv(inst, "scl", offMs).toDouble(), i.toDouble()).toFloat()
                c.op *= (1f - fv(inst, "fade", offMs) * i).coerceAtLeast(0f)
                out.add(c)
            }
        }
        "radial_repeat" -> {
            val n = fv(inst, "count", offMs).toInt().coerceIn(2, 16)
            val r = fv(inst, "radius", offMs)
            for (i in 0 until n) {
                val a = 2.0 * Math.PI * i / n
                val c = base.copyOf()
                c.px += (r * Math.cos(a)).toFloat()
                c.py += (r * Math.sin(a)).toFloat()
                c.sc *= fv(inst, "scl", offMs)
                out.add(c)
            }
        }
        "grid_repeat" -> {
            val cols = fv(inst, "cols", offMs).toInt().coerceIn(1, 6)
            val rows = fv(inst, "rows", offMs).toInt().coerceIn(1, 6)
            val sp = fv(inst, "space", offMs)
            for (y in 0 until rows) for (x in 0 until cols) {
                val c = base.copyOf()
                c.px += (x - (cols - 1) / 2f) * sp
                c.py += (y - (rows - 1) / 2f) * sp
                c.sc *= fv(inst, "scl", offMs)
                out.add(c)
            }
        }
        "scatter_repeat" -> {
            val n = fv(inst, "count", offMs).toInt().coerceIn(1, 20)
            val area = fv(inst, "area", offMs)
            val seed = fv(inst, "seed", offMs)
            for (i in 0 until n) {
                val c = base.copyOf()
                c.px += (hash1(seed + i * 3.1f) - 0.5f) * 2f * area
                c.py += (hash1(seed + i * 7.7f + 5f) - 0.5f) * 2f * area
                c.sc *= fv(inst, "scl", offMs)
                out.add(c)
            }
        }
    }
    return out
}

private fun composeParent(parent: TransformVals, child: TransformVals): TransformVals {
    val a = Math.toRadians(parent.rz.toDouble())
    val c = Math.cos(a).toFloat()
    val s = Math.sin(a).toFloat()
    val x = child.px * parent.sc
    val y = child.py * parent.sc
    return TransformVals(
        parent.px + x * c - y * s, parent.py + x * s + y * c, parent.sc * child.sc,
        parent.rz + child.rz, child.rx + parent.rx, child.ry + parent.ry, parent.op * child.op, child.sx, child.sy
    )
}

fun computeRenderState(ctx: Context, project: Project, ph: Long, bypassGrade: Boolean, maxSide: Int): RenderState {
    val loc = project.clipAt(ph)
    var main: MainRender? = null
    var clip: Clip? = null
    var off = 0L
    if (loc != null) {
        val c = loc.first
        clip = c
        off = loc.second
        val chain = ArrayList<Pass>()
        for (inst in c.fx) {
            if (inst.effect.shader.isBlank()) continue
            if (bypassGrade && inst.effect.id == "color_grade") continue
            val vals = HashMap<String, Float>()
            for (p in inst.effect.params) vals[p.id] = inst.valueAt(p, off)
            chain.add(Pass(inst.effect, vals, off / 1000f, c.durMs / 1000f))
        }
        val tr = computeTransform(c, off)
        main = MainRender(chain, tr, computeRepeats(c, off, tr), c.blend, c.mesh, c.meshSpin)
    }
    val lrs = ArrayList<LayerRender>()
    val order = project.layers.sortedBy { it.track }
    for (l in order) {
        if (l.type == "audio" || l.type == "null") continue
        if (ph < l.startMs || ph >= l.startMs + l.lenMs) continue
        val lo = ph - l.startMs
        val sig = LayerGfx.signature(project, l, lo, maxSide)
        if (l.bmp == null || sig != l.sig) {
            l.bmp = LayerGfx.render(ctx, project, l, lo, maxSide)
            l.sig = sig
        }
        val chain = ArrayList<Pass>()
        for (inst in l.fx) {
            if (inst.effect.shader.isBlank()) continue
            val vals = HashMap<String, Float>()
            for (p in inst.effect.params) vals[p.id] = inst.valueAt(p, lo)
            chain.add(Pass(inst.effect, vals, lo / 1000f, l.lenMs / 1000f))
        }
        var tr = computeTransform(l, lo)
        if (l.parentId != 0L) {
            val par = project.layers.firstOrNull { it.id == l.parentId }
            if (par != null) tr = composeParent(computeTransform(par, ph - par.startMs), tr)
        }
        lrs.add(LayerRender(l.id, l.bmp, chain, tr, computeRepeats(l, lo, tr), l.blend, l.mesh, l.meshSpin))
    }
    return RenderState(main, lrs, clip, off)
}
