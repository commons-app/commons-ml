package org.commons.ml.vision

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.commons.ml.common.Detection
import org.commons.ml.common.DetectionOptions
import org.commons.ml.common.DetectionResult
import org.commons.ml.runtime.ModelInput
import org.commons.ml.runtime.MlRuntimeException
import org.commons.ml.runtime.ModelLoadException
import org.commons.ml.runtime.ModelRuntime
import org.commons.ml.runtime.ModelSession
import org.commons.ml.runtime.RuntimeClosedException
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Runs the OpenCV Zoo YuNet and LPD-YuNet ONNX graphs through ONNX Runtime.
 *
 * Face YuNet and LPD-YuNet share the ONNX Runtime integration, but they do not
 * share the output contract: face YuNet has cls/obj/bbox heads, while LPD-YuNet
 * has SSD-style loc/conf/iou outputs and four corner points per detection.
 */
class OnnxYuNetDetector internal constructor(
    runtime: ModelRuntime,
    private val kind: DetectorKind
) : Detector {
    private val session: ModelSession = runtime.openSession(kind.assetName)
    private val inputWidth: Int
    private val inputHeight: Int
    private var closed = false

    init {
        try {
            val shape = session.inputShape
            if (shape.size != 4) {
                throw ModelLoadException(
                    kind.assetName,
                    "expected NCHW model input, got ${shape.contentToString()}"
                )
            }
            inputHeight = shape[2].takeIf { it > 0 }?.toInt() ?: kind.inputHeight
            inputWidth = shape[3].takeIf { it > 0 }?.toInt() ?: kind.inputWidth
        } catch (error: MlRuntimeException) {
            try {
                session.close()
            } catch (closeError: MlRuntimeException) {
                error.addSuppressed(closeError)
            }
            throw error
        }
    }

    /** Detects faces or plates and maps model coordinates back to the source bitmap. */
    override suspend fun detect(source: Bitmap, options: DetectionOptions): DetectionResult {
        checkOpen()
        val threshold = options.confidenceThreshold
        val regions =
            chunkRegions(source)
        val detections = regions.flatMap { region ->
            val crop = Bitmap.createBitmap(source, region.left, region.top, region.width, region.height)
            try {
                detectRegion(crop, threshold).map { detection ->
                    detection.copy(
                        bounds = RectF(detection.bounds).apply {
                            offset(region.left.toFloat(), region.top.toFloat())
                        }
                    )
                }
            } finally {
                // Android may return the original bitmap for a full-image crop.
                // Never recycle the caller-owned source image.
                if (crop !== source) crop.recycle()
            }
        }
        val result = nonMaximumSuppression(detections).take(options.maximumResults)
        return DetectionResult.Success(result)
    }

    private fun detectRegion(source: Bitmap, threshold: Float): List<Detection> {
        // The bundled models have fixed input dimensions. Plate detection gets
        // more detail through tiled crops rather than an invalid dynamic shape.
        val inferenceWidth = inputWidth
        val inferenceHeight = inputHeight
        val input = FloatArray(1 * 3 * inferenceWidth * inferenceHeight)
        val resized = Bitmap.createScaledBitmap(source, inferenceWidth, inferenceHeight, true)
        try {
            val pixels = IntArray(inferenceWidth * inferenceHeight)
            resized.getPixels(pixels, 0, inferenceWidth, 0, 0, inferenceWidth, inferenceHeight)
            val planeSize = inferenceWidth * inferenceHeight
            for (y in 0 until inferenceHeight) {
                for (x in 0 until inferenceWidth) {
                    val color = pixels[y * inferenceWidth + x]
                    val index = y * inferenceWidth + x
                    // OpenCV Zoo models are trained with OpenCV's BGR input convention.
                    // Match OpenCV's FaceDetectorYN/blobFromImage preprocessing:
                    // BGR channels, unchanged 8-bit pixel scale (0..255).
                    input[index] = (color and 0xff).toFloat()
                    input[planeSize + index] = ((color shr 8) and 0xff).toFloat()
                    input[2 * planeSize + index] = ((color shr 16) and 0xff).toFloat()
                }
            }

        val outputs = session.run(
            ModelInput(
                input,
                longArrayOf(1, 3, inferenceHeight.toLong(), inferenceWidth.toLong())
            )
        ).map { Output(it.name, it.values) }
        if (kind == DetectorKind.LICENSE_PLATE) {
            Log.d(
                "PlateDetector",
                "ONNX outputs: ${outputs.joinToString { "${it.name}=${it.size}" }}, threshold=$threshold"
            )
        }
        return if (kind == DetectorKind.LICENSE_PLATE) {
            decodeLicensePlates(
                outputs,
                source.width,
                source.height,
                inferenceWidth,
                inferenceHeight,
                threshold
            )
        } else {
            decodeFaces(outputs, source.width, source.height, inferenceWidth, inferenceHeight, threshold)
        }
        } finally {
            if (resized !== source) resized.recycle()
        }
    }

    private fun decodeFaces(
        outputs: List<Output>,
        sourceWidth: Int,
        sourceHeight: Int,
        modelWidth: Int,
        modelHeight: Int,
        threshold: Float
    ): List<Detection> {
        val grouped = outputs.associateBy { it.name }
        val detections = mutableListOf<Detection>()
        for (stride in intArrayOf(8, 16, 32)) {
            val cls = grouped["cls_$stride"] ?: continue
            val obj = grouped["obj_$stride"] ?: continue
            val bbox = grouped["bbox_$stride"] ?: continue
            val count = min(cls.size, min(obj.size, bbox.size / 4))
            val gridWidth = (modelWidth + stride - 1) / stride
            for (index in 0 until count) {
                val score = sqrt(probability(cls[index]) * probability(obj[index]))
                if (score < threshold) continue
                val gridX = index % gridWidth
                val gridY = index / gridWidth
                val offset = index * 4
                val centerX = (bbox[offset] + gridX) * stride
                val centerY = (bbox[offset + 1] + gridY) * stride
                val width = exp(bbox[offset + 2]) * stride
                val height = exp(bbox[offset + 3]) * stride
                val left = centerX - width / 2f
                val top = centerY - height / 2f
                val box = RectF(
                    left * sourceWidth / modelWidth,
                    top * sourceHeight / modelHeight,
                    (left + width) * sourceWidth / modelWidth,
                    (top + height) * sourceHeight / modelHeight
                )
                box.intersect(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat())
                if (box.width() > 1f && box.height() > 1f) {
                    detections += Detection(kind.detectionType, score, box)
                }
            }
        }
        return nonMaximumSuppression(detections)
    }

    /**
     * Decodes OpenCV Zoo's LPD-YuNet output contract. The model emits:
     *   loc: [N, 14] (SSD offsets; four corners use columns 4, 6, 10, 12)
     *   conf: [N, 2]  (background and plate confidence)
     *   iou: [N, 1]   (predicted localization quality)
     *
     * This follows the reference lpd_yunet.py implementation. The app's
     * BlurRegion pipeline currently accepts axis-aligned rectangles, so the
     * predicted quadrilateral is conservatively enclosed by one rectangle.
     */
    private fun decodeLicensePlates(
        outputs: List<Output>,
        sourceWidth: Int,
        sourceHeight: Int,
        modelWidth: Int,
        modelHeight: Int,
        threshold: Float
    ): List<Detection> {
        val grouped = outputs.associateBy { it.name.lowercase() }
        val loc = grouped["loc"] ?: return emptyList()
        val conf = grouped["conf"] ?: return emptyList()
        val iou = grouped["iou"] ?: return emptyList()
        val priors = generatePlatePriors(modelWidth, modelHeight)
        val count = min(priors.size, min(loc.size / 14, min(conf.size / 2, iou.size)))
        val detections = mutableListOf<Detection>()
        val topScores = (0 until count)
            .map { index ->
                val classScore = conf[index * 2 + 1].coerceIn(0f, 1f)
                val iouScore = iou[index].coerceIn(0f, 1f)
                sqrt(classScore * iouScore)
            }
            .sortedDescending()
            .take(5)
        Log.d("PlateDetector", "ONNX plate candidates=$count, topScores=$topScores, threshold=$threshold")
        val scaleX = modelWidth.toFloat()
        val scaleY = modelHeight.toFloat()

        for (index in 0 until count) {
            val classScore = conf[index * 2 + 1].coerceIn(0f, 1f)
            val iouScore = iou[index].coerceIn(0f, 1f)
            val score = sqrt(classScore * iouScore)
            if (score < threshold) continue

            val prior = priors[index]
            val offset = index * 14
            val points = floatArrayOf(
                (prior.cx + loc[offset + 4] * 0.1f * prior.width) * scaleX,
                (prior.cy + loc[offset + 5] * 0.1f * prior.height) * scaleY,
                (prior.cx + loc[offset + 6] * 0.1f * prior.width) * scaleX,
                (prior.cy + loc[offset + 7] * 0.1f * prior.height) * scaleY,
                (prior.cx + loc[offset + 10] * 0.1f * prior.width) * scaleX,
                (prior.cy + loc[offset + 11] * 0.1f * prior.height) * scaleY,
                (prior.cx + loc[offset + 12] * 0.1f * prior.width) * scaleX,
                (prior.cy + loc[offset + 13] * 0.1f * prior.height) * scaleY
            )
            val left = points.filterIndexed { point, _ -> point % 2 == 0 }.minOrNull() ?: continue
            val top = points.filterIndexed { point, _ -> point % 2 == 1 }.minOrNull() ?: continue
            val right = points.filterIndexed { point, _ -> point % 2 == 0 }.maxOrNull() ?: continue
            val bottom = points.filterIndexed { point, _ -> point % 2 == 1 }.maxOrNull() ?: continue
            val box = RectF(
                left * sourceWidth / scaleX,
                top * sourceHeight / scaleY,
                right * sourceWidth / scaleX,
                bottom * sourceHeight / scaleY
            )
            box.intersect(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat())
            val width = box.width()
            val height = box.height()
            if (width > 1f && height > 1f) {
                val aspectRatio = width / height
                // Standard license plates are horizontal rectangles (aspect ratio ~1.3 to 6.5).
                // Reject square or tall false positives like headlights, grills, or road signs.
                if (aspectRatio in MIN_PLATE_ASPECT_RATIO..MAX_PLATE_ASPECT_RATIO) {
                    detections += Detection(kind.detectionType, score, box)
                }
            }
        }
        return nonMaximumSuppression(detections)
    }

    private fun generatePlatePriors(modelWidth: Int, modelHeight: Int): List<PlatePrior> {
        val minSizes = arrayOf(
            intArrayOf(10, 16, 24),
            intArrayOf(32, 48),
            intArrayOf(64, 96),
            intArrayOf(128, 192, 256)
        )
        val steps = intArrayOf(8, 16, 32, 64)
        fun halve(value: Int): Int = value / 2
        val featureMap2 = intArrayOf(halve((modelHeight + 1) / 2), halve((modelWidth + 1) / 2))
        val featureMap3 = intArrayOf(halve(featureMap2[0]), halve(featureMap2[1]))
        val featureMap4 = intArrayOf(halve(featureMap3[0]), halve(featureMap3[1]))
        val featureMap5 = intArrayOf(halve(featureMap4[0]), halve(featureMap4[1]))
        val featureMap6 = intArrayOf(halve(featureMap5[0]), halve(featureMap5[1]))
        val featureMaps = arrayOf(featureMap3, featureMap4, featureMap5, featureMap6)
        val priors = mutableListOf<PlatePrior>()
        for (feature in featureMaps.indices) {
            val rows = featureMaps[feature][0]
            val columns = featureMaps[feature][1]
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    for (size in minSizes[feature]) {
                        priors += PlatePrior(
                            cx = (column + 0.5f) * steps[feature] / modelWidth,
                            cy = (row + 0.5f) * steps[feature] / modelHeight,
                            width = size.toFloat() / modelWidth,
                            height = size.toFloat() / modelHeight
                        )
                    }
                }
            }
        }
        return priors
    }

    /**
     * Slices the source image into small, high-resolution chunks (640×480 px, 2× model size).
     * Because each chunk is small, minimal downsampling occurs when resized to the model's 320×240 input,
     * allowing the model to detect small license plates easily.
     */
    private fun chunkRegions(source: Bitmap): List<InferenceRegion> {
        val chunkWidth = min(source.width, inputWidth * CHUNK_SCALE_FACTOR)
        val chunkHeight = min(source.height, inputHeight * CHUNK_SCALE_FACTOR)

        val xPositions = chunkPositions(source.width, chunkWidth)
        val yPositions = chunkPositions(source.height, chunkHeight)

        return yPositions.flatMap { y ->
            xPositions.map { x ->
                InferenceRegion(x, y, chunkWidth, chunkHeight)
            }
        }
    }

    private fun chunkPositions(total: Int, window: Int): List<Int> {
        if (window >= total) return listOf(0)
        val overlap = (window * CHUNK_OVERLAP_FRACTION).roundToInt()
        val step = max(1, window - overlap)
        val count = ceil((total - overlap).toFloat() / step).toInt()
        return (0 until count).map { i ->
            min(i * step, total - window)
        }.distinct()
    }

    /** YuNet exports sigmoid probabilities, not logits. Match OpenCV's clamp. */
    private fun probability(value: Float): Float = value.coerceIn(0f, 1f)

    private fun nonMaximumSuppression(input: List<Detection>, iouThreshold: Float = 0.3f): List<Detection> {
        val remaining = input.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<Detection>()
        while (remaining.isNotEmpty()) {
            val best = remaining.removeAt(0)
            selected += best
            remaining.removeAll { intersectionOverUnion(best.bounds, it.bounds) > iouThreshold }
        }
        return selected
    }

    private fun intersectionOverUnion(first: RectF, second: RectF): Float {
        val overlap = RectF(first)
        if (!overlap.intersect(second)) return 0f
        val intersection = overlap.width() * overlap.height()
        val union = first.width() * first.height() + second.width() * second.height() - intersection
        return if (union <= 0f) 0f else max(0f, min(1f, intersection / union))
    }

    override fun close() {
        if (closed) return
        closed = true
        session.close()
    }

    private fun checkOpen() {
        if (closed) throw RuntimeClosedException()
    }

    private data class Output(val name: String, private val values: FloatArray) {
        val size: Int get() = values.size
        operator fun get(index: Int): Float = values[index]
    }

    private data class PlatePrior(
        val cx: Float,
        val cy: Float,
        val width: Float,
        val height: Float
    )

    private data class InferenceRegion(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int
    )

    private companion object {
        /** Each chunk is 2× the model input size (640×480 px). */
        const val CHUNK_SCALE_FACTOR = 2
        /** Fractional overlap between adjacent chunks (20%). */
        const val CHUNK_OVERLAP_FRACTION = 0.20f
        /** Minimum width-to-height ratio for a valid license plate rectangle. */
        const val MIN_PLATE_ASPECT_RATIO = 1.3f
        /** Maximum width-to-height ratio for a valid license plate rectangle. */
        const val MAX_PLATE_ASPECT_RATIO = 5.5f
    }
}
