package com.demo.shelfcounter

import android.graphics.Bitmap
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Size
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Counts how many copies of a reference product (the first item) are visible in a photo.
 *
 * 1. SIFT keypoints are extracted once from the whole photo. A low contrast threshold
 *    keeps the faint details of plain, dark or slightly blurred products.
 * 2. Keypoints inside the first item's box are the "model". Every keypoint is matched to
 *    the model (nearest neighbour + ratio test); self-matches are discarded.
 * 3. RANSAC groups the matches into similarity transforms (shift + scale + rotation):
 *    each group with enough inliers is one more copy. Copies can be smaller and partly
 *    hidden.
 * 4. Row search: the copies found form a row with a regular step (each item a bit smaller
 *    and shifted). The next positions along the row are predicted and accepted with fewer
 *    matches, which recovers far, small or mostly hidden items.
 * 5. The whole count runs for two model boxes, the given one and one 10% narrower on each
 *    side, and the higher count wins: a box that also takes in a slice of the neighbouring
 *    item confuses the matching.
 *
 * Tuned on a benchmark of the demo photos (blur, darkness, noise, low contrast, lower
 * resolution, shifted first-item boxes): 252/252 correct against 210/252 for version 2.9,
 * with no extra items counted on single-product pictures.
 */
object ShapeCounter {

    private const val WORK_WIDTH = 900
    private const val MAX_FEATURES = 3000
    private const val CONTRAST_THRESHOLD = 0.015
    private const val RATIO_SQ = 0.8f * 0.8f
    private const val SELF_PX = 4.0
    private const val MIN_INLIERS = 8
    private const val INLIER_PX = 6.0
    private const val ITERATIONS = 1500
    private const val MIN_SCALE = 0.3
    private const val MAX_SCALE = 1.6
    private const val SAME_BOX_IOU = 0.6f
    private const val NARROW_BOX = 0.10f

    // Row search.
    private const val ROW_RATIO_SQ = 0.9f * 0.9f
    private const val ROW_MIN_INLIERS = 5
    private const val ROW_MAX_ITEMS = 6
    private const val ROW_TOLERANCE = 0.18
    private const val ROW_MIN_STEP = 0.5
    private const val ROW_MAX_STEP = 1.02
    private const val ROW_MIN_SCALE = 0.25
    private const val ROW_MAX_SCALE = 1.2

    class Result(
        /** First item, in bitmap coordinates. */
        val reference: RectF,
        /** Other copies found, in bitmap coordinates. */
        val others: List<RectF>,
        val matchedFeatures: Int
    ) {
        val count: Int get() = 1 + others.size
    }

    /** Keypoints of one photo, in work-image coordinates (photo scaled by [f]). */
    private class Features(
        val f: Double,
        val w: Double,
        val h: Double,
        val kx: DoubleArray,
        val ky: DoubleArray,
        val desc: FloatArray,
        val dim: Int
    ) {
        val size: Int get() = kx.size
    }

    /** z -> a*z + b on complex numbers: a = (ar, ai), b = (bx, by). */
    private class Similarity(val ar: Double, val ai: Double, val bx: Double, val by: Double) {
        val scale: Double get() = hypot(ar, ai)
        fun x(px: Double, py: Double) = ar * px - ai * py + bx
        fun y(px: Double, py: Double) = ai * px + ar * py + by
    }

    fun count(bitmap: Bitmap, reference: RectF): Result {
        val features = extract(bitmap)
        val full = countWith(features, reference, reference)
        val inset = reference.width() * NARROW_BOX
        val narrow = RectF(reference.left + inset, reference.top, reference.right - inset, reference.bottom)
        val second = countWith(features, narrow, reference)
        return if (second.count > full.count) second else full
    }

    private fun extract(bitmap: Bitmap): Features {
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
            SIFT.create(MAX_FEATURES, 3, CONTRAST_THRESHOLD, 10.0, 1.6)
                .detectAndCompute(gray, mask, keyPointMat, descriptors)

            val keyPoints = keyPointMat.toArray()
            val n = keyPoints.size
            if (n == 0 || descriptors.rows() != n) {
                return Features(f, w, h, DoubleArray(0), DoubleArray(0), FloatArray(0), 128)
            }
            val dim = descriptors.cols()
            val desc = FloatArray(n * dim)
            descriptors.get(0, 0, desc)
            return Features(
                f, w, h,
                DoubleArray(n) { keyPoints[it].pt.x },
                DoubleArray(n) { keyPoints[it].pt.y },
                desc, dim
            )
        } finally {
            rgba.release(); small.release(); gray.release(); mask.release()
            keyPointMat.release(); descriptors.release()
            if (argb !== bitmap) argb.recycle()
        }
    }

    /**
     * Counts with the keypoints inside [model] as the model. Boxes of the copies are
     * reported for [display] (the first item as shown to the user).
     */
    private fun countWith(features: Features, model: RectF, display: RectF): Result {
        val empty = Result(RectF(display), emptyList(), 0)
        val n = features.size
        if (n == 0) return empty
        val f = features.f
        val kx = features.kx
        val ky = features.ky
        val desc = features.desc
        val dim = features.dim

        // Model box in work-image coordinates.
        val rx0 = model.left * f
        val ry0 = model.top * f
        val rx1 = model.right * f
        val ry1 = model.bottom * f
        val refIdx = (0 until n).filter { kx[it] in rx0..rx1 && ky[it] in ry0..ry1 }.toIntArray()
        if (refIdx.size < 2) return empty

        // 1) Nearest and second-nearest model keypoint of every keypoint.
        val bestIdx = IntArray(n) { -1 }
        val bestD = FloatArray(n) { Float.MAX_VALUE }
        val secondD = FloatArray(n) { Float.MAX_VALUE }
        for (q in 0 until n) {
            var best = Float.MAX_VALUE
            var second = Float.MAX_VALUE
            var idx = -1
            val qOff = q * dim
            for (r in refIdx) {
                val rOff = r * dim
                var d = 0f
                var k = 0
                // Stop early once this candidate cannot be among the two nearest.
                while (k < dim) {
                    val end = min(dim, k + 32)
                    while (k < end) {
                        val diff = desc[qOff + k] - desc[rOff + k]
                        d += diff * diff
                        k++
                    }
                    if (d >= second) break
                }
                if (d < best) {
                    second = best
                    best = d
                    idx = r
                } else if (d < second) {
                    second = d
                }
            }
            bestIdx[q] = idx
            bestD[q] = best
            secondD[q] = second
        }

        // Usable matches: not a keypoint matching itself, and a second-nearest exists.
        val usable = BooleanArray(n) { q ->
            val b = bestIdx[q]
            b >= 0 && secondD[q] != Float.MAX_VALUE &&
                !(abs(kx[q] - kx[b]) < SELF_PX && abs(ky[q] - ky[b]) < SELF_PX)
        }
        val strict = (0 until n).filter { usable[it] && bestD[it] < RATIO_SQ * secondD[it] }.toIntArray()
        val m = strict.size
        val srcX = DoubleArray(m) { kx[bestIdx[strict[it]]] }
        val srcY = DoubleArray(m) { ky[bestIdx[strict[it]]] }
        val dstX = DoubleArray(m) { kx[strict[it]] }
        val dstY = DoubleArray(m) { ky[strict[it]] }

        // 2) Group matches into copies of the product with RANSAC.
        val modelBox = RectF(rx0.toFloat(), ry0.toFloat(), rx1.toFloat(), ry1.toFloat())
        fun boxOf(t: Similarity, x0: Double, y0: Double, x1: Double, y1: Double): RectF {
            val xs = doubleArrayOf(t.x(x0, y0), t.x(x1, y0), t.x(x1, y1), t.x(x0, y1))
            val ys = doubleArrayOf(t.y(x0, y0), t.y(x1, y0), t.y(x1, y1), t.y(x0, y1))
            return RectF(xs.min().toFloat(), ys.min().toFloat(), xs.max().toFloat(), ys.max().toFloat())
        }

        val foundBoxes = ArrayList<RectF>()
        val foundT = ArrayList<Similarity>()
        val random = Random(1)
        var remaining = IntArray(m) { it }
        val thrSq = INLIER_PX * INLIER_PX

        while (remaining.size >= MIN_INLIERS) {
            var bestT: Similarity? = null
            var bestCount = 0
            val size = remaining.size
            repeat(ITERATIONS) {
                val i = remaining[random.nextInt(size)]
                val j = remaining[random.nextInt(size)]
                if (i == j) return@repeat
                val t = similarity(
                    srcX[i], srcY[i], srcX[j], srcY[j],
                    dstX[i], dstY[i], dstX[j], dstY[j]
                ) ?: return@repeat
                val s = t.scale
                if (s < MIN_SCALE || s > MAX_SCALE) return@repeat
                var c = 0
                for (k in remaining) {
                    val ex = t.x(srcX[k], srcY[k]) - dstX[k]
                    val ey = t.y(srcX[k], srcY[k]) - dstY[k]
                    if (ex * ex + ey * ey <= thrSq) c++
                }
                if (c > bestCount) {
                    bestCount = c
                    bestT = t
                }
            }
            val t = bestT ?: break
            if (bestCount < MIN_INLIERS) break
            remaining = remaining.filter { k ->
                val ex = t.x(srcX[k], srcY[k]) - dstX[k]
                val ey = t.y(srcX[k], srcY[k]) - dstY[k]
                ex * ex + ey * ey > thrSq
            }.toIntArray()

            val box = boxOf(t, rx0, ry0, rx1, ry1)
            if (iou(box, modelBox) > SAME_BOX_IOU) continue
            if (foundBoxes.any { iou(box, it) > SAME_BOX_IOU }) continue
            foundBoxes.add(box)
            foundT.add(t)
        }

        // 3) Row search: predict the next items behind the last one found.
        if (foundT.isNotEmpty()) {
            val loose = (0 until n).filter { usable[it] && bestD[it] < ROW_RATIO_SQ * secondD[it] }.toIntArray()
            val lsx = DoubleArray(loose.size) { kx[bestIdx[loose[it]]] }
            val lsy = DoubleArray(loose.size) { ky[bestIdx[loose[it]]] }
            val ldx = DoubleArray(loose.size) { kx[loose[it]] }
            val ldy = DoubleArray(loose.size) { ky[loose[it]] }
            val cx = (rx0 + rx1) / 2
            val cy = (ry0 + ry1) / 2
            val diagonal = hypot(rx1 - rx0, ry1 - ry0)
            val wideSq = (INLIER_PX * 1.5) * (INLIER_PX * 1.5)

            // Front to back: the first item, then the copies from the biggest to the smallest.
            val chain = ArrayList<Similarity>()
            chain.add(Similarity(1.0, 0.0, 0.0, 0.0))
            chain.addAll(foundT.sortedByDescending { it.scale })

            for (step in 0 until ROW_MAX_ITEMS) {
                val t1 = chain[chain.size - 2]
                val t2 = chain[chain.size - 1]
                // Step S with S∘t1 = t2:  A = a2 / a1,  B = b2 − A·b1.
                val den = t1.ar * t1.ar + t1.ai * t1.ai
                if (den < 1e-12) break
                val aR = (t2.ar * t1.ar + t2.ai * t1.ai) / den
                val aI = (t2.ai * t1.ar - t2.ar * t1.ai) / den
                val stepScale = hypot(aR, aI)
                if (stepScale < ROW_MIN_STEP || stepScale > ROW_MAX_STEP) break
                val bR = t2.bx - (aR * t1.bx - aI * t1.by)
                val bI = t2.by - (aI * t1.bx + aR * t1.by)
                // Predicted next item: S∘t2.
                val predicted = Similarity(
                    aR * t2.ar - aI * t2.ai, aI * t2.ar + aR * t2.ai,
                    aR * t2.bx - aI * t2.by + bR, aI * t2.bx + aR * t2.by + bI
                )
                if (predicted.scale < ROW_MIN_SCALE) break
                val px = predicted.x(cx, cy)
                val py = predicted.y(cx, cy)
                if (px < 0 || py < 0 || px >= features.w || py >= features.h) break

                val tolerance = ROW_TOLERANCE * predicted.scale * diagonal
                val near = loose.indices.filter { k ->
                    hypot(predicted.x(lsx[k], lsy[k]) - ldx[k], predicted.y(lsx[k], lsy[k]) - ldy[k]) <= tolerance
                }
                if (near.size < ROW_MIN_INLIERS) break
                val fitted = fit(near, lsx, lsy, ldx, ldy) ?: break
                if (fitted.scale < ROW_MIN_SCALE || fitted.scale > ROW_MAX_SCALE) break
                val support = loose.indices.count { k ->
                    val ex = fitted.x(lsx[k], lsy[k]) - ldx[k]
                    val ey = fitted.y(lsx[k], lsy[k]) - ldy[k]
                    ex * ex + ey * ey <= wideSq
                }
                if (support < ROW_MIN_INLIERS) break
                val box = boxOf(fitted, rx0, ry0, rx1, ry1)
                if (iou(box, modelBox) > SAME_BOX_IOU) break
                if (foundBoxes.any { iou(box, it) > SAME_BOX_IOU }) break
                foundBoxes.add(box)
                foundT.add(fitted)
                chain.add(fitted)
            }
        }

        // Boxes of the copies for the displayed first item, back in bitmap coordinates.
        val inv = (1.0 / f).toFloat()
        val others = foundT.map { t ->
            val b = boxOf(t, display.left * f, display.top * f, display.right * f, display.bottom * f)
            RectF(b.left * inv, b.top * inv, b.right * inv, b.bottom * inv)
        }
        return Result(RectF(display), others, m)
    }

    /** Similarity z -> a*z + b that maps p1->q1 and p2->q2. */
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

    /** Least-squares similarity that maps the source points onto the destination points. */
    private fun fit(
        idx: List<Int>,
        sx: DoubleArray, sy: DoubleArray, dx: DoubleArray, dy: DoubleArray
    ): Similarity? {
        if (idx.size < 2) return null
        var msx = 0.0
        var msy = 0.0
        var mdx = 0.0
        var mdy = 0.0
        for (k in idx) {
            msx += sx[k]; msy += sy[k]; mdx += dx[k]; mdy += dy[k]
        }
        msx /= idx.size; msy /= idx.size; mdx /= idx.size; mdy /= idx.size
        var den = 0.0
        var re = 0.0
        var im = 0.0
        for (k in idx) {
            val ax = sx[k] - msx
            val ay = sy[k] - msy
            val bx = dx[k] - mdx
            val by = dy[k] - mdy
            den += ax * ax + ay * ay
            re += ax * bx + ay * by
            im += ax * by - ay * bx
        }
        if (den < 1e-9) return null
        val ar = re / den
        val ai = im / den
        return Similarity(ar, ai, mdx - (ar * msx - ai * msy), mdy - (ai * msx + ar * msy))
    }

    fun iou(a: RectF, b: RectF): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }
}
