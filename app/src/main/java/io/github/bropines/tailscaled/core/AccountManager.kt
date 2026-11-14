package io.github.bropines.tailscaled.core
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable
data class TailscaleAccount(
    val id: String = "",
    val name: String = "",
    val avatarUrl: String? = null
)

object AccountManager {
    /**
     * What can be said about a profile without a daemon to ask: where it logs
     * in, whether it has ever registered, and the two properties that are
     * decided before its first start. Read straight from the profile's own
     * preferences, so it also answers for accounts that are not active.
     */
    data class AccountFacts(
        val loginServer: String,
        val hasAuthKey: Boolean,
        val honestOs: Boolean,
        val registered: Boolean
    )

    fun facts(context: Context, id: String): AccountFacts {
        val prefs = profilePrefs(context, id)
        val server = (prefs.getString("login_server", "") ?: "").trim()
        return AccountFacts(
            loginServer = if (server.isEmpty()) DEFAULT_LOGIN_SERVER else hostOf(server),
            hasAuthKey = !(prefs.getString("authkey", "") ?: "").isBlank(),
            honestOs = prefs.getBoolean(ProfileHostinfo.KEY, false),
            registered = ProfileHostinfo.isRegistered(context, id)
        )
    }

    /** The coordination server a profile with no login server of its own uses. */
    private const val DEFAULT_LOGIN_SERVER = "tailscale.com"

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').trim().ifEmpty { DEFAULT_LOGIN_SERVER }

    private const val PREFS_NAME = "account_manager"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_ACTIVE_ID = "active_account_id"
    private const val KEY_PRIMARY_ID = "primary_account_id"

    /** The account the app was installed with, and the main one until another is chosen. */
    const val FIRST_ID = "default"

    /**
     * The main account among [accounts]: the one cannot be deleted and the app falls back to
     * when the account in use is. [chosen] while it is still there; otherwise the first account,
     * and otherwise whichever comes first — a backup restored from before the choice existed,
     * or a choice whose account was deleted some other way, still names one that exists.
     */
    internal fun primaryOf(accounts: List<TailscaleAccount>, chosen: String?): String =
        accounts.firstOrNull { it.id == chosen }?.id
            ?: accounts.firstOrNull { it.id == FIRST_ID }?.id
            ?: accounts.first().id

    fun getPrimaryId(context: Context): String =
        primaryOf(getAccounts(context), context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PRIMARY_ID, null))

    /** Makes [id] the main account; the one that was frees up to be deleted. */
    fun setPrimary(context: Context, id: String) {
        if (getAccounts(context).none { it.id == id }) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_PRIMARY_ID, id).apply()
    }

    /**
     * The JSON the list was last decoded from, and the list. The list sits on per-tick and
     * per-binder-call paths (the exit-node guard, every document operation of the provider),
     * and decoding it each time was the cost of every one of them. The key is the stored text
     * itself, so whatever writes the preference — [saveAccounts], a backup restore — is seen at
     * the next read with no invalidation to forget: SharedPreferences hands back the same
     * String until the value changes, and an unchanged value compares at once. One process
     * (the manifest declares no other), so the in-memory preference is the only copy.
     */
    @Volatile
    private var decoded: Pair<String, List<TailscaleAccount>>? = null

    fun getAccounts(context: Context): List<TailscaleAccount> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ACCOUNTS, null)
        if (json.isNullOrBlank()) return DEFAULT_ACCOUNTS
        decoded?.let { (text, list) -> if (text == json) return list }
        val list = runCatching { AppJson.decodeFromString<List<TailscaleAccount>>(json) }
            .getOrNull()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_ACCOUNTS
        decoded = json to list
        return list
    }

    private val DEFAULT_ACCOUNTS = listOf(TailscaleAccount(FIRST_ID, "Default"))

    /** The account in use among [accounts]: the one [stored] names while it is there, else the first. */
    internal fun activeOf(accounts: List<TailscaleAccount>, stored: String?): TailscaleAccount =
        accounts.find { it.id == stored } ?: accounts.first()

    fun getActiveAccount(context: Context): TailscaleAccount =
        activeOf(getAccounts(context), context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ACTIVE_ID, FIRST_ID))

    /** The id of [getActiveAccount]. */
    fun activeId(context: Context): String = getActiveAccount(context).id

    /**
     * The preferences of profile [id] — the active one when it is not given: `appctr_<id>`,
     * where every per-profile setting lives (agents.md, Account Isolation).
     */
    fun profilePrefs(context: Context, id: String = activeId(context)): SharedPreferences =
        context.getSharedPreferences("appctr_$id", Context.MODE_PRIVATE)

    fun setActiveAccount(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_ID, id)
            .apply()
        // The widgets name the profile and show its exit node — a switch while stopped,
        // from Tasker or an assistant included.
        WidgetUpdates.request(context)
        try {
            val rootsUri = android.provider.DocumentsContract.buildRootsUri(context.packageName + ".documents")
            context.contentResolver.notifyChange(rootsUri, null)
        } catch (e: Exception) {
            android.util.Log.e("AccountManager", "Failed to notify SAF roots change: ${e.message}")
        }
    }

    @Synchronized
    fun addAccount(context: Context, name: String): TailscaleAccount {
        val accounts = getAccounts(context).toMutableList()
        val newAccount = TailscaleAccount(id = System.currentTimeMillis().toString(), name = name)
        accounts.add(newAccount)
        saveAccounts(context, accounts)
        return newAccount
    }

    @Synchronized
    fun deleteAccount(context: Context, id: String) {
        val primary = getPrimaryId(context)
        if (id == primary) return
        // Read before the list changes: afterwards the deleted id is no longer found, and the
        // active account would quietly read as whichever came first.
        val wasActive = getActiveAccount(context).id == id
        val accounts = getAccounts(context).filter { it.id != id }
        saveAccounts(context, accounts)
        if (wasActive) {
            setActiveAccount(context, primary)
        }
        
        // Clean up data
        val stateDir = java.io.File(context.filesDir, "states/$id")
        if (stateDir.exists()) stateDir.deleteRecursively()

        // Received Taildrop files live outside the state directory since 4.0.
        val taildropDir = TaildropPaths.dir(context, id)
        if (taildropDir.exists()) taildropDir.deleteRecursively()

        // Drop the profile's preferences too — they hold the auth key and login
        // server. They used to be left on disk indefinitely after deletion.
        try { context.deleteSharedPreferences("appctr_$id") } catch (e: Exception) {}

        // Clean up avatar
        val avatarFile = java.io.File(context.filesDir, "avatars/$id.png")
        if (avatarFile.exists()) avatarFile.delete()

        // The tokens of the devices this profile managed (RemoteManage): node ids mean
        // nothing outside its tailnet. The devices keep their side until it is revoked there.
        RemoteManage.tokens(context).removeProfile(id)
        ManagedLast.forget(context, id)
    }

    @Synchronized
    fun renameAccount(context: Context, id: String, newName: String) {
        val accounts = getAccounts(context).map {
            if (it.id == id) it.copy(name = newName) else it
        }
        saveAccounts(context, accounts)
        WidgetUpdates.request(context)
    }

    @Synchronized
    fun updateAccountAvatar(context: Context, id: String, avatarUrl: String) {
        val accounts = getAccounts(context).map {
            if (it.id == id) it.copy(avatarUrl = avatarUrl) else it
        }
        saveAccounts(context, accounts)
    }

    private fun saveAccounts(context: Context, accounts: List<TailscaleAccount>) {
        val json = AppJson.encodeToString(accounts)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNTS, json)
            .apply()
    }
}
