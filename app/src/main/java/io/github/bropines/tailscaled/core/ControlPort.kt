package io.github.bropines.tailscaled.core

import android.content.Context

/**
 * Which port the daemon's control connection uses.
 *
 * tailscaled opens its control channel (Noise, "ts2021") by upgrading a plain
 * port 80 connection and stays on it once the handshake passes; port 443 is
 * only the fallback for a dial that fails (control/controlhttp/client.go).
 * Some networks let that handshake through and then drop the stream without a
 * reset after a kilobyte or two — measured on two Russian ISPs in 2026-10: the
 * node registered in 0.2 s, the map poll was never answered, and the app sat
 * in "connecting" for good, while the same channel inside TLS on port 443
 * carried hundreds of requests. A control proxy hid it, because the stream
 * never crossed the network in the clear.
 *
 * On (the default) the daemon gets upstream's `TS_FORCE_NOISE_443` and goes
 * straight to TLS; it still dials port 80 for a control server that has no
 * HTTPS port, so a plain-http Headscale keeps working. Off is upstream's own
 * order, for the opposite network where TLS to the control server is what
 * breaks. The daemon reads the variable at start, so a change restarts it;
 * Root Mode carries it in the env file.
 */
object ControlPort {
    const val KEY = "control_tls_only"
    const val ENV = "TS_FORCE_NOISE_443"

    fun isTlsOnly(context: Context): Boolean = GlobalSettings.getBoolean(context, KEY, true)

    fun setTlsOnly(context: Context, tlsOnly: Boolean) = GlobalSettings.setBoolean(context, KEY, tlsOnly)
}
