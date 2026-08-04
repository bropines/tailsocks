package io.github.bropines.tailscaled.ui

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import java.io.File

// ----------------------------------------------------------------
// Widget State — stored in Glance DataStore (no WorkManager needed
// to write, just updateAppWidgetState + widget.update())
// ----------------------------------------------------------------
object WidgetStateKeys {
    val IS_RUNNING       = booleanPreferencesKey("is_running")
    val IS_PENDING       = booleanPreferencesKey("is_pending")
    val PROFILE_NAME     = stringPreferencesKey("profile_name")
    val EXIT_NODE        = stringPreferencesKey("exit_node")
}

// Use the built-in Preferences DataStore definition — no custom serializer needed.
val WidgetStateDef: GlanceStateDefinition<Preferences> = PreferencesGlanceStateDefinition
