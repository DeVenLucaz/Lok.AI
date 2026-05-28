package com.lokai.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lokai.app.model.AgentProfile
import com.lokai.app.model.AgentSession
import com.lokai.app.model.ChatSession
import com.lokai.app.model.DownloadedModel
import com.lokai.app.ui.screens.*
import com.lokai.app.viewmodel.AgentViewModel
import com.lokai.app.viewmodel.ChatViewModel
import com.lokai.app.viewmodel.DownloadViewModel

private val BgNavBar    = Color(0xFF141414)
private val AccentAmber = Color(0xFFF5A623)
private val TextSubtle  = Color(0xFF555555)

private sealed class NavTab(
    val route: String,
    val label: String,
    val icon:  ImageVector
) {
    object Chat      : NavTab("chat",      "Chat",      Icons.Filled.Chat)
    object Agents    : NavTab("agents",    "Agents",    Icons.Filled.SmartToy)
    object MyModels  : NavTab("my_models", "My Models", Icons.Filled.Inventory2)
    object Models    : NavTab("models",    "Browse",    Icons.Filled.Storage)
    object Device    : NavTab("device",    "Device",    Icons.Filled.Memory)
    object Settings  : NavTab("settings",  "Settings",  Icons.Filled.Settings)

    companion object {
        val all = listOf<NavTab>(Chat, Agents, MyModels, Models, Device, Settings)
        fun fromRoute(route: String): NavTab =
            all.firstOrNull { it.route == route } ?: MyModels
    }
}

/** Secondary screens that overlay the main tab scaffold (no bottom nav) */
private sealed class OverlayScreen {
    object None : OverlayScreen()
    object CreateAgent : OverlayScreen()
    data class AgentChat(val agent: AgentProfile, val modelPath: String) : OverlayScreen()
    object HFSearch : OverlayScreen()
    object Sessions : OverlayScreen()
}

@Composable
fun LokaiNavGraph() {
    // FIX: store route as String to avoid NavTab null reference during
    // recomposition after onboarding->main transition. Resolve the tab
    // object via fromRoute() inline — never stored as nullable state.
    var currentRoute by remember { mutableStateOf("my_models") }
    var overlay      by remember { mutableStateOf<OverlayScreen>(OverlayScreen.None) }

    // Shared ViewModels
    val chatVm:     ChatViewModel     = viewModel()
    val agentVm:    AgentViewModel    = viewModel()
    val downloadVm: DownloadViewModel = viewModel()

    var pendingChatModel by remember { mutableStateOf<DownloadedModel?>(null) }

    // ─── Overlay screens (full-screen, no bottom nav) ─────────────────────────

    when (val o = overlay) {
        is OverlayScreen.CreateAgent -> {
            val dlState by downloadVm.uiState.collectAsStateWithLifecycle()
            AgentCreateScreen(
                downloadedModels = dlState.downloadedModels,
                onBack           = { overlay = OverlayScreen.None },
                onCreated        = {
                    overlay      = OverlayScreen.None
                    currentRoute = "agents"
                }
            )
            return
        }
        is OverlayScreen.AgentChat -> {
            AgentChatScreen(
                agent               = o.agent,
                downloadedModelPath = o.modelPath,
                onBack              = { overlay = OverlayScreen.None },
                vm                  = agentVm
            )
            return
        }
        is OverlayScreen.HFSearch -> {
            HFSearchScreen(onBack = { overlay = OverlayScreen.None })
            return
        }
        is OverlayScreen.Sessions -> {
            SessionsListScreen(
                onOpenRegular = { _: ChatSession  -> overlay = OverlayScreen.None },
                onOpenAgent   = { _: AgentSession -> overlay = OverlayScreen.None }
            )
            return
        }
        is OverlayScreen.None -> { /* continue to main scaffold */ }
    }

    // ─── Main tab scaffold ─────────────────────────────────────────────────────

    Scaffold(
        containerColor = Color(0xFF0D0D0D),
        bottomBar = {
            NavigationBar(containerColor = BgNavBar, tonalElevation = 0.dp) {
                NavTab.all.forEach { tab ->
                    val selected = currentRoute == tab.route
                    NavigationBarItem(
                        selected = selected,
                        onClick  = { currentRoute = tab.route },
                        icon     = {
                            Icon(
                                imageVector        = tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label  = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor   = AccentAmber,
                            selectedTextColor   = AccentAmber,
                            unselectedIconColor = TextSubtle,
                            unselectedTextColor = TextSubtle,
                            indicatorColor      = Color(0xFF222200)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        when (NavTab.fromRoute(currentRoute)) {
            NavTab.Chat -> ChatScreen(
                chatVm       = chatVm,
                initialModel = pendingChatModel.also { pendingChatModel = null }
            )
            NavTab.Agents -> AgentListScreen(
                onCreateAgent = { overlay = OverlayScreen.CreateAgent },
                onOpenAgent   = { agent ->
                    val dlState = downloadVm.uiState.value
                    val modelPath = dlState.downloadedModels
                        .firstOrNull { it.modelId == agent.modelId }?.localPath
                        ?: return@AgentListScreen
                    overlay = OverlayScreen.AgentChat(agent, modelPath)
                }
            )
            NavTab.MyModels -> MyModelsScreen(
                downloadVm   = downloadVm,
                onChatClick  = { model ->
                    pendingChatModel = model
                    currentRoute = "chat"
                },
                onAgentClick = { _ ->
                    overlay = OverlayScreen.CreateAgent
                }
            )
            NavTab.Models   -> ModelBrowserScreen(
                onHFSearch = { overlay = OverlayScreen.HFSearch }
            )
            NavTab.Device   -> DeviceScreen()
            NavTab.Settings -> SettingsScreen(
                onViewSessions = { overlay = OverlayScreen.Sessions },
                innerPadding   = innerPadding   // FIX: pass so bottom nav bar doesn't clip content
            )
        }
    }
}
