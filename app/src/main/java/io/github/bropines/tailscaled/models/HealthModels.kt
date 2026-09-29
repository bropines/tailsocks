package io.github.bropines.tailscaled.models

import io.github.bropines.tailscaled.core.AppJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One of the daemon's health warnings, as appctr's GetHealthWarningsJSON hands
 * it over: the code names the problem, the rest says how much it matters.
 */
@Serializable
data class HealthWarning(
    @SerialName("Code") val code: String = "",
    @SerialName("Title") val title: String = "",
    @SerialName("Text") val text: String = "",
    /** "high", "medium" or "low". */
    @SerialName("Severity") val severity: String = "",
    @SerialName("ImpactsConnectivity") val impactsConnectivity: Boolean = false,
    /** When it broke, in Unix milliseconds; 0 when the daemon did not say. */
    @SerialName("BrokenSinceMs") val brokenSinceMs: Long = 0,
)

fun parseHealthWarnings(json: String?): List<HealthWarning> =
    if (json.isNullOrBlank()) emptyList()
    else runCatching { AppJson.decodeFromString<List<HealthWarning>>(json) }.getOrDefault(emptyList())
