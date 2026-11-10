package com.vectorizer.app

import android.graphics.Bitmap
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/**
 * Image -> SVG engine (no external library):
 * 1) k-means colour quantisation
 * 2) per-colour mask -> pixel-edge boundary tracing
 * 3) Douglas-Peucker simplification
 * 4) optional smoothing into cubic Bezier curves
 */
object Vectorizer {

    fun convert(bmp: Bitmap, colors: Int, speckle: Int, smooth: Boolean): String {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val palette = quantize(px, colors)
        val idx = assign(px, palette)
        val counts = IntArray(palette.size)
        var hasTransparent = false
        for (i in idx) {
            if (i >= 0) counts[i]++ else hasTransparent = true
        }
        val order = palette.indices.filter { counts[it] > 0 }.sortedByDescending { counts[it] }

        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\">\n")
        var first = true
        for (c in order) {
            val hex = "#" + Integer.toHexString(0x1000000 or (palette[c] and 0xFFFFFF)).substring(1).uppercase()
            if (first && !hasTransparent) {
                sb.append("<rect width=\"$w\" height=\"$h\" fill=\"$hex\"/>\n")
                first = false
                continue
            }
            first = false
            val mask = BooleanArray(w * h)
            for (i in mask.indices) mask[i] = idx[i] == c
            val d = traceMask(mask, w, h, speckle, smooth)
            if (d.isNotEmpty()) {
                sb.append("<path fill=\"$hex\" stroke=\"$hex\" stroke-width=\"0.6\" stroke-linejoin=\"round\" fill-rule=\"evenodd\" d=\"")
                sb.append(d)
                sb.append("\"/>\n")
            }
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    // ---------- colour quantisation ----------

    private fun dist2(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 255) - ((b shr 16) and 255)
        val dg = ((a shr 8) and 255) - ((b shr 8) and 255)
        val db = (a and 255) - (b and 255)
        return dr * dr + dg * dg + db * db
    }

    private fun nearest(p: Int, pal: IntArray): Int {
        var best = 0
        var bd = Int.MAX_VALUE
        for (c in pal.indices) {
            val d = dist2(p, pal[c])
            if (d < bd) {
                bd = d
                best = c
            }
        }
        return best
    }

    private fun quantize(px: IntArray, k: Int): IntArray {
        val step = maxOf(1, px.size / 20000)
        val sample = IntArray(px.size / step + 1)
        var n = 0
        var i = 0
        while (i < px.size) {
            if ((px[i] ushr 24) >= 128) {
                sample[n++] = px[i] and 0xFFFFFF
            }
            i += step
        }
        if (n == 0) return intArrayOf(0)

        val kk = min(k, n)
        val rnd = Random(42)
        val centers = IntArray(kk)
        centers[0] = sample[rnd.nextInt(n)]
        val minD = DoubleArray(n) { Double.MAX_VALUE }
        for (c in 1 until kk) {
            var total = 0.0
            for (j in 0 until n) {
                val dd = dist2(sample[j], centers[c - 1]).toDouble()
                if (dd < minD[j]) minD[j] = dd
                total += minD[j]
            }
            if (total <= 0.0) {
                centers[c] = sample[rnd.nextInt(n)]
                continue
            }
            var r = rnd.nextDouble() * total
            var pick = n - 1
            for (j in 0 until n) {
                r -= minD[j]
                if (r <= 0) {
                    pick = j
                    break
                }
            }
            centers[c] = sample[pick]
        }

        repeat(10) {
            val sumR = LongArray(kk)
            val sumG = LongArray(kk)
            val sumB = LongArray(kk)
            val cnt = IntArray(kk)
            for (j in 0 until n) {
                val p = sample[j]
                var best = 0
                var bd = Int.MAX_VALUE
                for (c in 0 until kk) {
                    val dd = dist2(p, centers[c])
                    if (dd < bd) {
                        bd = dd
                        best = c
                    }
                }
                sumR[best] += ((p shr 16) and 255).toLong()
                sumG[best] += ((p shr 8) and 255).toLong()
                sumB[best] += (p and 255).toLong()
                cnt[best]++
            }
            for (c in 0 until kk) {
                if (cnt[c] > 0) {
                    val r = (sumR[c] / cnt[c]).toInt()
                    val g = (sumG[c] / cnt[c]).toInt()
                    val b = (sumB[c] / cnt[c]).toInt()
                    centers[c] = (r shl 16) or (g shl 8) or b
                }
            }
        }
        return centers
    }

    private fun assign(px: IntArray, pal: IntArray): IntArray {
        val out = IntArray(px.size)
        val cache = IntArray(32768) { -1 }
        for (i in px.indices) {
            val p = px[i]
            if ((p ushr 24) < 128) {
                out[i] = -1
                continue
            }
            val key = (((p shr 19) and 31) shl 10) or (((p shr 11) and 31) shl 5) or ((p shr 3) and 31)
            var c = cache[key]
            if (c < 0) {
                c = nearest(p and 0xFFFFFF, pal)
                cache[key] = c
            }
            out[i] = c
        }
        return out
    }

    // ---------- contour tracing ----------

    private class IntBuf {
        var a = IntArray(64)
        var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
    }

    private val DX = intArrayOf(1, 0, -1, 0)
    private val DY = intArrayOf(0, 1, 0, -1)

    private fun traceMask(mask: BooleanArray, w: Int, h: Int, speckle: Int, smooth: Boolean): String {
        val vw = w + 1
        val dirs = ByteArray(vw * (h + 1))
        fun add(x: Int, y: Int, d: Int) {
            val k = y * vw + x
            dirs[k] = (dirs[k].toInt() or (1 shl d)).toByte()
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                if (y == 0 || !mask[(y - 1) * w + x]) add(x, y, 0)
                if (x == w - 1 || !mask[y * w + x + 1]) add(x + 1, y, 1)
                if (y == h - 1 || !mask[(y + 1) * w + x]) add(x + 1, y + 1, 2)
                if (x == 0 || !mask[y * w + x - 1]) add(x, y + 1, 3)
            }
        }

        val sb = StringBuilder()
        val pts = IntBuf()
        for (start in dirs.indices) {
            while ((dirs[start].toInt() and 0xFF) != 0) {
                val sx = start % vw
                val sy = start / vw
                var d = Integer.numberOfTrailingZeros(dirs[start].toInt() and 0xFF)
                pts.n = 0
                pts.add(sx)
                pts.add(sy)
                var x = sx
                var y = sy
                while (true) {
                    val k = y * vw + x
                    dirs[k] = (dirs[k].toInt() and (1 shl d).inv()).toByte()
                    x += DX[d]
                    y += DY[d]
                    if (x == sx && y == sy) break
                    val m = dirs[y * vw + x].toInt() and 0xFF
                    val t1 = (d + 1) % 4
                    val t3 = (d + 3) % 4
                    val nd = if ((m and (1 shl t1)) != 0) t1
                    else if ((m and (1 shl d)) != 0) d
                    else if ((m and (1 shl t3)) != 0) t3
                    else -1
                    if (nd < 0) break
                    if (nd != d) {
                        pts.add(x)
                        pts.add(y)
                    }
                    d = nd
                }
                val cnt = pts.n / 2
                if (cnt < 3) continue
                var area2 = 0L
                for (i in 0 until cnt) {
                    val j = (i + 1) % cnt
                    area2 += pts.a[2 * i].toLong() * pts.a[2 * j + 1] - pts.a[2 * j].toLong() * pts.a[2 * i + 1]
                }
                if (abs(area2) / 2 < speckle) continue
                appendLoop(sb, pts, cnt, smooth)
            }
        }
        return sb.toString()
    }

    private fun simplify(xs: DoubleArray, ys: DoubleArray, eps: Double): IntArray {
        val n = xs.size
        if (n <= 3) return IntArray(n) { it }
        var far = 1
        var fd = -1.0
        for (i in 1 until n) {
            val d = hypot(xs[i] - xs[0], ys[i] - ys[0])
            if (d > fd) {
                fd = d
                far = i
            }
        }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[far] = true
        val stack = ArrayList<IntArray>()
        stack.add(intArrayOf(0, far))
        stack.add(intArrayOf(far, n))
        while (stack.isNotEmpty()) {
            val seg = stack.removeAt(stack.size - 1)
            val a = seg[0]
            val b = seg[1]
            if (b - a < 2) continue
            val ax = xs[a % n]
            val ay = ys[a % n]
            val bx = xs[b % n]
            val by = ys[b % n]
            val dx = bx - ax
            val dy = by - ay
            val len = hypot(dx, dy)
            var maxD = -1.0
            var mi = -1
            for (i in a + 1 until b) {
                val d = if (len == 0.0) hypot(xs[i] - ax, ys[i] - ay)
                else abs(dx * (ys[i] - ay) - dy * (xs[i] - ax)) / len
                if (d > maxD) {
                    maxD = d
                    mi = i
                }
            }
            if (maxD > eps && mi >= 0) {
                keep[mi] = true
                stack.add(intArrayOf(a, mi))
                stack.add(intArrayOf(mi, b))
            }
        }
        var c = 0
        for (v in keep) if (v) c++
        val out = IntArray(c)
        var j = 0
        for (i in 0 until n) if (keep[i]) out[j++] = i
        return out
    }

    private fun num(sb: StringBuilder, v: Double) {
        val r = Math.round(v * 10).toDouble() / 10
        if (r == floor(r)) sb.append(r.toLong()) else sb.append(r)
    }

    private fun appendLoop(sb: StringBuilder, pts: IntBuf, m: Int, smooth: Boolean) {
        val xs = DoubleArray(m) { pts.a[2 * it].toDouble() }
        val ys = DoubleArray(m) { pts.a[2 * it + 1].toDouble() }
        val kept = simplify(xs, ys, if (smooth) 1.0 else 0.6)
        val k = kept.size
        if (k < 3) return
        val px = DoubleArray(k) { xs[kept[it]] }
        val py = DoubleArray(k) { ys[kept[it]] }

        if (!smooth) {
            sb.append('M')
            num(sb, px[0]); sb.append(' '); num(sb, py[0])
            for (i in 1 until k) {
                sb.append('L')
                num(sb, px[i]); sb.append(' '); num(sb, py[i])
            }
            sb.append('Z')
            return
        }

        val corner = BooleanArray(k)
        for (i in 0 until k) {
            val p = (i + k - 1) % k
            val q = (i + 1) % k
            val ax = px[i] - px[p]
            val ay = py[i] - py[p]
            val bx = px[q] - px[i]
            val by = py[q] - py[i]
            val la = hypot(ax, ay)
            val lb = hypot(bx, by)
            corner[i] = if (la == 0.0 || lb == 0.0) true else (ax * bx + ay * by) / (la * lb) < 0.35
        }

        sb.append('M')
        num(sb, px[0]); sb.append(' '); num(sb, py[0])
        for (i in 0 until k) {
            val p0 = (i + k - 1) % k
            val p1 = i
            val p2 = (i + 1) % k
            val p3 = (i + 2) % k
            val seg = hypot(px[p2] - px[p1], py[p2] - py[p1])
            val lim = seg * 0.4
            var c1x = px[p1]
            var c1y = py[p1]
            var c2x = px[p2]
            var c2y = py[p2]
            if (!corner[p1]) {
                var tx = (px[p2] - px[p0]) / 6.0
                var ty = (py[p2] - py[p0]) / 6.0
                val l = hypot(tx, ty)
                if (l > lim && l > 0.0) {
                    tx *= lim / l
                    ty *= lim / l
                }
                c1x += tx
                c1y += ty
            }
            if (!corner[p2]) {
                var tx = (px[p3] - px[p1]) / 6.0
                var ty = (py[p3] - py[p1]) / 6.0
                val l = hypot(tx, ty)
                if (l > lim && l > 0.0) {
                    tx *= lim / l
                    ty *= lim / l
                }
                c2x -= tx
                c2y -= ty
            }
            sb.append('C')
            num(sb, c1x); sb.append(' '); num(sb, c1y); sb.append(' ')
            num(sb, c2x); sb.append(' '); num(sb, c2y); sb.append(' ')
            num(sb, px[p2]); sb.append(' '); num(sb, py[p2])
        }
        sb.append('Z')
    }
}
