package com.demo.shelfcounter

import android.graphics.Bitmap
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Counts how many copies of a reference product are visible in a photo.
 * Two methods run and the higher count wins:
 *
 * A) [countByFeatures] – identical labels (same product, same print).
 * B) [countByTopShape] – same container shape even with different labels
 *    (e.g. fragrances of the same detergent): the top of the first item (cap and
 *    neck) is searched at smaller scales, following the row behind it.
 *
 * Method A:
 * 1. SIFT keypoints are extracted from the whole photo.
 * 2. Keypoints inside the reference box (the first item) are the "model".
 * 3. Every other keypoint is matched to the model (nearest neighbour + ratio test).
 *    Self-matches (a keypoint matching itself) are discarded.
 * 4. Matches are grouped with RANSAC into similarity transforms (shift + scale +
 *    rotation): each group with enough inliers is one more copy of the product.
 *    This tolerates items further back being smaller and partly hidden.
 *
 * Works best when every item is at least partly visible (shoot the row at an angle)
 * and the image is sharp.
 */
object ShapeCounter {

    private const val WORK_WIDTH = 900
    private const val MAX_FEATURES = 3000
    private const val RATIO_SQ = 0.8f * 0.8f
    private const val SELF_PX = 4.0
    private const val MIN_INLIERS = 8
    private const val INLIER_PX = 6.0
    private const val ITERATIONS = 800
    private const val MIN_SCALE = 0.3
    private const val MAX_SCALE = 1.6
    private const val SAME_BOX_IOU = 0.6f

    class Result(
        /** First item, in bitmap coordinates. */
        val reference: RectF,
        /** Other copies found, in bitmap coordinates. */
        val others: List<RectF>,
        val matchedFeatures: Int
    ) {
        val count: Int get() = 1 + others.size
    }

    private class Similarity(val ar: Double, val ai: Double, val bx: Double, val by: Double) {
        val scale: Double get() = hypot(ar, ai)
        fun x(px: Double, py: Double) = ar * px - ai * py + bx
        fun y(px: Double, py: Double) = ai * px + ar * py + by
    }

    /** Runs both methods and keeps the one that finds more items. */
    fun count(bitmap: Bitmap, reference: RectF): Result {
        val byFeatures = try {
            countByFeatures(bitmap, reference)
        } catch (t: Throwable) {
            Result(RectF(reference), emptyList(), 0)
        }
        val byShape = try {
            countByTopShape(bitmap, reference)
        } catch (t: Throwable) {
            Result(RectF(reference), emptyList(), 0)
        }
        return if (byShape.count > byFeatures.count) byShape else byFeatures
    }

    fun countByFeatures(bitmap: Bitmap, reference: RectF): Result {
        val f = min(1.0, WORK_WIDTH.toDouble() / bitmap.width)
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)

        val rgba = Mat()
        val small = Mat()
        val gray = Mat()
        val mask = Mat()
        val keyPointMat = MatOfKeyPoint()
        val descriptors = Mat()
        try {
            Utils.bitmapToMat(argb, rgba)
            val w = Math.round(bitmap.width * f).toDouble()
            val h = Math.round(bitmap.height * f).toDouble()
            Imgproc.resize(rgba, small, Size(w, h), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
            SIFT.create(MAX_FEATURES).detectAndCompute(gray, mask, keyPointMat, descriptors)

            val keyPoints = keyPointMat.toArray()
            val n = keyPoints.size
            if (n == 0 || descriptors.rows() != n) return Result(RectF(reference), emptyList(), 0)
            val dim = descriptors.cols()
            val desc = FloatArray(n * dim)
            descriptors.get(0, 0, desc)

            val kx = DoubleArray(n) { keyPoints[it].pt.x }
            val ky = DoubleArray(n) { keyPoints[it].pt.y }

            // Reference box in work-image coordinates.
            val rx0 = reference.left * f
            val ry0 = reference.top * f
            val rx1 = reference.right * f
            val ry1 = reference.bottom * f
            val refIdx = (0 until n).filter { kx[it] in rx0..rx1 && ky[it] in ry0..ry1 }
            if (refIdx.size < 2) return Result(RectF(reference), emptyList(), 0)

            // 1) Match every keypoint to its nearest model keypoint.
            val srcX = ArrayList<Double>()
            val srcY = ArrayList<Double>()
            val dstX = ArrayList<Double>()
            val dstY = ArrayList<Double>()
            for (q in 0 until n) {
                var best = Float.MAX_VALUE
                var second = Float.MAX_VALUE
                var bestIdx = -1
                val qOff = q * dim
                for (r in refIdx) {
                    val rOff = r * dim
                    var d = 0f
                    for (k in 0 until dim) {
                        val diff = desc[qOff + k] - desc[rOff + k]
                        d += diff * diff
                    }
                    if (d < best) {
                        second = best
                        best = d
                        bestIdx = r
                    } else if (d < second) {
                        second = d
                    }
                }
                if (bestIdx < 0 || second == Float.MAX_VALUE) continue
                if (abs(kx[q] - kx[bestIdx]) < SELF_PX && abs(ky[q] - ky[bestIdx]) < SELF_PX) continue
                if (best < RATIO_SQ * second) {
                    srcX.add(kx[bestIdx]); srcY.add(ky[bestIdx])
                    dstX.add(kx[q]); dstY.add(ky[q])
                }
            }

            // 2) Group matches into copies of the product with RANSAC.
            val refBox = RectF(rx0.toFloat(), ry0.toFloat(), rx1.toFloat(), ry1.toFloat())
            val found = ArrayList<RectF>()
            val random = Random(1)
            var remaining = srcX.indices.toMutableList()
            val thrSq = INLIER_PX * INLIER_PX

            while (remaining.size >= MIN_INLIERS) {
                var bestT: Similarity? = null
                var bestInliers: List<Int> = emptyList()
                repeat(ITERATIONS) {
                    val i = remaining[random.nextInt(remaining.size)]
                    val j = remaining[random.nextInt(remaining.size)]
                    if (i == j) return@repeat
                    val t = similarity(
                        srcX[i], srcY[i], srcX[j], srcY[j],
                        dstX[i], dstY[i], dstX[j], dstY[j]
                    ) ?: return@repeat
                    val s = t.scale
                    if (s < MIN_SCALE || s > MAX_SCALE) return@repeat
                    val inliers = remaining.filter { k ->
                        val ex = t.x(srcX[k], srcY[k]) - dstX[k]
                        val ey = t.y(srcX[k], srcY[k]) - dstY[k]
                        ex * ex + ey * ey <= thrSq
                    }
                    if (inliers.size > bestInliers.size) {
                        bestT = t
                        bestInliers = inliers
                    }
                }
                val t = bestT ?: break
                if (bestInliers.size < MIN_INLIERS) break
                val used = bestInliers.toHashSet()
                remaining = remaining.filterNot { it in used }.toMutableList()

                val xs = doubleArrayOf(t.x(rx0, ry0), t.x(rx1, ry0), t.x(rx1, ry1), t.x(rx0, ry1))
                val ys = doubleArrayOf(t.y(rx0, ry0), t.y(rx1, ry0), t.y(rx1, ry1), t.y(rx0, ry1))
                val box = RectF(
                    xs.min().toFloat(), ys.min().toFloat(),
                    xs.max().toFloat(), ys.max().toFloat()
                )
                if (iou(box, refBox) > SAME_BOX_IOU) continue
                if (found.any { iou(box, it) > SAME_BOX_IOU }) continue
                found.add(box)
            }

            val inv = (1.0 / f).toFloat()
            val others = found.map { RectF(it.left * inv, it.top * inv, it.right * inv, it.bottom * inv) }
            return Result(RectF(reference), others, srcX.size)
        } finally {
            rgba.release(); small.release(); gray.release(); mask.release()
            keyPointMat.release(); descriptors.release()
            if (argb !== bitmap) argb.recycle()
        }
    }

    // ------------------------------------------------------------------ method B

    private const val SHAPE_WORK_WIDTH = 480
    private const val SHAPE_THRESHOLD = 0.5
    private const val SHAPE_COLOR_WEIGHT = 0.85
    private const val SHAPE_MAX_COLOR_DIST = 35.0
    private const val SHAPE_OVERLAP = 0.4f

    private class Hit(val score: Double, val scale: Double, val box: RectF)

    /**
     * Template = top-centre of the first item (cap + neck). It is matched at scales
     * 1.05 → 0.25 (colour + edge normalised correlation, plus a mean-colour check);
     * then a chain is followed from the first item: each next item is a bit smaller,
     * close to the previous one and in a consistent direction.
     */
    fun countByTopShape(bitmap: Bitmap, reference: RectF): Result {
        val f = min(1.0, SHAPE_WORK_WIDTH.toDouble() / bitmap.width)
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val mats = ArrayList<Mat>()
        fun <T : Mat> keep(m: T): T { mats.add(m); return m }
        try {
            val rgba = keep(Mat())
            Utils.bitmapToMat(argb, rgba)
            val small = keep(Mat())
            Imgproc.resize(
                rgba, small,
                Size(Math.round(bitmap.width * f).toDouble(), Math.round(bitmap.height * f).toDouble()),
                0.0, 0.0, Imgproc.INTER_AREA
            )
            val rgb = keep(Mat())
            Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.GaussianBlur(rgb, rgb, Size(9.0, 9.0), 0.0)
            val gray8 = keep(Mat())
            Imgproc.cvtColor(rgb, gray8, Imgproc.COLOR_RGB2GRAY)
            val gray = keep(Mat())
            gray8.convertTo(gray, CvType.CV_32F)
            val gx = keep(Mat())
            val gy = keep(Mat())
            Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3)
            val edges = keep(Mat())
            Core.magnitude(gx, gy, edges)
            Imgproc.GaussianBlur(edges, edges, Size(9.0, 9.0), 0.0)
            val lab = keep(Mat())
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)

            val imgW = rgb.cols()
            val imgH = rgb.rows()

            // Template: top-centre of the first item.
            val rw = reference.width() * f
            val rh = reference.height() * f
            val tplW = 0.5 * rw
            val tplH = min(0.55 * rw, 0.35 * rh)
            val cx = reference.centerX() * f
            val tx0 = max(0, Math.round(cx - tplW / 2).toInt())
            val ty0 = max(0, Math.round(reference.top * f).toInt())
            val tx1 = min(imgW, Math.round(cx + tplW / 2).toInt())
            val ty1 = min(imgH, Math.round(reference.top * f + tplH).toInt())
            val tw = tx1 - tx0
            val th = ty1 - ty0
            if (tw < 8 || th < 8) return Result(RectF(reference), emptyList(), 0)
            val tplRect = org.opencv.core.Rect(tx0, ty0, tw, th)
            val tplColor = keep(rgb.submat(tplRect).clone())
            val tplEdge = keep(edges.submat(tplRect).clone())
            val tplMean = Core.mean(lab.submat(tplRect))

            // Search only around the first item (the row behind it).
            val sx0 = max(0, Math.round(tx0 - 3.0 * rw).toInt())
            val sx1 = min(imgW, Math.round(tx1 + 3.0 * rw).toInt())
            val sy0 = max(0, Math.round(ty0 - 1.2 * rh).toInt())
            val sy1 = min(imgH, Math.round(ty0 + 0.6 * rh).toInt())
            if (sx1 - sx0 <= tw || sy1 - sy0 <= th) return Result(RectF(reference), emptyList(), 0)
            val roi = org.opencv.core.Rect(sx0, sy0, sx1 - sx0, sy1 - sy0)
            val roiColor = rgb.submat(roi)
            val roiEdge = edges.submat(roi)
            val roiLab = lab.submat(roi)

            val hits = ArrayList<Hit>()
            val tColor = keep(Mat())
            val tEdge = keep(Mat())
            val r1 = keep(Mat())
            val r2 = keep(Mat())
            val r = keep(Mat())
            var s = 1.05
            while (s >= 0.25) {
                val sw = Math.round(tw * s).toInt()
                val sh = Math.round(th * s).toInt()
                if (sw >= 8 && sh >= 8 && sw < roi.width && sh < roi.height) {
                    Imgproc.resize(tplColor, tColor, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                    Imgproc.resize(tplEdge, tEdge, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                    Imgproc.matchTemplate(roiColor, tColor, r1, Imgproc.TM_CCOEFF_NORMED)
                    Imgproc.matchTemplate(roiEdge, tEdge, r2, Imgproc.TM_CCOEFF_NORMED)
                    Core.addWeighted(r1, SHAPE_COLOR_WEIGHT, r2, 1.0 - SHAPE_COLOR_WEIGHT, 0.0, r)
                    Core.patchNaNs(r, 0.0)
                    for (k in 0 until 30) {
                        val mm = Core.minMaxLoc(r)
                        if (mm.maxVal < SHAPE_THRESHOLD) break
                        val x = mm.maxLoc.x.toInt()
                        val y = mm.maxLoc.y.toInt()
                        val m = Core.mean(roiLab.submat(org.opencv.core.Rect(x, y, sw, sh)))
                        val dl = m.`val`[0] - tplMean.`val`[0]
                        val da = m.`val`[1] - tplMean.`val`[1]
                        val db = m.`val`[2] - tplMean.`val`[2]
                        if (Math.sqrt(dl * dl + da * da + db * db) <= SHAPE_MAX_COLOR_DIST) {
                            val bx = (x + sx0).toFloat()
                            val by = (y + sy0).toFloat()
                            hits.add(Hit(mm.maxVal, s, RectF(bx, by, bx + sw, by + sh)))
                        }
                        Imgproc.rectangle(
                            r, Point(x - sw / 2.0, y - sh / 2.0), Point(x + sw / 2.0, y + sh / 2.0),
                            Scalar(-1.0), -1
                        )
                    }
                }
                s *= 0.93
            }

            // Non-maximum suppression (overlap relative to the smaller box).
            hits.sortByDescending { it.score }
            val refTpl = RectF(tx0.toFloat(), ty0.toFloat(), tx1.toFloat(), ty1.toFloat())
            val kept = ArrayList<Hit>()
            for (h in hits) {
                if (kept.all { overlap(h.box, it.box) <= SHAPE_OVERLAP }) kept.add(h)
            }
            kept.removeAll { overlap(it.box, refTpl) > SHAPE_OVERLAP }

            // Follow the row from the first item.
            val chain = ArrayList<Hit>()
            var cur = Hit(1.0, 1.0, refTpl)
            val visited = ArrayList<RectF>().apply { add(refTpl) }
            var dirX = 0.0
            var dirY = 0.0
            var hasDir = false
            while (chain.size < 40) {
                val ccx = cur.box.centerX().toDouble()
                val ccy = cur.box.centerY().toDouble()
                val cw = cur.box.width().toDouble()
                val ch = cur.box.height().toDouble()
                var best: Hit? = null
                var bestValue = Double.MAX_VALUE
                var bestDx = 0.0
                var bestDy = 0.0
                for (k in kept) {
                    if (chain.any { it === k }) continue
                    if (k.scale < cur.scale * 0.5 || k.scale > cur.scale * 1.05) continue
                    val dx = k.box.centerX() - ccx
                    val dy = k.box.centerY() - ccy
                    val d = hypot(dx, dy)
                    if (d > 1.6 * cw || d < 0.15 * cw) continue
                    if (dy > 0.3 * ch) continue
                    if (visited.any { overlap(k.box, it) > SHAPE_OVERLAP }) continue
                    if (hasDir && (dx * dirX + dy * dirY) / d < 0.3) continue
                    val v = d / k.score
                    if (v < bestValue) {
                        bestValue = v; best = k; bestDx = dx / d; bestDy = dy / d
                    }
                }
                val next = best ?: break
                chain.add(next)
                visited.add(next.box)
                dirX = bestDx; dirY = bestDy; hasDir = true
                cur = next
            }

            // Template hits -> whole-item boxes, back in bitmap coordinates.
            val inv = (1.0 / f).toFloat()
            val offX = (tx0 - reference.left * f).toFloat()
            val offY = (ty0 - reference.top * f).toFloat()
            val others = chain.map { h ->
                val sc = h.scale.toFloat()
                val left = h.box.left - offX * sc
                val top = h.box.top - offY * sc
                RectF(
                    left * inv, top * inv,
                    (left + rw.toFloat() * sc) * inv, (top + rh.toFloat() * sc) * inv
                )
            }
            return Result(RectF(reference), others, kept.size)
        } finally {
            mats.forEach { it.release() }
            if (argb !== bitmap) argb.recycle()
        }
    }

    /** Intersection divided by the area of the smaller box. */
    private fun overlap(a: RectF, b: RectF): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val small = min(a.width() * a.height(), b.width() * b.height())
        return if (small <= 0f) 0f else ix * iy / small
    }

    /** Similarity z -> a*z + b (complex numbers) that maps p1->q1 and p2->q2. */
    private fun similarity(
        p1x: Double, p1y: Double, p2x: Double, p2y: Double,
        q1x: Double, q1y: Double, q2x: Double, q2y: Double
    ): Similarity? {
        val dx = p2x - p1x
        val dy = p2y - p1y
        val den = dx * dx + dy * dy
        if (den < 1e-6) return null
        val ex = q2x - q1x
        val ey = q2y - q1y
        val ar = (ex * dx + ey * dy) / den
        val ai = (ey * dx - ex * dy) / den
        val bx = q1x - (ar * p1x - ai * p1y)
        val by = q1y - (ai * p1x + ar * p1y)
        return Similarity(ar, ai, bx, by)
    }

    fun iou(a: RectF, b: RectF): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }
}
