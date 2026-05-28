package com.lokai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lokai.app.data.inference.InferenceMode
import com.lokai.app.viewmodel.SettingsViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import kotlin.math.roundToInt

private val BgPage     = Color(0xFF0D0D0D)
private val BgCard     = Color(0xFF141414)
private val AccentAmber = Color(0xFFF5A623)
private val TextPrimary = Color(0xFFE0E0E0)
private val TextSubtle  = Color(0xFF666666)

@Composable
fun SettingsScreen(
    vm:             SettingsViewModel = viewModel(),
    onViewSessions: () -> Unit        = {},
    // FIX: accept innerPadding from the Scaffold so the nav bar height is
    // accounted for. LokaiNavGraph now passes this down.
    innerPadding:   PaddingValues     = PaddingValues()
) {
    val s by vm.settings.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = BgPage) {
        LazyColumn(
            // FIX: use innerPadding.calculateBottomPadding() so the list
            // scrolls fully above the bottom nav bar (was getting clipped).
            contentPadding = PaddingValues(
                start  = 16.dp,
                top    = 20.dp,
                end    = 16.dp,
                bottom = 20.dp + innerPadding.calculateBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Settings",
                    color      = AccentAmber,
                    fontSize   = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text("Persisted across sessions", color = TextSubtle, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
            }

            // ── Inference ─────────────────────────────────────────────────────
            item {
                SettingsSection("Inference") {
                    // Threads
                    val autoLabel = if (s.threads == 0) "Auto (${Runtime.getRuntime().availableProcessors()})"
                                    else "${s.threads}"
                    SliderRow(
                        label     = "Threads",
                        valueLabel = autoLabel,
                        value     = s.threads.toFloat(),
                        range     = 0f..16f,
                        steps     = 15,
                        onChanged = { vm.setThreads(it.roundToInt()) }
                    )
                    Divider(color = Color(0xFF222222))
                    // Context size
                    SliderRow(
                        label     = "Context size",
                        valueLabel = "${s.contextSize} tokens",
                        value     = s.contextSize.toFloat(),
                        range     = 128f..8192f,
                        steps     = 62,
                        onChanged = { vm.setContextSize(snapToContextSize(it)) }
                    )
                    Divider(color = Color(0xFF222222))
                    // Max tokens
                    SliderRow(
                        label     = "Max tokens",
                        valueLabel = "${s.maxTokens}",
                        value     = s.maxTokens.toFloat(),
                        range     = 50f..2048f,
                        steps     = 39,
                        onChanged = { vm.setMaxTokens(it.roundToInt()) }
                    )
                    Divider(color = Color(0xFF222222))
                    // Temperature
                    SliderRow(
                        label     = "Temperature",
                        valueLabel = "%.2f".format(s.temperature),
                        value     = s.temperature,
                        range     = 0.0f..2.0f,
                        steps     = 39,
                        onChanged = { vm.setTemperature((it * 100).roundToInt() / 100f) }
                    )
                    Divider(color = Color(0xFF222222))
                    // Default mode
                    DefaultModeRow(
                        current   = s.defaultMode,
                        onChanged = { vm.setDefaultMode(it) }
                    )
                }
            }

            // ── System prompt ─────────────────────────────────────────────────
            item {
                SettingsSection("System Prompt (Regular Chat)") {
                    var text by remember(s.customSystemPrompt) { mutableStateOf(s.customSystemPrompt) }
                    OutlinedTextField(
                        value         = text,
                        onValueChange = { text = it },
                        modifier      = Modifier.fillMaxWidth(),
                        placeholder   = { Text("Optional custom system prompt…", color = TextSubtle, fontSize = 12.sp) },
                        minLines      = 3,
                        maxLines      = 6,
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor    = AccentAmber,
                            unfocusedBorderColor  = Color(0xFF2A2A2A),
                            focusedTextColor      = TextPrimary,
                            unfocusedTextColor    = TextPrimary,
                            focusedContainerColor = Color(0xFF1A1A1A),
                            unfocusedContainerColor = Color(0xFF1A1A1A)
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.setCustomSystemPrompt(text.trim()) },
                        colors  = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color.Black),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            // ── Session ───────────────────────────────────────────────────────
            item {
                SettingsSection("Sessions") {
                    ToggleRow(
                        label    = "Auto-save sessions",
                        subLabel = "Save chat history every 5 exchanges",
                        checked  = s.autoSaveSessions,
                        onChecked = { vm.setAutoSave(it) }
                    )
                    Divider(color = Color(0xFF222222))
                    ActionRow(
                        label    = "View History",
                        subLabel = "Browse all chat and agent sessions",
                        onClick  = onViewSessions
                    )
                }
            }

            // ── Battery ───────────────────────────────────────────────────────
            item {
                SettingsSection("Battery") {
                    SliderRow(
                        label     = "Battery warning threshold",
                        valueLabel = "${s.batteryWarnPercent}%",
                        value     = s.batteryWarnPercent.toFloat(),
                        range     = 5f..50f,
                        steps     = 8,
                        onChanged = { vm.setBatteryWarnPercent(it.roundToInt()) }
                    )
                }
            }

            // ── About ─────────────────────────────────────────────────────────
            item {
                SettingsSection("About") {
                    SettingsInfoRow("Version",       "0.5.0-phase5")
                    Divider(color = Color(0xFF222222))
                    SettingsInfoRow("Inference",     "llama.cpp · CPU only")
                    Divider(color = Color(0xFF222222))
                    SettingsInfoRow("Cloud / Ads",   "None · fully offline")
                    Divider(color = Color(0xFF222222))
                    SettingsInfoRow("License",       "Source Available · All Rights Reserved")
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ─── Section container ────────────────────────────────────────────────────────

@Composable
private fun SettingsSection(
    title:   String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text       = title.uppercase(),
            color      = TextSubtle,
            fontSize   = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(BgCard)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            content()
        }
    }
}

// ─── Row types ────────────────────────────────────────────────────────────────

@Composable
private fun SliderRow(
    label:      String,
    valueLabel: String,
    value:      Float,
    range:      ClosedFloatingPointRange<Float>,
    steps:      Int,
    onChanged:  (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextPrimary, fontSize = 13.sp)
            Text(valueLabel, color = AccentAmber, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value         = value,
            onValueChange = onChanged,
            valueRange    = range,
            steps         = steps,
            colors        = SliderDefaults.colors(
                thumbColor       = AccentAmber,
                activeTrackColor = AccentAmber,
                inactiveTrackColor = Color(0xFF2A2A2A)
            )
        )
    }
}

@Composable
private fun ToggleRow(
    label:     String,
    subLabel:  String,
    checked:   Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 13.sp)
            Text(subLabel, color = TextSubtle, fontSize = 11.sp)
        }
        Switch(
            checked          = checked,
            onCheckedChange  = onChecked,
            colors           = SwitchDefaults.colors(
                checkedThumbColor     = Color.Black,
                checkedTrackColor     = AccentAmber,
                uncheckedThumbColor   = TextSubtle,
                uncheckedTrackColor   = Color(0xFF2A2A2A)
            )
        )
    }
}

@Composable
private fun DefaultModeRow(current: InferenceMode, onChanged: (InferenceMode) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("Default inference mode", color = TextPrimary, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InferenceMode.values().forEach { mode ->
                val selected = mode == current
                FilterChip(
                    selected = selected,
                    onClick  = { onChanged(mode) },
                    label    = { Text(mode.label, fontSize = 11.sp) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor  = AccentAmber,
                        selectedLabelColor      = Color.Black,
                        containerColor          = Color(0xFF222222),
                        labelColor              = TextSubtle
                    )
                )
            }
        }
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSubtle, fontSize = 12.sp)
        Text(value, color = TextPrimary, fontSize = 12.sp)
    }
}

@Composable
private fun ActionRow(label: String, subLabel: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label,    color = TextPrimary, fontSize = 14.sp)
            Text(subLabel, color = TextSubtle,  fontSize = 12.sp)
        }
        Icon(
            imageVector        = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint               = TextSubtle,
            modifier           = Modifier.size(20.dp)
        )
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun snapToContextSize(raw: Float): Int {
    val sizes = listOf(128, 256, 512, 1024, 2048, 4096, 8192)
    return sizes.minByOrNull { Math.abs(it - raw.roundToInt()) } ?: 2048
}
