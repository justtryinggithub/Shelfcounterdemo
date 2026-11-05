package com.demo.shelfcounter

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Android 15 (Samsung A56, One UI 7) draws apps edge-to-edge. This pads a view
 * so its content stays clear of the status bar, navigation bar and camera cutout.
 */
fun View.applySystemBarInsets(top: Boolean = true, bottom: Boolean = true, sides: Boolean = true) {
    val baseLeft = paddingLeft
    val baseTop = paddingTop
    val baseRight = paddingRight
    val baseBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.setPadding(
            baseLeft + (if (sides) bars.left else 0),
            baseTop + (if (top) bars.top else 0),
            baseRight + (if (sides) bars.right else 0),
            baseBottom + (if (bottom) bars.bottom else 0)
        )
        insets
    }
}

fun barcodeFormatName(format: Int): String = when (format) {
    Barcode.FORMAT_EAN_13 -> "EAN-13"
    Barcode.FORMAT_EAN_8 -> "EAN-8"
    Barcode.FORMAT_UPC_A -> "UPC-A"
    Barcode.FORMAT_UPC_E -> "UPC-E"
    Barcode.FORMAT_CODE_128 -> "Code 128"
    Barcode.FORMAT_CODE_39 -> "Code 39"
    Barcode.FORMAT_CODE_93 -> "Code 93"
    Barcode.FORMAT_ITF -> "ITF"
    Barcode.FORMAT_CODABAR -> "Codabar"
    else -> "Barcode"
}
