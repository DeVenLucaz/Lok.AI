package com.lokai.app.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lokai.app.data.inference.InferenceMode
import com.lokai.app.data.inference.LlamaEngineHolder
import com.lokai.app.data.session.SessionRepository
import com.lokai.app.data.settings.LokaiSettings
import com.lokai.app.data.settings.SettingsRepository
import com.lokai.app.model.ChatMessage
import com.lokai.app.model.ChatSession
import com.lokai.app.model.DownloadedModel
import com.lokai.app.model.ThinkingLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import android.app.ActivityManager

// ─── UI State ─────────────────────────────────────────────────────────────────

data class ChatUiState(
    val session:               ChatSession?    = null,
    val isModelLoaded:         Boolean         = false,
    val isGenerating:          Boolean         = false,
    val streamingText:         String          = "",
    val streamingLog:          List<ThinkingLog> = emptyList(),
    val inferenceMode:         InferenceMode   = InferenceMode.NORMAL,
    val loadingModel:          Boolean         = false,
    val loadError:             String?         = null,
    val currentModel:          DownloadedModel? = null,
    val ramMb:                 Long            = 0L,
    val batteryPct:            Int             = 100,
    val isBatteryLow:          Boolean         = false,
    val showModeTooltip:       Boolean         = false,
    val tooltipMode:           InferenceMode?  = null,
    val showModelPicker:       Boolean         = false,
    val contextUsed:           Int             = 0,
    val contextMax:            Int             = 0,
    val nativeLibraryMissing:  Boolean         = false
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // FIX (Bug 4): Use shared singleton instead of `LlamaEngine()`.
    // Creating a second LlamaEngine instance while AgentViewModel has one loaded
    // causes both to manipulate the same native llama.cpp global state → crash.
    private val engine      get() = LlamaEngineHolder.engine
    private val sessionRepo  = SessionRepository(application)
    private val settingsRepo = SettingsRepository(application)
    private val activityMgr  = application.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var settings:      LokaiSettings = LokaiSettings()
    private var inferenceJob:  Job?          = null
    private var ramMonitorJob: Job?          = null
    private var sessionId:     String?       = null

    // Battery receiver
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct   = (level * 100 / scale.toFloat()).toInt()
            _uiState.update { it.copy(
                batteryPct   = pct,
                isBatteryLow = pct <= settings.batteryWarnPercent
            ) }
        }
    }

    init {
        if (!com.lokai.app.data.inference.LlamaEngine.isLibraryLoaded) {
            _uiState.update { it.copy(nativeLibraryMissing = true) }
        }
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                settings = s
                _uiState.update { it.copy(isBatteryLow = it.batteryPct <= s.batteryWarnPercent) }
            }
        }
        registerBatteryReceiver()
    }

    // ─── Model loading ────────────────────────────────────────────────────────

    fun loadModel(model: DownloadedModel) {
        if (_uiState.value.loadingModel) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(loadingModel = true, loadError = null) }

            // FIX (Bug 4): Unload the shared engine before loading a new model.
            // AgentViewModel may have loaded its own model into the same engine.
            if (_uiState.value.isModelLoaded || LlamaEngineHolder.isLoaded) {
                engine.unloadModel()
                LlamaEngineHolder.isLoaded = false
                _uiState.update { it.copy(isModelLoaded = false) }
            }

            val threads = if (settings.threads > 0) settings.threads
                          else Runtime.getRuntime().availableProcessors().coerceAtMost(8)

            val ok = engine.loadModel(
                modelPath   = model.localPath,
                threads     = threads,
                contextSize = settings.contextSize
            )

            if (ok) {
                LlamaEngineHolder.isLoaded = true
                val mode = when {
                    model.thinkingTrained -> InferenceMode.PRECISE
                    else                  -> settings.defaultMode.let {
                        if (it == InferenceMode.PRECISE) InferenceMode.NORMAL else it
                    }
                }
                val session = ChatSession(
                    modelId       = model.modelId,
                    modelName     = model.name,
                    inferenceMode = mode
                )
                sessionId = session.id
                if (settings.autoSaveSessions) sessionRepo.save(session)

                _uiState.update { it.copy(
                    isModelLoaded = true,
                    loadingModel  = false,
                    currentModel  = model,
                    session       = session,
                    inferenceMode = mode,
                    contextUsed   = engine.getContextUsed(),
                    contextMax    = engine.getContextMax()
                ) }
                startRamMonitor()
            } else {
                LlamaEngineHolder.isLoaded = false
                _uiState.update { it.copy(
                    loadingModel = false,
                    loadError    = "Failed to load model. It may be corrupted or require more RAM."
                ) }
            }
        }
    }

    fun resumeSession(session: ChatSession) {
        sessionId = session.id
        _uiState.update { it.copy(session = session, inferenceMode = session.inferenceMode) }
    }

    // ─── Mode switching ───────────────────────────────────────────────────────

    fun setMode(mode: InferenceMode) {
        val showTooltip = when (mode) {
            InferenceMode.PRECISE -> !settings.tooltipPreciseSeen
            InferenceMode.FOCUSED -> !settings.tooltipFocusedSeen
            InferenceMode.NORMAL  -> false
        }
        _uiState.update { it.copy(
            inferenceMode   = mode,
            showModeTooltip = showTooltip,
            tooltipMode     = if (showTooltip) mode else null
        ) }
        viewModelScope.launch {
            sessionId?.let { id -> sessionRepo.updateMode(id, mode) }
        }
        if (showTooltip) {
            viewModelScope.launch {
                when (mode) {
                    InferenceMode.PRECISE -> settingsRepo.markTooltipPreciseSeen()
                    InferenceMode.FOCUSED -> settingsRepo.markTooltipFocusedSeen()
                    else -> {}
                }
            }
        }
    }

    fun dismissTooltip() {
        _uiState.update { it.copy(showModeTooltip = false, tooltipMode = null) }
    }

    // ─── Send message ─────────────────────────────────────────────────────────

    fun sendMessage(userText: String) {
        if (!_uiState.value.isModelLoaded) return
        if (_uiState.value.isGenerating) return
        if (userText.isBlank()) return

        val currentState   = _uiState.value
        val currentSession = currentState.session ?: return
        val mode           = currentState.inferenceMode

        val userMsg  = ChatMessage(role = "user", content = userText.trim())
        val messages = currentSession.messages + userMsg

        _uiState.update { it.copy(
            session       = currentSession.copy(messages = messages),
            isGenerating  = true,
            streamingText = "",
            streamingLog  = emptyList()
        ) }

        inferenceJob = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            val log     = mutableListOf<ThinkingLog>()

            fun addLog(msg: String) {
                log.add(ThinkingLog(message = msg))
                _uiState.update { s -> s.copy(streamingLog = log.toList()) }
            }

            addLog("Mode: ${mode.label}")
            addLog("Context: ${engine.getContextUsed()} / ${engine.getContextMax()} tokens")

            val prompt = buildPrompt(messages, mode, settings)
            addLog("Generating response...")

            val responseBuffer = StringBuilder()

            try {
                engine.runInference(
                    prompt      = prompt,
                    maxTokens   = applyModeMaxTokens(settings.maxTokens, mode),
                    temperature = applyModeTemp(settings.temperature, mode),
                    mode        = mode
                ).collect { token ->
                    responseBuffer.append(token)
                    _uiState.update { it.copy(streamingText = responseBuffer.toString()) }
                }

                val elapsedMs = System.currentTimeMillis() - startMs
                addLog("Done in %.1fs".format(elapsedMs / 1000f))

                val assistantMsg = ChatMessage(
                    role        = "assistant",
                    content     = responseBuffer.toString().trim(),
                    thinkingLog = log.toList(),
                    thinkingMs  = elapsedMs
                )

                val finalMessages  = messages + assistantMsg
                val updatedSession = currentSession.copy(
                    messages  = finalMessages,
                    updatedAt = System.currentTimeMillis()
                )

                _uiState.update { it.copy(
                    session       = updatedSession,
                    isGenerating  = false,
                    streamingText = "",
                    streamingLog  = emptyList(),
                    contextUsed   = engine.getContextUsed(),
                    contextMax    = engine.getContextMax()
                ) }

                // FIX (Bug 9): Save after every exchange. The previous counter
                // (save at 1, then every 5) could lose up to 4 message pairs if
                // the app was killed between saves. Each completed exchange is
                // cheap to persist and the user expects history to be reliable.
                if (settings.autoSaveSessions) {
                    sessionRepo.save(updatedSession)
                }

                updateBenchmark(responseBuffer.toString(), elapsedMs)

            } catch (e: CancellationException) {
                val partial = responseBuffer.toString().trim()
                if (partial.isNotEmpty()) {
                    val assistantMsg = ChatMessage(
                        role        = "assistant",
                        content     = partial + " [stopped]",
                        thinkingLog = log.toList(),
                        thinkingMs  = System.currentTimeMillis() - startMs
                    )
                    val updatedSession = currentSession.copy(messages = messages + assistantMsg)
                    _uiState.update { it.copy(
                        session       = updatedSession,
                        isGenerating  = false,
                        streamingText = "",
                        streamingLog  = emptyList()
                    ) }
                    // FIX (Bug 9): also save stopped responses
                    if (settings.autoSaveSessions) {
                        sessionRepo.save(updatedSession)
                    }
                } else {
                    _uiState.update { it.copy(
                        isGenerating  = false,
                        streamingText = "",
                        streamingLog  = emptyList()
                    ) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isGenerating  = false,
                    streamingText = "",
                    streamingLog  = emptyList(),
                    loadError     = "Inference error: ${e.message}"
                ) }
            }
        }
    }

    fun stopGeneration() {
        inferenceJob?.cancel()
        engine.stopInference()
    }

    // ─── Model picker ─────────────────────────────────────────────────────────

    fun showModelPicker()    = _uiState.update { it.copy(showModelPicker = true) }
    fun dismissModelPicker() = _uiState.update { it.copy(showModelPicker = false) }
    fun clearLoadError()     = _uiState.update { it.copy(loadError = null) }

    // ─── Export ───────────────────────────────────────────────────────────────

    fun buildExportIntent(): Intent? {
        val session = _uiState.value.session ?: return null
        return SessionRepository(getApplication()).buildExportIntent(session)
    }

    // ─── RAM monitor ──────────────────────────────────────────────────────────

    private fun startRamMonitor() {
        ramMonitorJob?.cancel()
        ramMonitorJob = viewModelScope.launch {
            while (isActive) {
                val info = ActivityManager.MemoryInfo()
                activityMgr.getMemoryInfo(info)
                _uiState.update { it.copy(ramMb = info.availMem / (1024L * 1024L)) }
                delay(2_000)
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun buildPrompt(
        messages: List<ChatMessage>,
        mode:     InferenceMode,
        settings: LokaiSettings
    ): String {
        val sb = StringBuilder()

        val systemPrompt = when (mode) {
            InferenceMode.FOCUSED ->
                "Think step by step. Be precise and careful. If unsure, say so.\n\n" +
                settings.customSystemPrompt
            else -> settings.customSystemPrompt
        }
        if (systemPrompt.isNotBlank()) {
            sb.appendLine("<|system|>")
            sb.appendLine(systemPrompt.trim())
            sb.appendLine("<|end|>")
        }

        val maxCtx        = engine.getContextMax().takeIf { it > 0 } ?: settings.contextSize
        val trimThreshold = (maxCtx * 0.8).toInt()
        fun estimateTokens(text: String) = (text.length / 4).coerceAtLeast(1)

        val workingMessages = messages.toMutableList()
        val firstExchange   = workingMessages.take(2)
        val rest            = if (workingMessages.size > 2) workingMessages.drop(2).toMutableList()
                              else mutableListOf()

        var estimate = (firstExchange + rest).sumOf { estimateTokens(it.content) }
        while (estimate > trimThreshold && rest.size > 0) {
            rest.removeAt(0)
            if (rest.isNotEmpty()) rest.removeAt(0)
            estimate = (firstExchange + rest).sumOf { estimateTokens(it.content) }
        }

        for (msg in firstExchange + rest) {
            when (msg.role) {
                "user"      -> { sb.appendLine("<|user|>");      sb.appendLine(msg.content); sb.appendLine("<|end|>") }
                "assistant" -> { sb.appendLine("<|assistant|>"); sb.appendLine(msg.content); sb.appendLine("<|end|>") }
            }
        }
        sb.append("<|assistant|>")
        return sb.toString()
    }

    private fun applyModeTemp(base: Float, mode: InferenceMode): Float = when (mode) {
        InferenceMode.NORMAL            -> base
        InferenceMode.PRECISE,
        InferenceMode.FOCUSED           -> 0.4f
    }

    private fun applyModeMaxTokens(base: Int, mode: InferenceMode): Int = when (mode) {
        InferenceMode.NORMAL            -> base
        InferenceMode.PRECISE,
        InferenceMode.FOCUSED           -> (base * 1.2).toInt()
    }

    private fun updateBenchmark(response: String, elapsedMs: Long) {
        if (elapsedMs <= 0 || response.isBlank()) return
        val tokensPerSec = (response.length / 4f) / (elapsedMs / 1000f)
        val model = _uiState.value.currentModel ?: return
        viewModelScope.launch(Dispatchers.IO) {
            LokaiDatabase.getInstance(getApplication())
                .downloadedModelDao()
                .updateBenchmark(model.modelId, tokensPerSec)
        }
    }

    private fun registerBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getApplication<Application>().registerReceiver(
                batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            getApplication<Application>().registerReceiver(batteryReceiver, filter)
        }
    }

    override fun onCleared() {
        super.onCleared()
        ramMonitorJob?.cancel()
        inferenceJob?.cancel()
        try { getApplication<Application>().unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        viewModelScope.launch(Dispatchers.IO) {
            engine.unloadModel()
            LlamaEngineHolder.isLoaded = false
        }
    }
}

private typealias LokaiDatabase = com.lokai.app.data.session.LokaiDatabase
