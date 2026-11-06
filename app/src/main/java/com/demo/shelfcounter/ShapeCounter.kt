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
 * Counts how many copies of a reference product (the "first item") are visible in a photo.
 *
 * 1. SIFT keypoints are extracted once from the whole photo ([extract]).
 * 2. For a candidate first-item box, keypoints inside it are the "model"; every other
 *    keypoint is matched to it (nearest neighbour + ratio test, self-matches discarded).
 * 3. Matches are grouped with RANSAC into similarity transforms (shift + scale + rotation):
 *    each group with enough inliers is one more copy. Items further back can be smaller
 *    and partly hidden.
 * 4. [countBest] tries several candidate boxes for the first item and keeps the best one:
 *    a box that is too big (several items) or misplaced finds fewer copies.
 */
object ShapeCounter {

    private const val WORK_WIDTH = 900
    private const val MAX_FEATURES = 2500
    private const val MAX_MODEL = 350
    private const val RATIO_SQ = 0.8f * 0.8f
    private const val SELF_PX = 4.0
    private const val MIN_INLIERS = 8
    private const val INLIER_PX = 6.0
    private const val ITERATIONS = 1000
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

    /** Keypoints of one photo, in work-image coordinates (photo scaled by [f]). */
    class Features(
        val f: Double,
        val kx: DoubleArray,
        val ky: DoubleArray,
        val desc: FloatArray,
        val dim: Int
    ) {
        val size: Int get() = kx.size
    }

    fun extract(bitmap: Bitmap): Features {
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
            if (n == 0 || descriptors.rows() != n) {
                return Features(f, DoubleArray(0), DoubleArray(0), FloatArray(0), 128)
            }
            val dim = descriptors.cols()
            val desc = FloatArray(n * dim)
            descriptors.get(0, 0, desc)
            return Features(
                f,
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

    /** Tries every candidate first-item box and keeps the one that finds the most copies. */
    fun countBest(bitmap: Bitmap, candidates: List<RectF>): Result {
        val features = extract(bitmap)
        var best: Result? = null
        for (c in candidates) {
            val r = countWith(features, c)
            if (best == null || r.count > best.count) best = r
        }
        return best ?: Result(RectF(candidates.firstOrNull() ?: RectF()), emptyList(), 0)
    }

    fun count(bitmap: Bitmap, reference: RectF): Result = countWith(extract(bitmap), reference)

    fun countWith(features: Features, reference: RectF): Result {
        val n = features.size
        if (n == 0) return Result(RectF(reference), emptyList(), 0)
        val f = features.f
        val kx = features.kx
        val ky = features.ky
        val desc = features.desc
        val dim = features.dim

        // Reference box in work-image coordinates.
        val rx0 = reference.left * f
        val ry0 = reference.top * f
        val rx1 = reference.right * f
        val ry1 = reference.bottom * f
        val inBox = (0 until n).filter { kx[it] in rx0..rx1 && ky[it] in ry0..ry1 }
        if (inBox.size < 2) return Result(RectF(reference), emptyList(), 0)
        // Big boxes: an evenly spread subset of the model keeps matching fast.
        val refIdx = if (inBox.size <= MAX_MODEL) inBox
        else List(MAX_MODEL) { inBox[(it.toLong() * inBox.size / MAX_MODEL).toInt()] }

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
