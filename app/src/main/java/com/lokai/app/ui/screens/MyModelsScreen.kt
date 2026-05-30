package com.lokai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lokai.app.model.DownloadedModel
import com.lokai.app.ui.components.DeleteModelDialog
import com.lokai.app.ui.components.MyModelCard
import com.lokai.app.viewmodel.DownloadViewModel

private val BgPage      = Color(0xFF0D0D0D)
private val AccentAmber = Color(0xFFF5A623)
private val TextSubtle  = Color(0xFF666666)
private val TextPrimary = Color(0xFFE0E0E0)

/**
 * "My Models" screen — lists all downloaded models.
 * Each card provides Chat and Agent launch buttons.
 * Long-press triggers delete confirmation.
 *
 * @param onChatClick  Called when user taps Chat on a model (Phase 4 will wire this to ChatScreen)
 * @param onAgentClick Called when user taps Agent on a model (Phase 5)
 */
@Composable
fun MyModelsScreen(
    downloadVm: DownloadViewModel = viewModel(),
    onChatClick:  (DownloadedModel) -> Unit = {},
    onAgentClick: (DownloadedModel) -> Unit = {}
) {
    val uiState by downloadVm.uiState.collectAsStateWithLifecycle()

    // Refresh on first composition
    LaunchedEffect(Unit) { downloadVm.refresh() }

    // Delete confirmation dialog
    uiState.deleteConfirmModelId?.let { modelId ->
        val name = uiState.downloadedModels.firstOrNull { it.modelId == modelId }?.name ?: modelId
        DeleteModelDialog(
            modelName = name,
            onConfirm = { downloadVm.confirmDelete(modelId) },
            onDismiss = { downloadVm.dismissDeleteConfirm() }
        )
    }

    // Delete error snackbar
    uiState.deleteError?.let { error ->
        LaunchedEffect(error) {
            // In a real app you'd show a Snackbar; for now just auto-clear
            downloadVm.clearDeleteError()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = BgPage) {
        when {
            uiState.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentAmber)
                }
            }

            uiState.downloadedModels.isEmpty() -> {
                EmptyMyModels()
            }

            else -> {
                LazyColumn(
                    contentPadding      = PaddingValues(
                        start  = 16.dp,
                        end    = 16.dp,
                        top    = 20.dp,
                        bottom = 20.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Column {
                            Text(
                                text       = "My Models",
                                color      = AccentAmber,
                                fontSize   = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text    = "${uiState.downloadedModels.size} model${if (uiState.downloadedModels.size == 1) "" else "s"} on device",
                                color   = TextSubtle,
                                fontSize= 12.sp
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    items(uiState.downloadedModels, key = { it.modelId }) { model ->
                        MyModelCard(
                            model        = model,
                            onChatClick  = onChatClick,
                            onAgentClick = onAgentClick,
                            onLongPress  = { downloadVm.requestDelete(it.modelId) }
                        )
                    }

                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
private fun EmptyMyModels() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier            = Modifier.padding(32.dp)
        ) {
            Text("📭", fontSize = 48.sp)
            Text(
                text       = "No models downloaded yet",
                color      = TextPrimary,
                fontSize   = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign  = TextAlign.Center
            )
            Text(
                text      = "Go to the Models tab to download a model for your device.",
                color     = TextSubtle,
                fontSize  = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
