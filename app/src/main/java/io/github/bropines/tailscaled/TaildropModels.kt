package io.github.bropines.tailscaled

import androidx.annotation.Keep

@Keep
data class SentFileEntry(
    val name: String,
    val target: String,
    val timestamp: Long
)
