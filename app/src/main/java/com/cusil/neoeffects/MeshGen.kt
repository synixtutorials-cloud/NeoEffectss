package com.cusil.neoeffects

object MeshGen {
    private class B {
        val l = ArrayList<Float>()
        fun v(x: Float, y: Float, z: Float, u: Float, w: Float) {
            l.add(x); l.add(y); l.add(z); l.add(u); l.add(w)
        }
        fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) {
            v(a[0], a[1], a[2], 0f, 1f); v(b[0], b[1], b[2], 1f, 1f); v(c[0], c[1], c[2], 1f, 0f)
            v(a[0], a[1], a[2], 0f, 1f); v(c[0], c[1], c[2], 1f, 0f); v(d[0], d[1], d[2], 0f, 0f)
        }
        fun tri(a: FloatArray, b: FloatArray, c: FloatArray, ua: Float, va: Float, ub: Float, vb: Float, uc: Float, vc: Float) {
            v(a[0], a[1], a[2], ua, va); v(b[0], b[1], b[2], ub, vb); v(c[0], c[1], c[2], uc, vc)
        }
        fun arr(): FloatArray = l.toFloatArray()
    }

    private fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
    private fun cs(a: Double) = Math.cos(a).toFloat()
    private fun sn(a: Double) = Math.sin(a).toFloat()
    private const val PI2 = 2.0 * Math.PI

    private fun cube(b: B, x: Float, y: Float, z: Float) {
        b.quad(p(-x, -y, z), p(x, -y, z), p(x, y, z), p(-x, y, z))
        b.quad(p(x, -y, -z), p(-x, -y, -z), p(-x, y, -z), p(x, y, -z))
        b.quad(p(-x, -y, -z), p(-x, -y, z), p(-x, y, z), p(-x, y, -z))
        b.quad(p(x, -y, z), p(x, -y, -z), p(x, y, -z), p(x, y, z))
        b.quad(p(-x, y, z), p(x, y, z), p(x, y, -z), p(-x, y, -z))
        b.quad(p(-x, -y, -z), p(x, -y, -z), p(x, -y, z), p(-x, -y, z))
    }

    private fun roundSides(b: B, n: Int, r: Float, h: Float, fullUv: Boolean) {
        for (i in 0 until n) {
            val a0 = PI2 * i / n
            val a1 = PI2 * (i + 1) / n
            val x0 = r * cs(a0); val z0 = r * sn(a0)
            val x1 = r * cs(a1); val z1 = r * sn(a1)
            if (fullUv) {
                b.quad(p(x0, -h, z0), p(x1, -h, z1), p(x1, h, z1), p(x0, h, z0))
            } else {
                val u0 = i.toFloat() / n
                val u1 = (i + 1).toFloat() / n
                b.v(x0, -h, z0, u0, 1f); b.v(x1, -h, z1, u1, 1f); b.v(x1, h, z1, u1, 0f)
                b.v(x0, -h, z0, u0, 1f); b.v(x1, h, z1, u1, 0f); b.v(x0, h, z0, u0, 0f)
            }
        }
    }

    private fun caps(b: B, n: Int, r: Float, h: Float) {
        for (i in 0 until n) {
            val a0 = PI2 * i / n
            val a1 = PI2 * (i + 1) / n
            val x0 = r * cs(a0); val z0 = r * sn(a0)
            val x1 = r * cs(a1); val z1 = r * sn(a1)
            val k = 0.5f / r
            b.tri(p(0f, h, 0f), p(x0, h, z0), p(x1, h, z1), 0.5f, 0.5f, 0.5f + x0 * k, 0.5f + z0 * k, 0.5f + x1 * k, 0.5f + z1 * k)
            b.tri(p(0f, -h, 0f), p(x1, -h, z1), p(x0, -h, z0), 0.5f, 0.5f, 0.5f + x1 * k, 0.5f + z1 * k, 0.5f + x0 * k, 0.5f + z0 * k)
        }
    }

    fun build(kind: Int): FloatArray {
        val b = B()
        when (kind) {
            1 -> cube(b, 0.6f, 0.6f, 0.6f)
            10 -> cube(b, 0.8f, 0.8f, 0.14f)
            2 -> {
                roundSides(b, 32, 0.6f, 0.6f, false)
                caps(b, 32, 0.6f, 0.6f)
            }
            3 -> {
                val latN = 14
                val lonN = 28
                val r = 0.7f
                for (i in 0 until latN) {
                    val f0 = -Math.PI / 2.0 + Math.PI * i / latN
                    val f1 = -Math.PI / 2.0 + Math.PI * (i + 1) / latN
                    for (j in 0 until lonN) {
                        val t0 = PI2 * j / lonN
                        val t1 = PI2 * (j + 1) / lonN
                        val a = p(r * cs(f0) * cs(t0), r * sn(f0), r * cs(f0) * sn(t0))
                        val bb = p(r * cs(f0) * cs(t1), r * sn(f0), r * cs(f0) * sn(t1))
                        val c = p(r * cs(f1) * cs(t1), r * sn(f1), r * cs(f1) * sn(t1))
                        val d = p(r * cs(f1) * cs(t0), r * sn(f1), r * cs(f1) * sn(t0))
                        val u0 = j.toFloat() / lonN
                        val u1 = (j + 1).toFloat() / lonN
                        val v0 = 1f - i.toFloat() / latN
                        val v1 = 1f - (i + 1).toFloat() / latN
                        b.tri(a, bb, c, u0, v0, u1, v0, u1, v1)
                        b.tri(a, c, d, u0, v0, u1, v1, u0, v1)
                    }
                }
            }
            4 -> {
                val n = 32
                val m = 16
                val rr = 0.45f
                val r = 0.2f
                for (i in 0 until n) for (j in 0 until m) {
                    val u0 = PI2 * i / n; val u1 = PI2 * (i + 1) / n
                    val v0 = PI2 * j / m; val v1 = PI2 * (j + 1) / m
                    fun pt(u: Double, v: Double) = p((rr + r * cs(v)) * cs(u), r * sn(v), (rr + r * cs(v)) * sn(u))
                    val a = pt(u0, v0); val bb = pt(u1, v0); val c = pt(u1, v1); val d = pt(u0, v1)
                    val fu0 = i.toFloat() / n; val fu1 = (i + 1).toFloat() / n
                    val fv0 = j.toFloat() / m; val fv1 = (j + 1).toFloat() / m
                    b.tri(a, bb, c, fu0, fv0, fu1, fv0, fu1, fv1)
                    b.tri(a, c, d, fu0, fv0, fu1, fv1, fu0, fv1)
                }
            }
            5 -> {
                roundSides(b, 6, 0.6f, 0.5f, true)
                caps(b, 6, 0.6f, 0.5f)
            }
            6 -> {
                val r = 0.8f
                for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
                    b.tri(p(sx * r, 0f, 0f), p(0f, sy * r, 0f), p(0f, 0f, sz * r), 0f, 1f, 1f, 1f, 0.5f, 0f)
                }
            }
            7 -> {
                val n = 48
                val ro = 0.85f
                val ri = 0.3f
                fun uv(x: Float, y: Float): FloatArray = floatArrayOf(x / (2f * ro) + 0.5f, 0.5f - y / (2f * ro))
                for (i in 0 until n) {
                    val a0 = PI2 * i / n; val a1 = PI2 * (i + 1) / n
                    val i0 = p(ri * cs(a0), ri * sn(a0), 0f); val o0 = p(ro * cs(a0), ro * sn(a0), 0f)
                    val i1 = p(ri * cs(a1), ri * sn(a1), 0f); val o1 = p(ro * cs(a1), ro * sn(a1), 0f)
                    val ui0 = uv(i0[0], i0[1]); val uo0 = uv(o0[0], o0[1]); val ui1 = uv(i1[0], i1[1]); val uo1 = uv(o1[0], o1[1])
                    b.tri(i0, o0, o1, ui0[0], ui0[1], uo0[0], uo0[1], uo1[0], uo1[1])
                    b.tri(i0, o1, i1, ui0[0], ui0[1], uo1[0], uo1[1], ui1[0], ui1[1])
                }
            }
            8 -> {
                val s = 0.8f
                b.quad(p(-s, -s, 0f), p(s, -s, 0f), p(s, s, 0f), p(-s, s, 0f))
                b.quad(p(-s, 0f, s), p(s, 0f, s), p(s, 0f, -s), p(-s, 0f, -s))
                b.quad(p(0f, -s, s), p(0f, -s, -s), p(0f, s, -s), p(0f, s, s))
            }
            9 -> {
                val n = 24
                val rings = 8
                val r = 1.3f
                for (i in 0 until n) for (k in 0 until rings) {
                    val a0 = PI2 * i / n; val a1 = PI2 * (i + 1) / n
                    val z0 = 0.8f - 0.9f * k; val z1 = 0.8f - 0.9f * (k + 1)
                    val u0 = i.toFloat() / n; val u1 = (i + 1).toFloat() / n
                    val v0 = k.toFloat() / rings; val v1 = (k + 1).toFloat() / rings
                    val a = p(r * cs(a0), r * sn(a0), z0); val bb = p(r * cs(a1), r * sn(a1), z0)
                    val c = p(r * cs(a1), r * sn(a1), z1); val d = p(r * cs(a0), r * sn(a0), z1)
                    b.tri(a, bb, c, u0, v0, u1, v0, u1, v1)
                    b.tri(a, c, d, u0, v0, u1, v1, u0, v1)
                }
            }
            11 -> {
                val n = 10
                val depth = 0.15f
                val pts = ArrayList<FloatArray>()
                for (i in 0 until n) {
                    val a = -Math.PI / 2.0 + PI2 * i / n
                    val r = if (i % 2 == 0) 0.7f else 0.3f
                    pts.add(floatArrayOf(r * cs(a), -r * sn(a)))
                }
                for (i in 0 until n) {
                    val a = pts[i]; val c = pts[(i + 1) % n]
                    b.tri(p(0f, 0f, depth), p(a[0], a[1], depth), p(c[0], c[1], depth), 0.5f, 0.5f, a[0] / 1.4f + 0.5f, 0.5f - a[1] / 1.4f, c[0] / 1.4f + 0.5f, 0.5f - c[1] / 1.4f)
                    b.tri(p(0f, 0f, -depth), p(c[0], c[1], -depth), p(a[0], a[1], -depth), 0.5f, 0.5f, c[0] / 1.4f + 0.5f, 0.5f - c[1] / 1.4f, a[0] / 1.4f + 0.5f, 0.5f - a[1] / 1.4f)
                    b.quad(p(a[0], a[1], -depth), p(c[0], c[1], -depth), p(c[0], c[1], depth), p(a[0], a[1], depth))
                }
            }
            else -> cube(b, 0.6f, 0.6f, 0.6f)
        }
        return b.arr()
    }
}
