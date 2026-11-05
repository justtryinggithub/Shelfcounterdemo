package com.demo.shelfcounter

/**
 * Counts identical products lined up in a row, using their barcodes.
 *
 * Every camera frame reports the barcodes visible at that moment. If the same
 * barcode value appears N times in one frame, N identical items are side by side.
 * The count for a product is the highest N that has been seen in at least
 * [confirmFrames] frames, which filters out one-frame misreads and flicker.
 * Counts only go up during a session; "Reset" clears everything.
 */
class ShelfCounter(private val confirmFrames: Int = 3) {

    class Product(val value: String, val format: Int) {
        var count: Int = 0
            internal set

        /** k -> number of frames in which at least k copies were visible. */
        internal val framesWithAtLeast = HashMap<Int, Int>()
    }

    data class Update(val newProduct: Boolean, val countIncreased: Boolean)

    private val products = LinkedHashMap<String, Product>()
    private var lastValue: String? = null

    /** @param detections one (rawValue, format) pair per barcode found in the frame. */
    fun onFrame(detections: List<Pair<String, Int>>): Update {
        var newProduct = false
        var increased = false
        var bestInFrame: Product? = null
        var bestN = 0

        for ((value, group) in detections.groupBy { it.first }) {
            val n = group.size
            val product = products.getOrPut(value) { Product(value, group.first().second) }

            for (k in 1..n) {
                product.framesWithAtLeast[k] = (product.framesWithAtLeast[k] ?: 0) + 1
            }

            var confirmed = product.count
            while ((product.framesWithAtLeast[confirmed + 1] ?: 0) >= confirmFrames) {
                confirmed++
            }
            if (confirmed > product.count) {
                if (product.count == 0) newProduct = true else increased = true
                product.count = confirmed
            }

            if (product.count > 0 && n > bestN) {
                bestN = n
                bestInFrame = product
            }
        }

        if (bestInFrame != null) lastValue = bestInFrame.value
        return Update(newProduct, increased)
    }

    fun isConfirmed(value: String): Boolean = (products[value]?.count ?: 0) > 0

    fun lastProduct(): Product? = lastValue?.let { products[it] }

    /** Confirmed products, in the order they were first seen. */
    fun results(): List<Product> = products.values.filter { it.count > 0 }

    fun productCount(): Int = results().size

    fun itemCount(): Int = results().sumOf { it.count }

    fun reset() {
        products.clear()
        lastValue = null
    }
}
