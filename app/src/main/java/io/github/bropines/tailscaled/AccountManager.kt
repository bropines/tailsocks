package io.github.bropines.tailscaled

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class TailscaleAccount(
    val id: String,
    val name: String
)

object AccountManager {
    private const val PREFS_NAME = "account_manager"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_ACTIVE_ID = "active_account_id"

    fun getAccounts(context: Context): List<TailscaleAccount> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ACCOUNTS, null) ?: return listOf(TailscaleAccount("default", "Default"))
        val type = object : TypeToken<List<TailscaleAccount>>() {}.type
        return Gson().fromJson(json, type)
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
    }

    fun renameAccount(context: Context, id: String, newName: String) {
        val accounts = getAccounts(context).map {
            if (it.id == id) it.copy(name = newName) else it
        }
        saveAccounts(context, accounts)
    }

    private fun saveAccounts(context: Context, accounts: List<TailscaleAccount>) {
        val json = Gson().toJson(accounts)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNTS, json)
            .apply()
    }
}
