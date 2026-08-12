package org.commons.ml.runtime

import android.content.Context
import android.content.res.AssetManager
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import java.io.FileNotFoundException
import java.nio.FloatBuffer

/** ONNX Runtime implementation of the internal Android model-runtime boundary. */
internal class OrtRuntime(context: Context) : ModelRuntime {
    private val assets: AssetManager = context.applicationContext.assets
    private val environment = OrtEnvironment.getEnvironment()
    private val sessions = mutableSetOf<OrtModelSession>()
    private var closed = false

    @Synchronized
    override fun openSession(assetName: String): ModelSession {
        checkOpen()
        val modelBytes = try {
            assets.open(assetName).use { it.readBytes() }
        } catch (error: FileNotFoundException) {
            throw ModelAssetMissingException(assetName, error)
        } catch (error: Exception) {
            throw ModelLoadException(assetName, "asset could not be read", error)
        }

        val session = try {
            val options = OrtSession.SessionOptions()
            try {
                environment.createSession(modelBytes, options)
            } finally {
                options.close()
            }
        } catch (error: Exception) {
            throw ModelLoadException(assetName, "ONNX Runtime rejected the model", error)
        }
        val wrapped = try {
            OrtModelSession(assetName, environment, session)
        } catch (error: MlRuntimeException) {
            try {
                session.close()
            } catch (closeError: Exception) {
                error.addSuppressed(closeError)
            }
            throw error
        }
        sessions += wrapped
        return wrapped
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val activeSessions = sessions.toList()
        sessions.clear()
        var failure: MlRuntimeException? = null
        activeSessions.forEach { session ->
            try {
                session.close()
            } catch (error: MlRuntimeException) {
                if (failure == null) failure = error
            }
        }
        failure?.let { throw it }
    }

    @Synchronized
    private fun checkOpen() {
        if (closed) throw RuntimeClosedException()
    }
}

private class OrtModelSession(
    private val assetName: String,
    private val environment: OrtEnvironment,
    private val session: OrtSession
) : ModelSession {
    private var closed = false

    override val inputName: String = session.inputNames.firstOrNull()
        ?: throw ModelLoadException(assetName, "the model has no inputs")

    override val inputShape: LongArray = try {
        val inputInfo = session.inputInfo[inputName]?.info as? TensorInfo
            ?: throw ModelLoadException(assetName, "the first input is not a tensor")
        inputInfo.shape
    } catch (error: MlRuntimeException) {
        throw error
    } catch (error: Exception) {
        throw ModelLoadException(assetName, "the input metadata is invalid", error)
    }

    @Synchronized
    override fun run(input: ModelInput): List<ModelOutput> {
        if (closed) throw RuntimeClosedException()
        val expectedSize = input.shape.fold(1L) { total, dimension -> total * dimension }
        requireValidInput(input, expectedSize)
        return try {
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(input.values),
                input.shape
            ).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    session.outputNames.mapIndexedNotNull { index, name ->
                        (result[index] as? OnnxTensor)?.let { output ->
                            output.floatBuffer.let { buffer ->
                                ModelOutput(name, FloatArray(buffer.remaining()).also(buffer::get))
                            }
                        }
                    }
                }
            }
        } catch (error: MlRuntimeException) {
            throw error
        } catch (error: Exception) {
            throw ModelInferenceException(assetName, error)
        }
    }

    private fun requireValidInput(input: ModelInput, expectedSize: Long) {
        if (input.shape.any { it <= 0L }) {
            throw InvalidModelInputException("Model input dimensions must be positive.")
        }
        if (inputShape.all { it > 0L } && !input.shape.contentEquals(inputShape)) {
            throw InvalidModelInputException(
                "Model input shape ${input.shape.contentToString()} does not match " +
                    "the model shape ${inputShape.contentToString()}."
            )
        }
        if (input.values.size.toLong() != expectedSize) {
            throw InvalidModelInputException(
                "Model input contains ${input.values.size} values; expected $expectedSize."
            )
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            session.close()
        } catch (error: Exception) {
            throw ResourceReleaseException("ONNX Runtime model session", error)
        }
    }
}
