package com.lokai.app.data.inference

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

private const val TAG = "LlamaEngine"

/**
 * Kotlin wrapper around the native JNI bridge.
 *
 * All JNI calls are declared here as external functions.
 * The native library is loaded once at class initialization.
 *
 * Usage:
 *   val engine = LlamaEngine()
 *   engine.loadModel(path, threads, contextSize)
 *   engine.runInference(prompt, maxTokens, temperature, mode).collect { token -> ... }
 *   engine.stopInference()
 *   engine.unloadModel()
 */
class LlamaEngine {

    // ─── Native function declarations ─────────────────────────────────────────

    private external fun loadModel(
        modelPath:   String,
        threads:     Int,
        contextSize: Int,
        gpuLayers:   Int        // always 0 on Android
    ): Boolean

    private external fun runInference(
        prompt:      String,
        maxTokens:   Int,
        temperature: Float,
        callback:    TokenCallback
    )

    internal external fun stopInference()

    external fun unloadModel()

    external fun getContextUsed(): Int

    external fun getContextMax(): Int

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Load a GGUF model from [modelPath].
     * Returns true on success, false if loading fails.
     * GPU layers are always 0 on Android (CPU-only inference).
     */
    fun loadModel(
        modelPath:   String,
        threads:     Int = Runtime.getRuntime().availableProcessors().coerceAtMost(8),
        contextSize: Int = 2048
    ): Boolean {
        Log.i(TAG, "loadModel: path=$modelPath threads=$threads ctx=$contextSize")
        return loadModel(
            modelPath   = modelPath,
            threads     = threads,
            contextSize = contextSize,
            gpuLayers   = 0     // CPU-only on Android
        )
    }

    /**
     * Run inference on [prompt] and emit tokens as a [Flow<String>].
     *
     * Temperature and max tokens are adjusted automatically based on [mode]:
     * - NORMAL:  uses provided values as-is
     * - PRECISE: temperature 0.4, maxTokens * 1.2
     * - FOCUSED: temperature 0.4, maxTokens * 1.2, system prefix injected
     *
     * The flow completes when generation is finished or [stopInference] is called.
     * On error, the flow throws an [InferenceException].
     */
    fun runInference(
        prompt:      String,
        maxTokens:   Int   = 512,
        temperature: Float = 0.7f,
        mode:        InferenceMode = InferenceMode.NORMAL
    ): Flow<String> = callbackFlow {

        val (effectiveTemp, effectiveMax, effectivePrompt) = applyMode(
            prompt, maxTokens, temperature, mode
        )

        val callback = object : TokenCallback {
            override fun onToken(token: String) {
                trySend(token)
            }
            override fun onComplete() {
                Log.i(TAG, "onComplete received")
                close()
            }
            override fun onError(message: String) {
                Log.e(TAG, "onError: $message")
                close(InferenceException(message))
            }
        }

        // Run inference on a background thread (JNI call blocks until done)
        val thread = Thread {
            runInference(
                prompt      = effectivePrompt,
                maxTokens   = effectiveMax,
                temperature = effectiveTemp,
                callback    = callback
            )
        }
        thread.start()

        awaitClose {
            stopInference()
            thread.interrupt()
        }
    }

    // ─── Mode application ─────────────────────────────────────────────────────

    private data class InferenceParams(
        val temperature: Float,
        val maxTokens:   Int,
        val prompt:      String
    )

    private fun applyMode(
        prompt:      String,
        maxTokens:   Int,
        temperature: Float,
        mode:        InferenceMode
    ): InferenceParams = when (mode) {

        InferenceMode.NORMAL -> InferenceParams(
            temperature = temperature,
            maxTokens   = maxTokens,
            prompt      = prompt
        )

        InferenceMode.PRECISE -> InferenceParams(
            temperature = 0.4f,
            maxTokens   = (maxTokens * 1.2).toInt(),
            prompt      = prompt   // thinking tokens activate naturally from model training
        )

        InferenceMode.FOCUSED -> {
            val prefix = "Think step by step. Be precise and careful. If unsure, say so.\n\n"
            InferenceParams(
                temperature = 0.4f,
                maxTokens   = (maxTokens * 1.2).toInt(),
                prompt      = prefix + prompt
            )
        }
    }

    var isLibraryLoaded = false
        private set

    companion object {
        init {
            try {
                System.loadLibrary("lokai_jni")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("LlamaEngine", "Failed to load native library: ${e.message}")
            }
        }
    }
}

// ─── Callback interface (implemented in JNI, called from C++) ─────────────────

interface TokenCallback {
    fun onToken(token: String)
    fun onComplete()
    fun onError(message: String)
}

// ─── Custom exception ─────────────────────────────────────────────────────────

class InferenceException(message: String) : Exception(message)
