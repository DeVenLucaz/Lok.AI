package com.lokai.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lokai.app.data.agent.*
import com.lokai.app.data.agent.strategies.*
import com.lokai.app.data.inference.InferenceMode
import com.lokai.app.data.inference.LlamaEngineHolder
import com.lokai.app.data.session.SessionRepository
import com.lokai.app.data.settings.SettingsRepository
import com.lokai.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// ─── UI States ────────────────────────────────────────────────────────────────

data class AgentListUiState(
    val agents:  List<AgentProfile> = emptyList(),
    val loading: Boolean            = true
)

data class AgentChatUiState(
    val agent:            AgentProfile?     = null,
    val session:          AgentSession?     = null,
    val isModelLoaded:    Boolean           = false,
    val isGenerating:     Boolean           = false,
    val streamingText:    String            = "",
    val streamingLog:     List<ThinkingLog> = emptyList(),
    val inferenceMode:    InferenceMode     = InferenceMode.NORMAL,
    val loadingModel:     Boolean           = false,
    val loadError:        String?           = null,
    val indexingProgress: String?           = null,
    val indexingError:    String?           = null,
    val contextUsed:      Int               = 0,
    val contextMax:       Int               = 0,
    val ramMb:            Long              = 0L,
    val overflowWarning:  String?           = null,
    val showModelPicker:  Boolean           = false
)

data class AgentCreateUiState(
    val name:                  String        = "",
    val category:              AgentCategory = AgentCategory.CODE,
    val modelId:               String        = "",
    val modelName:             String        = "",
    val filePath:              String?       = null,
    val fileName:              String?       = null,
    val systemPrompt:          String        = "",
    val inferenceMode:         InferenceMode = InferenceMode.NORMAL,
    val customChunkSize:       Int           = 250,
    val customChunksRetrieved: Int           = 3,
    val customFallback:        Boolean       = true,
    val customTemperature:     Float         = 0.7f,
    val customMaxTokens:       Int           = 512,
    val customContextSize:     Int           = 2048,
    val customStrategy:        String        = "Summary+Retrieval",
    val saving:                Boolean       = false,
    val error:                 String?       = null,
    val done:                  Boolean       = false
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val agentRepo    = AgentRepository(application)
    private val settingsRepo = SettingsRepository(application)

    // FIX (Bug 4): Access shared singleton — do NOT create LlamaEngine() here.
    // A second instance would collide with ChatViewModel's instance in the native layer.
    private val engine get() = LlamaEngineHolder.engine

    private val _listState   = MutableStateFlow(AgentListUiState())
    val listState: StateFlow<AgentListUiState> = _listState.asStateFlow()

    private val _createState = MutableStateFlow(AgentCreateUiState())
    val createState: StateFlow<AgentCreateUiState> = _createState.asStateFlow()

    private val _chatState   = MutableStateFlow(AgentChatUiState())
    val chatState: StateFlow<AgentChatUiState> = _chatState.asStateFlow()

    private var inferenceJob:   Job?            = null
    private var cachedChunks:   List<FileChunk> = emptyList()
    private var currentAgentId: String?         = null

    init {
        viewModelScope.launch {
            agentRepo.observeAll().collect { agents ->
                _listState.update { it.copy(agents = agents, loading = false) }
            }
        }
    }

    // ─── Agent sessions ───────────────────────────────────────────────────────

    val allAgentSessions: StateFlow<List<AgentSession>> =
        agentRepo.observeAllSessions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteAgentSession(sessionId: String) {
        viewModelScope.launch { agentRepo.deleteSession(sessionId) }
    }

    // ─── Agent list ───────────────────────────────────────────────────────────

    fun deleteAgent(agentId: String) {
        viewModelScope.launch { agentRepo.delete(agentId) }
    }

    // ─── Agent create ─────────────────────────────────────────────────────────

    fun onNameChange(v: String)                { _createState.update { it.copy(name = v) } }
    fun onCategoryChange(v: AgentCategory)     {
        _createState.update { it.copy(category = v, systemPrompt = v.defaultSystemPrompt()) }
    }
    fun onModelChange(id: String, name: String){ _createState.update { it.copy(modelId = id, modelName = name) } }
    fun onFileSelected(path: String, name: String){ _createState.update { it.copy(filePath = path, fileName = name) } }
    fun onSystemPromptChange(v: String)        { _createState.update { it.copy(systemPrompt = v) } }
    fun onInferenceModeChange(v: InferenceMode){ _createState.update { it.copy(inferenceMode = v) } }
    fun onCustomChunkSizeChange(v: Int)        { _createState.update { it.copy(customChunkSize = v) } }
    fun onCustomChunksRetrievedChange(v: Int)  { _createState.update { it.copy(customChunksRetrieved = v) } }
    fun onCustomFallbackChange(v: Boolean)     { _createState.update { it.copy(customFallback = v) } }
    fun onCustomTemperatureChange(v: Float)    { _createState.update { it.copy(customTemperature = v) } }
    fun onCustomMaxTokensChange(v: Int)        { _createState.update { it.copy(customMaxTokens = v) } }
    fun onCustomContextSizeChange(v: Int)      { _createState.update { it.copy(customContextSize = v) } }
    fun onCustomStrategyChange(v: String)      { _createState.update { it.copy(customStrategy = v) } }
    fun resetCreateState()                     { _createState.value = AgentCreateUiState() }

    fun saveAgent() {
        val s = _createState.value
        if (s.name.isBlank()) { _createState.update { it.copy(error = "Agent name cannot be blank.") }; return }
        if (s.modelId.isBlank()) { _createState.update { it.copy(error = "Please select a model.") }; return }

        _createState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val agent = AgentProfile(
                name                  = s.name.trim(),
                category              = s.category,
                modelId               = s.modelId,
                modelName             = s.modelName,
                filePath              = s.filePath,
                fileName              = s.fileName,
                systemPrompt          = s.systemPrompt.ifBlank { s.category.defaultSystemPrompt() },
                inferenceMode         = s.inferenceMode,
                customChunkSize       = s.customChunkSize,
                customChunksRetrieved = s.customChunksRetrieved,
                customFallback        = s.customFallback,
                customTemperature     = s.customTemperature,
                customMaxTokens       = s.customMaxTokens,
                customContextSize     = s.customContextSize,
                customStrategy        = s.customStrategy
            )
            agentRepo.save(agent)

            if (agent.filePath != null) {
                val indexError = agentRepo.indexAgentFile(agent) { msg ->
                    _createState.update { it.copy(error = "Indexing: $msg") }
                }
                if (indexError != null) {
                    _createState.update { it.copy(saving = false, error = indexError) }
                    return@launch
                }
            }
            _createState.update { it.copy(saving = false, done = true) }
        }
    }

    // ─── Agent chat ───────────────────────────────────────────────────────────

    fun openAgent(agentId: String, downloadedModelPath: String) {
        if (currentAgentId == agentId && _chatState.value.isModelLoaded) return
        currentAgentId = agentId

        viewModelScope.launch {
            val agent = agentRepo.getById(agentId) ?: return@launch
            val session = agentRepo.getLatestSession(agentId)
                ?: AgentSession(
                    agentId       = agent.id,
                    agentName     = agent.name,
                    category      = agent.category,
                    modelId       = agent.modelId,
                    modelName     = agent.modelName,
                    inferenceMode = agent.inferenceMode
                )

            // FIX (Bug 4): Unload whatever model is currently in the shared engine
            // before loading ours. Both ChatViewModel and AgentViewModel share the
            // same LlamaEngine instance — loading without unloading first corrupts
            // the native context and causes a crash.
            if (_chatState.value.isModelLoaded || LlamaEngineHolder.isLoaded) {
                withContext(Dispatchers.IO) { engine.unloadModel() }
                LlamaEngineHolder.isLoaded = false
                _chatState.update { it.copy(isModelLoaded = false) }
            }

            _chatState.update {
                it.copy(agent = agent, session = session, loadingModel = true, loadError = null)
            }

            cachedChunks = agentRepo.getChunks(agentId)

            val settings   = settingsRepo.settings.first()
            val threads    = if (settings.threads > 0) settings.threads
                             else Runtime.getRuntime().availableProcessors().coerceAtMost(8)
            val contextSz  = if (agent.category == AgentCategory.CUSTOM)
                                 agent.customContextSize else settings.contextSize

            val ok = withContext(Dispatchers.IO) {
                engine.loadModel(
                    modelPath   = downloadedModelPath,
                    threads     = threads,
                    contextSize = contextSz
                )
            }

            if (ok) {
                LlamaEngineHolder.isLoaded = true
                _chatState.update {
                    it.copy(
                        isModelLoaded = true,
                        loadingModel  = false,
                        inferenceMode = agent.inferenceMode,
                        contextMax    = engine.getContextMax()
                    )
                }
                agentRepo.touchLastUsed(agentId)
            } else {
                LlamaEngineHolder.isLoaded = false
                _chatState.update { it.copy(loadingModel = false, loadError = "Failed to load model.") }
            }
        }
    }

    fun sendMessage(text: String) {
        val state   = _chatState.value
        val agent   = state.agent   ?: return
        val session = state.session ?: return
        if (!state.isModelLoaded || state.isGenerating || text.isBlank()) return

        val userMsg        = ChatMessage(role = "user", content = text.trim())
        val updatedMsgs    = session.messages + userMsg
        val updatedSession = session.copy(messages = updatedMsgs, updatedAt = System.currentTimeMillis())

        _chatState.update {
            it.copy(
                session         = updatedSession,
                isGenerating    = true,
                streamingText   = "",
                streamingLog    = emptyList(),
                overflowWarning = null
            )
        }

        inferenceJob = viewModelScope.launch(Dispatchers.IO) {
            val thinkStart = System.currentTimeMillis()

            fun log(msg: String) {
                _chatState.update { s ->
                    s.copy(streamingLog = s.streamingLog + ThinkingLog(message = msg))
                }
            }

            log("Mode: ${state.inferenceMode.label}")

            val maxCtx  = engine.getContextMax().takeIf { it > 0 } ?: 2048
            val maxTok  = when (state.inferenceMode) {
                InferenceMode.NORMAL            -> 512
                InferenceMode.PRECISE,
                InferenceMode.FOCUSED           -> 640
            }
            val temp    = when (state.inferenceMode) {
                InferenceMode.NORMAL            -> 0.7f
                InferenceMode.PRECISE,
                InferenceMode.FOCUSED           -> 0.4f
            }

            // Build prompt via appropriate strategy
            // FIX: CodeStrategy.build() returns CodeStrategy.Result (wraps context + warning),
            // while all other strategies return ContextBuilder.BuiltContext directly.
            // We unwrap both to a common ContextBuilder.BuiltContext here.
            var overflowWarning: String? = null
            val context: ContextBuilder.BuiltContext = when (agent.category) {
                AgentCategory.CODE -> {
                    val result = CodeStrategy.build(agent, cachedChunks, updatedMsgs, text, maxCtx)
                    overflowWarning = result.overflowWarning
                    result.context
                }
                AgentCategory.STORY     -> StoryStrategy.build(agent, cachedChunks, updatedMsgs, text, maxCtx)
                AgentCategory.RESEARCH  -> ResearchStrategy.build(agent, cachedChunks, updatedMsgs, text, maxCtx)
                AgentCategory.REFERENCE -> ReferenceStrategy.build(agent, cachedChunks, updatedMsgs, text, maxCtx)
                AgentCategory.CUSTOM    -> CustomStrategy.build(agent, cachedChunks, updatedMsgs, text, maxCtx)
            }

            if (overflowWarning != null) {
                _chatState.update { it.copy(overflowWarning = overflowWarning) }
            }

            log("Context: ${context.tokensUsed} / ${context.tokensMax} tokens used")
            if (context.retrievedCount > 0) log("Retrieved ${context.retrievedCount} section(s)")
            log("Generating response…")

            val sb = StringBuilder()
            try {
                engine.runInference(context.prompt, maxTok, temp, state.inferenceMode)
                    .collect { token: String ->
                        sb.append(token)
                        _chatState.update { s -> s.copy(streamingText = sb.toString()) }
                    }
            } catch (e: Exception) {
                sb.append("\n[Error: ${e.message}]")
            }

            val responseText = sb.toString().trim()
            val thinkMs      = System.currentTimeMillis() - thinkStart
            log("Done in %.1fs".format(thinkMs / 1000f))

            // Fallback retrieval when model signals uncertainty
            var finalText = responseText
            val canFallback = agent.category != AgentCategory.CODE &&
                              agent.category != AgentCategory.REFERENCE &&
                              (agent.category != AgentCategory.CUSTOM || agent.customFallback) &&
                              TfIdfEngine.containsUncertainty(responseText)

            if (canFallback && cachedChunks.isNotEmpty()) {
                log("Uncertainty detected — searching deeper…")
                val already = TfIdfEngine.retrieve(text, cachedChunks, topN = context.retrievedCount)
                val fallbackCtx: ContextBuilder.BuiltContext = when (agent.category) {
                    AgentCategory.STORY    ->
                        StoryStrategy.buildFallback(agent, cachedChunks, updatedMsgs, text, maxCtx, already)
                    AgentCategory.RESEARCH ->
                        ResearchStrategy.buildFallback(agent, cachedChunks, updatedMsgs, text, maxCtx, already)
                    else                   -> context   // shouldn't reach here given the canFallback guard
                }
                log("Found ${fallbackCtx.retrievedCount} additional section(s). Re-generating…")

                val sb2 = StringBuilder()
                try {
                    engine.runInference(fallbackCtx.prompt, maxTok, temp, state.inferenceMode)
                        .collect { token: String ->
                            sb2.append(token)
                            _chatState.update { s -> s.copy(streamingText = sb2.toString()) }
                        }
                } catch (_: Exception) {}
                finalText = sb2.toString().trim().ifBlank { responseText }
                log("Fallback complete.")
            }

            val finalLog     = _chatState.value.streamingLog
            val assistantMsg = ChatMessage(
                role        = "assistant",
                content     = finalText,
                thinkingLog = finalLog,
                thinkingMs  = thinkMs
            )
            val finalSession = updatedSession.copy(
                messages  = updatedMsgs + assistantMsg,
                updatedAt = System.currentTimeMillis()
            )

            // FIX (Bug 9): Save after every exchange — no more counter-based saving
            agentRepo.saveSession(finalSession)

            _chatState.update { s ->
                s.copy(
                    session       = finalSession,
                    isGenerating  = false,
                    streamingText = "",
                    contextUsed   = engine.getContextUsed()
                )
            }
        }
    }

    fun stopGeneration() {
        inferenceJob?.cancel()
        engine.stopInference()
        val current = _chatState.value
        if (current.isGenerating && current.streamingText.isNotBlank()) {
            val stopped = ChatMessage(
                role        = "assistant",
                content     = current.streamingText.trim() + " [stopped]",
                thinkingLog = current.streamingLog
            )
            val updated = current.session?.copy(
                messages  = current.session.messages + stopped,
                updatedAt = System.currentTimeMillis()
            )
            _chatState.update { it.copy(session = updated, isGenerating = false, streamingText = "") }
            if (updated != null) viewModelScope.launch { agentRepo.saveSession(updated) }
        } else {
            _chatState.update { it.copy(isGenerating = false, streamingText = "") }
        }
    }

    fun toggleInferenceMode() {
        val next = when (_chatState.value.inferenceMode) {
            InferenceMode.NORMAL            -> InferenceMode.FOCUSED
            InferenceMode.FOCUSED,
            InferenceMode.PRECISE           -> InferenceMode.NORMAL
        }
        _chatState.update { it.copy(inferenceMode = next) }
    }

    override fun onCleared() {
        super.onCleared()
        // Do NOT unload engine here — ChatViewModel may still be using it.
    }
}
