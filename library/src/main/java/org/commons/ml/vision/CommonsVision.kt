package org.commons.ml.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.commons.ml.common.AiDetector
import org.commons.ml.common.DetectionOptions
import org.commons.ml.common.DetectionResult
import org.commons.ml.common.DetectionType
import org.commons.ml.runtime.MlRuntimeException
import org.commons.ml.runtime.ModelRuntime
import org.commons.ml.runtime.OrtRuntime
import org.commons.ml.runtime.RuntimeClosedException

private const val TAG = "CommonsVision"

/**
 * Facade for on-device face and license-plate detection.
 *
 * Create one instance for the lifetime of the consumer and close it when it is
 * no longer needed. Detection is suspendable so callers can choose their own
 * coroutine dispatcher.
 */
class CommonsVision(context: Context) : AutoCloseable {
    private val detector: AiDetector = Detector(context.applicationContext)

    suspend fun detect(
        bitmap: Bitmap,
        options: DetectionOptions = DetectionOptions()
    ): DetectionResult = detector.detect(bitmap, options)

    override fun close() {
        detector.close()
    }

    private class Detector(
        context: Context
    ) : AiDetector {
        private val runtime: ModelRuntime = OrtRuntime(context)
        private val face: AiDetector?
        private val plate: AiDetector?
        private val plateInitializationError: MlRuntimeException?
        private val faceInitializationError: MlRuntimeException?
        private var closed = false

        init {
            val faceResult = openDetector(runtime, DetectorKind.FACE)
            face = faceResult.first
            faceInitializationError = faceResult.second

            val plateResult = openDetector(runtime, DetectorKind.LICENSE_PLATE)
            plate = plateResult.first
            plateInitializationError = plateResult.second
        }

        override suspend fun detect(bitmap: Bitmap, options: DetectionOptions): DetectionResult {
            checkOpen()
            val faces = if (face == null) {
                faceInitializationError?.let {
                    Log.e(TAG, "Face detector unavailable (${it.code}).", it)
                }
                emptyList()
            } else {
                try {
                    when (val faceResult = face.detect(bitmap, options)) {
                        is DetectionResult.Success -> faceResult.detections
                        is DetectionResult.Partial -> faceResult.detections
                        is DetectionResult.Unavailable -> return faceResult
                    }
                } catch (error: MlRuntimeException) {
                    Log.e(TAG, "Face detection failed (${error.code}).", error)
                    emptyList()
                }
            }

            val plates = if (plate == null) {
                plateInitializationError?.let {
                    Log.e(TAG, "License-plate detector unavailable (${it.code}).", it)
                }
                emptyList()
            } else {
                try {
                    when (val result = plate.detect(bitmap, options)) {
                        is DetectionResult.Success -> result.detections
                        is DetectionResult.Partial -> result.detections
                        is DetectionResult.Unavailable -> emptyList()
                    }
                } catch (error: MlRuntimeException) {
                    Log.e(TAG, "License-plate detection failed (${error.code}).", error)
                    emptyList()
                }
            }

            return if (plate == null) {
                DetectionResult.Partial(faces + plates, listOf(DetectionType.LICENSE_PLATE))
            } else {
                DetectionResult.Success(faces + plates)
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            var failure: MlRuntimeException? = null
            listOf(face, plate, runtime).forEach { resource ->
                if (resource == null) return@forEach
                try {
                    resource.close()
                } catch (error: MlRuntimeException) {
                    if (failure == null) failure = error
                }
            }
            failure?.let { throw it }
        }

        private fun checkOpen() {
            if (closed) throw RuntimeClosedException()
        }

        private fun openDetector(
            runtime: ModelRuntime,
            kind: DetectorKind
        ): Pair<AiDetector?, MlRuntimeException?> = try {
            OnnxYuNetDetector(runtime, kind) to null
        } catch (error: MlRuntimeException) {
            Log.e(TAG, "Unable to initialize ${kind.detectionType} detector (${error.code}).", error)
            null to error
        }

    }

}
