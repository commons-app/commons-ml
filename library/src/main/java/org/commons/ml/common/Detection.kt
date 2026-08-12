package org.commons.ml.common

import android.graphics.Bitmap
import android.graphics.RectF

/** Low-level detector contract used by the library facade. */
interface AiDetector : AutoCloseable {
    suspend fun detect(bitmap: Bitmap, options: DetectionOptions = DetectionOptions()): DetectionResult
}

/** Result of running all detector capabilities available on the device. */
sealed interface DetectionResult {
    data class Success(val detections: List<Detection>) : DetectionResult
    data class Partial(val detections: List<Detection>, val skipped: List<DetectionType>) : DetectionResult
    data class Unavailable(val reason: String) : DetectionResult
}

/** A model prediction expressed in pixels of the displayed source bitmap. */
data class Detection(
    val type: DetectionType,
    val confidence: Float,
    val bounds: RectF
)

/** Kind of object represented by a detection. */
enum class DetectionType { FACE, LICENSE_PLATE }

/** Options controlling confidence filtering and result count. */
data class DetectionOptions(
    val confidenceThreshold: Float = 0.5f,
    val maximumResults: Int = 100
) {
    init {
        require(confidenceThreshold in 0f..1f) { "confidenceThreshold must be between 0 and 1" }
        require(maximumResults > 0) { "maximumResults must be positive" }
    }
}
