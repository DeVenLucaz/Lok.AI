package com.lokai.app.data.inference

/**
 * Application-wide singleton holder for [LlamaEngine].
 *
 * FIX (Bug 4): The crash in AgentViewModel was caused by both ChatViewModel and
 * AgentViewModel each instantiating their own LlamaEngine(). Since llama.cpp
 * maintains global C++ state, two simultaneous instances calling loadModel() or
 * runInference() corrupt each other's context and crash.
 *
 * Solution: all ViewModels must access the same LlamaEngine instance via this
 * holder. The engine is created once at app start (via LokaiApplication) and
 * lives for the process lifetime.
 *
 * Usage in ViewModels:
 *   private val engine get() = LlamaEngineHolder.engine
 *
 * Before loading a new model always check/clear isLoaded:
 *   if (LlamaEngineHolder.isLoaded) engine.unloadModel()
 *   LlamaEngineHolder.isLoaded = false
 */
object LlamaEngineHolder {

    /**
     * The single shared LlamaEngine instance.
     * lateinit — initialised in LokaiApplication.onCreate() before any ViewModel is created.
     */
    lateinit var engine: LlamaEngine
        private set

    /**
     * Whether a model is currently loaded into [engine].
     * Written by ChatViewModel and AgentViewModel when they load/unload models,
     * so both know whether a prior context needs to be freed before they load theirs.
     */
    @Volatile
    var isLoaded: Boolean = false

    /**
     * Call exactly once, from LokaiApplication.onCreate().
     */
    fun init(engine: LlamaEngine) {
        this.engine = engine
    }
}
