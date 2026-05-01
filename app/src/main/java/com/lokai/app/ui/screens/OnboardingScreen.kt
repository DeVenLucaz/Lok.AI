package com.lokai.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn          // FIX: added
import androidx.compose.foundation.lazy.items               // FIX: added
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lokai.app.model.DeviceProfile
import com.lokai.app.model.DeviceTier
import com.lokai.app.model.ModelEntry
import com.lokai.app.viewmodel.OnboardingViewModel
import kotlinx.coroutines.delay

private val Amber    = Color(0xFFF5A623)
private val Bg       = Color(0xFF0D0D0D)
private val SurfaceC = Color(0xFF1A1A1A)
private val Subtle   = Color(0xFF555555)
private val OnSurf   = Color(0xFFE0E0E0)

// ─── Entry point ──────────────────────────────────────────────────────────────

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    // FIX: removed default viewModel() here — VM is now hoisted in LokaiRoot
    // and passed down to prevent disposal crash when onFinished() fires on page 2.
    vm: OnboardingViewModel
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Page state machine: scan → profile → models → done
    var page by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.scanComplete) {
        if (state.scanComplete && page == 0) {
            delay(600)
            page = 1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                (fadeIn(tween(400)) + slideInVertically { it / 8 })
                    .togetherWith(fadeOut(tween(250)))
            },
            label = "onboarding_page"
        ) { p ->
            when (p) {
                0 -> ScanPage(
                    isScanning   = state.isScanning,
                    scanProgress = state.scanProgress,
                    scanLabel    = state.scanLabel
                )
                1 -> ProfilePage(
                    profile  = state.profile,
                    onNext   = { page = 2 }
                )
                2 -> ModelsPage(
                    models   = state.topModels,
                    tier     = state.profile?.tier ?: DeviceTier.MID,
                    onFinish = {
                        vm.markOnboardingDone()
                        onFinished()
                    }
                )
            }
        }
    }
}

// ─── Page 0: Animated scan ────────────────────────────────────────────────────

@Composable
private fun ScanPage(
    isScanning: Boolean,
    scanProgress: Float,
    scanLabel: String
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon with pulse
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector        = Icons.Filled.Memory,
                contentDescription = null,
                tint               = Amber.copy(alpha = if (isScanning) pulse else 1f),
                modifier           = Modifier.size(72.dp)
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text       = "Analysing your device",
            color      = OnSurf,
            fontSize   = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text      = "Finding AI models that will actually run on your phone",
            color     = Subtle,
            fontSize  = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(40.dp))

        // Progress bar
        LinearProgressIndicator(
            progress  = { scanProgress },
            modifier  = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color          = Amber,
            trackColor     = SurfaceC
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text      = scanLabel,
            color     = Subtle,
            fontSize  = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ─── Page 1: Device profile card ──────────────────────────────────────────────

@Composable
private fun ProfilePage(
    profile: DeviceProfile?,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text       = "Your device",
            color      = Subtle,
            fontSize   = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text       = profile?.deviceName ?: "Android Device",
            color      = OnSurf,
            fontSize   = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        // Tier badge
        if (profile != null) {
            val tierColor = Color(profile.tier.badgeColor)
            Box(
                modifier = Modifier
                    .background(tierColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .border(1.dp, tierColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text       = profile.tier.displayName,
                    color      = tierColor,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 15.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text      = profile.tier.description,
                color     = Subtle,
                fontSize  = 13.sp,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(28.dp))

        // Stats grid
        if (profile != null) {
            Surface(
                color  = SurfaceC,
                shape  = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatRow(Icons.Filled.Memory,         "RAM",     "%.1f GB total · %.1f GB free".format(profile.totalRamGb, profile.availableRamGb))
                    StatRow(Icons.Filled.DeveloperBoard, "Chip",    profile.chipName)
                    StatRow(Icons.Filled.Speed,          "Cores",   "${profile.cpuCores} × ${profile.cpuArch}")
                    StatRow(Icons.Filled.Videocam,       "GPU",     profile.gpuVendor.displayName)
                    StatRow(Icons.Filled.Android,        "Android", "${profile.androidVersion} (API ${profile.apiLevel})")
                }
            }
        } else {
            CircularProgressIndicator(color = Amber)
        }

        Spacer(Modifier.height(36.dp))

        Button(
            onClick  = onNext,
            enabled  = profile != null,
            colors   = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape    = RoundedCornerShape(12.dp)
        ) {
            Text("See compatible models →", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = Amber,
            modifier           = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, color = Subtle,  fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(value, color = OnSurf, fontSize = 13.sp)
        }
    }
}

// ─── Page 2: Top compatible models ────────────────────────────────────────────

@Composable
private fun ModelsPage(
    models: List<ModelEntry>,
    tier: DeviceTier,
    onFinish: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text       = "What'll run on your phone",
            color      = OnSurf,
            fontSize   = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text      = "Here are the top picks for ${tier.displayName} tier devices",
            color     = Subtle,
            fontSize  = 13.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        if (models.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(SurfaceC, RoundedCornerShape(16.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No compatible models found for this device tier.", color = Subtle, textAlign = TextAlign.Center)
            }
        } else {
            // FIX: replaced plain Column + forEach with LazyColumn to prevent
            // layout overflow crash on small screens
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(models.take(3)) { model ->
                    OnboardingModelCard(model)
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick  = onFinish,
            colors   = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape    = RoundedCornerShape(12.dp)
        ) {
            Text("Let's find your first model →", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text     = "You can browse all models and download any time",
            color    = Subtle,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun OnboardingModelCard(model: ModelEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceC, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text       = model.name,
                    color      = OnSurf,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 14.sp
                )
                if (model.thinkingTrained) {
                    Spacer(Modifier.width(6.dp))
                    Text("⚡", fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text     = model.bestFor,
                color    = Subtle,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text     = "%.1f GB".format(model.variants.minOfOrNull { it.ramRequiredGb } ?: 0f),
                color    = Amber,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text     = "RAM req.",
                color    = Subtle,
                fontSize = 11.sp
            )
        }
    }
}
