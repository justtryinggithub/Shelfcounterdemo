package com.demo.shelfcounter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
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

/**
 * Flow:
 *  1. SCANNING  – live camera; the first 1D barcode read in 3 frames is saved as the product.
 *  2. COUNTING  – "Count items" freezes the image; ML Kit finds the item closest to the
 *                 barcode (the first item of the row), then [ShapeCounter] counts the
 *                 identical items behind it.
 *  3. REVIEW    – numbered boxes on the photo; the user can redraw the first item,
 *                 adjust the count with −/+, then Save or Retake.
 *
 * Version 2.7: a new barcode shows "Barcode acquired, counting in 1 second"; one second
 * later counting starts. Copies must also match the first item's colours (ShapeCounter).
 *
 * Version 2.6: as soon as a new barcode is read the app counts automatically, twice, on
 * two different frames. If both counts agree the value is saved in the list, the camera
 * stays open and "Count done. Looking for the next barcode" is shown until a new barcode
 * (one not counted yet) is read. If they differ, the last frame is frozen for review.
 * "Recount items" repeats the double count for the current barcode.
 */
class ScanActivity : AppCompatActivity() {

    private enum class Mode { SCANNING, COUNTING, REVIEW }

    private class Saved(val format: Int, var count: Int)

    private lateinit var previewView: PreviewView
    private lateinit var frozenView: ImageView
    private lateinit var overlay: BarcodeOverlayView
    private lateinit var lastBarcode: TextView
    private lateinit var lastFormat: TextView
    private lateinit var lastCount: TextView
    private lateinit var summary: TextView
    private lateinit var hint: TextView
    private lateinit var progress: ProgressBar
    private lateinit var torchButton: Button
    private lateinit var countButton: Button
    private lateinit var liveControls: View
    private lateinit var reviewControls: View

    private lateinit var scanner: BarcodeScanner
    private lateinit var objectDetector: ObjectDetector
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraController: LifecycleCameraController? = null
    private var toneGenerator: ToneGenerator? = null
    private var torchOn = false
    private var finishing = false
    private var openCvReady = false

    private var mode = Mode.SCANNING

    // Barcode being counted.
    private var currentValue: String? = null
    private var currentFormat = Barcode.FORMAT_UNKNOWN
    private val frameHits = HashMap<String, Int>()
    private var lastBarcodeBox: Rect? = null
    private var lastBarcodeSeenAt = 0L

    // Current count (REVIEW mode).
    private var frozenBitmap: Bitmap? = null
    private var countValue = 0
    private var countJob = 0

    // Double count (2.6).
    private var pass = 0
    private var firstPassCount = 0
    private var waitingNext = false
    private lateinit var doneBanner: TextView
    private val clearItems = Runnable { if (mode == Mode.SCANNING) overlay.setItems(null, emptyList()) }
    private val autoStart = Runnable { if (mode == Mode.SCANNING && !waitingNext) startCount() }

    // Saved results, in the order they were counted.
    private val results = LinkedHashMap<String, Saved>()

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
        frozenView = findViewById(R.id.frozenView)
        overlay = findViewById(R.id.overlay)
        lastBarcode = findViewById(R.id.lastBarcode)
        lastFormat = findViewById(R.id.lastFormat)
        lastCount = findViewById(R.id.lastCount)
        summary = findViewById(R.id.summary)
        hint = findViewById(R.id.hint)
        doneBanner = findViewById(R.id.doneBanner)
        progress = findViewById(R.id.progress)
        torchButton = findViewById(R.id.torchButton)
        countButton = findViewById(R.id.countButton)
        liveControls = findViewById(R.id.liveControls)
        reviewControls = findViewById(R.id.reviewControls)
        findViewById<View>(R.id.uiLayer).applySystemBarInsets()

        openCvReady = try {
            OpenCVLoader.initLocal()
        } catch (t: Throwable) {
            false
        }

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

        overlay.onBoxDrawn = { viewBox -> onUserDrewBox(viewBox) }

        findViewById<Button>(R.id.closeButton).setOnClickListener { finishWithResults() }
        findViewById<Button>(R.id.resetButton).setOnClickListener { resetCurrent() }
        countButton.setOnClickListener { startCount() }
        findViewById<Button>(R.id.retakeButton).setOnClickListener { backToScanning(keepBarcode = true) }
        findViewById<Button>(R.id.saveButton).setOnClickListener { saveCount() }
        findViewById<Button>(R.id.minusButton).setOnClickListener { adjustCount(-1) }
        findViewById<Button>(R.id.plusButton).setOnClickListener { adjustCount(+1) }
        torchButton.setOnClickListener { toggleTorch() }
        onBackPressedDispatcher.addCallback(this) {
            if (mode == Mode.SCANNING) finishWithResults() else backToScanning(keepBarcode = true)
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
        if (finishing) return
        val valid = barcodes.filter { !it.rawValue.isNullOrBlank() && it.boundingBox != null }

        // While counting live, only keep track of where the current barcode is.
        if (mode == Mode.COUNTING) {
            valid.firstOrNull { it.rawValue!!.trim() == currentValue }?.let {
                lastBarcodeBox = Rect(it.boundingBox!!)
                lastBarcodeSeenAt = SystemClock.elapsedRealtime()
            }
            return
        }
        if (mode != Mode.SCANNING) return

        var newlySaved = false

        for (barcode in valid) {
            val value = barcode.rawValue!!.trim()
            val hits = (frameHits[value] ?: 0) + 1
            frameHits[value] = hits
            // A barcode already in the list does not start a new count (use Recount items).
            if (hits >= CONFIRM_FRAMES && value != currentValue && !results.containsKey(value)) {
                currentValue = value
                currentFormat = barcode.format
                newlySaved = true
            }
            if (value == currentValue) {
                lastBarcodeBox = Rect(barcode.boundingBox!!)
                lastBarcodeSeenAt = SystemClock.elapsedRealtime()
            }
        }

        overlay.setBoxes(valid.map { barcode ->
            val value = barcode.rawValue!!.trim()
            BarcodeOverlayView.Box(
                barcode.boundingBox!!, value, value == currentValue || results.containsKey(value)
            )
        })

        if (newlySaved) {
            // New barcode: the "count done" message goes away, "barcode acquired" is shown
            // and counting starts by itself one second later.
            setWaitingNext(false)
            showBanner(R.string.barcode_acquired, R.drawable.bg_acquired_banner)
            feedback()
            pulse(lastBarcode)
            updateUi()
            overlay.removeCallbacks(autoStart)
            overlay.postDelayed(autoStart, AUTO_START_MS)
        }
    }

    // ---------------------------------------------------------------- counting

    /** "Recount items" and new barcodes: count twice on two different frames. */
    private fun startCount() {
        if (currentValue == null) {
            Toast.makeText(this, R.string.scan_barcode_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (!openCvReady) {
            Toast.makeText(this, R.string.opencv_failed, Toast.LENGTH_LONG).show()
            return
        }
        if (mode != Mode.SCANNING) return
        overlay.removeCallbacks(autoStart)
        overlay.removeCallbacks(clearItems)
        setWaitingNext(false)
        overlay.clearAll()
        pass = 1
        firstPassCount = 0
        countPass()
    }

    /** One counting pass on the live camera image (no freezing). */
    private fun countPass() {
        val bitmap = previewView.bitmap
        if (bitmap == null || overlay.width == 0) {
            pass = 0
            setMode(Mode.SCANNING, null)
            Toast.makeText(this, R.string.no_image, Toast.LENGTH_SHORT).show()
            return
        }

        // Barcode position, if it was in view a moment ago (view coordinates).
        val barcodeBox = lastBarcodeBox?.takeIf {
            SystemClock.elapsedRealtime() - lastBarcodeSeenAt < BARCODE_RECENT_MS
        }

        setMode(Mode.COUNTING, getString(R.string.hint_counting_pass, pass))

        val job = ++countJob
        objectDetector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { objects ->
                if (job != countJob || mode != Mode.COUNTING) return@addOnSuccessListener
                val sx = bitmap.width.toFloat() / overlay.width
                val sy = bitmap.height.toFloat() / overlay.height
                val barcodeInBitmap = barcodeBox?.let {
                    RectF(it.left * sx, it.top * sy, it.right * sx, it.bottom * sy)
                }
                val boxes = objects.map { RectF(it.boundingBox) }
                val first = pickFirstItem(boxes, barcodeInBitmap, bitmap.width, bitmap.height)
                if (first == null) freezeAndAskToDraw(bitmap) else countPassInBackground(bitmap, first, job)
            }
            .addOnFailureListener {
                if (job == countJob && mode == Mode.COUNTING) freezeAndAskToDraw(bitmap)
            }
    }

    private fun countPassInBackground(bitmap: Bitmap, first: RectF, job: Int) {
        worker.execute {
            val result = try {
                ShapeCounter.count(bitmap, first)
            } catch (t: Throwable) {
                ShapeCounter.Result(RectF(first), emptyList(), 0)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || job != countJob || mode != Mode.COUNTING) return@runOnUiThread
                if (pass == 1) {
                    // First count done: take the second one on a new frame.
                    firstPassCount = result.count
                    pass = 2
                    countValue = result.count
                    updateUi(getString(R.string.hint_counting_pass, pass))
                    overlay.postDelayed({
                        if (job == countJob && mode == Mode.COUNTING && pass == 2) countPass()
                    }, SECOND_PASS_MS)
                } else {
                    pass = 0
                    if (result.count == firstPassCount) countsAgree(bitmap, result)
                    else countsDiffer(bitmap, result)
                }
            }
        }
    }

    /** Both counts are the same: save, keep the camera open, wait for the next barcode. */
    private fun countsAgree(bitmap: Bitmap, result: ShapeCounter.Result) {
        val value = currentValue ?: return
        results[value] = Saved(currentFormat, result.count)
        countValue = result.count
        feedback()
        pulse(lastCount)
        setMode(Mode.SCANNING, null)
        setWaitingNext(true)

        // Show the counted items on the live image for a moment.
        val sx = overlay.width.toFloat() / bitmap.width
        val sy = overlay.height.toFloat() / bitmap.height
        fun toView(r: RectF) = RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy)
        overlay.setItems(toView(result.reference), result.others.map { toView(it) })
        overlay.removeCallbacks(clearItems)
        overlay.postDelayed(clearItems, ITEM_SHOW_MS)
    }

    /** The two counts differ: freeze the last frame so the user can check and fix it. */
    private fun countsDiffer(bitmap: Bitmap, result: ShapeCounter.Result) {
        frozenBitmap = bitmap
        frozenView.setImageBitmap(bitmap)
        frozenView.visibility = View.VISIBLE
        val sx = overlay.width.toFloat() / bitmap.width
        val sy = overlay.height.toFloat() / bitmap.height
        fun toView(r: RectF) = RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy)
        overlay.setItems(toView(result.reference), result.others.map { toView(it) })
        countValue = result.count
        setMode(Mode.REVIEW, getString(R.string.hint_counts_differ, firstPassCount, result.count))
    }

    private fun freezeAndAskToDraw(bitmap: Bitmap) {
        pass = 0
        frozenBitmap = bitmap
        frozenView.setImageBitmap(bitmap)
        frozenView.visibility = View.VISIBLE
        askToDraw()
    }

    private fun setWaitingNext(waiting: Boolean) {
        waitingNext = waiting
        if (waiting) showBanner(R.string.count_done, R.drawable.bg_done_banner)
        else doneBanner.visibility = View.GONE
        updateUi()
    }

    private fun showBanner(text: Int, background: Int) {
        doneBanner.setText(text)
        doneBanner.setBackgroundResource(background)
        doneBanner.visibility = View.VISIBLE
    }

    /** The detected object closest to the barcode; without a barcode, the biggest one. */
    private fun pickFirstItem(boxes: List<RectF>, barcode: RectF?, w: Int, h: Int): RectF? {
        val imageArea = w.toFloat() * h
        val candidates = boxes.filter {
            val area = it.width() * it.height()
            area > imageArea * 0.01f && area < imageArea * 0.8f
        }
        if (candidates.isEmpty()) return null
        if (barcode == null) return candidates.maxByOrNull { it.width() * it.height() }
        val cx = barcode.centerX()
        val cy = barcode.centerY()
        return candidates.minByOrNull { box ->
            val dx = max(0f, max(box.left - cx, cx - box.right))
            val dy = max(0f, max(box.top - cy, cy - box.bottom))
            hypot(dx, dy)
        }
    }

    private fun askToDraw() {
        overlay.setItems(null, emptyList())
        countValue = 0
        setMode(Mode.REVIEW, getString(R.string.hint_draw_box))
    }

    private fun onUserDrewBox(viewBox: RectF) {
        val bitmap = frozenBitmap ?: return
        if (mode != Mode.REVIEW) return
        val sx = bitmap.width.toFloat() / overlay.width
        val sy = bitmap.height.toFloat() / overlay.height
        val box = RectF(viewBox.left * sx, viewBox.top * sy, viewBox.right * sx, viewBox.bottom * sy)
        box.intersect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        runCount(bitmap, box)
    }

    private fun runCount(bitmap: Bitmap, first: RectF) {
        val job = ++countJob
        val sx = overlay.width.toFloat() / bitmap.width
        val sy = overlay.height.toFloat() / bitmap.height
        fun toView(r: RectF) = RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy)

        overlay.setItems(toView(first), emptyList())
        setMode(Mode.COUNTING, getString(R.string.hint_counting))

        worker.execute {
            val result = try {
                ShapeCounter.count(bitmap, first)
            } catch (t: Throwable) {
                ShapeCounter.Result(RectF(first), emptyList(), 0)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || job != countJob || mode != Mode.COUNTING) return@runOnUiThread
                overlay.setItems(toView(result.reference), result.others.map { toView(it) })
                countValue = result.count
                feedback()
                pulse(lastCount)
                setMode(Mode.REVIEW, getString(R.string.hint_result, result.count))
            }
        }
    }

    private fun adjustCount(delta: Int) {
        if (mode != Mode.REVIEW) return
        countValue = max(0, countValue + delta)
        pulse(lastCount)
        updateUi()
    }

    private fun saveCount() {
        if (mode != Mode.REVIEW) return
        val value = currentValue ?: return
        if (countValue > 0) {
            results[value] = Saved(currentFormat, countValue)
            Toast.makeText(this, getString(R.string.saved_toast, value, countValue), Toast.LENGTH_SHORT).show()
        }
        backToScanning(keepBarcode = true)
        setWaitingNext(true)
    }

    private fun backToScanning(keepBarcode: Boolean) {
        countJob++
        pass = 0
        setWaitingNext(false)
        frozenView.setImageDrawable(null)
        frozenView.visibility = View.GONE
        frozenBitmap = null
        countValue = 0
        overlay.clearAll()
        if (!keepBarcode) resetBarcode()
        setMode(Mode.SCANNING, null)
    }

    private fun resetCurrent() {
        overlay.removeCallbacks(autoStart)
        setWaitingNext(false)
        resetBarcode()
        overlay.clearAll()
        updateUi()
    }

    private fun resetBarcode() {
        currentValue = null
        currentFormat = Barcode.FORMAT_UNKNOWN
        frameHits.clear()
        lastBarcodeBox = null
        lastBarcodeSeenAt = 0L
    }

    // ---------------------------------------------------------------- UI

    private fun setMode(newMode: Mode, hintText: String?) {
        mode = newMode
        overlay.drawingEnabled = newMode == Mode.REVIEW
        progress.visibility = if (newMode == Mode.COUNTING) View.VISIBLE else View.GONE
        liveControls.visibility = if (newMode == Mode.SCANNING) View.VISIBLE else View.GONE
        reviewControls.visibility = if (newMode == Mode.REVIEW) View.VISIBLE else View.GONE
        updateUi(hintText)
    }

    private fun updateUi(hintText: String? = null) {
        val value = currentValue
        if (value == null) {
            lastBarcode.text = "—"
            lastFormat.setText(R.string.waiting_for_barcode)
        } else {
            lastBarcode.text = value
            val status = when {
                mode == Mode.COUNTING -> R.string.status_counting
                results.containsKey(value) -> R.string.status_in_list
                mode == Mode.REVIEW -> R.string.status_counted
                else -> R.string.status_saved
            }
            lastFormat.text = getString(status, barcodeFormatName(currentFormat))
        }
        val saved = value?.let { results[it] }
        lastCount.text = when {
            mode == Mode.REVIEW -> countValue.toString()
            mode == Mode.COUNTING && pass == 2 -> countValue.toString()
            saved != null -> saved.count.toString()
            else -> "–"
        }
        countButton.isEnabled = value != null && mode == Mode.SCANNING
        summary.text = getString(R.string.summary, results.size, results.values.sumOf { it.count })
        hint.text = hintText ?: when {
            mode != Mode.SCANNING -> hint.text
            value == null -> getString(R.string.hint_scan_barcode)
            waitingNext -> getString(R.string.hint_next)
            else -> getString(R.string.hint_frame_row)
        }
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
        // A count on screen that was not saved yet is kept.
        if (mode == Mode.REVIEW && countValue > 0) {
            currentValue?.let { value ->
                results[value] = Saved(currentFormat, countValue)
            }
        }
        finishing = true
        val entries = results.entries.filter { it.value.count > 0 }
        val intent = Intent(this, ResultActivity::class.java).apply {
            putStringArrayListExtra(ResultActivity.EXTRA_VALUES, ArrayList(entries.map { it.key }))
            putStringArrayListExtra(
                ResultActivity.EXTRA_FORMATS,
                ArrayList(entries.map { barcodeFormatName(it.value.format) })
            )
            putExtra(ResultActivity.EXTRA_COUNTS, entries.map { it.value.count }.toIntArray())
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        countJob++
        overlay.removeCallbacks(autoStart)
        overlay.removeCallbacks(clearItems)
        cameraController?.clearImageAnalysisAnalyzer()
        scanner.close()
        objectDetector.close()
        worker.shutdownNow()
        toneGenerator?.release()
        toneGenerator = null
    }

    companion object {
        private const val CONFIRM_FRAMES = 3
        private const val BARCODE_RECENT_MS = 1500L
        private const val AUTO_START_MS = 1000L
        private const val SECOND_PASS_MS = 400L
        private const val ITEM_SHOW_MS = 2500L
    }
}
