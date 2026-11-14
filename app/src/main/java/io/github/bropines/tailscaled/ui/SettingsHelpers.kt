package io.github.bropines.tailscaled.ui

import androidx.compose.ui.graphics.Color

/*
 * Small values the settings pages make or clean up: the SOCKS5 credentials and the
 * automation secret, a device name reduced to a DNS label, a random loopback listener,
 * and the theme preset a swatch stands for.
 */

/** The SOCKS5 login and password it makes are credentials: from the system's secure source, not kotlin.random. */
fun generateRandomString(length: Int = 12): String = generateSecureToken(length)

/**
 * Cryptographically random token for the automation secret. URL-safe alphabet
 * without the look-alike characters (0/O, 1/l/I) so it survives being retyped.
 */
fun generateSecureToken(length: Int = 32): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    val random = java.security.SecureRandom()
    return buildString(length) {
        repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
}

/** Reduces a device name to what a DNS label may contain. */
fun sanitizeHostnameInput(raw: String): String =
    raw.trim()
        .replace(" ", "-")
        .lowercase()
        .replace(Regex("[^a-z0-9-]"), "")
        .trim('-')
        .take(63)

fun generateRandomLoopbackAddress(): String {
    val x = (1..254).random()
    val y = (1..254).random()
    val z = (1..254).random()
    val port = (1024..65535).random()
    return "127.$x.$y.$z:$port"
}

data class PresetItem(val id: String, val color: Color, val name: String)
