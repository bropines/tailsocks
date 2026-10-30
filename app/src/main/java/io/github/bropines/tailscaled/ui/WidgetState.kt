package io.github.bropines.tailscaled.ui

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition

/**
 * A widget's Glance state: its serialized model (WidgetModels.kt), which only
 * [WidgetUpdates] writes and the composables read, and an exit-node widget's own
 * configuration, which its configuration screen writes. Glance deletes both with the widget.
 */
object WidgetStateKeys {
    val MODEL = stringPreferencesKey("model")
    val CONFIG = stringPreferencesKey("config")
}

val WidgetStateDef: GlanceStateDefinition<Preferences> = PreferencesGlanceStateDefinition
