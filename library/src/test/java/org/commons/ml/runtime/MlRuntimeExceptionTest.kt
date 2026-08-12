package org.commons.ml.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MlRuntimeExceptionTest {
    @Test
    fun missingAssetHasStableCodeAndAssetName() {
        val error = ModelAssetMissingException("models/missing.ort")

        assertEquals(MlRuntimeException.Code.MODEL_ASSET_MISSING, error.code)
        assertEquals("models/missing.ort", error.assetName)
    }

    @Test
    fun inferenceFailurePreservesCause() {
        val cause = IllegalStateException("native failure")
        val error = ModelInferenceException("models/face.ort", cause)

        assertIs<IllegalStateException>(error.cause)
        assertEquals(MlRuntimeException.Code.INFERENCE_FAILED, error.code)
    }
}
