package com.lokai.app.viewmodel

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lokai.app.KEY_ONBOARDING_DONE
import com.lokai.app.onboardingDataStore
import com.lokai.app.data.device.DeviceDetector
import com.lokai.app.data.models.ModelCatalog
import com.lokai.app.model.DeviceProfile
import com.lokai.app.model.ModelEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
        application.onboardingDataStore.data
            .map { it[KEY_ONBOARDING_DONE] ?: false }
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
            // FIX: use same physical-biased filter as ModelRepository (total + swap×0.2)
            val filterRam = profile.totalRamGb + (profile.swapGb * 0.2f)
            val (compatible, _) = catalog.filterByRam(filterRam)
            val topModels = compatible.sortedBy { it.minRamGb }.take(3)
            _state.update {
                it.copy(
                    isScanning   = false,
                    scanComplete = true,
                    profile      = profile,
                    topModels    = topModels
                )
            }
        }
    }

    /**
     * FIX: onComplete callback is invoked AFTER the DataStore write finishes.
     *
     * Previously markOnboardingDone() launched a fire-and-forget coroutine, then
     * the caller immediately called onFinished() on the next line — disposing the
     * Activity/Composition and cancelling viewModelScope mid-write, causing a crash.
     *
     * Now the caller passes onComplete and navigation only happens once the write
     * has actually committed to disk.
     */
    fun markOnboardingDone(onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                getApplication<Application>().onboardingDataStore.edit { prefs ->
                    prefs[KEY_ONBOARDING_DONE] = true
                }
            } finally {
                // Always navigate even if write fails — don't leave user stuck on page 2
                onComplete()
            }
        }
    }
}
