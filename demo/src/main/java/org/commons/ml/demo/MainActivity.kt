package org.commons.ml.demo

import android.app.AlertDialog
import org.commons.ml.common.DetectionOptions
import org.commons.ml.common.DetectionResult
import org.commons.ml.common.DetectionType
import org.commons.ml.vision.CommonsVision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/** Standalone demo for local face and license-plate detection. */
class MainActivity : ComponentActivity() {
    private lateinit var imageView: ImageView
    private lateinit var overlay: DetectionOverlayView
    private var bitmap: Bitmap? = null
    private var sourceUri: Uri? = null
    private var detector: CommonsVision? = null
    private var thresholdState by mutableFloatStateOf(0.5f)
    private var statusMessage by mutableStateOf("")

    private val openImage =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { loadImage(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setStatus("Select a photo. Inference stays on this device.")
    }

    private fun buildUi() {
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Commons ML", style = MaterialTheme.typography.headlineSmall)
                        Text("Review detected faces and license plates", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { openImage.launch(arrayOf("image/*")) }) { Text("Open") }
                            Button(onClick = { detect() }, enabled = bitmap != null) { Text("Detect") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { applyRedaction() }, enabled = ::overlay.isInitialized && overlay.getDetections().isNotEmpty()) { Text("Redact") }
                            TextButton(onClick = { overlay.removeSelected() }) { Text("Delete") }
                        }
                        Text(statusMessage, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
                        Text("Confidence threshold: ${(thresholdState * 100).toInt()}")
                        Slider(
                            value = thresholdState,
                            onValueChange = { thresholdState = it },
                            valueRange = 0.05f..0.95f,
                            onValueChangeFinished = { if (bitmap != null) detect() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        AndroidView(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            factory = {
                                val frame = android.widget.FrameLayout(it)
                                imageView = ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER; background = ColorDrawable(0xffeeeeee.toInt()) }
                                overlay = DetectionOverlayView(it)
                                frame.addView(imageView)
                                frame.addView(overlay)
                                frame
                            }
                        )
                    }
                }
            }
        }
    }

    private fun loadImage(uri: Uri) {
        sourceUri = uri
        contentResolver.openInputStream(uri)?.use { stream ->
            val decoded = BitmapFactory.decodeStream(stream) ?: return
            bitmap = decoded
            imageView.setImageBitmap(bitmap)
            overlay.setSourceSize(bitmap!!.width, bitmap!!.height)
            overlay.setDetections(emptyList())
            setStatus("Loaded ${bitmap!!.width}×${bitmap!!.height}.")
        }
    }

    private fun detect() {
        val source = bitmap ?: run {
            setStatus("Open an image first.")
            return
        }
        setStatus("Running ONNX Runtime locally…")
        lifecycleScope.launch {
            val dialogView = LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.dialog, null)
            val titleView = dialogView.findViewById<TextView>(R.id.autodetect_title)
            val descView = dialogView.findViewById<TextView>(R.id.autodetect_description)
            val percentView = dialogView.findViewById<TextView>(R.id.autodetect_percent)
            val progressBar = dialogView.findViewById<ProgressBar>(R.id.autodetect_progress)

            titleView?.text = "Auto-blurring faces and license plates."
            descView?.text = "You can discard false positives using the ❌ button of each blurring area."
            percentView?.text = "0%"
            progressBar?.progress = 0

            val progressDialog = AlertDialog.Builder(this@MainActivity)
                .setView(dialogView)
                .setCancelable(false)
                .create()
            progressDialog.show()

            runCatching {
                withContext(Dispatchers.Default) {
                    val started = System.nanoTime()
                    val options = DetectionOptions(confidenceThreshold = thresholdState)
                    val result = getDetector().detect(source, options) { progress ->
                        launch(Dispatchers.Main) {
                            val percent = (progress * 100).toInt()
                            progressBar?.progress = percent
                            percentView?.text = "$percent%"
                        }
                    }
                    Pair(result, (System.nanoTime() - started) / 1_000_000)
                }
            }.onSuccess { result ->
                // Ensure 100% is displayed and give a brief delay so the user can comfortably read it
                progressBar?.progress = 100
                percentView?.text = "100%"
                delay(700.milliseconds)
                progressDialog.dismiss()

                val detections = when (val value = result.first) {
                    is DetectionResult.Success -> value.detections
                    is DetectionResult.Partial -> value.detections
                    is DetectionResult.Unavailable -> {
                        overlay.setDetections(emptyList())
                        setStatus("Detection unavailable: ${value.reason}")
                        return@onSuccess
                    }
                }
                overlay.setDetections(detections)
                setStatus(
                    String.format(
                        Locale.US,
                        "Detected %d regions (%d faces, %d plates) in %d ms. Tap/drag boxes; delete false positives.",
                        detections.size,
                        detections.count { it.type == DetectionType.FACE },
                        detections.count { it.type == DetectionType.LICENSE_PLATE },
                        result.second
                    )
                )
            }.onFailure { error ->
                progressDialog.dismiss()
                setStatus("Detection failed: ${diagnosticMessage(error)}")
            }
        }
    }

    private fun diagnosticMessage(error: Throwable): String {
        val messages = buildList {
            var current: Throwable? = error
            while (current != null && size < 4) {
                val detail = current.message?.takeIf { it.isNotBlank() }
                add(detail ?: current.javaClass.simpleName)
                current = current.cause
            }
        }
        return messages.joinToString(" → ")
    }

    private fun setStatus(message: String) {
        statusMessage = message
    }

    private fun applyRedaction() {
        val source = bitmap ?: return
        val regions = overlay.getDetections().map { it.bounds }
        if (regions.isEmpty()) {
            setStatus("No regions selected. Run detection or draw boxes in a future iteration.")
            return
        }
        val redacted = source.copy(Bitmap.Config.ARGB_8888, true)
        pixelate(redacted, regions)
        bitmap?.recycle()
        bitmap = redacted
        imageView.setImageBitmap(redacted)
        overlay.setDetections(emptyList())
        setStatus("Applied local pixelation to ${regions.size} regions.")
    }

    private fun getDetector(): CommonsVision =
        detector ?: CommonsVision(this).also { detector = it }

    override fun onDestroy() {
        detector?.close()
        bitmap?.recycle()
        super.onDestroy()
    }

    private fun pixelate(bitmap: Bitmap, regions: List<RectF>) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (original in regions) {
            val region = RectF(original)
            region.inset(-region.width() * 0.12f, -region.height() * 0.12f)
            region.intersect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            if (region.width() < 2f || region.height() < 2f) continue
            val left = region.left.toInt()
            val top = region.top.toInt()
            val width = region.width().toInt().coerceAtLeast(2)
            val height = region.height().toInt().coerceAtLeast(2)
            val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
            val tiny = Bitmap.createScaledBitmap(crop, 12, 12, true)
            canvas.drawBitmap(tiny, null, region, paint)
            crop.recycle()
            tiny.recycle()
        }
    }
}
