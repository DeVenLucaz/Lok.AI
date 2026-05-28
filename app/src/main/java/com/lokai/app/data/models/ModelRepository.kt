package com.lokai.app.data.models

import com.lokai.app.model.DeviceProfile
import com.lokai.app.model.ModelEntry

/**
 * Combines the [ModelCatalog] with a [DeviceProfile] to produce
 * filtered and sorted model lists for display.
 */
class ModelRepository(private val catalog: ModelCatalog) {

    /**
     * Returns models split into compatible/incompatible based on the device profile.
     *
     * Compatible models are sorted: thinking-trained first, then by minRamGb descending
     * (bigger = more capable within what the device handles).
     *
     * Incompatible models are sorted by minRamGb ascending (closest to fitting first).
     */
    fun getModelsForDevice(profile: DeviceProfile): ModelResult {
        // FIX: Filter by physical RAM only — llama.cpp loads model weights into RAM
        // and swap/zram is too slow for LLM inference on Android. Using effectiveRamGb
        // (which includes swap) caused oversized models to appear as "Compatible".
        // We add a small 0.2× swap bonus (vs the old 0.6×) to allow models that
        // only slightly exceed physical RAM and can use a little zram for KV cache.
        val filterRamGb = profile.totalRamGb + (profile.swapGb * 0.2f)

        val (compatible, incompatible) = catalog.filterByRam(filterRamGb)

        val sortedCompatible = compatible.sortedWith(
            compareByDescending<ModelEntry> { it.thinkingTrained }
                .thenByDescending { it.minRamGb }
        )

        val sortedIncompatible = incompatible.sortedBy { it.minRamGb }

        return ModelResult(
            compatible        = sortedCompatible,
            incompatible      = sortedIncompatible,
            deviceRamGb       = profile.effectiveRamGb,   // kept for UI display
            filterRamGb       = filterRamGb               // actual value used for filtering
        )
    }

    /**
     * Returns a model by ID, or null if not found.
     */
    fun getById(id: String): ModelEntry? = catalog.allModels().firstOrNull { it.id == id }
}

data class ModelResult(
    val compatible:   List<ModelEntry>,
    val incompatible: List<ModelEntry>,
    /** Effective RAM (total + swap×0.6) — shown in UI as device capability */
    val deviceRamGb:  Float,
    /** Physical-biased RAM (total + swap×0.2) — actually used for filtering */
    val filterRamGb:  Float = deviceRamGb
)
