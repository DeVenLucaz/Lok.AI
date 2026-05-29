package com.lokai.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lokai.app.data.agent.*
import com.lokai.app.data.agent.strategies.*
import com.lokai.app.data.inference.InferenceMode
import com.lokai.app.data.inference.LlamaEngine
import com.lokai.app.data.session.SessionRepository
import com.lokai.app.data.settings.SettingsRepository
import com.lokai.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// ─── UI State ─────────────────────────────────────────────────────────────────

data class AgentListUiState(
    val agents:  List<AgentProfile> = emptyList(),
    val loading: Boolean            = true
)

data class AgentChatUiState(
    val agent:           AgentProfile?     = null,
    val session:         AgentSession?     = null,
    val isModelLoaded:   Boolean           = false,
    val isGenerating:    Boolean           = false,
    val streamingText:   String            = "",
    val streamingLog:    List<ThinkingLog> = emptyList(),
    val inferenceMode:   InferenceMode     = InferenceMode.NORMAL,
    val loadingModel:    Boolean           = false,
    val loadError:       String?           = null,
    val indexingProgress:String?           = null,  // non-null while indexing
    val indexingError:   String?           = null,
    val contextUsed:     Int               = 0,
    val contextMax:      Int               = 0,
    val ramMb:           Long              = 0L,
    val overflowWarning: String?           = null,
    val showModelPicker: Boolean           = false
)

data class AgentCreateUiState(
    val name:         String       = "",
    val category:     AgentCategory= AgentCategory.CODE,
    val modelId:      String       = "",
    val modelName:    String       = "",
    val filePath:     String?      = null,
    val fileName:     String?      = null,
    val systemPrompt: String       = "",
    val inferenceMode:InferenceMode= InferenceMode.NORMAL,
    // custom settings
    val customChunkSize:      Int    = 250,
    val customChunksRetrieved:Int    = 3,
    val customFallback:       Boolean= true,
    val customTemperature:    Float  = 0.7f,
    val customMaxTokens:      Int    = 512,
    val customContextSize:    Int    = 2048,
    val customStrategy:       String = "Summary+Retrieval",
    // flow
    val saving:       Boolean     = false,
    val error:        String?     = null,
    val done:         Boolean     = false
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val agentRepo   = AgentRepository(application)
    private val settingsRepo= SettingsRepository(application)

    // FIX (Bug 4): Use LlamaEngineHolder singleton instead of creating a new
    // LlamaEngine() instance here. When both ChatViewModel and AgentViewModel
    // each create their own LlamaEngine(), they both call into the same native
    // llama.cpp singleton simultaneously, causing a crash or corrupted state.
    // All inference ViewModels must share one engine instance.
    private val engine get() = LlamaEngineHolder.engine

    // ─── Agent list ───────────────────────────────────────────────────────────

    private val _listState = MutableStateFlow(AgentListUiState())
    val listState: StateFlow<AgentListUiState> = _listState.asStateFlow()

    // ─── Agent create ─────────────────────────────────────────────────────────

    private val _createState = MutableStateFlow(AgentCreateUiState())
    val createState: StateFlow<AgentCreateUiState> = _createState.asStateFlow()

    // ─── Agent chat ───────────────────────────────────────────────────────────

    private val _chatState = MutableStateFlow(AgentChatUiState())
    val chatState: StateFlow<AgentChatUiState> = _chatState.asStateFlow()

    private var inferenceJob:  Job? = null
    private var cachedChunks:  List<FileChunk> = emptyList()
    private var currentAgentId: String? = null

    init {
        viewModelScope.launch {
            agentRepo.observeAll().collect { agents ->
                _listState.update { it.copy(agents = agents, loading = false) }
            }
        }
    }

    // ─── Agent sessions (for history screen) ─────────────────────────────────

    val allAgentSessions: StateFlow<List<AgentSession>> =
        agentRepo.observeAllSessions()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteAgentSession(sessionId: String) {
        viewModelScope.launch { agentRepo.deleteSession(sessionId) }
    }

    // ─── Agent list actions ───────────────────────────────────────────────────

    fun deleteAgent(agentId: String) {
        viewModelScope.launch { agentRepo.delete(agentId) }
    }

    // ─── Agent create actions ─────────────────────────────────────────────────

    fun onNameChange(v: String)          { _createState.update { it.copy(name = v) } }
    fun onCategoryChange(v: AgentCategory) {
        _createState.update {
            it.copy(category = v, systemPrompt = v.defaultSystemPrompt())
        }
    }
    fun onModelChange(id: String, name: String) {
        _createState.update { it.copy(modelId = id, modelName = name) }
    }
    fun onFileSelected(path: String, name: String) {
        _createState.update { it.copy(filePath = path, fileName = name) }
    }
    fun onSystemPromptChange(v: String)  { _createState.update { it.copy(systemPrompt = v) } }
    fun onInferenceModeChange(v: InferenceMode){ _createState.update { it.copy(inferenceMode = v) } }
    fun onCustomChunkSizeChange(v: Int)  { _createState.update { it.copy(customChunkSize = v) } }
    fun onCustomChunksRetrievedChange(v: Int){ _createState.update { it.copy(customChunksRetrieved = v) } }
    fun onCustomFallbackChange(v: Boolean){ _createState.update { it.copy(customFallback = v) } }
    fun onCustomTemperatureChange(v: Float){ _createState.update { it.copy(customTemperature = v) } }
    fun onCustomMaxTokensChange(v: Int)  { _createState.update { it.copy(customMaxTokens = v) } }
    fun onCustomContextSizeChange(v: Int){ _createState.update { it.copy(customContextSize = v) } }
    fun onCustomStrategyChange(v: String){ _createState.update { it.copy(customStrategy = v) } }

    fun resetCreateState() { _createState.value = AgentCreateUiState() }

    fun saveAgent() {
        val s = _createState.value
        if (s.name.isBlank()) {
            _createState.update { it.copy(error = "Agent name cannot be blank.") }
            return
        }
        if (s.modelId.isBlank()) {
            _createState.update { it.copy(error = "Please select a model.") }
            return
        }
        _createState.update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            val agent = AgentProfile(
                name              = s.name.trim(),
                category          = s.category,
                modelId           = s.modelId,
                modelName         = s.modelName,
                filePath          = s.filePath,
                fileName          = s.fileName,
                systemPrompt      = s.systemPrompt.ifBlank { s.category.defaultSystemPrompt() },
                inferenceMode     = s.inferenceMode,
                customChunkSize       = s.customChunkSize,
                customChunksRetrieved = s.customChunksRetrieved,
                customFallback        = s.customFallback,
                customTemperature     = s.customTemperature,
                customMaxTokens       = s.customMaxTokens,
                customContextSize     = s.customContextSize,
                customStrategy        = s.customStrategy
            )

            // Save profile first
            agentRepo.save(agent)

            // Index file in background if one was attached
            if (agent.filePath != null) {
                val indexError = agentRepo.indexAgentFile(agent) { msg ->
                    _createState.update { it.copy(saving = true, error = "Indexing: $msg") }
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
                    agentId   = agent.id,
                    agentName = agent.name,
                    category  = agent.category,
                    modelId   = agent.modelId,
                    modelName = agent.modelName,
                    inferenceMode = agent.inferenceMode
                )

            // FIX (Bug 4): Explicitly unload the previous model before loading the
            // new one. Without this, if ChatViewModel already loaded a model into
            // the shared LlamaEngine, and AgentViewModel calls loadModel() on top of
            // it, the native llama.cpp context is in an undefined state → crash.
            if (_chatState.value.isModelLoaded || LlamaEngineHolder.isLoaded) {
                withContext(Dispatchers.IO) { engine.unloadModel() }
                _chatState.update { it.copy(isModelLoaded = false) }
            }

            _chatState.update {
                it.copy(agent = agent, session = session, loadingModel = true, loadError = null)
            }

            // Load chunks into memory
            cachedChunks = agentRepo.getChunks(agentId)

            // Load model
            val settings = settingsRepo.settings.first()
            val threads  = if (settings.threads > 0) settings.threads
                           else Runtime.getRuntime().availableProcessors().coerceAtMost(8)
            val ok = withContext(Dispatchers.IO) {
                engine.loadModel(
                    modelPath   = downloadedModelPath,
                    threads     = threads,
                    contextSize = if (agent.category == AgentCategory.CUSTOM)
                                      agent.customContextSize else settings.contextSize
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
        val state  = _chatState.value
        val agent  = state.agent       ?: return
        val session= state.session     ?: return
        if (!state.isModelLoaded || state.isGenerating || text.isBlank()) return

        val userMsg = ChatMessage(role = "user", content = text.trim())
        val updatedMessages = session.messages + userMsg
        val updatedSession  = session.copy(messages = updatedMessages, updatedAt = System.currentTimeMillis())

        _chatState.update {
            it.copy(
                session       = updatedSession,
                isGenerating  = true,
                streamingText = "",
                streamingLog  = emptyList(),
                overflowWarning = null
            )
        }

        inferenceJob = viewModelScope.launch(Dispatchers.IO) {
            val settings   = settingsRepo.settings.first()
            val thinkStart = System.currentTimeMillis()

            fun log(msg: String) {
                _chatState.update { s ->
                    s.copy(streamingLog = s.streamingLog + ThinkingLog(message = msg))
                }
            }

            log("Mode: ${state.inferenceMode.label}")

            // Build context using the appropriate strategy
            val maxCtx = engine.getContextMax().takeIf { it > 0 } ?: settings.contextSize
            val context = buildContext(agent, cachedChunks, session.messages, text, maxCtx, ::log)

            log("Context: ${context.tokensUsed} / ${context.tokensMax} tokens used")
            if (context.retrievedCount > 0)
                log("Retrieved ${context.retrievedCount} section(s)")
            log("Generating response…")

            val maxTok = when (state.inferenceMode) {
                InferenceMode.NORMAL  -> settings.maxTokens
                InferenceMode.PRECISE,
                InferenceMode.FOCUSED -> (settings.maxTokens * 1.2).toInt()
            }
            val temp = when (state.inferenceMode) {
                InferenceMode.NORMAL  -> settings.temperature
                InferenceMode.PRECISE,
                InferenceMode.FOCUSED -> 0.4f
            }

            val sb = StringBuilder()
            try {
                engine.runInference(context.prompt, maxTok, temp, state.inferenceMode)
                    .collect { token ->
                        sb.append(token)
                        _chatState.update { s -> s.copy(streamingText = sb.toString()) }
                    }
            } catch (e: Exception) {
                sb.append("\n[Error: ${e.message}]")
            }

            val responseText = sb.toString().trim()
            val thinkMs = System.currentTimeMillis() - thinkStart
            log("Done in %.1fs".format(thinkMs / 1000f))

            // Automatic fallback retrieval if uncertainty detected
            var finalText = responseText
            val shouldFallback = agent.category != AgentCategory.CODE &&
                                 agent.category != AgentCategory.REFERENCE &&
                                 (agent.category != AgentCategory.CUSTOM || agent.customFallback) &&
                                 TfIdfEngine.containsUncertainty(responseText)

            if (shouldFallback && cachedChunks.isNotEmpty()) {
                log("Uncertainty detected — searching deeper…")
                val initialRetrieved = if (context.retrievedCount > 0)
                    TfIdfEngine.retrieve(text, cachedChunks, topN = context.retrievedCount) else emptyList()
                val fallbackCtx = buildFallbackContext(
                    agent, cachedChunks, session.messages, text, maxCtx, initialRetrieved
                )
                log("Found ${fallbackCtx.retrievedCount} additional section(s). Re-generating…")

                val sb2 = StringBuilder()
                try {
                    engine.runInference(fallbackCtx.prompt, maxTok, temp, state.inferenceMode)
                        .collect { token ->
                            sb2.append(token)
                            _chatState.update { s -> s.copy(streamingText = sb2.toString()) }
                        }
                } catch (_: Exception) {}
                finalText = sb2.toString().trim().ifBlank { responseText }
                log("Fallback complete.")
            }

            val finalLog = _chatState.value.streamingLog
            val assistantMsg = ChatMessage(
                role        = "assistant",
                content     = finalText,
                thinkingLog = finalLog,
                thinkingMs  = thinkMs
            )
            val finalSession = updatedSession.copy(
                messages  = updatedMessages + assistantMsg,
                updatedAt = System.currentTimeMillis()
            )

            // FIX (Bug 9): Save after every exchange, not every 5. The original
            // counter-based approach (save at 1, then every 5) meant up to 4
            // message pairs could be lost if the app was killed mid-conversation.
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
            val partialMsg = ChatMessage(
                role        = "assistant",
                content     = current.streamingText.trim() + " [stopped]",
                thinkingLog = current.streamingLog
            )
            val updatedSession = current.session?.copy(
                messages  = (current.session.messages) + partialMsg,
                updatedAt = System.currentTimeMillis()
            )
            _chatState.update { it.copy(
                session = updatedSession, isGenerating = false, streamingText = ""
            ) }
            if (updatedSession != null) {
                viewModelScope.launch { agentRepo.saveSession(updatedSession) }
            }
        } else {
            _chatState.update { it.copy(isGenerating = false, streamingText = "") }
        }
    }

    fun toggleInferenceMode() {
        val agent = _chatState.value.agent ?: return
        val current = _chatState.value.inferenceMode
        val next = when (current) {
            InferenceMode.NORMAL  -> InferenceMode.FOCUSED
            InferenceMode.FOCUSED,
            InferenceMode.PRECISE -> InferenceMode.NORMAL
        }
        _chatState.update { it.copy(inferenceMode = next) }
    }

    override fun onCleared() {
        super.onCleared()
        // Note: do NOT unload the engine here — ChatViewModel may still be using it.
        // The engine is managed globally via LlamaEngineHolder.
    }

    // ─── Context assembly helpers ─────────────────────────────────────────────

    private fun buildContext(
        agent:    AgentProfile,
        chunks:   List<FileChunk>,
        history:  List<ChatMessage>,
        message:  String,
        maxCtx:   Int,
        log:      (String) -> Unit
    ): ContextBuilder.BuiltContext {
        if (chunks.isEmpty() && agent.filePath != null) {
            log("⚠️ No indexed chunks found — file may not have been indexed yet.")
        }
        return when (agent.category) {
            AgentCategory.CODE -> {
                val r = CodeStrategy.build(agent, chunks, history, message, maxCtx)
                r.overflowWarning?.let { log(it) }
                r.context
            }
            AgentCategory.STORY     -> StoryStrategy.build(agent, chunks, history, message, maxCtx)
            AgentCategory.RESEARCH  -> ResearchStrategy.build(agent, chunks, history, message, maxCtx)
            AgentCategory.REFERENCE -> ReferenceStrategy.build(agent, chunks, history, message, maxCtx)
            AgentCategory.CUSTOM    -> CustomStrategy.build(agent, chunks, history, message, maxCtx)
        }
    }

    private fun buildFallbackContext(
        agent:    AgentProfile,
        chunks:   List<FileChunk>,
        history:  List<ChatMessage>,
        message:  String,
        maxCtx:   Int,
        already:  List<FileChunk>
    ): ContextBuilder.BuiltContext = when (agent.category) {
        AgentCategory.STORY    -> StoryStrategy.buildFallback(agent, chunks, history, message, maxCtx, already)
        AgentCategory.RESEARCH -> ResearchStrategy.buildFallback(agent, chunks, history, message, maxCtx, already)
        else -> buildContext(agent, chunks, history, message, maxCtx) {}
    }
}
