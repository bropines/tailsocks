package io.github.bropines.tailscaled.admin.notify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.bropines.tailscaled.admin.api.AdminApiException
import io.github.bropines.tailscaled.admin.api.AdminArea
import io.github.bropines.tailscaled.admin.api.AdminBackend
import io.github.bropines.tailscaled.admin.api.BackendFeature
import io.github.bropines.tailscaled.admin.api.Capabilities
import io.github.bropines.tailscaled.admin.api.Listing
import io.github.bropines.tailscaled.admin.api.UserListType
import io.github.bropines.tailscaled.admin.attention.Attention
import io.github.bropines.tailscaled.admin.attention.AttentionInput
import io.github.bropines.tailscaled.admin.attention.AttentionKind
import io.github.bropines.tailscaled.admin.attention.AttentionSection
import io.github.bropines.tailscaled.admin.profile.AdminProfile
import io.github.bropines.tailscaled.admin.profile.AdminProfiles
import io.github.bropines.tailscaled.admin.secure.AdminWriteGate
import io.github.bropines.tailscaled.admin.secure.LockState
import io.github.bropines.tailscaled.core.wrapContextWithLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One background check of one admin profile. Reads only — the device list, the users, the
 * keys and the credential's own entry — so it needs no unlock (see PeerVersionSource); every
 * change still goes through the gates, from the console or from [AttentionActionActivity].
 * A profile deleted or switched off since cancels its own job.
 */
class AttentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val profileId = inputData.getString(KEY_PROFILE) ?: return Result.failure()
        val app = applicationContext
        val (profile, checks) = withContext(Dispatchers.IO) {
            AdminProfiles.store(app).get(profileId) to AttentionPrefs.of(app).checks(profileId)
        }
        if (profile == null || !checks.enabled) {
            AttentionScheduler.cancel(app, profileId)
            if (profile == null) withContext(Dispatchers.IO) { AttentionPrefs.of(app).forget(profileId) }
            return Result.success()
        }
        try {
            AttentionCheck.run(app, profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The next period tries again; a check that fails says nothing rather than something wrong.
            Log.w(TAG, "check of ${profile.id} failed: ${e.javaClass.simpleName}")
        }
        return Result.success()
    }

    companion object {
        const val KEY_PROFILE = "profile"
        private const val TAG = "AdminAttention"
    }
}

/** The check itself: read, compute, compare with what was notified, notify what is new. */
internal object AttentionCheck {
    private const val TAG = "AdminAttention"

    suspend fun run(context: Context, profile: AdminProfile, now: Long = System.currentTimeMillis()) {
        val backend = withContext(Dispatchers.IO) { AdminProfiles.newBackend(context, profile) }
        var refused = false
        var credentialRead = true
        try {
            backend.refreshCapabilities()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdminApiException.Unauthorized) {
            refused = true
        } catch (e: Exception) {
            credentialRead = false
            Log.i(TAG, "capabilities of ${profile.id}: ${e.javaClass.simpleName}")
        }

        suspend fun <T> read(feature: BackendFeature, area: AdminArea, fetch: suspend AdminBackend.() -> Listing<T>): List<T>? {
            val caps = backend.capabilities.value
            if (refused || !caps.has(feature) || !caps.canRead(area)) return null
            return try {
                backend.fetch().items
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdminApiException.Unauthorized) {
                refused = true
                null
            } catch (e: Exception) {
                Log.i(TAG, "$area of ${profile.id}: ${e.javaClass.simpleName}")
                null
            }
        }

        val devices = read(BackendFeature.DEVICES, AdminArea.DEVICES) { listDevices() }
        val users = read(BackendFeature.USERS, AdminArea.USERS) { listUsers(UserListType.ALL) }
        val keys = read(BackendFeature.KEYS, AdminArea.AUTH_KEYS) { listKeys() }
        val caps = backend.capabilities.value
        val items = Attention.compute(
            AttentionInput(
                devices = devices, users = users, keys = keys,
                ownKeyId = caps.ownKeyId, credentialExpires = caps.credentialExpires, credentialRefused = refused,
            ),
            now,
        )
        val sources = Attention.sourcesRead(devices != null, users != null, keys != null, credentialRead || refused)

        // Nothing would show: keep the remembered set as it is, so these notify once they can.
        if (!AttentionNotifier.canPost(context)) return

        val prefs = AttentionPrefs.of(context)
        val diff = withContext(Dispatchers.IO) { AttentionNotifyOnce.diff(prefs.seen(profile.id), items, sources) }
        val words = wrapContextWithLocale(context)
        val writable = { area: AdminArea? -> area != null && canWrite(context, profile, caps, area) }

        for (item in diff.fresh) when (item.kind) {
            AttentionKind.DEVICE_APPROVAL, AttentionKind.USER_APPROVAL ->
                AttentionNotifier.postApproval(words, profile, item, actions = writable(item.kind.area))
            AttentionKind.CREDENTIAL_REFUSED -> AttentionNotifier.postCredentialRefused(words, profile, item)
            else -> Unit
        }
        val expiring = items.filter { it.kind.notifies && (it.kind.section == AttentionSection.EXPIRY || it.kind == AttentionKind.CREDENTIAL_EXPIRING) }
        val expiryKeys = expiring.mapTo(mutableSetOf()) { it.notifyKey }
        val expiryResolved = diff.resolved.any { k -> AttentionNotifyOnce.kindOf(k)?.let { !AttentionNotifier.ownsNotification(it) } == true }
        if (diff.fresh.any { it.notifyKey in expiryKeys }) AttentionNotifier.postExpiry(words, profile, expiring, now)
        else if (expiryResolved) AttentionNotifier.refreshExpiryIfShown(words, profile, expiring, now)
        // Approved or deleted elsewhere since: its notification has nothing left to ask.
        for (key in diff.resolved) {
            val kind = AttentionNotifyOnce.kindOf(key) ?: continue
            if (AttentionNotifier.ownsNotification(kind)) AttentionNotifier.cancelItem(words, profile.id, key)
        }
        withContext(Dispatchers.IO) { prefs.setSeen(profile.id, diff.seen) }
        if (diff.fresh.isNotEmpty()) Log.i(TAG, "${profile.id}: ${diff.fresh.size} new, ${diff.resolved.size} resolved")
    }

    /** Whether a notification may offer to change [area]: the profile, the phone's lock and the credential all allow it. */
    fun canWrite(context: Context, profile: AdminProfile, caps: Capabilities, area: AdminArea): Boolean =
        !profile.readOnly && AdminWriteGate.lockState(context) != LockState.NO_SCREEN_LOCK && caps.canWrite(area)
}
