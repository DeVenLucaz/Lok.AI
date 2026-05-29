package com.lokai.app

import android.app.Application
import com.lokai.app.data.inference.LlamaEngine
import com.lokai.app.data.inference.LlamaEngineHolder

/**
 * Application subclass that initialises process-wide singletons.
 *
 * FIX (Bug 4): LlamaEngine must be a singleton shared by all ViewModels.
 * Creating separate instances in ChatViewModel and AgentViewModel causes both
 * to call into the same native llama.cpp global state simultaneously → crash.
 *
 * We create one LlamaEngine here in onCreate() and store it in LlamaEngineHolder.
 * Every ViewModel accesses it via LlamaEngineHolder.engine.
 *
 * To activate: add android:name=".LokaiApplication" to the <application> tag
 * in AndroidManifest.xml (see PLACEMENT_NOTE below).
 */
class LokaiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialise the shared inference engine.
        // LlamaEngine's static init block loads lokai_jni.so; if it fails,
        // LlamaEngine.isLibraryLoaded will be false and ViewModels will show
        // the appropriate "native library missing" error state.
        LlamaEngineHolder.init(LlamaEngine())
    }
}

/*
 * PLACEMENT_NOTE
 * ─────────────────────────────────────────────────────────────────────────────
 * In AndroidManifest.xml, change:
 *
 *   <application
 *       android:icon="@mipmap/ic_launcher"
 *       ...
 *
 * To:
 *
 *   <application
 *       android:name=".LokaiApplication"
 *       android:icon="@mipmap/ic_launcher"
 *       ...
 *
 * That single line is the only manifest change required.
 * ─────────────────────────────────────────────────────────────────────────────
 */
