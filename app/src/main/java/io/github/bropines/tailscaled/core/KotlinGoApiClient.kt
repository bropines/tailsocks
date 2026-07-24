package io.github.bropines.tailscaled.core

import appctr.Appctr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * KotlinGoApiClient
 *
 * Coroutine wrapper for TailSocks Go-based LocalAPI v0 bindings (`appctr/api.go`).
 * Acts as a single source of truth for daemon configuration via native Go JNI.
 */
object KotlinGoApiClient {

    // --- 1. Status & Profiles ---

    suspend fun getStatus(includePeers: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getStatusJSON(includePeers) }
    }

    suspend fun getProfiles(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getProfilesJSON() }
    }

    suspend fun switchProfile(profileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.switchProfile(profileId) }
    }

    // --- 2. Preferences & Daemon Lifecycle ---

    suspend fun getPrefs(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getPrefsJSON() }
    }

    suspend fun patchPrefs(jsonPayload: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.patchPrefsJSON(jsonPayload) }
    }

    suspend fun startDaemon(jsonPayload: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.startDaemon(jsonPayload) }
    }

    suspend fun loginInteractive(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.loginInteractive() }
    }

    suspend fun logoutDaemon(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.logoutDaemon() }
    }

    // --- 3. Diagnostics & Topologies ---

    suspend fun getNetcheck(requestDerp: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getNetcheckJSON(requestDerp) }
    }

    suspend fun ping(targetIp: String, pingType: String = "disco"): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.pingTarget(targetIp, pingType) }
    }

    suspend fun whoIs(addr: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.whoIsAddr(addr) }
    }

    suspend fun getDerpMap(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getDERPMapJSON() }
    }

    // --- 4. Taildrive & Files ---

    suspend fun getDriveShares(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getDriveSharesJSON() }
    }

    suspend fun putDriveShare(name: String, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.putDriveShare(name, path) }
    }

    suspend fun deleteDriveShare(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.deleteDriveShare(name) }
    }

    suspend fun setFileServerAddress(addr: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.setFileServerAddr(addr) }
    }

    suspend fun getFileTargets(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getFileTargetsJSON() }
    }

    suspend fun getWaitingFiles(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getWaitingFilesJSON() }
    }

    // --- 5. Serve & Funnel ---

    suspend fun getServeConfig(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Appctr.getServeConfigJSON() }
    }

    suspend fun setServeConfig(configJson: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.setServeConfigJSON(configJson) }
    }

    suspend fun resetServeConfig(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.resetServeConfig() }
    }

    // --- 6. DNS ---

    suspend fun setDns(dnsJson: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { Appctr.setDNSJSON(dnsJson) }
    }
}
