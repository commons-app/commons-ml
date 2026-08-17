package org.commons.ml.common

/**
 * Receives detection progress updates as image chunks are processed.
 *
 */
fun interface DetectionProgressListener {
    /**
     * Called after each image chunk finishes ONNX inference.
     *
     * @param progress normalized value from 0.0 to 1.0 representing
     *                 the progress of total inference work completed
     *                 across all active detection phases.
     */
    fun onProgress(progress: Float)
}
