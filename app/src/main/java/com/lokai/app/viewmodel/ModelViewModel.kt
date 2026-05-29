package com.lokai.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lokai.app.data.device.DeviceDetector
import com.lokai.app.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ModelViewModel(application: Application) : AndroidViewModel(application) {

    private val catalog    = ModelCatalog(application)
    private val repository = ModelRepository(catalog)
    private val detector   = DeviceDetector(application)

    private val _result    = MutableStateFlow<ModelResult?>(null)
    val result: StateFlow<ModelResult?> = _result

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _hfSearchState = MutableStateFlow(HFSearchState())
    val hfSearchState: StateFlow<HFSearchState> = _hfSearchState

    // FIX (Bug 6): Store effectiveRamGb from DeviceDetector.
    // DeviceDetector now uses ActivityManager (physical RAM only) so this value
    // is accurate regardless of ColorOS / MIUI virtual RAM expansion.
    // All filtering, display, and HF search use this one value.
    private var deviceRamGb: Float = 4f

    init {
        loadModels()
    }

    private fun loadModels() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            val profile  = detector.detect()
            // FIX (Bug 6): Use profile.effectiveRamGb directly — DeviceDetector
            // is now the single source of truth. No more local recalculation.
            deviceRamGb  = profile.effectiveRamGb
            _result.value = repository.getModelsForDevice(profile)
            _isLoading.value = false
        }
    }

    fun refresh() = loadModels()

    // ─── HuggingFace search ───────────────────────────────────────────────────

    fun searchHuggingFace(query: String) {
        if (query.isBlank()) return
        _hfSearchState.value = HFSearchState(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = HuggingFaceSearch.search(query, deviceRamGb)
                _hfSearchState.value = HFSearchState(
                    results     = results,
                    hasSearched = true
                )
            } catch (e: Exception) {
                _hfSearchState.value = HFSearchState(
                    error       = "Search failed: ${e.message}",
                    hasSearched = true
                )
            }
        }
    }
}
