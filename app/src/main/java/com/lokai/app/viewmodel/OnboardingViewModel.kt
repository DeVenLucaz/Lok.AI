package com.lokai.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lokai.app.data.device.DeviceDetector
import com.lokai.app.data.models.ModelCatalog
import com.lokai.app.model.DeviceProfile
import com.lokai.app.model.ModelEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// Shared singleton DataStore accessor — avoids duplicate delegate crash with MainActivity.
// Both MainActivity and OnboardingViewModel must use this same object.
internal object OnboardingDataStore {
    private val Context.store by preferencesDataStore("onboarding")
    val KEY_DONE = booleanPreferencesKey("onboarding_done")
    fun of(context: Context) = context.store
}

data class OnboardingState(
    val isScanning:   Boolean        = true,
    val scanProgress: Float          = 0f,
    val scanLabel:    String         = "Reading hardware…",
    val scanComplete: Boolean        = false,
    val profile:      DeviceProfile? = null,
    val topModels:    List<ModelEntry> = emptyList()
)

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val detector = DeviceDetector(application)
    private val catalog  = ModelCatalog(application)

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    val isOnboardingDone: Flow<Boolean> =
        OnboardingDataStore.of(application).data
            .map { it[OnboardingDataStore.KEY_DONE] ?: false }
            .catch { emit(false) }

    init { runScan() }

    private fun runScan() {
        viewModelScope.launch(Dispatchers.IO) {
            val steps = listOf(
                0.15f to "Reading /proc/meminfo…",
                0.30f to "Reading /proc/cpuinfo…",
                0.45f to "Detecting GPU vendor…",
                0.60f to "Checking swap / zram…",
                0.75f to "Classifying device tier…",
                0.90f to "Filtering compatible models…",
                1.00f to "Done."
            )
            for ((progress, label) in steps) {
                _state.update { it.copy(scanProgress = progress, scanLabel = label) }
                delay(340)
            }
            val profile = detector.detect()
            val (compatible, _) = catalog.filterByRam(profile.effectiveRamGb)
            val topModels = compatible.sortedBy { it.minRamGb }.take(3)
            _state.update {
                it.copy(isScanning = false, scanComplete = true, profile = profile, topModels = topModels)
            }
        }
    }

    fun markOnboardingDone() {
        viewModelScope.launch {
            OnboardingDataStore.of(getApplication()).edit { prefs ->
                prefs[OnboardingDataStore.KEY_DONE] = true
            }
        }
    }
}
