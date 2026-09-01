package io.github.bropines.tailscaled.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServeConfig(
    @SerialName("etag") val etag: String? = null,
    @SerialName("TCP") val tcp: Map<Int, TCPPortHandler>? = null,
    @SerialName("Web") val web: Map<String, WebServerConfig>? = null,
    @SerialName("AllowFunnel") val allowFunnel: Map<String, Boolean>? = null,
    @SerialName("Services") val services: Map<String, ServiceConfig>? = null
)

@Serializable
data class ServiceConfig(
    @SerialName("TCP") val tcp: Map<Int, TCPPortHandler>? = null,
    @SerialName("Web") val web: Map<String, WebServerConfig>? = null
)

@Serializable
data class TCPPortHandler(
    @SerialName("HTTPS") val https: Boolean? = null,
    @SerialName("HTTP") val http: Boolean? = null,
    @SerialName("TCPForward") val tcpForward: String? = null,
    @SerialName("TerminateTLS") val terminateTLS: String? = null,
    @SerialName("ProxyProtocol") val proxyProtocol: Int? = null,
    @SerialName("Disabled") val disabled: Boolean? = null
)

@Serializable
data class WebServerConfig(
    @SerialName("Handlers") val handlers: Map<String, HTTPHandler>? = null
)

@Serializable
data class HTTPHandler(
    @SerialName("Path") val path: String? = null,
    @SerialName("Proxy") val proxy: String? = null,
    @SerialName("Text") val text: String? = null,
    @SerialName("Redirect") val redirect: String? = null
)
