package com.vectorizer.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private var source: Bitmap? = null
    private var svg: String? = null
    private var showingSvg = false

    private lateinit var preview: ImageView
    private lateinit var web: WebView
    private lateinit var hint: TextView
    private lateinit var progress: ProgressBar
    private lateinit var seekColors: SeekBar
    private lateinit var seekSpeckle: SeekBar
    private lateinit var labelColors: TextView
    private lateinit var labelSpeckle: TextView
    private lateinit var checkSmooth: CheckBox
    private lateinit var btnConvert: Button
    private lateinit var btnToggle: Button
    private lateinit var btnSave: Button

    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadImage(uri)
    }

    private val saver = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/svg+xml")
    ) { uri ->
        if (uri != null) save(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        web = findViewById(R.id.web)
        hint = findViewById(R.id.hint)
        progress = findViewById(R.id.progress)
        seekColors = findViewById(R.id.seekColors)
        seekSpeckle = findViewById(R.id.seekSpeckle)
        labelColors = findViewById(R.id.labelColors)
        labelSpeckle = findViewById(R.id.labelSpeckle)
        checkSmooth = findViewById(R.id.checkSmooth)
        btnConvert = findViewById(R.id.btnConvert)
        btnToggle = findViewById(R.id.btnToggle)
        btnSave = findViewById(R.id.btnSave)
        web.setBackgroundColor(Color.WHITE)

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = updateLabels()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        seekColors.setOnSeekBarChangeListener(listener)
        seekSpeckle.setOnSeekBarChangeListener(listener)
        updateLabels()

        findViewById<Button>(R.id.btnPick).setOnClickListener { pick.launch("image/*") }
        btnConvert.setOnClickListener { convert() }
        btnToggle.setOnClickListener {
            if (svg == null) toast(getString(R.string.need_svg)) else showSvg(!showingSvg)
        }
        btnSave.setOnClickListener {
            if (svg == null) toast(getString(R.string.need_svg)) else saver.launch("vector.svg")
        }
    }

    private fun updateLabels() {
        labelColors.text = getString(R.string.colors_label, seekColors.progress + 2)
        labelSpeckle.text = getString(R.string.speckle_label, seekSpeckle.progress)
    }

    private fun loadImage(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: throw IllegalStateException("decode failed")
            val big = max(bmp.width, bmp.height)
            if (big > MAX_SIDE) {
                val s = MAX_SIDE.toFloat() / big
                bmp = Bitmap.createScaledBitmap(
                    bmp,
                    max(1, (bmp.width * s).toInt()),
                    max(1, (bmp.height * s).toInt()),
                    true
                )
            }
            source = bmp
            svg = null
            preview.setImageBitmap(bmp)
            hint.visibility = View.GONE
            showSvg(false)
        } catch (e: Throwable) {
            toast(getString(R.string.failed))
        }
    }

    private fun convert() {
        val bmp = source
        if (bmp == null) {
            toast(getString(R.string.need_image))
            return
        }
        val colors = seekColors.progress + 2
        val speckle = seekSpeckle.progress
        val smooth = checkSmooth.isChecked
        progress.visibility = View.VISIBLE
        btnConvert.isEnabled = false
        thread {
            val result = try {
                Vectorizer.convert(bmp, colors, speckle, smooth)
            } catch (e: Throwable) {
                null
            }
            runOnUiThread {
                progress.visibility = View.GONE
                btnConvert.isEnabled = true
                if (result == null) {
                    toast(getString(R.string.failed))
                } else {
                    svg = result
                    showSvg(true)
                }
            }
        }
    }

    private fun showSvg(on: Boolean) {
        showingSvg = on && svg != null
        if (showingSvg) {
            val page = svg!!.replaceFirst(
                Regex("width=\"\\d+\" height=\"\\d+\""),
                "width=\"100%\" height=\"100%\""
            )
            val html = "<html><body style=\"margin:0;background:#fff;height:100%\">" +
                "<div style=\"position:absolute;inset:0\">" + page + "</div></body></html>"
            web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            web.visibility = View.VISIBLE
            preview.visibility = View.GONE
            btnToggle.text = getString(R.string.show_original)
        } else {
            web.visibility = View.GONE
            preview.visibility = View.VISIBLE
            btnToggle.text = getString(R.string.show_svg)
        }
    }

    private fun save(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(svg!!.toByteArray(Charsets.UTF_8)) }
            toast(getString(R.string.saved))
        } catch (e: Throwable) {
            toast(getString(R.string.failed))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MAX_SIDE = 900
    }
}
