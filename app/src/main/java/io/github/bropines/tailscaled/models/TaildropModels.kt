package io.github.bropines.tailscaled.models
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.core.*
import io.github.bropines.tailscaled.ui.*

import androidx.annotation.Keep

@Keep
data class SentFileEntry(
    val name: String,
    val target: String,
    val timestamp: Long
)

@Keep
data class TaildropFile(
    val Name: String,
    val Size: Long,
    val ModTime: Long,
    val Path: String
)
