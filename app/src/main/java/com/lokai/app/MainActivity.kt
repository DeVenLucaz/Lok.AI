package com.lokai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.lokai.app.ui.navigation.LokaiNavGraph
import com.lokai.app.ui.screens.OnboardingScreen
import com.lokai.app.ui.theme.LokAITheme
import com.lokai.app.viewmodel.OnboardingDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter(this).install()
        enableEdgeToEdge()
        setContent {
            LokAITheme {
                LokaiRoot()
            }
        }
    }
}

@Composable
private fun LokaiRoot() {
    val context = LocalContext.current

    var resolved       by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        OnboardingDataStore.of(context).data
            .map { it[OnboardingDataStore.KEY_DONE] ?: false }
            .catch { emit(false) }
            .collect { done ->
                if (!resolved) {
                    showOnboarding = !done
                    resolved = true
                }
            }
    }

    if (!resolved) return

    if (showOnboarding) {
        OnboardingScreen(onFinished = { showOnboarding = false })
    } else {
        LokaiNavGraph()
    }
}
