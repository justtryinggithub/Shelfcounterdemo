package com.demo.shelfcounter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Continuous counting: the camera stays open while the user walks along the aisle.
 *
 *  - Every 1D barcode read in [CONFIRM_FRAMES] frames becomes a product in the list.
 *  - While a product's barcode is in view, the app grabs a frame about every
 *    [RECOUNT_MS] ms, finds the item closest to the barcode (ML Kit object detection,
 *    the first item of the row) and counts the identical items behind it ([ShapeCounter]).
 *  - A product keeps the highest count seen, so a better angle can only improve it.
 *  - −/+ fix the last product by hand (automatic counting then stops for it).
 */
class ScanActivity : AppCompatActivity() {

    private class Product(val value: String, val format: Int) {
        var count = 1
        var manual = false
        var lastCountedAt = 0L
        var thumbnail: Bitmap? = null
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: BarcodeOverlayView
    private lateinit var lastBarcode: TextView
    private lateinit var lastFormat: TextView
    private lateinit var lastCount: TextView
    private lateinit var thumbnailView: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var summary: TextView
    private lateinit var torchButton: Button
    private lateinit var minusButton: Button
    private lateinit var plusButton: Button

    private lateinit var scanner: BarcodeScanner
    private lateinit var objectDetector: ObjectDetector
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraController: LifecycleCameraController? = null
    private var toneGenerator: ToneGenerator? = null
    private var torchOn = false
    private var finishing = false
    private var openCvReady = false

    private val products = LinkedHashMap<String, Product>()
    private val frameHits = HashMap<String, Int>()
    private val lastSeen = HashMap<String, Long>()
    private var lastValue: String? = null
    private var shownThumbnail: Bitmap? = null

    private var busy = false
    private var countingValue: String? = null
    private var lastJobEndedAt = 0L

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        lastBarcode = findViewById(R.id.lastBarcode)
        lastFormat = findViewById(R.id.lastFormat)
        lastCount = findViewById(R.id.lastCount)
        thumbnailView = findViewById(R.id.thumbnail)
        progress = findViewById(R.id.progress)
        summary = findViewById(R.id.summary)
        torchButton = findViewById(R.id.torchButton)
        minusButton = findViewById(R.id.minusButton)
        plusButton = findViewById(R.id.plusButton)
        findViewById<View>(R.id.uiLayer).applySystemBarInsets()

        openCvReady = try {
            OpenCVLoader.initLocal()
        } catch (t: Throwable) {
            false
        }
        if (!openCvReady) Toast.makeText(this, R.string.opencv_failed, Toast.LENGTH_LONG).show()

        // 1D (linear) barcodes only.
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_CODABAR
            )
            .build()
        scanner = BarcodeScanning.getClient(options)

        objectDetector = ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .build()
        )

        toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
        } catch (e: RuntimeException) {
            null
        }

        findViewById<Button>(R.id.closeButton).setOnClickListener { finishWithResults() }
        findViewById<Button>(R.id.resetButton).setOnClickListener { resetAll() }
        minusButton.setOnClickListener { adjustLast(-1) }
        plusButton.setOnClickListener { adjustLast(+1) }
        torchButton.setOnClickListener { toggleTorch() }
        onBackPressedDispatcher.addCallback(this) { finishWithResults() }

        updateUi()

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // ---------------------------------------------------------------- camera / barcode

    private fun startCamera() {
        val controller = LifecycleCameraController(this)
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)

        // Higher analysis resolution helps read small barcodes from a distance.
        controller.setImageAnalysisResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
        )

        val mainExecutor = ContextCompat.getMainExecutor(this)
        controller.setImageAnalysisAnalyzer(
            mainExecutor,
            MlKitAnalyzer(
                listOf(scanner),
                ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                mainExecutor
            ) { result ->
                val barcodes = result.getValue(scanner) ?: emptyList()
                onBarcodes(barcodes)
            }
        )

        controller.bindToLifecycle(this)
        previewView.controller = controller
        cameraController = controller
    }

    private fun onBarcodes(barcodes: List<Barcode>) {
        if (finishing) return
        val now = SystemClock.elapsedRealtime()

        val valid = barcodes.filter { !it.rawValue.isNullOrBlank() && it.boundingBox != null }
        for (barcode in valid) {
            val value = barcode.rawValue!!.trim()
            // A barcode out of view for a while must be confirmed again.
            if (now - (lastSeen[value] ?: 0L) > HIT_RESET_MS) frameHits[value] = 0
            lastSeen[value] = now
            val hits = (frameHits[value] ?: 0) + 1
            frameHits[value] = hits
            if (hits >= CONFIRM_FRAMES && !products.containsKey(value)) {
                products[value] = Product(value, barcode.format)
                lastValue = value
                feedback()
                pulse(lastBarcode)
            }
        }

        overlay.setBoxes(valid.map { barcode ->
            val value = barcode.rawValue!!.trim()
            BarcodeOverlayView.Box(barcode.boundingBox!!, value, products.containsKey(value))
        })

        // Count one product at a time: the visible one that was counted least recently.
        if (!busy && openCvReady && now - lastJobEndedAt >= MIN_GAP_MS) {
            val next = valid
                .mapNotNull { b -> products[b.rawValue!!.trim()]?.let { p -> p to b } }
                .filter { (p, _) -> !p.manual && now - p.lastCountedAt >= RECOUNT_MS }
                .minByOrNull { (p, _) -> p.lastCountedAt }
            if (next != null) startCount(next.first, Rect(next.second.boundingBox!!))
        }

        updateUi()
    }

    // ---------------------------------------------------------------- counting

    private fun startCount(product: Product, barcodeViewBox: Rect) {
        val bitmap = previewView.bitmap ?: return
        if (overlay.width == 0 || overlay.height == 0) return

        busy = true
        countingValue = product.value
        lastValue = product.value

        val sx = bitmap.width.toFloat() / overlay.width
        val sy = bitmap.height.toFloat() / overlay.height
        val barcode = RectF(
            barcodeViewBox.left * sx, barcodeViewBox.top * sy,
            barcodeViewBox.right * sx, barcodeViewBox.bottom * sy
        )

        objectDetector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { objects ->
                if (finishing || isDestroyed) return@addOnSuccessListener
                val first = pickFirstItem(objects.map { RectF(it.boundingBox) }, barcode, bitmap.width, bitmap.height)
                if (first == null) {
                    finishCount(product, null, null)
                    return@addOnSuccessListener
                }
                worker.execute {
                    var result: ShapeCounter.Result? = null
                    var thumb: Bitmap? = null
                    try {
                        val r = ShapeCounter.count(bitmap, first)
                        result = r
                        thumb = renderThumbnail(bitmap, r)
                    } catch (t: Throwable) {
                        // Count failed on this frame; the next frame will try again.
                    }
                    runOnUiThread { finishCount(product, result, thumb) }
                }
            }
            .addOnFailureListener {
                if (!finishing && !isDestroyed) finishCount(product, null, null)
            }
    }

    private fun finishCount(product: Product, result: ShapeCounter.Result?, thumb: Bitmap?) {
        busy = false
        countingValue = null
        lastJobEndedAt = SystemClock.elapsedRealtime()
        if (finishing || isDestroyed) return
        product.lastCountedAt = lastJobEndedAt

        // Ignore results for a product that was reset or fixed by hand meanwhile.
        if (result != null && products[product.value] === product && !product.manual) {
            if (result.count > product.count) {
                product.count = result.count
                feedback()
                pulse(lastCount)
            }
            if (result.count >= product.count && thumb != null) product.thumbnail = thumb
        }
        updateUi()
    }

    /** The detected object closest to the barcode (the smallest one if it contains it). */
    private fun pickFirstItem(boxes: List<RectF>, barcode: RectF, w: Int, h: Int): RectF? {
        val imageArea = w.toFloat() * h
        val cx = barcode.centerX()
        val cy = barcode.centerY()
        return boxes
            .filter {
                val area = it.width() * it.height()
                area > imageArea * 0.01f && area < imageArea * 0.8f
            }
            .minWithOrNull(compareBy<RectF>({ box ->
                val dx = max(0f, max(box.left - cx, cx - box.right))
                val dy = max(0f, max(box.top - cy, cy - box.bottom))
                hypot(dx, dy)
            }, { box -> box.width() * box.height() }))
    }

    /** A small picture of the counted area, with the items numbered (1 = first item). */
    private fun renderThumbnail(bitmap: Bitmap, result: ShapeCounter.Result): Bitmap {
        val all = listOf(result.reference) + result.others
        val area = RectF(all[0])
        all.forEach { area.union(it) }
        val padX = area.width() * 0.12f
        val padY = area.height() * 0.08f
        area.inset(-padX, -padY)
        val crop = Rect(
            max(0, area.left.roundToInt()), max(0, area.top.roundToInt()),
            min(bitmap.width, area.right.roundToInt()), min(bitmap.height, area.bottom.roundToInt())
        )
        if (crop.width() < 2 || crop.height() < 2) crop.set(0, 0, bitmap.width, bitmap.height)

        val scale = min(1f, THUMB_MAX_PX / max(crop.width(), crop.height()).toFloat())
        val outW = max(1, (crop.width() * scale).roundToInt())
        val outH = max(1, (crop.height() * scale).roundToInt())
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(bitmap, crop, Rect(0, 0, outW, outH), Paint(Paint.FILTER_BITMAP_FLAG))

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, outW / 80f)
        }
        val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = max(14f, outW / 9f)
        }
        val radius = text.textSize * 0.8f
        all.forEachIndexed { i, r ->
            val color = if (i == 0) 0xFF4ADE80.toInt() else 0xFF38BDF8.toInt()
            val box = RectF(
                (r.left - crop.left) * scale, (r.top - crop.top) * scale,
                (r.right - crop.left) * scale, (r.bottom - crop.top) * scale
            )
            stroke.color = color
            canvas.drawRect(box, stroke)
            badge.color = color
            val bx = max(radius, min(box.left + radius, outW - radius))
            val by = max(radius, min(box.top + radius, outH - radius))
            canvas.drawCircle(bx, by, radius, badge)
            canvas.drawText((i + 1).toString(), bx, by - (text.descent() + text.ascent()) / 2f, text)
        }
        return out
    }

    // ---------------------------------------------------------------- manual fixes

    private fun adjustLast(delta: Int) {
        val product = lastValue?.let { products[it] } ?: return
        product.count = max(0, product.count + delta)
        product.manual = true
        pulse(lastCount)
        updateUi()
    }

    private fun resetAll() {
        products.clear()
        frameHits.clear()
        lastSeen.clear()
        lastValue = null
        overlay.clearAll()
        updateUi()
    }

    // ---------------------------------------------------------------- UI

    private fun updateUi() {
        val last = lastValue?.let { products[it] }
        if (last == null) {
            lastBarcode.text = "—"
            lastFormat.setText(R.string.waiting_for_barcode)
            lastCount.text = "–"
        } else {
            lastBarcode.text = last.value
            val status = when {
                last.manual -> R.string.status_adjusted
                countingValue == last.value -> R.string.status_counting
                else -> R.string.status_counted
            }
            lastFormat.text = getString(status, barcodeFormatName(last.format))
            lastCount.text = last.count.toString()
        }

        val thumb = last?.thumbnail
        if (thumb !== shownThumbnail) {
            shownThumbnail = thumb
            thumbnailView.setImageBitmap(thumb)
            thumbnailView.visibility = if (thumb == null) View.GONE else View.VISIBLE
        }

        progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        minusButton.isEnabled = last != null
        plusButton.isEnabled = last != null
        val counted = products.values.filter { it.count > 0 }
        summary.text = getString(R.string.summary, counted.size, counted.sumOf { it.count })
    }

    private fun feedback() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        overlay.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    private fun pulse(view: View) {
        view.animate().cancel()
        view.animate().scaleX(1.3f).scaleY(1.3f).setDuration(90).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }.start()
    }

    private fun toggleTorch() {
        val controller = cameraController ?: return
        if (controller.cameraInfo?.hasFlashUnit() != true) {
            Toast.makeText(this, R.string.no_flash, Toast.LENGTH_SHORT).show()
            return
        }
        torchOn = !torchOn
        controller.enableTorch(torchOn)
        torchButton.setText(if (torchOn) R.string.light_off else R.string.light_on)
    }

    private fun finishWithResults() {
        if (finishing) return
        finishing = true
        val items = products.values.filter { it.count > 0 }
        val intent = Intent(this, ResultActivity::class.java).apply {
            putStringArrayListExtra(ResultActivity.EXTRA_VALUES, ArrayList(items.map { it.value }))
            putStringArrayListExtra(
                ResultActivity.EXTRA_FORMATS,
                ArrayList(items.map { barcodeFormatName(it.format) })
            )
            putExtra(ResultActivity.EXTRA_COUNTS, items.map { it.count }.toIntArray())
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraController?.clearImageAnalysisAnalyzer()
        scanner.close()
        objectDetector.close()
        worker.shutdownNow()
        toneGenerator?.release()
        toneGenerator = null
    }

    companion object {
        private const val CONFIRM_FRAMES = 3
        private const val HIT_RESET_MS = 2000L
        private const val RECOUNT_MS = 1500L
        private const val MIN_GAP_MS = 300L
        private const val THUMB_MAX_PX = 360
    }
}
