package io.github.bropines.tailscaled.ui

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition

/**
 * A widget's Glance state: one key, its serialized model (WidgetModels.kt). Only
 * [WidgetUpdates] writes it; the composables read nothing else.
 */
object WidgetStateKeys {
    val MODEL = stringPreferencesKey("model")
}

val WidgetStateDef: GlanceStateDefinition<Preferences> = PreferencesGlanceStateDefinition
