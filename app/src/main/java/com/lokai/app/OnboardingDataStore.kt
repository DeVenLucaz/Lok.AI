package com.lokai.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.rxjava2.RxPreferenceDataStoreBuilder
import androidx.datastore.preferences.preferencesDataStore

val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")

// Single top-level delegate — declared ONLY here, used by both MainActivity and OnboardingViewModel.
// Having two preferencesDataStore delegates with the same name causes IllegalStateException.
val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "onboarding"
)
