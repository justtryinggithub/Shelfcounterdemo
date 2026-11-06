package com.demo.shelfcounter

import android.Manifest
import android.content.ContentValues
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
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
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
 *  - Tapping the thumbnail opens REVIEW: the best counted frame, frozen. Dragging a box
 *    around the first item recounts it; "Save frame" stores the picture for analysis.
 */
class ScanActivity : AppCompatActivity() {

    /** Best counted frame of the last product (kept for review / saving). */
    private class Frame(val value: String, val bitmap: Bitmap, var result: ShapeCounter.Result, val barcode: RectF)

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
    private lateinit var frozenView: ImageView
    private lateinit var hint: TextView
    private lateinit var liveRow: View
    private lateinit var reviewRow: View

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
    private var bestFrame: Frame? = null
    private var reviewing = false
    private val clearItems = Runnable { overlay.setItems(null, emptyList()) }

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
        frozenView = findViewById(R.id.frozenView)
        hint = findViewById(R.id.hint)
        liveRow = findViewById(R.id.liveRow)
        reviewRow = findViewById(R.id.reviewRow)
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
        thumbnailView.setOnClickListener { openReview() }
        findViewById<Button>(R.id.doneButton).setOnClickListener { closeReview() }
        findViewById<Button>(R.id.saveFrameButton).setOnClickListener { saveFrame() }
        overlay.onBoxDrawn = { viewBox -> recountWithBox(viewBox) }
        onBackPressedDispatcher.addCallback(this) {
            if (reviewing) closeReview() else finishWithResults()
        }

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
        if (finishing || reviewing) return
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
                val candidates = firstItemCandidates(
                    objects.map { RectF(it.boundingBox) }, barcode, bitmap.width, bitmap.height
                )
                countInBackground(product, bitmap, candidates, barcode)
            }
            .addOnFailureListener {
                if (finishing || isDestroyed) return@addOnFailureListener
                val candidates = firstItemCandidates(emptyList(), barcode, bitmap.width, bitmap.height)
                countInBackground(product, bitmap, candidates, barcode)
            }
    }

    private fun countInBackground(product: Product, bitmap: Bitmap, candidates: List<RectF>, barcode: RectF) {
        worker.execute {
            var result: ShapeCounter.Result? = null
            var thumb: Bitmap? = null
            try {
                val r = ShapeCounter.countBest(bitmap, candidates)
                result = r
                thumb = renderThumbnail(bitmap, r)
            } catch (t: Throwable) {
                // Count failed on this frame; the next frame will try again.
            }
            runOnUiThread { finishCount(product, bitmap, barcode, result, thumb) }
        }
    }

    private fun finishCount(
        product: Product, bitmap: Bitmap, barcode: RectF,
        result: ShapeCounter.Result?, thumb: Bitmap?
    ) {
        busy = false
        countingValue = null
        lastJobEndedAt = SystemClock.elapsedRealtime()
        if (finishing || isDestroyed) return
        product.lastCountedAt = lastJobEndedAt

        // Show the counted items on the live image for a moment.
        if (result != null && overlay.width > 0 && !reviewing) {
            val sx = overlay.width.toFloat() / bitmap.width
            val sy = overlay.height.toFloat() / bitmap.height
            fun toView(r: RectF) = RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy)
            overlay.setItems(toView(result.reference), result.others.map { toView(it) })
            overlay.removeCallbacks(clearItems)
            overlay.postDelayed(clearItems, ITEM_SHOW_MS)
        }

        // Ignore results for a product that was reset or fixed by hand meanwhile.
        if (result != null && products[product.value] === product && !product.manual) {
            if (result.count > product.count) {
                product.count = result.count
                feedback()
                pulse(lastCount)
            }
            if (result.count >= product.count && thumb != null) {
                product.thumbnail = thumb
                bestFrame = Frame(product.value, bitmap, result, barcode)
            }
        }
        updateUi()
    }

    /**
     * Possible boxes for the first item of the row; [ShapeCounter.countBest] keeps the one
     * that finds the most copies. They are:
     *  - up to 2 ML Kit objects next to the barcode, clearly bigger than the barcode itself
     *    (the barcode sticker alone is not an item);
     *  - 3 boxes estimated from the barcode, for a product standing just above it
     *    (2.5, 3.5 and 5 barcodes wide, 2.6 times as tall as wide).
     */
    private fun firstItemCandidates(boxes: List<RectF>, barcode: RectF, w: Int, h: Int): List<RectF> {
        val imageArea = w.toFloat() * h
        val minArea = max(imageArea * 0.01f, barcode.width() * barcode.height() * 4f)
        val cx = barcode.centerX()
        val cy = barcode.centerY()
        val bw = max(barcode.width(), barcode.height())
        val out = ArrayList<RectF>()

        boxes
            .filter {
                val area = it.width() * it.height()
                area > minArea && area < imageArea * 0.6f && it.width() > bw * 1.3f
            }
            .map { box ->
                val dx = max(0f, max(box.left - cx, cx - box.right))
                val dy = max(0f, max(box.top - cy, cy - box.bottom))
                box to hypot(dx, dy)
            }
            .filter { (_, d) -> d < bw * 2f }
            .sortedWith(compareBy<Pair<RectF, Float>>({ it.second }, { it.first.width() * it.first.height() }))
            .take(2)
            .forEach { out.add(it.first) }

        val bottom = min(h.toFloat(), barcode.bottom + barcode.height() * 0.3f)
        for (k in floatArrayOf(2.5f, 3.5f, 5f)) {
            val bw2 = k * bw / 2f
            val box = RectF(
                max(0f, cx - bw2), max(0f, bottom - 2.6f * k * bw),
                min(w.toFloat(), cx + bw2), bottom
            )
            if (box.width() > 8f && box.height() > 8f) out.add(box)
        }
        return out
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

    // ---------------------------------------------------------------- review

    private fun openReview() {
        val frame = bestFrame
        val product = lastValue?.let { products[it] }
        if (frame == null || product == null || frame.value != product.value) return
        reviewing = true
        overlay.removeCallbacks(clearItems)
        overlay.setBoxes(emptyList())
        frozenView.setImageBitmap(frame.bitmap)
        frozenView.visibility = View.VISIBLE
        showFrameItems(frame)
        overlay.drawingEnabled = true
        liveRow.visibility = View.GONE
        reviewRow.visibility = View.VISIBLE
        hint.setText(R.string.hint_review)
        updateUi()
    }

    private fun closeReview() {
        reviewing = false
        overlay.drawingEnabled = false
        overlay.clearAll()
        frozenView.setImageDrawable(null)
        frozenView.visibility = View.GONE
        liveRow.visibility = View.VISIBLE
        reviewRow.visibility = View.GONE
        hint.setText(R.string.hint_walk)
        updateUi()
    }

    private fun showFrameItems(frame: Frame) {
        if (overlay.width == 0) return
        val sx = overlay.width.toFloat() / frame.bitmap.width
        val sy = overlay.height.toFloat() / frame.bitmap.height
        fun toView(r: RectF) = RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy)
        overlay.setItems(toView(frame.result.reference), frame.result.others.map { toView(it) })
    }

    /** The user drew the first item on the frozen frame: count again with that box. */
    private fun recountWithBox(viewBox: RectF) {
        val frame = bestFrame ?: return
        if (!reviewing || busy) return
        val product = products[frame.value] ?: return
        val sx = frame.bitmap.width.toFloat() / overlay.width
        val sy = frame.bitmap.height.toFloat() / overlay.height
        val box = RectF(viewBox.left * sx, viewBox.top * sy, viewBox.right * sx, viewBox.bottom * sy)
        busy = true
        updateUi()
        worker.execute {
            val result = try {
                ShapeCounter.count(frame.bitmap, box)
            } catch (t: Throwable) {
                ShapeCounter.Result(RectF(box), emptyList(), 0)
            }
            val thumb = try {
                renderThumbnail(frame.bitmap, result)
            } catch (t: Throwable) {
                null
            }
            runOnUiThread {
                busy = false
                lastJobEndedAt = SystemClock.elapsedRealtime()
                if (finishing || isDestroyed) return@runOnUiThread
                frame.result = result
                product.count = result.count
                product.manual = true
                if (thumb != null) product.thumbnail = thumb
                if (reviewing) showFrameItems(frame)
                feedback()
                pulse(lastCount)
                updateUi()
            }
        }
    }

    /** Saves the frozen frame (clean + annotated) to Pictures/ShelfCounter for analysis. */
    private fun saveFrame() {
        val frame = bestFrame ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, R.string.save_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val stamp = System.currentTimeMillis()
        val base = "shelfcounter_${frame.value}_$stamp"
        val result = frame.result
        worker.execute {
            val ok = try {
                writeJpeg("${base}_clean.jpg", frame.bitmap)
                writeJpeg("${base}_boxes.jpg", annotate(frame.bitmap, result, frame.barcode))
                true
            } catch (t: Throwable) {
                false
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.frame_saved) else getString(R.string.frame_not_saved),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun writeJpeg(name: String, bitmap: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ShelfCounter")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("no uri")
        contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        } ?: throw IllegalStateException("no stream")
    }

    private fun annotate(bitmap: Bitmap, result: ShapeCounter.Result, barcode: RectF): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(3f, out.width / 200f)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = max(24f, out.width / 25f)
        }
        stroke.color = 0xFFFF00FF.toInt()
        canvas.drawRect(barcode, stroke)
        (listOf(result.reference) + result.others).forEachIndexed { i, r ->
            val color = if (i == 0) 0xFF4ADE80.toInt() else 0xFF38BDF8.toInt()
            stroke.color = color
            text.color = color
            canvas.drawRect(r, stroke)
            canvas.drawText((i + 1).toString(), r.left + 8f, r.top + text.textSize, text)
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
        bestFrame = null
        products.clear()
        frameHits.clear()
        lastSeen.clear()
        lastValue = null
        overlay.removeCallbacks(clearItems)
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
        overlay.removeCallbacks(clearItems)
        cameraController?.clearImageAnalysisAnalyzer()
        scanner.close()
        objectDetector.close()
        worker.shutdownNow()
        toneGenerator?.release()
        toneGenerator = null
    }

    companion object {
        private const val CONFIRM_FRAMES = 2
        private const val HIT_RESET_MS = 2000L
        private const val RECOUNT_MS = 900L
        private const val MIN_GAP_MS = 150L
        private const val ITEM_SHOW_MS = 2500L
        private const val THUMB_MAX_PX = 360
    }
}
