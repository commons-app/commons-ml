package org.commons.ml.runtime

/**
 * Internal runtime boundary used by the vision layer.
 *
 * Vision code depends on model sessions and tensor values, not on ONNX Runtime
 * classes. A different Android inference engine can implement this boundary
 * without changing the detection domain code.
 */
internal interface ModelRuntime : AutoCloseable {
    fun openSession(assetName: String): ModelSession
}

internal interface ModelSession : AutoCloseable {
    val inputName: String
    val inputShape: LongArray

    fun run(input: ModelInput): List<ModelOutput>
}

internal data class ModelInput(
    val values: FloatArray,
    val shape: LongArray
)

internal data class ModelOutput(
    val name: String,
    val values: FloatArray
)
