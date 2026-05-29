package com.lokai.app.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lokai.app.data.inference.InferenceMode
import com.lokai.app.model.AgentProfile
import com.lokai.app.model.ChatMessage
import com.lokai.app.model.DownloadedModel
import com.lokai.app.ui.components.ThinkingPanel
import com.lokai.app.ui.components.ThinkingPanelLive
import com.lokai.app.viewmodel.AgentViewModel
import kotlinx.coroutines.launch

private val BgPage       = Color(0xFF0D0D0D)
private val BgTopBar     = Color(0xFF111111)
private val BgInput      = Color(0xFF111111)
private val BgUser       = Color(0xFF1F1A00)
private val BgBot        = Color(0xFF181818)
private val BgField      = Color(0xFF1A1A1A)
private val Amber        = Color(0xFFF5A623)
private val TextPrimary  = Color(0xFFE0E0E0)
private val TextSubtle   = Color(0xFF666666)
private val Border       = Color(0xFF2A2A2A)

/**
 * Agent chat screen.
 *
 * @param agent               The agent to open
 * @param downloadedModelPath Absolute path to the agent's model GGUF on disk
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
    agent:               AgentProfile,
    downloadedModelPath: String,
    onBack:              () -> Unit,
    vm:                  AgentViewModel = viewModel()
) {
    val state     by vm.chatState.collectAsStateWithLifecycle()
    val listState  = rememberLazyListState()
    val scope      = rememberCoroutineScope()
    var inputText  by remember { mutableStateOf("") }

    // Open agent when screen enters
    LaunchedEffect(agent.id) {
        vm.openAgent(agent.id, downloadedModelPath)
    }

    // Auto-scroll
    val msgCount  = state.session?.messages?.size ?: 0
    val streaming = state.streamingText
    LaunchedEffect(msgCount, streaming) {
        val total = msgCount + if (streaming.isNotEmpty()) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    Scaffold(
        containerColor = BgPage,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(agent.category.emoji, fontSize = 16.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text       = agent.name,
                                color      = TextPrimary,
                                fontSize   = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines   = 1,
                                overflow   = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text     = "${agent.category.displayName} · ${agent.category.strategyName}",
                            color    = Amber,
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    // Mode toggle
                    if (state.isModelLoaded) {
                        val modeLabel = state.inferenceMode.label
                        TextButton(onClick = vm::toggleInferenceMode) {
                            Text(modeLabel, color = Amber, fontSize = 12.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgTopBar)
            )
        },
        bottomBar = {
            AgentInputBar(
                inputText    = inputText,
                onTextChange = { inputText = it },
                isGenerating = state.isGenerating,
                isModelLoaded= state.isModelLoaded,
                onSend = {
                    if (inputText.isNotBlank()) {
                        val text = inputText
                        inputText = ""
                        vm.sendMessage(text)
                    }
                },
                onStop = vm::stopGeneration
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loadingModel -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Amber)
                            Spacer(Modifier.height(12.dp))
                            Text("Loading model…", color = TextSubtle, fontSize = 13.sp)
                        }
                    }
                }
                state.loadError != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.loadError!!, color = Color(0xFFFF5252), fontSize = 13.sp)
                    }
                }
                else -> {
                    val messages = state.session?.messages ?: emptyList()
                    LazyColumn(
                        state          = listState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier       = Modifier.fillMaxSize()
                    ) {
                        // Context info banner
                        if (state.contextUsed > 0) {
                            item {
                                AgentContextBanner(
                                    used = state.contextUsed,
                                    max  = state.contextMax
                                )
                            }
                        }

                        // Overflow warning
                        if (state.overflowWarning != null) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1000)),
                                    shape  = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = state.overflowWarning!!,
                                        color = Color(0xFFFF9800),
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }

                        if (messages.isEmpty() && !state.isGenerating) {
                            item { AgentEmptyState(agent = agent) }
                        }

                        items(messages, key = { it.id }) { msg ->
                            AgentMessageBubble(
                                message   = msg,
                                agentName = agent.name
                            )
                        }

                        // Streaming bubble
                        if (state.isGenerating) {
                            item {
                                Column {
                                    if (state.streamingLog.isNotEmpty()) {
                                        ThinkingPanelLive(log = state.streamingLog)
                                        Spacer(Modifier.height(6.dp))
                                    }
                                    if (state.streamingText.isNotBlank()) {
                                        AgentBotBubble(text = state.streamingText + "▌", agentName = agent.name)
                                    }
                                }
                            }
                        }

                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentContextBanner(used: Int, max: Int) {
    val pct = (used * 100f / max.coerceAtLeast(1)).toInt()
    val color = when {
        pct > 80 -> Color(0xFFFF5252)
        pct > 60 -> Color(0xFFFF9800)
        else     -> Color(0xFF555555)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141414), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Context: $used / $max tokens ($pct%)", color = color, fontSize = 11.sp,
             fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
}

@Composable
private fun AgentEmptyState(agent: AgentProfile) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(agent.category.emoji, fontSize = 40.sp)
            Spacer(Modifier.height(12.dp))
            Text(agent.name, color = Amber, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                text      = agent.category.description,
                color     = TextSubtle,
                fontSize  = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier  = Modifier.padding(horizontal = 32.dp)
            )
            if (agent.fileName != null) {
                Spacer(Modifier.height(8.dp))
                Text("📄 ${agent.fileName}", color = Color(0xFF555555), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun AgentMessageBubble(message: ChatMessage, agentName: String) {
    if (message.isUser) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            SelectionContainer {
                Box(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                        .background(BgUser)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(message.content, color = TextPrimary, fontSize = 14.sp)
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (message.thinkingLog.isNotEmpty()) {
                ThinkingPanel(
                    isGenerating = false,
                    log       = message.thinkingLog,
                    thinkingMs = message.thinkingMs
                )
                Spacer(Modifier.height(6.dp))
            }
            AgentBotBubble(text = message.content, agentName = agentName)
        }
    }
}

@Composable
private fun AgentBotBubble(text: String, agentName: String) {
    Column {
        Text(agentName, color = Amber, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
        SelectionContainer {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    .background(BgBot)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(text, color = TextPrimary, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun AgentInputBar(
    inputText:     String,
    onTextChange:  (String) -> Unit,
    isGenerating:  Boolean,
    isModelLoaded: Boolean,
    onSend:        () -> Unit,
    onStop:        () -> Unit
) {
    Surface(color = BgInput, tonalElevation = 0.dp) {
        // FIX (Bug 8): Added imePadding() to the Row so the keyboard doesn't cover
        // the input bar. Same root cause as ChatScreen — enableEdgeToEdge() in
        // MainActivity makes the app draw behind system bars, requiring explicit
        // IME inset handling here. navigationBarsPadding() is kept for gesture nav.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value         = inputText,
                onValueChange = onTextChange,
                placeholder   = { Text("Ask about your file…", color = TextSubtle, fontSize = 14.sp) },
                modifier      = Modifier.weight(1f),
                maxLines      = 5,
                enabled       = isModelLoaded && !isGenerating,
                shape         = RoundedCornerShape(20.dp),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Amber,
                    unfocusedBorderColor = Border,
                    focusedTextColor     = TextPrimary,
                    unfocusedTextColor   = TextPrimary,
                    cursorColor          = Amber,
                    disabledBorderColor  = Color(0xFF2A2A2A),
                    disabledTextColor    = TextSubtle
                )
            )
            Spacer(Modifier.width(8.dp))
            if (isGenerating) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(46.dp)
                        .background(Color(0xFF330000), RoundedCornerShape(23.dp))
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = Color(0xFFFF5252))
                }
            } else {
                IconButton(
                    onClick  = onSend,
                    enabled  = isModelLoaded && inputText.isNotBlank(),
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            if (isModelLoaded && inputText.isNotBlank()) Amber else Color(0xFF2A2A2A),
                            RoundedCornerShape(23.dp)
                        )
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Send",
                         tint = if (isModelLoaded && inputText.isNotBlank()) Color.Black else TextSubtle)
                }
            }
        }
    }
}
