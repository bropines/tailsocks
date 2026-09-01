package io.github.bropines.tailscaled.core
import io.github.bropines.tailscaled.R
import io.github.bropines.tailscaled.BuildConfig

import io.github.bropines.tailscaled.admin.*
import io.github.bropines.tailscaled.models.*
import io.github.bropines.tailscaled.ui.*

import android.content.Context
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
    private const val PREFS_NAME = "account_manager"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_ACTIVE_ID = "active_account_id"

    fun getAccounts(context: Context): List<TailscaleAccount> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ACCOUNTS, null)
        if (json.isNullOrBlank()) return listOf(TailscaleAccount("default", "Default"))
        return runCatching { AppJson.decodeFromString<List<TailscaleAccount>>(json) }
            .getOrDefault(listOf(TailscaleAccount("default", "Default")))
    }

    fun getActiveAccount(context: Context): TailscaleAccount {
        val accounts = getAccounts(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeId = prefs.getString(KEY_ACTIVE_ID, "default")
        return accounts.find { it.id == activeId } ?: accounts.first()
    }

    fun setActiveAccount(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_ID, id)
            .apply()
        try {
            val rootsUri = android.provider.DocumentsContract.buildRootsUri(context.packageName + ".documents")
            context.contentResolver.notifyChange(rootsUri, null)
        } catch (e: Exception) {
            android.util.Log.e("AccountManager", "Failed to notify SAF roots change: ${e.message}")
        }
    }

    fun addAccount(context: Context, name: String): TailscaleAccount {
        val accounts = getAccounts(context).toMutableList()
        val newAccount = TailscaleAccount(id = System.currentTimeMillis().toString(), name = name)
        accounts.add(newAccount)
        saveAccounts(context, accounts)
        return newAccount
    }

    fun deleteAccount(context: Context, id: String) {
        if (id == "default") return
        val accounts = getAccounts(context).filter { it.id != id }
        saveAccounts(context, accounts)
        if (getActiveAccount(context).id == id) {
            setActiveAccount(context, "default")
        }
        
        // Clean up data
        val stateDir = java.io.File(context.filesDir, "states/$id")
        if (stateDir.exists()) stateDir.deleteRecursively()

        // Drop the profile's preferences too — they hold the auth key and login
        // server. They used to be left on disk indefinitely after deletion.
        try { context.deleteSharedPreferences("appctr_$id") } catch (e: Exception) {}

        // Clean up avatar
        val avatarFile = java.io.File(context.filesDir, "avatars/$id.png")
        if (avatarFile.exists()) avatarFile.delete()
    }

    fun renameAccount(context: Context, id: String, newName: String) {
        val accounts = getAccounts(context).map {
            if (it.id == id) it.copy(name = newName) else it
        }
        saveAccounts(context, accounts)
    }

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
