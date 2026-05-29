package com.lokai.app.ui.screens

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lokai.app.data.inference.InferenceMode
import com.lokai.app.model.ChatMessage
import com.lokai.app.model.DownloadedModel
import com.lokai.app.ui.components.RamIndicator
import com.lokai.app.ui.components.ThinkingPanel
import com.lokai.app.ui.components.ThinkingPanelLive
import com.lokai.app.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

// ─── Colors ───────────────────────────────────────────────────────────────────

private val BgPage        = Color(0xFF0D0D0D)
private val BgTopBar      = Color(0xFF111111)
private val BgInputBar    = Color(0xFF111111)
private val BgUserBubble  = Color(0xFF1F1A00)
private val BgBotBubble   = Color(0xFF181818)
private val BgField       = Color(0xFF1A1A1A)
private val AccentAmber   = Color(0xFFF5A623)
private val TextPrimary   = Color(0xFFE0E0E0)
private val TextSubtle    = Color(0xFF666666)
private val BorderSubtle  = Color(0xFF2A2A2A)

/**
 * Main Chat screen.
 *
 * @param initialModel  If non-null, this model is loaded immediately on first composition.
 *                      Used when navigating here from MyModelsScreen → Chat button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatVm:       ChatViewModel   = viewModel(),
    initialModel: DownloadedModel? = null
) {
    val state   by chatVm.uiState.collectAsStateWithLifecycle()
    val context  = LocalContext.current
    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()

    // Load model passed in from MyModelsScreen
    LaunchedEffect(initialModel) {
        initialModel?.let { chatVm.loadModel(it) }
    }

    // Auto-scroll to bottom when messages change or streaming
    val msgCount = state.session?.messages?.size ?: 0
    val streaming = state.streamingText
    LaunchedEffect(msgCount, streaming) {
        val total = msgCount + (if (streaming.isNotEmpty()) 1 else 0)
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    // Model picker sheet state
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        containerColor = BgPage,
        topBar = {
            ChatTopBar(
                modelName    = state.currentModel?.name,
                ramMb        = state.ramMb,
                batteryPct   = state.batteryPct,
                isBatteryLow = state.isBatteryLow,
                isGenerating = state.isGenerating,
                onPickModel  = { chatVm.showModelPicker() },
                onExport     = {
                    val intent = chatVm.buildExportIntent() ?: return@ChatTopBar
                    context.startActivity(Intent.createChooser(intent, "Share conversation"))
                },
                hasMessages  = (state.session?.messages?.size ?: 0) > 0
            )
        },
        bottomBar = {
            ChatInputBar(
                isModelLoaded  = state.isModelLoaded,
                isGenerating   = state.isGenerating,
                inferenceMode  = state.inferenceMode,
                isThinkingModel= state.currentModel?.thinkingTrained == true,
                contextUsed    = state.contextUsed,
                contextMax     = state.contextMax,
                onSend         = { text -> chatVm.sendMessage(text) },
                onStop         = { chatVm.stopGeneration() },
                onModeToggle   = { mode -> chatVm.setMode(mode) }
            )
        }
    ) { innerPadding ->

        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {

            when {
                state.nativeLibraryMissing -> NativeLibraryMissingError()

                state.loadingModel -> LoadingModelOverlay()

                !state.isModelLoaded && state.currentModel == null -> NoChatModelEmpty(
                    onPickModel = { chatVm.showModelPicker() }
                )

                else -> {
                    val messages = state.session?.messages ?: emptyList()

                    LazyColumn(
                        state           = listState,
                        contentPadding  = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier        = Modifier.fillMaxSize()
                    ) {
                        // Welcome line when no messages yet
                        if (messages.isEmpty() && !state.isGenerating) {
                            item {
                                WelcomePrompt(modelName = state.currentModel?.name ?: "")
                            }
                        }

                        items(messages, key = { it.id }) { msg ->
                            if (msg.isUser) {
                                UserBubble(message = msg)
                            } else {
                                AssistantBubble(message = msg, isGenerating = false)
                            }
                        }

                        // In-flight streaming bubble
                        if (state.isGenerating || state.streamingText.isNotEmpty()) {
                            item(key = "streaming") {
                                AssistantStreamingBubble(
                                    text         = state.streamingText,
                                    log          = state.streamingLog,
                                    isGenerating = state.isGenerating
                                )
                            }
                        }
                    }
                }
            }

            // Load error snackbar
            state.loadError?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { chatVm.clearLoadError() }) {
                            Text("Dismiss", color = AccentAmber)
                        }
                    },
                    containerColor = Color(0xFF2D0000),
                    contentColor   = Color(0xFFFF8A80)
                ) {
                    Text(error, fontSize = 13.sp)
                }
            }

            // Mode tooltip overlay
            if (state.showModeTooltip && state.tooltipMode != null) {
                ModeTooltipOverlay(
                    mode      = state.tooltipMode!!,
                    onDismiss = { chatVm.dismissTooltip() }
                )
            }
        }
    }

    // Model picker bottom sheet
    if (state.showModelPicker) {
        ModelPickerSheet(
            sheetState    = sheetState,
            onModelSelected = { model ->
                scope.launch { sheetState.hide() }
                chatVm.loadModel(model)
                chatVm.dismissModelPicker()
            },
            onDismiss = { chatVm.dismissModelPicker() }
        )
    }
}

// ─── Top bar ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    modelName:    String?,
    ramMb:        Long,
    batteryPct:   Int,
    isBatteryLow: Boolean,
    isGenerating: Boolean,
    hasMessages:  Boolean,
    onPickModel:  () -> Unit,
    onExport:     () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text       = modelName ?: "Lok.AI Chat",
                    color      = TextPrimary,
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                if (modelName != null) {
                    Text("Local inference · offline", color = TextSubtle, fontSize = 10.sp)
                }
            }
        },
        actions = {
            RamIndicator(
                availableRamMb = ramMb,
                isGenerating   = isGenerating,
                batteryPct     = batteryPct,
                isBatteryLow   = isBatteryLow
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onPickModel) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = "Switch model",
                    tint = TextSubtle, modifier = Modifier.size(20.dp))
            }
            if (hasMessages) {
                IconButton(onClick = onExport) {
                    Icon(Icons.Filled.Share, contentDescription = "Export",
                        tint = TextSubtle, modifier = Modifier.size(18.dp))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = BgTopBar)
    )
}

// ─── Input bar ────────────────────────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    isModelLoaded:   Boolean,
    isGenerating:    Boolean,
    inferenceMode:   InferenceMode,
    isThinkingModel: Boolean,
    contextUsed:     Int,
    contextMax:      Int,
    onSend:          (String) -> Unit,
    onStop:          () -> Unit,
    onModeToggle:    (InferenceMode) -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    Surface(color = BgInputBar, tonalElevation = 0.dp) {
        // FIX (Bug 3): imePadding() pushes the input bar up when the soft keyboard
        // opens. Without this, enableEdgeToEdge() in MainActivity causes the keyboard
        // to slide up over the input bar instead of moving it up. The Scaffold's
        // bottomBar sits in the window insets area and needs explicit IME handling.
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .imePadding()
        ) {

            // Context bar (only shown when model is loaded and context is known)
            if (contextMax > 0) {
                ContextBar(used = contextUsed, max = contextMax)
                Spacer(Modifier.height(6.dp))
            }

            Row(
                verticalAlignment     = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Mode toggle button
                ModeSwitcherButton(
                    mode            = inferenceMode,
                    isThinkingModel = isThinkingModel,
                    enabled         = isModelLoaded && !isGenerating,
                    onClick         = {
                        // Cycle: NORMAL → PRECISE/FOCUSED → NORMAL
                        val next = when (inferenceMode) {
                            InferenceMode.NORMAL  -> if (isThinkingModel) InferenceMode.PRECISE
                                                     else InferenceMode.FOCUSED
                            InferenceMode.PRECISE,
                            InferenceMode.FOCUSED -> InferenceMode.NORMAL
                        }
                        onModeToggle(next)
                    }
                )

                // Text input
                OutlinedTextField(
                    value         = inputText,
                    onValueChange = { inputText = it },
                    modifier      = Modifier.weight(1f),
                    enabled       = isModelLoaded && !isGenerating,
                    placeholder   = {
                        Text(
                            if (isModelLoaded) "Message..." else "Load a model first",
                            color    = TextSubtle,
                            fontSize = 14.sp
                        )
                    },
                    maxLines = 5,
                    shape    = RoundedCornerShape(12.dp),
                    colors   = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = AccentAmber,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor     = TextPrimary,
                        unfocusedTextColor   = TextPrimary,
                        cursorColor          = AccentAmber,
                        focusedContainerColor   = BgField,
                        unfocusedContainerColor = BgField,
                        disabledContainerColor  = BgField
                    ),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )

                // Send / Stop button
                if (isGenerating) {
                    IconButton(
                        onClick  = onStop,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Filled.StopCircle,
                            contentDescription = "Stop",
                            tint               = Color(0xFFE53935),
                            modifier           = Modifier.size(28.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick  = {
                            if (inputText.isNotBlank()) {
                                onSend(inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled  = isModelLoaded && inputText.isNotBlank(),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = "Send",
                            tint               = if (isModelLoaded && inputText.isNotBlank())
                                                    AccentAmber else TextSubtle,
                            modifier           = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSwitcherButton(
    mode: InferenceMode,
    isThinkingModel: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val label = mode.label
    val bgColor = when (mode) {
        InferenceMode.NORMAL  -> Color(0xFF1A1A1A)
        InferenceMode.PRECISE -> Color(0xFF1F1A00)
        InferenceMode.FOCUSED -> Color(0xFF001A10)
    }
    val textColor = when (mode) {
        InferenceMode.NORMAL  -> TextSubtle
        InferenceMode.PRECISE,
        InferenceMode.FOCUSED -> AccentAmber
    }

    TextButton(
        onClick  = onClick,
        enabled  = enabled,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .height(48.dp),
        contentPadding = PaddingValues(horizontal = 10.dp)
    ) {
        Text(label, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ContextBar(used: Int, max: Int) {
    val fraction = if (max > 0) used.toFloat() / max.toFloat() else 0f
    val color = when {
        fraction > 0.9f -> Color(0xFFE53935)
        fraction > 0.7f -> Color(0xFFF5A623)
        else            -> Color(0xFF333333)
    }
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LinearProgressIndicator(
            progress      = { fraction },
            modifier      = Modifier.weight(1f).height(2.dp).clip(RoundedCornerShape(2.dp)),
            color         = color,
            trackColor    = Color(0xFF222222)
        )
        Text(
            text     = "$used / $max tokens",
            color    = TextSubtle,
            fontSize = 9.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}

// ─── Message Bubbles ──────────────────────────────────────────────────────────

@Composable
private fun UserBubble(message: ChatMessage) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(BgUserBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            SelectionContainer {
                Text(message.content, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun AssistantBubble(message: ChatMessage, isGenerating: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Thinking panel — only shown if there are log entries
        if (message.thinkingLog.isNotEmpty()) {
            ThinkingPanel(
                isGenerating = false,
                log          = message.thinkingLog,
                thinkingMs   = message.thinkingMs,
                modifier     = Modifier.padding(bottom = 6.dp)
            )
        }

        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(BgBotBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            SelectionContainer {
                Text(message.content, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun AssistantStreamingBubble(
    text:         String,
    log:          List<com.lokai.app.model.ThinkingLog>,
    isGenerating: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ThinkingPanelLive(
            log      = log,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        if (text.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                    .background(BgBotBubble)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text      = text + if (isGenerating) "▌" else "",
                    color     = TextPrimary,
                    fontSize  = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

// ─── Overlays & empty states ─────────────────────────────────────────────────

@Composable
private fun WelcomePrompt(modelName: String) {
    Box(
        modifier       = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("💬", fontSize = 36.sp)
            Text(
                text       = modelName.ifBlank { "Lok.AI" },
                color      = AccentAmber,
                fontSize   = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text("Running on-device · offline · private", color = TextSubtle, fontSize = 11.sp)
        }
    }
}

@Composable
private fun NoChatModelEmpty(onPickModel: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier            = Modifier.padding(32.dp)
        ) {
            Text("🤖", fontSize = 48.sp)
            Text("No model loaded", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Download a model from the Browse tab,\nor pick a downloaded model below.",
                color = TextSubtle, fontSize = 13.sp
            )
            Button(
                onClick = onPickModel,
                colors  = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color.Black)
            ) {
                Text("Pick a model", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LoadingModelOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(color = AccentAmber)
            Text("Loading model...", color = TextSubtle, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ModeTooltipOverlay(mode: InferenceMode, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.padding(32.dp),
            shape    = RoundedCornerShape(12.dp),
            colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
        ) {
            Column(
                modifier            = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(mode.label, color = AccentAmber, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(mode.tooltip, color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Got it", color = AccentAmber)
                }
            }
        }
    }
}

// ─── Model Picker Sheet ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    sheetState:     SheetState,
    onModelSelected: (DownloadedModel) -> Unit,
    onDismiss:       () -> Unit
) {
    val downloadVm: com.lokai.app.viewmodel.DownloadViewModel = viewModel()
    val dlState by downloadVm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { downloadVm.refresh() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = Color(0xFF141414)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Select Model",
                color      = TextPrimary,
                fontSize   = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))

            if (dlState.downloadedModels.isEmpty()) {
                Text(
                    "No models downloaded yet.\nGo to the Browse tab to download one.",
                    color    = TextSubtle,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                dlState.downloadedModels.forEach { model ->
                    ModelPickerRow(
                        model    = model,
                        onClick  = { onModelSelected(model) }
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ModelPickerRow(model: DownloadedModel, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1C1C1C))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(model.displayQuant, color = TextSubtle, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (model.thinkingTrained) {
            Text("⚡", fontSize = 14.sp)
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick  = onClick,
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color.Black)
        ) {
            Text("Load", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// Provide LocalTextStyle for input field
@Composable
private fun LocalTextStyle() = androidx.compose.material3.LocalTextStyle

@Composable
private fun NativeLibraryMissingError() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPage),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector        = Icons.Filled.Warning,
                contentDescription = null,
                tint               = Color(0xFFEF9A9A),
                modifier           = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text       = "Inference engine unavailable",
                color      = Color(0xFFE0E0E0),
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text      = "The native library (lokai_jni) could not be loaded. " +
                            "This APK was likely built without the llama.cpp submodule. " +
                            "Rebuild with: git submodule update --init --recursive",
                color     = Color(0xFF666666),
                fontSize  = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
