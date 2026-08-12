package org.commons.ml.runtime

/** Typed failures raised by the Android inference runtime. */
sealed class MlRuntimeException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    abstract val code: Code

    enum class Code {
        CLOSED,
        MODEL_ASSET_MISSING,
        MODEL_LOAD_FAILED,
        INVALID_INPUT,
        INFERENCE_FAILED,
        RESOURCE_RELEASE_FAILED
    }
}

class RuntimeClosedException : MlRuntimeException("The Commons ML runtime is closed.") {
    override val code = Code.CLOSED
}

class ModelAssetMissingException(
    val assetName: String,
    cause: Throwable? = null
) : MlRuntimeException("Model asset '$assetName' was not found.", cause) {
    override val code = Code.MODEL_ASSET_MISSING
}

class ModelLoadException(
    val assetName: String,
    message: String,
    cause: Throwable? = null
) : MlRuntimeException("Unable to load model '$assetName': $message", cause) {
    override val code = Code.MODEL_LOAD_FAILED
}

class InvalidModelInputException(
    message: String
) : MlRuntimeException(message) {
    override val code = Code.INVALID_INPUT
}

class ModelInferenceException(
    val assetName: String,
    cause: Throwable
) : MlRuntimeException("Inference failed for model '$assetName'.", cause) {
    override val code = Code.INFERENCE_FAILED
}

class ResourceReleaseException(
    resource: String,
    cause: Throwable
) : MlRuntimeException("Unable to release $resource.", cause) {
    override val code = Code.RESOURCE_RELEASE_FAILED
}
