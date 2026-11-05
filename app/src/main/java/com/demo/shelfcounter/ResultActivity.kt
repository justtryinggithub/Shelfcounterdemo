package com.demo.shelfcounter

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/** Shows the final list:  <barcode>  number of objects = <n> */
class ResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VALUES = "com.demo.shelfcounter.VALUES"
        const val EXTRA_FORMATS = "com.demo.shelfcounter.FORMATS"
        const val EXTRA_COUNTS = "com.demo.shelfcounter.COUNTS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)
        findViewById<View>(R.id.header).applySystemBarInsets(bottom = false)
        findViewById<View>(R.id.bottomBar).applySystemBarInsets(top = false)

        val values = intent.getStringArrayListExtra(EXTRA_VALUES) ?: arrayListOf()
        val formats = intent.getStringArrayListExtra(EXTRA_FORMATS) ?: arrayListOf()
        val counts = intent.getIntArrayExtra(EXTRA_COUNTS) ?: IntArray(0)
        val n = minOf(values.size, counts.size)

        val container = findViewById<LinearLayout>(R.id.listContainer)
        val inflater = LayoutInflater.from(this)
        for (i in 0 until n) {
            val row = inflater.inflate(R.layout.item_result, container, false)
            row.findViewById<TextView>(R.id.rowIndex).text = (i + 1).toString()
            row.findViewById<TextView>(R.id.rowBarcode).text = values[i]
            row.findViewById<TextView>(R.id.rowFormat).text = formats.getOrNull(i) ?: ""
            row.findViewById<TextView>(R.id.rowCount).text = getString(R.string.objects_eq, counts[i])
            container.addView(row)
        }
        findViewById<TextView>(R.id.emptyText).visibility = if (n == 0) View.VISIBLE else View.GONE

        val total = (0 until n).sumOf { counts[it] }
        findViewById<TextView>(R.id.resultSummary).text = getString(R.string.results_summary, n, total)

        val shareButton = findViewById<Button>(R.id.shareButton)
        shareButton.isEnabled = n > 0
        shareButton.setOnClickListener {
            val text = (0 until n).joinToString("\n") { i ->
                "${values[i]}  ${getString(R.string.objects_eq, counts[i])}"
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.results_title))
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_title)))
        }

        findViewById<Button>(R.id.homeButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.newCountButton).setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
            finish()
        }
    }
}
