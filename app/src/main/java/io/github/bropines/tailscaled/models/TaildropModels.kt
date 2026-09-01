package io.github.bropines.tailscaled.models

import kotlinx.serialization.Serializable

@Serializable
data class SentFileEntry(
    val name: String,
    val target: String,
    val timestamp: Long
)

@Serializable
data class TaildropFile(
    val Name: String = "",
    val Size: Long = 0,
    val ModTime: Long = 0,
    val Path: String = ""
)
