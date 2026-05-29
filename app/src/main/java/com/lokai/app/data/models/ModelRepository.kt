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
     *
     * FIX (Bug 6): The filtering RAM value and the UI display RAM value were
     * computed differently:
     *  - ModelRepository (here) was using  profile.totalRamGb + profile.swapGb * 0.2f
     *  - DownloadViewModel was using        profile.effectiveRamGb  (= total + swap * 0.6f)
     *  - The Browse screen header showed   profile.effectiveRamGb  → different number
     *
     * Now EVERYTHING uses profile.effectiveRamGb from DeviceDetector, which after
     * the DeviceDetector fix (Bug 5) is: physicalRamGb + swapGb * 0.2f.
     * Single source of truth — no more inconsistencies between filter and display.
     */
    fun getModelsForDevice(profile: DeviceProfile): ModelResult {
        // FIX (Bug 6): Use effectiveRamGb from DeviceDetector as the single value
        // for both filtering and display. DeviceDetector now returns physical RAM
        // (via ActivityManager) + 0.2× swap. No need to recalculate here.
        val filterRamGb = profile.effectiveRamGb

        val (compatible, incompatible) = catalog.filterByRam(filterRamGb)

        val sortedCompatible = compatible.sortedWith(
            compareByDescending<ModelEntry> { it.thinkingTrained }
                .thenByDescending { it.minRamGb }
        )

        val sortedIncompatible = incompatible.sortedBy { it.minRamGb }

        return ModelResult(
            compatible   = sortedCompatible,
            incompatible = sortedIncompatible,
            deviceRamGb  = filterRamGb   // same value for display and filtering
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
    /** Effective RAM used for filtering (physical + 0.2× swap) — also shown in UI */
    val deviceRamGb:  Float
)
