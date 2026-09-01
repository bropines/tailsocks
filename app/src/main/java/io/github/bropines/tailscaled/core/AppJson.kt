package io.github.bropines.tailscaled.core

import kotlinx.serialization.json.Json

/**
 * The single JSON codec for the app, replacing Gson.
 *
 * Configured to be as forgiving as Gson was, because the daemon status carries
 * far more fields than we model and profiles on disk may predate a field:
 *  - ignoreUnknownKeys: don't fail on fields we don't declare.
 *  - explicitNulls = false + coerceInputValues: a missing or null key becomes
 *    the property's default (every nullable model field defaults to null).
 *  - isLenient: tolerate the daemon's occasional non-strict JSON.
 *
 * kotlinx.serialization generates serializers at compile time, so R8 keeps them
 * automatically — this is what lets minification run without Gson keep rules.
 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
}
