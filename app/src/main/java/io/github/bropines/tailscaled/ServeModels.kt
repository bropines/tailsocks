package io.github.bropines.tailscaled

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class ServeConfig(
    @SerializedName("etag") val etag: String? = null,
    @SerializedName("TCP") val tcp: Map<Int, TCPPortHandler>? = null,
    @SerializedName("Web") val web: Map<String, WebServerConfig>? = null,
    @SerializedName("AllowFunnel") val allowFunnel: Map<String, Boolean>? = null,
    @SerializedName("Services") val services: Map<String, ServiceConfig>? = null
)

@Keep
data class ServiceConfig(
    @SerializedName("TCP") val tcp: Map<Int, TCPPortHandler>? = null,
    @SerializedName("Web") val web: Map<String, WebServerConfig>? = null,
    @SerializedName("AllowFunnel") val allowFunnel: Map<String, Boolean>? = null
)

@Keep
data class TCPPortHandler(
    @SerializedName("HTTPS") val https: Boolean? = null,
    @SerializedName("HTTP") val http: Boolean? = null,
    @SerializedName("TCPForward") val tcpForward: String? = null,
    @SerializedName("TerminateTLS") val terminateTLS: String? = null,
    @SerializedName("ProxyProtocol") val proxyProtocol: Int? = null,
    @SerializedName("Disabled") val disabled: Boolean? = null
)

@Keep
data class WebServerConfig(
    @SerializedName("Handlers") val handlers: Map<String, HTTPHandler>? = null
)

@Keep
data class HTTPHandler(
    @SerializedName("Path") val path: String? = null,
    @SerializedName("Proxy") val proxy: String? = null,
    @SerializedName("Text") val text: String? = null,
    @SerializedName("Redirect") val redirect: String? = null
)
