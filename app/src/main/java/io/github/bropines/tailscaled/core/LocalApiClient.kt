package io.github.bropines.tailscaled.core

import appctr.Appctr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TailSocks Production Kotlin LocalAPI Client
 * 
 * Provides a strongly-typed, asynchronous coroutine interface for interacting with
 * the embedded Tailscale daemon's Unix socket LocalAPI v0.
 */
object LocalApiClient {

    // --- 1. Node Status & Profiles ---

    /**
     * Retrieves raw JSON node status from /localapi/v0/status.
     * @param includePeers whether to include full peer list metadata
     */
    suspend fun getStatus(includePeers: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getStatusJSON(includePeers) }
    }

    /**
     * Fetches raw JSON array of all registered profiles from /localapi/v0/profiles/.
     */
    suspend fun getProfiles(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getProfilesJSON() }
    }

    /**
     * Switches the active daemon profile session by profile ID.
     */
    suspend fun switchProfile(profileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.switchProfile(profileId) }
    }

    // --- 2. Preferences & Daemon State ---

    /**
     * Fetches current raw IPN preferences JSON from /localapi/v0/prefs.
     */
    suspend fun getPrefs(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getPrefsJSON() }
    }

    /**
     * Applies incremental preference updates via PATCH /localapi/v0/prefs.
     */
    suspend fun patchPrefs(jsonPayload: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.patchPrefsJSON(jsonPayload) }
    }

    /**
     * Sends initial daemon engine parameters via POST /localapi/v0/start.
     */
    suspend fun startDaemon(jsonPayload: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.startDaemon(jsonPayload) }
    }

    /**
     * Triggers web interactive login flow via POST /localapi/v0/login-interactive.
     */
    suspend fun loginInteractive(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.loginInteractive() }
    }

    /**
     * Logs out current daemon session via POST /localapi/v0/logout.
     */
    suspend fun logoutDaemon(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.logoutDaemon() }
    }

    // --- 3. Diagnostics & Network Topologies ---

    /**
     * Performs or retrieves netcheck network diagnostic results from /localapi/v0/netcheck.
     */
    suspend fun getNetcheck(requestDerp: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getNetcheckJSON(requestDerp) }
    }

    /**
     * Pings a target node IP via POST /localapi/v0/ping.
     */
    suspend fun ping(targetIp: String, pingType: String = "disco"): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.pingTarget(targetIp, pingType) }
    }

    /**
     * Identifies remote IP owner metadata via GET /localapi/v0/whois.
     */
    suspend fun whoIs(addr: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.whoIsAddr(addr) }
    }

    /**
     * Retrieves current DERP map JSON from /localapi/v0/derp/map.
     */
    suspend fun getDerpMap(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getDERPMapJSON() }
    }

    // --- 4. Taildrive & File Sharing ---

    /**
     * Fetches current Taildrive shared directories list JSON.
     */
    suspend fun getDriveShares(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getDriveSharesJSON() }
    }

    /**
     * Adds or updates a local directory share in Taildrive.
     */
    suspend fun putDriveShare(name: String, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.putDriveShare(name, path) }
    }

    /**
     * Removes a Taildrive share by name.
     */
    suspend fun deleteDriveShare(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.deleteDriveShare(name) }
    }

    /**
     * Sets local Web interface server address for Taildrive.
     */
    suspend fun setFileServerAddress(addr: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.setFileServerAddr(addr) }
    }

    /**
     * Fetches tailnet nodes capable of receiving files via Taildrop.
     */
    suspend fun getFileTargets(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getFileTargetsJSON() }
    }

    /**
     * Lists incoming files waiting to be received via Taildrop.
     */
    suspend fun getWaitingFiles(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getWaitingFilesJSON() }
    }

    // --- 5. Serve & Funnel ---

    /**
     * Retrieves active Serve & Funnel rules JSON from /localapi/v0/serve-config.
     */
    suspend fun getServeConfig(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getServeConfigJSON() }
    }

    /**
     * Applies new Serve/Funnel rules using the Reset-then-Apply pattern.
     */
    suspend fun setServeConfig(configJson: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.setServeConfigJSON(configJson) }
    }

    /**
     * Clears all active Serve and Funnel rules.
     */
    suspend fun resetServeConfig(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.resetServeConfig() }
    }

    // --- 6. DNS Configuration ---

    /**
     * Pushes custom DNS configuration updates to /localapi/v0/set-dns.
     */
    suspend fun setDns(dnsJson: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.setDNSJSON(dnsJson) }
    }
}
