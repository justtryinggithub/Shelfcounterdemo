package com.demo.shelfcounter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.Button
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

class ScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: BarcodeOverlayView
    private lateinit var lastBarcode: TextView
    private lateinit var lastFormat: TextView
    private lateinit var lastCount: TextView
    private lateinit var summary: TextView
    private lateinit var hint: TextView
    private lateinit var torchButton: Button

    private lateinit var scanner: BarcodeScanner
    private var cameraController: LifecycleCameraController? = null
    private var toneGenerator: ToneGenerator? = null
    private var torchOn = false
    private var finishing = false

    private val counter = ShelfCounter(confirmFrames = 3)

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
        summary = findViewById(R.id.summary)
        hint = findViewById(R.id.hint)
        torchButton = findViewById(R.id.torchButton)
        findViewById<View>(R.id.uiLayer).applySystemBarInsets()

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

        toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
        } catch (e: RuntimeException) {
            null
        }

        findViewById<Button>(R.id.closeButton).setOnClickListener { finishWithResults() }
        findViewById<Button>(R.id.resetButton).setOnClickListener {
            counter.reset()
            overlay.setBoxes(emptyList())
            updateUi(hasDetections = false)
        }
        torchButton.setOnClickListener { toggleTorch() }
        onBackPressedDispatcher.addCallback(this) { finishWithResults() }

        updateUi(hasDetections = false)

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

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

        val valid = barcodes.filter { !it.rawValue.isNullOrBlank() }
        val update = counter.onFrame(valid.map { it.rawValue!!.trim() to it.format })

        overlay.setBoxes(valid.mapNotNull { barcode ->
            val box = barcode.boundingBox ?: return@mapNotNull null
            val value = barcode.rawValue!!.trim()
            BarcodeOverlayView.Box(box, value, counter.isConfirmed(value))
        })

        if (update.newProduct || update.countIncreased) {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            overlay.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.VIRTUAL_KEY
            )
            pulse(lastCount)
        }
        updateUi(hasDetections = valid.isNotEmpty())
    }

    private fun updateUi(hasDetections: Boolean) {
        val last = counter.lastProduct()
        if (last == null) {
            lastBarcode.text = "—"
            lastFormat.setText(R.string.waiting_for_barcode)
            lastCount.text = "0"
        } else {
            lastBarcode.text = last.value
            lastFormat.text = getString(R.string.format_line, barcodeFormatName(last.format))
            lastCount.text = last.count.toString()
        }
        summary.text = getString(R.string.summary, counter.productCount(), counter.itemCount())
        hint.visibility = if (hasDetections) View.INVISIBLE else View.VISIBLE
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
        val items = counter.results()
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
        toneGenerator?.release()
        toneGenerator = null
    }
}
